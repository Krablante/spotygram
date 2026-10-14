package app.spotygram

import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun profileTrackId(user: Long, key: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    val encoded = CharArray(hash.size * 2)
    for (i in hash.indices) {
        val byte = hash[i].toInt() and 255
        encoded[i * 2] = digits[byte ushr 4]
        encoded[i * 2 + 1] = digits[byte and 15]
    }
    return "profile:$user:${encoded.concatToString()}"
}

/** Profile discovery and audio metadata use the existing Telegram session and file reader. */
class Profiles(private val app: SpotygramApp) {
    val selfId = MutableStateFlow(app.prefs.getLong("profile_self", 0))
    val loading = MutableStateFlow(emptySet<Long>())
    val errors = MutableStateFlow(emptyMap<Long, String>())
    val photos = MutableStateFlow(emptyMap<Long, String>())
    val audioRevision = MutableStateFlow(0L)
    val editingAudio = MutableStateFlow(false)
    val uploadingAudio = MutableStateFlow(false)
    private val audioEdits = Mutex()
    private var editedProfileRefresh: Job? = null
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val photoLoads = ConcurrentHashMap.newKeySet<Long>()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var paused = false
    private val telegram
        get() = app.telegram

    private val library
        get() = app.library

    private fun cached(id: Long) = library.state.value.profiles.firstOrNull { it.id == id }

    private fun enter(job: Job): Boolean =
        synchronized(jobs) {
            if (paused) false
            else {
                jobs.add(job)
                true
            }
        }

    private suspend fun <T> active(block: suspend () -> T): T {
        val job = currentCoroutineContext().job
        if (!enter(job)) throw CancellationException("Profile work was stopped")
        try {
            return block()
        } finally {
            jobs.remove(job)
        }
    }

    private suspend fun user(id: Long): JSONObject {
        try {
            return telegram.request(json("getUser", "user_id" to id))
        } catch (e: TelegramException) {
            if (e.code !in setOf(400, 404)) throw e
        }
        val username = cached(id)?.username.orEmpty()
        if (username.isNotEmpty()) {
            try {
                val chat = telegram.request(json("searchPublicChat", "username" to username))
                val type = chat.getJSONObject("type")
                // A renamed username must never resolve this source to another person.
                if (type.kind() == "chatTypePrivate" && type.optLong("user_id") == id)
                    return telegram.request(json("getUser", "user_id" to id))
            } catch (e: TelegramException) {
                if (e.code !in setOf(400, 404)) throw e
            }
        }
        telegram.request(json("getContacts"))
        return telegram.request(json("getUser", "user_id" to id))
    }

