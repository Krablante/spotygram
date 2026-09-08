package app.spotygram

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class SpotygramApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var library: Library
    lateinit var telegram: Telegram
    val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val busy = MutableStateFlow(false)
    val chatChoices = MutableStateFlow<List<ChatChoice>>(emptyList())
    val downloading = MutableStateFlow(DownloadState())
    val theme = MutableStateFlow("system")
    private val scanning = Mutex()
    private val artworkFiles = ConcurrentHashMap<Int, MutableSet<String>>()
    var player: androidx.media3.common.Player? = null

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        telegram = Telegram(this, scope)
        theme.value = prefs.getString("theme", "system") ?: "system"
        action {
            library.reload()
            library.verifyFiles()
        }
        scope.launch {
            for (update in telegram.updates) {
                try {
                    handleUpdate(update)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        scope.launch {
            telegram.auth
                .map { it.type }
                .distinctUntilChanged()
                .collect { type ->
                    if (type == "authorizationStateReady")
                        action {
                            loadChats()
                            refresh()
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

    fun changeTheme(value: String) {
        prefs.edit().putString("theme", value).apply()
        theme.value = value
    }

    fun localMode() {
        prefs.edit().putBoolean("local_mode", true).apply()
    }

    fun track(id: String) = library.state.value.tracks.firstOrNull { it.id == id }

    suspend fun loadChats() {
        val choices = mutableMapOf<Long, String>()
        for (list in listOf("chatListMain", "chatListArchive")) {
            try {
                telegram.request(json("loadChats", "chat_list" to json(list), "limit" to 100))
            } catch (e: TelegramException) {
                if (e.code != 404) throw e
            }
            val ids =
                telegram
                    .request(json("getChats", "chat_list" to json(list), "limit" to 200))
                    .getJSONArray("chat_ids")
            for (i in 0 until ids.length()) {
                val chat = telegram.request(json("getChat", "chat_id" to ids.getLong(i)))
                if (chat.optJSONObject("type")?.kind() != "chatTypeSecret")
                    choices[chat.getLong("id")] = chat.getString("title")
            }
        }
        val me = telegram.request(json("getMe"))
        val saved =
            telegram.request(
                json("createPrivateChat", "user_id" to me.getLong("id"), "force" to false)
            )
        choices[saved.getLong("id")] = "Избранное"
        chatChoices.value =
            choices
                .map { ChatChoice(it.key, it.value) }
                .sortedWith(
                    compareBy<ChatChoice> { it.title != "Избранное" }
                        .thenBy { it.title.lowercase() }
                )
    }

    suspend fun searchChats(query: String) {
        if (query.isBlank()) {
            loadChats()
            return
        }
        val ids =
            telegram
                .request(json("searchChatsOnServer", "query" to query, "limit" to 100))
                .getJSONArray("chat_ids")
        val choices = mutableListOf<ChatChoice>()
        for (i in 0 until ids.length()) {
            val chat = telegram.request(json("getChat", "chat_id" to ids.getLong(i)))
            if (chat.optJSONObject("type")?.kind() != "chatTypeSecret")
                choices += ChatChoice(chat.getLong("id"), chat.getString("title"))
        }
        chatChoices.value = choices
    }

    suspend fun addPublicChat(value: String) {
        val name =
            value
                .trim()
                .removePrefix("https://t.me/")
                .removePrefix("http://t.me/")
                .removePrefix("@")
                .substringBefore('/')
        require(name.matches(Regex("[A-Za-z0-9_]{4,}"))) { "Укажите @имя публичного канала" }
        val chat = telegram.request(json("searchPublicChat", "username" to name))
        val choice = ChatChoice(chat.getLong("id"), chat.getString("title"))
        library.source(choice, true)
        refresh()
    }

    suspend fun refresh(older: Boolean = false) = scanning.withLock {
        if (telegram.auth.value.type != "authorizationStateReady") return@withLock
        busy.value = true
        try {
            for (source in library.state.value.sources) for (document in listOf(false, true)) {
                val cursor = if (document) source.documentCursor else source.cursor
                val complete = if (document) source.documentsComplete else source.complete
                if (older && complete) continue
                if (library.state.value.sources.none { it.id == source.id }) continue
                val newest =
                    library.state.value.tracks
                        .filter { it.chatId == source.id && it.document == document }
                        .maxOfOrNull { it.messageId } ?: 0L
                var from = if (older) cursor else 0
                do {
                    val response =
                        telegram.request(
                            json(
                                "searchChatMessages",
                                "chat_id" to source.id,
                                "topic_id" to null,
                                "query" to "",
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
                    val result = mutableListOf<Track>()
                    for (i in 0 until messages.length()) parseTrack(
                            messages.getJSONObject(i),
                            source.title,
                        )
                        ?.let { result += it }
                    library.upsert(result)
                    val next = response.optLong("next_from_message_id")
                    if (older || cursor == 0L && !complete)
                        library.cursor(source.id, next, next == 0L, document)
                    library.reload()
                    delay(120)
                    if (
                        older ||
                            newest == 0L ||
                            next == 0L ||
                            next == from ||
                            next <= newest ||
                            result.any { it.messageId <= newest }
                    )
                        break
                    from = next
                } while (library.state.value.sources.any { it.id == source.id })
            }
        } finally {
            busy.value = false
        }
    }

    suspend fun searchMusic(query: String) = scanning.withLock {
        if (query.isBlank() || telegram.auth.value.type != "authorizationStateReady")
            return@withLock
        busy.value = true
        try {
            for (source in library.state.value.sources) for (document in listOf(false, true)) {
                val response =
                    telegram.request(
                        json(
                            "searchChatMessages",
                            "chat_id" to source.id,
                            "topic_id" to null,
                            "query" to query,
                            "sender_id" to null,
                            "from_message_id" to 0,
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
                val tracks =
                    (0 until messages.length()).mapNotNull {
                        parseTrack(messages.getJSONObject(it), source.title)
                    }
                library.upsert(tracks)
                library.reload()
                delay(120)
            }
            notices.emit("Поиск в выбранных чатах завершён")
        } finally {
            busy.value = false
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
            else {
                val fid = it.getInt("id")
                artworkFiles.getOrPut(fid) { ConcurrentHashMap.newKeySet() }.add(id)
                scope.launch { runCatching { telegram.download(fid, priority = 1) } }
            }
        }
        val local = file.optJSONObject("local")
        return Track(
            id,
            chat,
            msg,
            file.getInt("id"),
            audio.optString("title").ifBlank {
                previous?.title
                    ?: audio.optString("file_name").substringBeforeLast('.').ifBlank {
                        "Аудиозапись"
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
        )
    }

    private suspend fun handleUpdate(update: JSONObject) {
        when (update.kind()) {
            "spotygramFileComplete" -> {
                val id = update.getInt("id")
                val path = update.getString("path")
                val artwork = artworkFiles.remove(id)
                if (artwork != null) artwork.forEach { library.art(it, path) }
                else if (library.state.value.tracks.any { it.fileId == id }) {
                    library.file(id, path)
                    withContext(Dispatchers.IO) {
                        val tracks =
                            library.state.value.tracks.filter {
                                it.fileId == id && it.document && it.duration == 0
                            }
                        if (tracks.isNotEmpty()) {
                            val metadata = MediaMetadataRetriever()
                            try {
                                metadata.setDataSource(path)
                                val duration =
                                    (metadata
                                        .extractMetadata(
                                            MediaMetadataRetriever.METADATA_KEY_DURATION
                                        )
                                        ?.toLongOrNull() ?: 0L) / 1000
                                val title =
                                    metadata.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_TITLE
                                    )
                                val artist =
                                    metadata.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_ARTIST
                                    )
                                library.upsert(
                                    tracks.map {
                                        it.copy(
                                            duration = duration.toInt(),
                                            title =
                                                title?.takeIf { s -> s.isNotBlank() } ?: it.title,
                                            artist =
                                                artist?.takeIf { s -> s.isNotBlank() } ?: it.artist,
                                        )
                                    }
                                )
                                library.reload()
                            } finally {
                                metadata.release()
                            }
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
                    library.state.value.tracks.firstOrNull {
                        it.chatId == update.optLong("chat_id") &&
                            it.messageId == update.optLong("message_id")
                    } ?: return
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
        val response =
            telegram.request(
                json("getMessage", "chat_id" to track.chatId, "message_id" to track.messageId)
            )
        val parsed =
            parseTrack(response, track.source) ?: error("В сообщении больше нет аудиофайла")
        library.upsert(listOf(parsed))
        library.reload()
        return parsed.fileId
    }

    suspend fun download(ids: List<String>) {
        val valid = ids.filter { track(it)?.let { t -> !t.local && t.available } == true }
        if (valid.isEmpty()) return
        library.enqueue(valid)
        ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))
    }

    suspend fun removeLocal(track: Track) {
        require(player?.currentMediaItem?.mediaId != track.id) {
            "Сначала переключите текущий трек"
        }
        if (track.chatId != 0L) telegram.request(json("deleteFile", "file_id" to track.fileId))
        library.removeLocal(track)
    }

    suspend fun messageLink(track: Track): String {
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

    suspend fun importAudio(uri: Uri) =
        withContext(Dispatchers.IO) {
            val dir = File(filesDir, "imports").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val file = File(dir, id)
            val retriever = MediaMetadataRetriever()
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: error("Файл недоступен")
                retriever.setDataSource(file.path)
                val duration =
                    (retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: error("Не удалось прочитать аудиофайл")) / 1000
                val name =
                    contentResolver
                        .query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        .orEmpty()
                val art =
                    retriever.embeddedPicture
                        ?.takeIf { it.size < 8 * 1024 * 1024 }
                        ?.let { data -> File(dir, "$id.jpg").apply { writeBytes(data) }.path }
                        .orEmpty()
                library.upsert(
                    listOf(
                        Track(
                            "local:$id",
                            0,
                            0,
                            0,
                            retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                ?.takeIf { it.isNotBlank() }
                                ?: name.substringBeforeLast('.').ifBlank { "Аудиозапись" },
                            retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                .orEmpty(),
                            duration.toInt(),
                            file.length(),
                            "С телефона",
                            System.currentTimeMillis() / 1000,
                            art,
                            file.path,
                        )
                    )
                )
                library.reload()
                notices.emit("Добавлено в музыку")
            } catch (e: Exception) {
                file.delete()
                throw e
            } finally {
                retriever.release()
            }
        }

    suspend fun logout() {
        player?.stop()
        player?.clearMediaItems()
        stopService(Intent(this, DownloadService::class.java))
        telegram.request(json("logOut"))
        prefs.edit().putBoolean("telegram_connected", false).apply()
        library.clearTelegram()
    }
}
