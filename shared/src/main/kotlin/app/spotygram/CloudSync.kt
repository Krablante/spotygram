package app.spotygram

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SyncState(val phase: String = "offline", val lastSync: Long = 0, val error: String = "")

/**
 * One event-driven worker uses the existing TDLib connection; Telegram owns transport and storage.
 */
class CloudSync(private val app: SpotygramApp) {
    val state = MutableStateFlow(SyncState())
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scope =
        CoroutineScope(app.scope.coroutineContext + SupervisorJob(app.scope.coroutineContext[Job]))
    private var worker: Job? = null
    private var retry: Job? = null
    private var connected = false
    private var initialized = false
    private var failures = 0
    private var retryAt = 0L
    private var floodAt = 0L
    private var rooms = emptyList<Long>()
    private val inspected = mutableSetOf<Long>()
    private var lastPreferences = emptyMap<String, String>()
    private val store
        get() = app.library.sync

    private val telegram
        get() = app.telegram

    private val prefix = "spotygram-sync/1"
    private val marker = "spotygram-storage/1"
    private val maxCompressed = 8 * 1024 * 1024
    private val maxExpanded = 32 * 1024 * 1024

    init {
        scope.launch { for (ignored in store.changes) signal() }
        scope.launch { for (ignored in telegram.cloudUpdates) signal() }
    }

    fun signal() {
        signals.trySend(Unit)
    }

    fun connection(ready: Boolean) {
        connected = ready
        if (!ready) {
            worker?.cancel()
            worker = null
            retry?.cancel()
            retry = null
            initialized = false
            state.value = state.value.copy(phase = "offline")
        } else {
            if (worker?.isActive != true)
                worker =
                    scope.launch(Dispatchers.IO) {
                        for (ignored in signals) {
                            if (!connected) continue
                            if (System.currentTimeMillis() < retryAt) continue
                            delay(800)
                            while (signals.tryReceive().isSuccess) {
                                /* Coalesce a burst of library and native events. */
                            }
                            try {
                                synchronize()
                                failures = 0
                                retry?.cancel()
                                retry = null
                                retryAt = 0
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                initialized = false
                                failures++
                                state.value = state.value.copy(phase = "error", error = friendly(e))
                                retry?.cancel()
                                val flood =
                                    Regex(
                                            "(?:FLOOD_WAIT_|retry after )([0-9]+)",
                                            RegexOption.IGNORE_CASE,
                                        )
                                        .find(e.message.orEmpty())
                                        ?.groupValues
                                        ?.get(1)
                                        ?.toLongOrNull()
                                        ?.coerceAtMost(Int.MAX_VALUE.toLong())
                                        ?.times(1000)
                                val wait =
                                    maxOf(
                                        flood ?: 0L,
                                        minOf(900_000L, 15_000L * (1L shl minOf(failures, 5))),
                                    )
                                retryAt = System.currentTimeMillis() + wait
                                if (flood != null) {
                                    floodAt = System.currentTimeMillis() + flood
                                    store.meta("flood/${store.account}", floodAt.toString())
                                }
                                retry = scope.launch {
                                    delay(wait)
                                    signal()
                                }
                            }
                        }
                    }
            signal()
        }
    }

    fun retry() {
        if (System.currentTimeMillis() < floodAt) return
        retry?.cancel()
        retryAt = 0
        signal()
    }

    suspend fun stop() {
        connected = false
        worker?.cancelAndJoin()
        worker = null
        retry?.cancelAndJoin()
        retry = null
        for (room in rooms) runCatching { telegram.request(json("closeChat", "chat_id" to room)) }
        initialized = false
        rooms = emptyList()
        inspected.clear()
        lastPreferences = emptyMap()
        retryAt = 0
        floodAt = 0
        telegram.storageChats.clear()
        state.value = SyncState()
    }