    private fun identity(user: JSONObject): UserProfile {
        require(user.optJSONObject("type")?.kind() == "userTypeRegular") {
            tr(R.string.profile_regular_user)
        }
        val title =
            listOf(user.optString("first_name"), user.optString("last_name"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { tr(R.string.telegram_profile) }
        val usernames = user.optJSONObject("usernames")?.optJSONArray("active_usernames")
        val photo = user.optJSONObject("profile_photo")?.optJSONObject("small")
        photo?.let(telegram::updateFile)
        val local = photo?.optJSONObject("local")
        if (local?.optBoolean("is_downloading_completed") != true)
            photos.update { it - user.getLong("id") }
        return UserProfile(
            user.getLong("id"),
            title.take(256),
            usernames?.optString(0).orEmpty(),
            if (local?.optBoolean("is_downloading_completed") == true) local.optString("path")
            else "",
        )
    }

    suspend fun currentUser(): UserProfile = active {
        app.awaitLibrary()
        val profile = identity(telegram.request(json("getMe")))
        selfId.value = profile.id
        app.prefs.edit().putLong("profile_self", profile.id).apply()
        library.profileIdentity(profile)
        cached(profile.id) ?: profile
    }

    suspend fun open(profile: UserProfile) = active {
        app.awaitLibrary()
        library.profileIdentity(profile, opened = true)
    }

    suspend fun select(profile: UserProfile, enabled: Boolean) = active {
        app.awaitLibrary()
        library.selectProfile(profile, enabled)
        if (enabled) load(profile.id)
    }

    suspend fun load(id: Long, force: Boolean = false) =
        locks
            .getOrPut(id) { Mutex() }
            .withLock {
                app.awaitLibrary()
                if (paused) return@withLock
                val old = cached(id)
                if (
                    !force &&
                        old != null &&
                        old.loadedAt > System.currentTimeMillis() - 60_000 &&
                        library.state.value.tracks.none {
                            it.profileUserId == id && it.available && it.fileId == 0
                        }
                )
                    return@withLock
                if (
                    telegram.auth.value.type != "authorizationStateReady" ||
                        telegram.connection.value.isNotEmpty()
                ) {
                    errors.update { it + (id to tr(R.string.profile_offline)) }
                    return@withLock
                }
                val job = currentCoroutineContext().job
                if (!enter(job)) return@withLock
                loading.update { it + id }
                errors.update { it - id }
                try {
                    val user = identity(user(id))
                    telegram.request(json("getUserFullInfo", "user_id" to id))
                    library.profileIdentity(user)
                    val tracks = linkedMapOf<String, Track>()
                    var offset = 0
                    var expectedCount = -1
                    do {
                        currentCoroutineContext().ensureActive()
                        val response =
                            telegram.request(
                                json(
                                    "getUserProfileAudios",
                                    "user_id" to id,
                                    "offset" to offset,
                                    "limit" to 100,
                                )
                            )
                        val audios = response.getJSONArray("audios")
                        val total = response.getInt("total_count")
                        check(total >= 0 && (expectedCount == -1 || expectedCount == total)) {
                            tr(R.string.profile_incomplete)
                        }
                        expectedCount = total
                        check(audios.length() > 0 || offset >= total) {
                            tr(R.string.profile_incomplete)
                        }
                        val page =
                            withContext(Dispatchers.Default) {
                                List(audios.length()) { i ->
                                    app.parseProfileAudio(audios.getJSONObject(i), user, offset + i)
                                }
                            }
                        for (audio in page) tracks.putIfAbsent(audio.id, audio)
                        offset += audios.length()
                    } while (offset < total)
                    check(tracks.size == expectedCount) { tr(R.string.profile_incomplete) }
                    library.profileAudios(id, tracks.values.toList())
                    val savedQueue =
                        runCatching {
                                val ids =
                                    JSONArray(
                                        app.getSharedPreferences("playback_queue")
                                            .getString("ids", "[]")
                                    )
                                List(ids.length()) { ids.getString(it) }
                            }
                            .getOrDefault(emptyList())
                    val protected =
                        (savedQueue + app.playback?.snapshot()?.ids.orEmpty())
                            .mapNotNull {
                                it.takeIf { it.startsWith("profile:") }
                                    ?.substringAfter(':')
                                    ?.substringBefore(':')
                                    ?.toLongOrNull()
                            }
                            .toSet() + setOf(selfId.value, id)
                    for (removed in library.pruneProfilePreviews(protected)) app.forgetTrackBinding(
                        removed
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    errors.update { it + (id to friendly(e)) }
                    throw e
                } finally {
                    loading.update { it - id }
                    jobs.remove(job)
                }
            }

    suspend fun resolve(track: Track): Int {
        // Fetching this source also registers TDLib's profile file-reference refresh mechanism.
        load(track.profileUserId)
        val current = app.track(track.id)
        if (current?.available != true || current.fileId == 0)
            error(tr(R.string.profile_audio_unavailable))
        return current.fileId
    }

    private fun requireConnection() {
        check(telegram.auth.value.type == "authorizationStateReady") {
            tr(R.string.telegram_required)
        }
        check(telegram.connection.value.isEmpty()) { tr(R.string.profile_offline) }
    }

    private suspend fun audioFile(track: Track): Int {
        if (!track.imported && track.available) return app.resolve(track)
        if (track.remoteId.isEmpty()) return 0
        return telegram
            .request(
                json(
                    "getRemoteFile",
                    "remote_file_id" to track.remoteId,
                    "file_type" to json("fileTypeAudio"),
                )
            )
            .getInt("id")
    }

    /** Inspect only the selected recording; TDLib owns the account's saved-file index. */
    suspend fun containsAudio(track: Track): Boolean = active {
        app.awaitLibrary()
        requireConnection()
        val id = audioFile(app.track(track.id) ?: track)
        if (id == 0) return@active false
        try {
            telegram.request(json("isProfileAudio", "file_id" to id))
            true
        } catch (e: TelegramException) {
            if (e.code != 404) throw e
            false
        }
    }

    /** A click sets one final state. Profile membership is owned by Telegram, not LibrarySync. */
    suspend fun changeAudio(track: Track, added: Boolean) = active {
        audioEdits.withLock {
            app.awaitLibrary()
            requireConnection()
            editingAudio.value = true
            var preliminary = 0
            try {
                val current = app.track(track.id) ?: track
                if (added && current.chatId != 0L) {
                    val properties =
                        telegram.request(
                            json(
                                "getMessageProperties",
                                "chat_id" to current.chatId,
                                "message_id" to current.messageId,
                            )
                        )
                    check(properties.getBoolean("can_be_saved")) {
                        tr(R.string.profile_audio_protected)
                    }
                }
                var id = audioFile(current)
                if (added && id == 0) {
                    check(validAudioCopy(current.path, current.size)) { tr(R.string.no_local_copy) }
                    uploadingAudio.value = true
                    app.platform.startProfileAudioUpload(app)
                    // Register the upload so its stable remote reference can be retained for the
                    // original imported song. A later restart can then offer Remove on that song.
                    id =
                        telegram
                            .request(
                                json(
                                    "preliminaryUploadFile",
                                    "file" to json("inputFileLocal", "path" to current.path),
                                    "file_type" to json("fileTypeAudio"),
                                    "priority" to 2,
                                )
                            )
                            .getInt("id")
                    preliminary = id
                }
                check(id != 0) { tr(R.string.profile_audio_unavailable) }
                if (added)
                    telegram.request(
                        json(
                            "addProfileAudio",
                            "audio" to
                                json(
                                    "inputAudio",
                                    "audio" to json("inputFileId", "id" to id),
                                    "duration" to current.duration,
                                    "title" to current.title,
                                    "performer" to current.artist,
                                ),
                        ),
                        timeout = if (preliminary != 0) 86_400_000 else 30_000,
                    )
                else telegram.request(json("removeProfileAudio", "file_id" to id))
                if (current.imported && added) {
                    val file = telegram.request(json("getFile", "file_id" to id))
                    val remote = file.getJSONObject("remote")
                    library.importedAudioReference(
                        current.id,
                        remote.getString("unique_id"),
                        remote.getString("id"),
                    )
                }
                audioRevision.update { it + 1 }
                // Refresh a previously opened/connected collection without delaying the button.
                // Bursts replace this one job; unrelated menus never read the whole profile.
                editedProfileRefresh?.cancel()
                editedProfileRefresh =
                    app.scope.launch {
                        try {
                            val own = if (selfId.value == 0L) currentUser().id else selfId.value
                            if (cached(own)?.let { it.loadedAt != 0L || it.selected } == true)
                                load(own, force = true)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                    }
            } finally {
                if (preliminary != 0)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching {
                            telegram.request(
                                json("cancelPreliminaryUploadFile", "file_id" to preliminary),
                                timeout = 5_000,
                            )
                        }
                    }
                editingAudio.value = false
                uploadingAudio.value = false
            }
        }
    }

    suspend fun search(query: String, server: Boolean): List<UserProfile> {
        val text = query.trim()
        if (text.isEmpty())
            return library.state.value.profiles
                .filter { it.id != selfId.value && it.openedAt != 0L }
                .sortedByDescending { it.openedAt }
                .take(8)
        val users = linkedMapOf<Long, UserProfile>()
        for (profile in library.state.value.profiles) {
            if (
                "${profile.title} ${profile.username}"
                    .contains(text.removePrefix("@"), ignoreCase = true)
            )
                users[profile.id] = profile
        }
        if (
            telegram.auth.value.type != "authorizationStateReady" ||
                telegram.connection.value.isNotEmpty()
        )
            return users.values.toList()
        suspend fun addUser(id: Long) {
            if (id == selfId.value || users.containsKey(id)) return
            val user = telegram.request(json("getUser", "user_id" to id), timeout = 8_000)
            if (user.optJSONObject("type")?.kind() == "userTypeRegular") users[id] = identity(user)
        }
        suspend fun addChat(chat: JSONObject) {
            val type = chat.optJSONObject("type") ?: return
            if (type.kind() == "chatTypePrivate") addUser(type.getLong("user_id"))
        }
        val link =
            text.startsWith("https://") || text.startsWith("http://") || text.startsWith("t.me/")
        if (server && (link || text.startsWith("@"))) {
            val username =
                if (link) {
                    val uri = URI(if (text.startsWith("t.me/")) "https://$text" else text)
                    require(uri.host == "t.me" && uri.path.trim('/').split('/').size == 1) {
                        tr(R.string.profile_link_required)
                    }
                    uri.path.trim('/')
                } else text.removePrefix("@")
            require(username.matches(Regex("[A-Za-z0-9_]{4,32}"))) {
                tr(R.string.profile_link_required)
            }
            addChat(
                telegram.request(json("searchPublicChat", "username" to username), timeout = 8_000)
            )
        } else if (!link && !text.startsWith("@")) {
            if (!server) {
                val contacts =
                    telegram
                        .request(
                            json("searchContacts", "query" to text, "limit" to 50),
                            timeout = 8_000,
                        )
                        .getJSONArray("user_ids")
                for (i in 0 until contacts.length()) addUser(contacts.getLong(i))
            }
            val ids =
                telegram
                    .request(
                        json(
                            if (server) "searchPublicChats" else "searchChats",
                            "query" to text,
                            "limit" to 50,
                        ),
                        timeout = 8_000,
                    )
                    .getJSONArray("chat_ids")
            for (i in 0 until minOf(ids.length(), 50)) {
                currentCoroutineContext().ensureActive()
                addChat(
                    telegram.request(json("getChat", "chat_id" to ids.getLong(i)), timeout = 8_000)
                )
            }
        }
        return users.values.filter { it.id != selfId.value }
    }

    suspend fun loadPhoto(profile: UserProfile) {
        if (
            paused ||
                profile.photo.isNotEmpty() ||
                photos.value.containsKey(profile.id) ||
                !photoLoads.add(profile.id)
        )
            return
        val job = currentCoroutineContext().job
        if (!enter(job)) {
            photoLoads.remove(profile.id)
            return
        }
        try {
            telegram.avatarSlots.withPermit {
                if (
                    telegram.auth.value.type != "authorizationStateReady" ||
                        telegram.connection.value.isNotEmpty()
                )
                    return@withPermit
                val user = telegram.request(json("getUser", "user_id" to profile.id))
                val small =
                    user.optJSONObject("profile_photo")?.optJSONObject("small") ?: return@withPermit
                telegram.updateFile(small)
                val response =
                    telegram.request(
                        json(
                            "downloadFile",
                            "file_id" to small.getInt("id"),
                            "priority" to 1,
                            "offset" to 0,
                            "limit" to 0,
                            "synchronous" to true,
                        )
                    )
                val local = response.getJSONObject("local")
                if (local.optBoolean("is_downloading_completed")) {
                    val path = local.getString("path")
                    library.profilePhoto(profile.id, path)
                    photos.update { it + (profile.id to path) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            photoLoads.remove(profile.id)
            jobs.remove(job)
        }
    }

    suspend fun stop() {
        editedProfileRefresh?.cancelAndJoin()
        editedProfileRefresh = null
        val caller = currentCoroutineContext().job
        val active =
            synchronized(jobs) {
                paused = true
                jobs.toList()
            }
        for (job in active) if (job != caller) job.cancelAndJoin()
    }

    fun resume() {
        synchronized(jobs) { paused = false }
    }

    fun reset() {
        selfId.value = 0
        errors.value = emptyMap()
        photos.value = emptyMap()
        audioRevision.update { it + 1 }
        app.prefs.edit().remove("profile_self").apply()
    }
}
