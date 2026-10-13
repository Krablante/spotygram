package app.spotygram

import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

class SpotygramApp(val platform: Platform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val filesDir
        get() = platform.filesDir

    val cacheDir
        get() = platform.cacheDir

    val backActions = BackActions()
    val language = MutableStateFlow(platform.locale.language)
    val languagePicker = MutableStateFlow(false)
    val updateRestartRequested = MutableStateFlow(false)
    val updateApplyRequested = MutableStateFlow(false)

    fun getSharedPreferences(name: String) = platform.preferences(name)

    lateinit var library: Library
    lateinit var telegram: Telegram
    val prefs by lazy { platform.preferences("settings") }
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val playbackCheckpoints = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val busy = MutableStateFlow(false)
    val startupError = MutableStateFlow("")
    val downloading = MutableStateFlow(DownloadState())
    val theme = MutableStateFlow("system")
    val hideDuplicates by lazy { MutableStateFlow(prefs.getBoolean("hide_duplicates", true)) }
    val resumeLongAudio by lazy { MutableStateFlow(prefs.getBoolean("resume_long_audio", true)) }
    val resumeMinutes by lazy {
        MutableStateFlow(prefs.getInt("resume_minutes", 30).coerceAtLeast(1))
    }

    private val scanning = Mutex()
    private val updating = Mutex()
    private val preferencesChanging = Mutex()
    private var preferencesSaving: Job? = null
    private var scanJob: Job? = null
    private lateinit var libraryReady: Deferred<Unit>
    private val artworkSlots = Semaphore(2)
    private val thumbnails = ConcurrentHashMap<String, Int>()
    private val resolvedFiles = ConcurrentHashMap<String, Int>()
    private val artworkFiles = ConcurrentHashMap<Int, MutableSet<String>>()
    var player: Player? = null
    var playback: PlaybackQueue? = null
    var downloadService: Downloads? = null
    val musicCache by lazy { MusicCache(this) }
    val listeningCache by lazy { ListeningCache(this) }
    val updates by lazy { AppUpdates(this) }
    val cloudSync by lazy { CloudSync(this) }
    var suppressCloudWrites = false
    val playbackMode =
        MutableStateFlow(
            run {
                val local = getSharedPreferences("playback_mode")
                val queue = getSharedPreferences("playback_queue")
                val position = getSharedPreferences("playback_position")
                val previous =
                    if (!queue.contains("ids")) prefs
                    else if (position.getString("version", "") == queue.getString("version", "?"))
                        position
                    else queue
                val source = if (local.contains("order")) local else previous
                PlaybackMode(
                    runCatching { PlaybackOrder.valueOf(source.getString("order", "")!!) }
                        .getOrElse {
                            if (
                                source.getBoolean(
                                    if (source === prefs) "queue_shuffle" else "shuffle",
                                    false,
                                )
                            )
                                PlaybackOrder.SHUFFLE
                            else PlaybackOrder.ORDERED
                        },
                    source.getInt("repeat", Player.REPEAT_MODE_OFF).coerceIn(0, 2),
                )
            }
        )
    val queueRevision = MutableStateFlow(0L)

    fun start() {
        AppText.initialize(platform)
        library = Library(platform)
        telegram =
            Telegram(platform, scope) {
                scanJob?.cancelAndJoin()
                updating.withLock {
                    while (telegram.updates.tryReceive().isSuccess) {
                        /* Drop events from the closed client. */
                    }
                    val bound = resolvedFiles.isNotEmpty()
                    resolvedFiles.clear()
                    thumbnails.clear()
                    artworkFiles.clear()
                    if (bound) {
                        try {
                            awaitLibrary()
                            library.clearFileBindings()
                        } catch (e: Exception) {
                            notices.tryEmit(friendly(e))
                        }
                    }
                }
            }
        theme.value = prefs.getString("theme", "system") ?: "system"
        libraryReady = scope.async {
            try {
                library.verifyFiles()
                applyCloudPreferences(library.sync.preferences())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                startupError.value = tr(R.string.library_open_failed)
                throw e
            }
        }
        scope.launch {
            for (update in telegram.updates) {
                try {
                    updating.withLock {
                        awaitLibrary()
                        handleUpdate(update)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        scope.launch {
            combine(telegram.auth.map { it.type }, telegram.connection) { type, connection ->
                    type == "authorizationStateReady" && connection.isEmpty()
                }
                .distinctUntilChanged()
                .collectLatest { connected ->
                    cloudSync.connection(connected)
                    listeningCache.signal()
                    if (!connected) scanJob?.cancelAndJoin()
                    else {
                        try {
                            refresh()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            notices.emit(friendly(e))
                        }
                    }
                }
        }
        if (prefs.getBoolean("telegram_connected", false)) telegram.start()
    }

    fun action(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            notices.emit(friendly(e))
        }
    }

    suspend fun awaitLibrary() {
        libraryReady.await()
    }

    suspend fun close() {
        scanJob?.cancelAndJoin()
        downloadService?.cancelForCacheCleanup()
        playback?.close()?.join()
        preferencesSaving?.join()
        cloudSync.close()
        telegram.close()
        scope.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.IO) { library.close() }
    }

    fun changeTheme(value: String) {
        changeSetting("theme", value)
    }

    fun changeLanguage(value: String) {
        changeSetting("language", value)
    }

    fun changeHideDuplicates(value: Boolean) {
        changeSetting("hide_duplicates", value)
    }

    fun localMode() {
        prefs.edit().putBoolean("local_mode", true).apply()
    }

    fun changeResumeLongAudio(value: Boolean) {
        playback?.savePosition()
        changeSetting("resume_long_audio", value)
    }

    fun changeResumeMinutes(value: Int) {
        if (value < 1) return
        playback?.savePosition()
        changeSetting("resume_minutes", value)
    }

    fun changeSetting(key: String, value: Any) = changeSettings(mapOf(key to value))

    fun changePlaybackOrder(order: PlaybackOrder) {
        val queue = playback
        if (queue != null) queue.setMode(order = order)
        else persistPlaybackMode(playbackMode.value.copy(order = order))
    }

    fun persistPlaybackMode(value: PlaybackMode) {
        val mode =
            if (value.random && value.repeat == Player.REPEAT_MODE_ALL)
                value.copy(repeat = Player.REPEAT_MODE_OFF)
            else value
        playbackMode.value = mode
        getSharedPreferences("playback_mode")
            .edit()
            .putString("order", mode.order.name)
            .putInt("repeat", mode.repeat)
            .apply()
    }

    fun changeSettings(values: Map<String, Any>) = action {
        preferencesChanging.withLock {
            awaitLibrary()
            if (!suppressCloudWrites) library.sync.settingsChanged(values)
            applyCloudPreferences(values)
        }
    }
        .also { preferencesSaving = it }

    fun applyCloudPreferences(values: Map<String, Any>) {
        val edit = prefs.edit()
        for ((key, value) in values) {
            if (key !in LibrarySync.settings) continue
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Number -> edit.putInt(key, value.toInt())
                is String -> edit.putString(key, value)
            }
        }
        edit.apply()
        theme.value = prefs.getString("theme", "system") ?: "system"
        hideDuplicates.value = prefs.getBoolean("hide_duplicates", true)
        resumeLongAudio.value = prefs.getBoolean("resume_long_audio", true)
        resumeMinutes.value = prefs.getInt("resume_minutes", 30).coerceAtLeast(1)
        val locale = platform.locale.language
        if (language.value != locale) {
            language.value = locale
            AppText.languageChanged()
        }
        values["discard_listened"]?.let {
            listeningCache.setEnabled(it as Boolean, publish = false)
        }
    }

    fun cloudLibraryChanged(keys: Set<String>) {
        if (keys.any { it.startsWith("source/") }) action { refresh() }
    }

    fun track(id: String) = library.state.value.byId[id]

    suspend fun loadChats() {
        // Populate TDLib's own index. Typing in the picker must not cancel this job.
        for (list in listOf("chatListMain", "chatListArchive")) {
            while (true) {
                try {
                    telegram.request(json("loadChats", "chat_list" to json(list), "limit" to 100))
                } catch (e: TelegramException) {
                    if (e.code != 404) throw e
                    break
                }
                currentCoroutineContext().ensureActive()
            }
        }
        val me = telegram.request(json("getMe"))
        val saved =
            telegram.request(
                json("createPrivateChat", "user_id" to me.getLong("id"), "force" to false)
            )
        AppText.rememberSavedChat(saved.getLong("id"))
        telegram.chatRevision.update { it + 1 }
    }

    suspend fun searchChats(query: String, onServer: Boolean = false): List<ChatChoice> {
        val ids = linkedSetOf<Long>()
        val requests =
            if (query.isBlank())
                listOf("chatListMain", "chatListArchive").map {
                    json("getChats", "chat_list" to json(it), "limit" to Int.MAX_VALUE)
                }
            else
                listOf(
                    json(
                        if (onServer) "searchChatsOnServer" else "searchChats",
                        "query" to query,
                        "limit" to if (onServer) 100 else Int.MAX_VALUE,
                    )
                )
        for (request in requests) {
            val result = telegram.request(request, timeout = 8_000).getJSONArray("chat_ids")
            for (i in 0 until result.length()) ids += result.getLong(i)
        }
        val savedId = AppText.savedChatId
        if (
            !onServer &&
                savedId != 0L &&
                telegram.chats.containsKey(savedId) &&
                (query.isBlank() || tr(R.string.saved_messages).contains(query, ignoreCase = true))
        )
            ids += savedId
        val choices = mutableListOf<ChatChoice>()
        for (id in ids) {
            if (id in telegram.storageChats) continue
            currentCoroutineContext().ensureActive()
            val cached = telegram.chats[id]
            if (cached != null) {
                choices += cached
                continue
            }
            val chat = telegram.request(json("getChat", "chat_id" to id))
            if (chat.optJSONObject("type")?.kind() != "chatTypeSecret")
                choices += ChatChoice(chat.getLong("id"), chat.getString("title"))
        }
        return choices
    }

    suspend fun addPublicChat(value: String) {
        val name =
            value
                .trim()
                .removePrefix("https://t.me/")
                .removePrefix("http://t.me/")
                .removePrefix("@")
                .substringBefore('/')
        require(name.matches(Regex("[A-Za-z0-9_]{4,}"))) { tr(R.string.channel_name_required) }
        val chat = telegram.request(json("searchPublicChat", "username" to name))
        val choice = ChatChoice(chat.getLong("id"), chat.getString("title"))
        library.source(choice, true)
    }

    suspend fun refresh() = scanning.withLock {
        awaitLibrary()
        if (telegram.auth.value.type != "authorizationStateReady") return@withLock
        scanJob = currentCoroutineContext().job
        busy.value = true
        try {
            for (source in library.state.value.sources) for (document in listOf(false, true)) {
                val cursor = if (document) source.documentCursor else source.cursor
                val complete = if (document) source.documentsComplete else source.complete
                var newest = if (document) source.documentNewest else source.newest
                try {
                    if (!complete) {
                        // Upgrade/resume the old one-page library without resetting its cursor.
                        if (newest == 0L && cursor != 0L) {
                            newest =
                                library.state.value.tracks
                                    .asSequence()
                                    .filter { it.chatId == source.id && it.document == document }
                                    .maxOfOrNull { it.messageId } ?: 0L
                            library.newest(source.id, newest, document)
                        }
                        val first =
                            scanPages(source, document, fromMessage = cursor, history = true)
                        if (cursor == 0L) newest = first
                    }
                    // Advance the catch-up boundary only after every intervening page is saved.
                    val latest = scanPages(source, document, cutoff = newest)
                    library.newest(source.id, maxOf(newest, latest), document)
                } catch (e: Exception) {
                    if (e is CancellationException || (e as? TelegramException)?.code == 429)
                        throw e
                    notices.emit("${source.title}: ${friendly(e)}")
                }
            }
        } finally {
            busy.value = false
            scanJob = null
            withContext(NonCancellable) { library.reload() }
        }
    }

    private suspend fun scanPages(
        source: Source,
        document: Boolean,
        fromMessage: Long = 0,
        cutoff: Long = 0,
        history: Boolean = false,
        query: String = "",
    ): Long {
        var from = fromMessage
        var newest = 0L
        var publishedAt = 0L
        do {
            currentCoroutineContext().ensureActive()
            if (library.state.value.sources.none { it.id == source.id }) break
            val response =
                telegram.request(
                    json(
                        "searchChatMessages",
                        "chat_id" to source.id,
                        "topic_id" to null,
                        "query" to query,
                        "sender_id" to null,
                        "from_message_id" to from,
                        "offset" to 0,
                        "limit" to 100,
                        "filter" to
                            json(
                                if (document) "searchMessagesFilterDocument"
                                else "searchMessagesFilterAudio"
                            ),
                    )
                )
            val messages = response.getJSONArray("messages")
            val pageNewest =
                (0 until messages.length()).maxOfOrNull {
                    messages.getJSONObject(it).getLong("id")
                } ?: 0L
            newest = maxOf(newest, pageNewest)
            val sourceTitle =
                library.state.value.sources.firstOrNull { it.id == source.id }?.title
                    ?: source.title
            val tracks =
                withContext(Dispatchers.Default) {
                    (0 until messages.length()).mapNotNull {
                        parseTrack(messages.getJSONObject(it), sourceTitle)
                    }
                }
            library.upsert(tracks)
            val next = response.getLong("next_from_message_id")
            check(next == 0L || next != from) {
                tr(R.string.history_stalled)
            }
            if (history) {
                if (from == 0L) library.newest(source.id, newest, document)
                library.cursor(source.id, next, next == 0L, document)
            }
            val now = System.nanoTime() / 1_000_000
            if (now - publishedAt >= 500 || next == 0L) {
                library.reload()
                publishedAt = now
            }
            if (next == 0L || cutoff > 0 && next <= cutoff) break
            from = next
        } while (true)
        library.reload()
        return newest
    }

    suspend fun searchMusic(query: String) = scanning.withLock {
        if (query.isBlank() || telegram.auth.value.type != "authorizationStateReady")
            return@withLock
        busy.value = true
        scanJob = currentCoroutineContext().job
        try {
            for (source in library.state.value.sources) for (document in listOf(false, true)) {
                scanPages(source, document, query = query)
            }
            notices.emit(tr(R.string.chat_search_complete))
        } finally {
            busy.value = false
            scanJob = null
            withContext(NonCancellable) { library.reload() }
        }
    }

    private fun parseTrack(message: JSONObject, title: String): Track? {
        val content = message.optJSONObject("content") ?: return null
        val document = content.kind() == "messageDocument"
        val audio = content.optJSONObject(if (document) "document" else "audio") ?: return null
        if (
            document &&
                !audio.optString("mime_type").startsWith("audio/") &&
                audio.optString("file_name").substringAfterLast('.').lowercase() !in
                    setOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "aiff", "alac")
        )
            return null
        val file = audio.getJSONObject(if (document) "document" else "audio")
        telegram.updateFile(file)
        val chat = message.getLong("chat_id")
        val msg = message.getLong("id")
        val id = "$chat:$msg"
        val previous = track(id)
        val thumbnail =
            audio
                .optJSONObject(if (document) "thumbnail" else "album_cover_thumbnail")
                ?.optJSONObject("file")
        var art = ""
        thumbnail?.let {
            telegram.updateFile(it)
            val local = it.optJSONObject("local")
            if (local?.optBoolean("is_downloading_completed") == true) art = local.optString("path")
        }
        thumbnails[id] = thumbnail?.getInt("id") ?: 0
        resolvedFiles[id] = file.getInt("id")
        val local = file.optJSONObject("local")
        return Track(
            id,
            chat,
            msg,
            file.getInt("id"),
            audio.optString("title").ifBlank {
                previous?.title
                    ?: audio.optString("file_name").substringBeforeLast('.').ifBlank {
                        tr(R.string.audio_recording)
                    }
            },
            audio.optString("performer").ifBlank { previous?.artist.orEmpty() },
            audio.optInt("duration").takeIf { it > 0 } ?: previous?.duration ?: 0,
            file.optLong("size"),
            title,
            message.optLong("date"),
            art,
            if (local?.optBoolean("is_downloading_completed") == true) local.optString("path")
            else "",
            document = document,
            fileKey = file.optJSONObject("remote")?.optString("unique_id").orEmpty(),
            remoteId = file.optJSONObject("remote")?.optString("id").orEmpty(),
        )
    }

    private suspend fun handleUpdate(update: JSONObject) {
        when (update.kind()) {
            "updateChatTitle" -> {
                val id = update.getLong("chat_id")
                val title = update.getString("title")
                if (library.state.value.sources.any { it.id == id && it.title != title })
                    library.sourceTitle(id, title)
            }
            "spotygramFileComplete" -> {
                val id = update.getInt("id")
                val path = update.getString("path")
                val artwork = artworkFiles.remove(id)
                if (artwork != null) library.art(artwork, path)
                else if (library.state.value.tracks.any { it.fileId == id }) {
                    library.file(id, path)
                    listeningCache.signal()
                    withContext(Dispatchers.IO) {
                        val tracks =
                            library.state.value.tracks.filter {
                                it.fileId == id && it.document && it.duration == 0
                            }
                        if (tracks.isNotEmpty()) {
                            val metadata = platform.metadata(path)
                            library.upsert(
                                tracks.map {
                                    it.copy(
                                        duration = (metadata.duration / 1000).toInt(),
                                        title =
                                            metadata.title.takeIf { s -> s.isNotBlank() }
                                                ?: it.title,
                                        artist =
                                            metadata.artist.takeIf { s -> s.isNotBlank() }
                                                ?: it.artist,
                                    )
                                }
                            )
                            library.reload()
                        }
                    }
                }
            }
            "updateNewMessage" -> {
                val message = update.getJSONObject("message")
                val source =
                    library.state.value.sources.firstOrNull { it.id == message.optLong("chat_id") }
                        ?: return
                parseTrack(message, source.title)?.let {
                    library.upsert(listOf(it))
                    library.reload()
                }
            }
            "updateMessageContent" -> {
                val existing =
                    track("${update.getLong("chat_id")}:${update.getLong("message_id")}") ?: return
                val message =
                    telegram.request(
                        json(
                            "getMessage",
                            "chat_id" to existing.chatId,
                            "message_id" to existing.messageId,
                        )
                    )
                parseTrack(message, existing.source)?.let {
                    library.upsert(listOf(it))
                    library.reload()
                }
            }
            "updateDeleteMessages" ->
                if (update.optBoolean("is_permanent")) {
                    val ids = update.getJSONArray("message_ids")
                    library.unavailable(
                        update.getLong("chat_id"),
                        (0 until ids.length()).map { ids.getLong(it) },
                    )
                }
        }
    }

    suspend fun resolve(track: Track): Int {
        if (track.chatId == 0L) return 0
        resolvedFiles[track.id]?.let {
            return it
        }
        val response =
            telegram.request(
                json("getMessage", "chat_id" to track.chatId, "message_id" to track.messageId)
            )
        val parsed = parseTrack(response, track.source) ?: error(tr(R.string.message_audio_missing))
        library.upsert(listOf(parsed))
        library.reload()
        return parsed.fileId
    }

    suspend fun loadArtwork(track: Track) = artworkSlots.withPermit {
        if (
            track.chatId == 0L ||
                track.art.isNotEmpty() ||
                !track.available ||
                telegram.auth.value.type != "authorizationStateReady"
        )
            return@withPermit
        try {
            if (!thumbnails.containsKey(track.id)) resolve(track)
            val id = thumbnails[track.id]?.takeIf { it != 0 } ?: return@withPermit
            val complete = telegram.files[id]?.takeIf { it.complete }
            if (complete != null) library.art(track.id, complete.path)
            else {
                artworkFiles.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(track.id)
                telegram.download(id, priority = 1)
                withTimeout(15_000) {
                    telegram.fileRevision.first { telegram.files[id]?.complete == true }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            // Artwork is optional; a failed thumbnail must not interrupt listening.
        }
    }

    suspend fun download(ids: List<String>) {
        check(!musicCache.state.value.clearing) { tr(R.string.cache_clearing) }
        val wasTemporary = ids.any { track(it)?.temporary == true }
        listeningCache.retain(ids)
        val valid = ids.filter { track(it)?.let { t -> !t.local && t.available } == true }
        if (valid.isEmpty()) {
            if (wasTemporary) notices.emit(tr(R.string.saved_on_device))
            return
        }
        library.enqueue(valid)
        platform.startDownloads(this)
    }

    suspend fun removeLocal(track: Track) {
        val current = player?.currentMediaItem?.mediaId?.let { this.track(it) }
        require(current?.id != track.id && !(track.local && current?.path == track.path)) {
            tr(R.string.switch_track_first)
        }
        if (track.chatId != 0L) listeningCache.removeCopy(track)
        else {
            playback?.removeTrack(track.id)
            library.removeLocal(track)
        }
    }

    suspend fun messageLink(track: Track): String {
        if (track.chatId > 0) {
            val serverId = track.messageId shr 20
            require(serverId in 1..Int.MAX_VALUE.toLong()) { tr(R.string.message_unavailable) }
            return platform.personalMessageLink(track.chatId, serverId, AppText.savedChatId)
        }
        return telegram
            .request(
                json(
                    "getMessageLink",
                    "chat_id" to track.chatId,
                    "message_id" to track.messageId,
                    "media_timestamp" to 0,
                    "for_album" to false,
                    "in_message_thread" to false,
                )
            )
            .getString("link")
    }

    suspend fun importAudio(name: String, open: () -> InputStream) =
        withContext(Dispatchers.IO) {
            awaitLibrary()
            val dir = File(filesDir, "imports").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val file = File(dir, id)
            val artwork = File(dir, "$id.jpg")
            var stored = false
            try {
                open().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val metadata = platform.metadata(file.path, name)
                require(metadata.duration > 0) { tr(R.string.audio_read_failed) }
                val art =
                    metadata.artwork
                        ?.takeIf { it.size < 8 * 1024 * 1024 }
                        ?.let { data -> artwork.apply { writeBytes(data) }.path }
                        .orEmpty()
                currentCoroutineContext().ensureActive()
                // Once SQLite owns the entry, cancellation or a failed reload must keep its files.
                withContext(NonCancellable) {
                    library.upsert(
                        listOf(
                            Track(
                                "local:$id",
                                0,
                                0,
                                0,
                                metadata.title.takeIf { it.isNotBlank() }
                                    ?: name.substringBeforeLast('.').ifBlank {
                                        tr(R.string.audio_recording)
                                    },
                                metadata.artist,
                                (metadata.duration / 1000).toInt(),
                                file.length(),
                                tr(R.string.from_device),
                                System.currentTimeMillis() / 1000,
                                art,
                                file.path,
                            )
                        )
                    )
                    stored = true
                }
                library.reload()
                notices.emit(tr(R.string.import_complete))
            } catch (e: Exception) {
                if (!stored) {
                    file.delete()
                    artwork.delete()
                }
                throw e
            }
        }

    suspend fun logout() {
        cloudSync.stop()
        suppressCloudWrites = true
        try {
            listeningCache.exclusive {
                scanJob?.cancelAndJoin()
                playback?.clear()
                playback?.forgetTelegramProgress()
                platform.stopDownloads(this)
                telegram.request(json("logOut"))
                prefs.edit().putBoolean("telegram_connected", false).apply()
                library.clearTelegram()
                library.sync.detach()
                resolvedFiles.clear()
                thumbnails.clear()
                artworkFiles.clear()
            }
        } finally {
            suppressCloudWrites = false
        }
    }
}