    suspend fun close() {
        stop()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun synchronize() {
        app.awaitLibrary()
        state.value = state.value.copy(phase = "syncing", error = "")
        if (!initialized) {
            val user = telegram.request(json("getMe")).getLong("id")
            store.bind(user)
            floodAt = store.meta("flood/$user").toLongOrNull() ?: 0
            if (floodAt > System.currentTimeMillis())
                throw TelegramException(
                    429,
                    "FLOOD_WAIT_${(floodAt - System.currentTimeMillis() + 999) / 1000}",
                )
            rooms = discover(user)
            telegram.storageChats.addAll(rooms)
            for (room in rooms) {
                quiet(room)
                telegram.request(json("openChat", "chat_id" to room))
            }
            initialized = true
        }
        // A second first-run client can create another room before either creation is visible.
        val newRoom = telegram.channelChats.any { it !in inspected }
        if (newRoom) {
            val found = discover(store.account)
            if (found != rooms) {
                rooms = found
                telegram.storageChats.addAll(rooms)
                for (room in rooms) {
                    quiet(room)
                    telegram.request(json("openChat", "chat_id" to room))
                }
            }
        }
        val changed = linkedSetOf<String>()
        for (room in rooms) changed += read(room)
        applyPreferences()
        if (changed.isNotEmpty())
            withContext(Dispatchers.Main.immediate) { app.cloudLibraryChanged(changed) }
        val room = rooms.first()
        var packets = store.meta("packets/$room").toIntOrNull() ?: 0
        val initial = store.meta("snapshot/$room").isEmpty()
        val firstPending = store.records(pending = true, limit = 1500)
        if (initial || firstPending.size == 1500) {
            snapshot(room)
            packets = 0
        }
        while (true) {
            val pending = store.records(pending = true, limit = 1500)
            if (pending.isEmpty()) break
            send(room, pending, snapshot = false)
            store.acknowledge(pending)
            packets++
            store.meta("packets/$room", packets.toString())
        }
        if (packets >= 32 || rooms.size > 1 && changed.isNotEmpty()) snapshot(room)
        quiet(room)
        val now = System.currentTimeMillis()
        store.meta("last_sync/${store.account}", now.toString())
        state.value = SyncState("ready", now)
    }

    private suspend fun snapshot(room: Long) {
        val all = store.records()
        val through = store.meta("cursor/$room").toLongOrNull() ?: 0L
        val sent = send(room, all, snapshot = true, through = through)
        store.acknowledge(all)
        store.meta("snapshot/$room", sent.getLong("id").toString())
        store.meta("packets/$room", "0")
        compact(room, through)
    }

    private suspend fun discover(user: Long): List<Long> {
        val candidates = linkedSetOf<Long>()
        val remembered = store.meta("channel/$user").toLongOrNull()
        if (remembered != null) candidates += remembered
        for (list in listOf("chatListMain", "chatListArchive")) {
            while (true) {
                try {
                    telegram.request(json("loadChats", "chat_list" to json(list), "limit" to 100))
                } catch (e: TelegramException) {
                    if (e.code != 404) throw e
                    break
                }
            }
        }
        val search =
            telegram
                .request(json("searchChatsOnServer", "query" to "spotygram", "limit" to 100))
                .getJSONArray("chat_ids")
        for (i in 0 until search.length()) candidates += search.getLong(i)
        candidates += telegram.channelChats
        val valid = mutableListOf<Long>()
        for (id in candidates) {
            val chat =
                try {
                    telegram.request(json("getChat", "chat_id" to id))
                } catch (e: TelegramException) {
                    if (e.code in listOf(400, 404, 406)) continue
                    throw e
                }
            val type = chat.getJSONObject("type")
            if (type.kind() != "chatTypeSupergroup" || !type.optBoolean("is_channel")) continue
            val group = type.getLong("supergroup_id")
            val info =
                try {
                    telegram.request(json("getSupergroup", "supergroup_id" to group))
                } catch (e: TelegramException) {
                    if (e.code in listOf(400, 404, 406)) continue
                    throw e
                }
            if (info.getJSONObject("status").kind() != "chatMemberStatusCreator") continue
            val full =
                try {
                    telegram.request(json("getSupergroupFullInfo", "supergroup_id" to group))
                } catch (e: TelegramException) {
                    if (e.code in listOf(400, 404, 406)) continue
                    throw e
                }
            val description = full.getString("description")
            if (!description.startsWith("spotygram-storage/")) continue
            require(description.startsWith("$marker\n")) { tr(R.string.sync_newer_version) }
            val identity = JSONObject(description.substringAfter('\n'))
            if (
                identity.getLong("account") == user &&
                    identity.optString("id").matches(Regex("[a-f0-9-]{36}"))
            )
                valid += id
        }
        inspected.addAll(candidates)
        if (valid.isEmpty()) {
            require(remembered == null) { tr(R.string.sync_storage_missing) }
            val uid = UUID.randomUUID().toString()
            val chat =
                telegram.request(
                    json(
                        "createNewSupergroupChat",
                        "title" to "spotygram-${uid.replace("-", "").take(12)}",
                        "is_forum" to false,
                        "is_channel" to true,
                        "description" to
                            "$marker\n${JSONObject().put("account", user).put("id", uid)}",
                        "message_auto_delete_time" to 0,
                        "for_import" to false,
                    )
                )
            valid += chat.getLong("id")
            inspected += chat.getLong("id")
        }
        val result = valid.distinct().sortedBy { -it }
        store.meta("channel/$user", result.first().toString())
        return result
    }

    private suspend fun quiet(room: Long) {
        val chat = telegram.request(json("getChat", "chat_id" to room))
        val settings = chat.getJSONObject("notification_settings")
        if (
            settings.optBoolean("use_default_mute_for", true) ||
                settings.optInt("mute_for") < 1_000_000
        ) {
            val muted =
                JSONObject(settings.toString())
                    .put("use_default_mute_for", false)
                    .put("mute_for", Int.MAX_VALUE)
                    .put("use_default_disable_pinned_message_notifications", false)
                    .put("disable_pinned_message_notifications", true)
                    .put("use_default_disable_mention_notifications", false)
                    .put("disable_mention_notifications", true)
            telegram.request(
                json(
                    "setChatNotificationSettings",
                    "chat_id" to room,
                    "notification_settings" to muted,
                )
            )
        }
        val positions = chat.optJSONArray("positions") ?: JSONArray()
        val archived =
            (0 until positions.length()).any {
                positions.getJSONObject(it).getJSONObject("list").kind() == "chatListArchive"
            }
        if (!archived)
            telegram.request(
                json("addChatToList", "chat_id" to room, "chat_list" to json("chatListArchive"))
            )
        if (chat.optInt("message_auto_delete_time") != 0)
            telegram.request(
                json(
                    "setChatMessageAutoDeleteTime",
                    "chat_id" to room,
                    "message_auto_delete_time" to 0,
                )
            )
    }

    private suspend fun read(room: Long): Set<String> {
        val previous = store.meta("cursor/$room").toLongOrNull() ?: 0L
        var covered = previous
        var from = 0L
        var newest = previous
        var stalled = 0
        var latestSnapshot = 0L
        val incoming = linkedMapOf<String, LibrarySync.Record>()
        history@ while (true) {
            val messages =
                telegram
                    .request(
                        json(
                            "getChatHistory",
                            "chat_id" to room,
                            "from_message_id" to from,
                            "offset" to 0,
                            "limit" to 100,
                            "only_local" to false,
                        )
                    )
                    .getJSONArray("messages")
            if (messages.length() == 0) break
            var next = from
            for (i in 0 until messages.length()) {
                val message = messages.getJSONObject(i)
                if (message.optJSONObject("sending_state") != null) continue
                val id = message.getLong("id")
                newest = maxOf(newest, id)
                if (id <= covered) break@history
                if (from != 0L && id >= from) continue
                next = id
                val content = message.getJSONObject("content")
                if (content.kind() == "messageSupergroupChatCreate") break@history
                val caption = content.optJSONObject("caption")?.optString("text").orEmpty()
                if (!caption.startsWith("spotygram-sync/")) continue
                require(caption.startsWith("$prefix ")) { tr(R.string.sync_newer_version) }
                val (packet, records) =
                    try {
                        val packet = download(content, caption)
                        packet to LibrarySync.decode(packet.getJSONArray("records"))
                    } catch (e: Exception) {
                        if (e is CancellationException || e is TelegramException) throw e
                        throw IllegalArgumentException(tr(R.string.sync_invalid_data), e)
                    }
                for (r in records) {
                    if (r.supersedes(incoming[r.key])) incoming[r.key] = r
                }
                if (packet.getString("kind") == "snapshot") {
                    covered = maxOf(covered, packet.getLong("through"))
                    latestSnapshot = maxOf(latestSnapshot, id)
                }
            }
            if (next == from) {
                require(++stalled <= 3) { tr(R.string.sync_history_incomplete) }
                delay(200)
            } else {
                from = next
                stalled = 0
            }
        }
        val changed = store.merge(incoming.values.toList())
        if (latestSnapshot != 0L) store.meta("snapshot/$room", latestSnapshot.toString())
        store.meta("cursor/$room", newest.toString())
        return changed
    }

    private suspend fun download(content: JSONObject, caption: String): JSONObject {
        val parts = caption.split(' ')
        require(
            parts.size == 6 &&
                parts[1].toLong() == store.account &&
                parts[2] in listOf("delta", "snapshot")
        )
        val file = content.getJSONObject("document").getJSONObject("document")
        require(file.getLong("size") in 1..maxCompressed.toLong()) {
            tr(R.string.sync_invalid_data)
        }
        val response =
            telegram.request(
                json(
                    "downloadFile",
                    "file_id" to file.getInt("id"),
                    "priority" to 4,
                    "offset" to 0,
                    "limit" to 0,
                    "synchronous" to true,
                ),
                timeout = 120_000,
            )
        val local = response.getJSONObject("local")
        require(local.getBoolean("is_downloading_completed")) {
            tr(R.string.sync_history_incomplete)
        }
        try {
            val bytes = File(local.getString("path")).readBytes()
            require(bytes.size == file.getLong("size").toInt() && digest(bytes) == parts[4]) {
                tr(R.string.sync_invalid_data)
            }
            val plain =
                GZIPInputStream(bytes.inputStream()).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= maxExpanded) {
                            tr(R.string.sync_invalid_data)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            val packet = JSONObject(plain.toString(Charsets.UTF_8))
            require(
                packet.getInt("format") == LibrarySync.FORMAT &&
                    packet.getLong("account") == store.account &&
                    packet.getString("kind") == parts[2] &&
                    packet.getLong("through") == parts[5].toLong()
            ) {
                tr(R.string.sync_invalid_data)
            }
            return packet
        } finally {
            // Metadata is already durable in SQLite; never accumulate a second document cache.
            runCatching { telegram.request(json("deleteFile", "file_id" to file.getInt("id"))) }
        }
    }

    private suspend fun send(
        room: Long,
        records: List<LibrarySync.Record>,
        snapshot: Boolean,
        through: Long = 0,
    ): JSONObject {
        val kind = if (snapshot) "snapshot" else "delta"
        val token = UUID.randomUUID().toString()
        val packet =
            JSONObject()
                .put("format", LibrarySync.FORMAT)
                .put("account", store.account)
                .put("kind", kind)
                .put("through", through)
                .put("records", JSONArray().apply { for (r in records) put(r.json()) })
        val plain = packet.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= maxExpanded) { tr(R.string.sync_invalid_data) }
        val bytes =
            ByteArrayOutputStream()
                .also { output -> GZIPOutputStream(output).use { it.write(plain) } }
                .toByteArray()
        require(bytes.size <= maxCompressed) { tr(R.string.sync_invalid_data) }
        val directory = File(app.cacheDir, "sync").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "spotygram-$token.spotygram")
        file.writeBytes(bytes)
        try {
            return telegram.sendConfirmed(
                json(
                    "sendMessage",
                    "chat_id" to room,
                    "options" to
                        json(
                            "messageSendOptions",
                            "disable_notification" to true,
                            "from_background" to true,
                        ),
                    "input_message_content" to
                        json(
                            "inputMessageDocument",
                            "document" to
                                json(
                                    "inputDocument",
                                    "document" to
                                        json("inputFileLocal", "path" to file.absolutePath),
                                    "disable_content_type_detection" to true,
                                ),
                            "caption" to
                                json(
                                    "formattedText",
                                    "text" to
                                        "$prefix ${store.account} $kind $token ${digest(bytes)} $through",
                                ),
                        ),
                )
            )
        } finally {
            file.delete()
        }
    }

    private suspend fun compact(room: Long, through: Long) {
        if (through == 0L) return
        val ids = mutableListOf<Long>()
        var from = through
        while (true) {
            val page =
                telegram
                    .request(
                        json(
                            "getChatHistory",
                            "chat_id" to room,
                            "from_message_id" to from,
                            "offset" to 0,
                            "limit" to 100,
                            "only_local" to false,
                        )
                    )
                    .getJSONArray("messages")
            var next = from
            for (i in 0 until page.length()) {
                val message = page.getJSONObject(i)
                val id = message.getLong("id")
                next = minOf(next, id)
                val caption =
                    message
                        .getJSONObject("content")
                        .optJSONObject("caption")
                        ?.optString("text")
                        .orEmpty()
                if (id <= through && caption.startsWith("$prefix ${store.account} ")) ids += id
            }
            if (next == from || page.length() == 0) break
            from = next
        }
        for (chunk in ids.distinct().chunked(100)) telegram.request(
            json(
                "deleteMessages",
                "chat_id" to room,
                "message_ids" to JSONArray(chunk),
                "revoke" to true,
            )
        )
    }

    private suspend fun applyPreferences() {
        val values = store.preferences()
        val serialized = values.mapValues { it.value.toString() }
        if (serialized == lastPreferences) return
        lastPreferences = serialized
        withContext(Dispatchers.Main.immediate) { app.applyCloudPreferences(values) }
    }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
