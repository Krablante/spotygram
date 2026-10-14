package app.spotygram

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

data class ShareFolder(val id: Int, val title: String)

data class ShareRecipient(
    val id: Long,
    val title: String,
    val channel: Boolean = false,
    val privateChat: Boolean = false,
    val forum: Boolean = false,
) {
    val displayTitle
        get() = chatTitle(id, title)

    val description
        get() =
            tr(
                when {
                    id == AppText.savedChatId -> R.string.share_for_self
                    channel -> R.string.share_channel
                    privateChat -> R.string.share_private_chat
                    forum -> R.string.share_forum
                    else -> R.string.share_group
                }
            )
}

data class ShareTopic(val id: Int, val title: String)

data class SharePage(val recipients: List<ShareRecipient>, val more: Boolean)

data class ShareRecording(val hasCaption: Boolean, val copy: Boolean, val forward: Boolean)

data class ShareDefaults(val author: Boolean = false, val caption: Boolean = false)

/** Recipient permissions and explicit sends; browsing never indexes a music source. */
class Sharing(private val app: SpotygramApp) {
    private val telegram
        get() = app.telegram

    private val prefs = app.platform.preferences("sharing")
    val defaults =
        MutableStateFlow(
            ShareDefaults(
                prefs.getBoolean("author", false),
                prefs.getBoolean("caption", false),
            )
        )
    val pinned = MutableStateFlow<List<Long>>(emptyList())
    val recent = MutableStateFlow<List<Long>>(emptyList())
    val sending = MutableStateFlow(false)
    private val edits = Mutex()
    private var account = 0L
    private var active: Job? = null
    private var permissionRevision = -1L
    private var permissionDocument = false
    private val permissions = mutableMapOf<Long, ShareRecipient?>()

    fun changeDefaults(
        author: Boolean = defaults.value.author,
        caption: Boolean = defaults.value.caption,
    ) {
        defaults.value = ShareDefaults(author, caption)
        prefs.edit().putBoolean("author", author).putBoolean("caption", caption).apply()
    }

    suspend fun prepare() {
        requireConnection()
        val me = telegram.request(json("getMe")).getLong("id")
        if (me != account) {
            account = me
            val saved = app.platform.preferences("sharing-$account")
            fun ids(key: String, limit: Int) =
                saved
                    .getString(key, "")
                    .orEmpty()
                    .split(',')
                    .mapNotNull(String::toLongOrNull)
                    .distinct()
                    .take(limit)
            pinned.value = ids("pinned", 8)
            recent.value = ids("recent", 24)
            permissions.clear()
        }
        val saved = telegram.request(json("createPrivateChat", "user_id" to me, "force" to false))
        telegram.rememberChat(saved)
        AppText.rememberSavedChat(saved.getLong("id"))
    }

    fun pin(id: Long) {
        if (account == 0L) return
        val old = pinned.value
        if (id !in old && old.size >= 8) {
            app.notices.tryEmit(tr(R.string.share_pin_limit))
            return
        }
        pinned.value = if (id in old) old - id else (listOf(id) + old).take(8)
        app.platform
            .preferences("sharing-$account")
            .edit()
            .putString("pinned", pinned.value.joinToString(","))
            .apply()
    }

    private fun rememberSend(id: Long) {
        recent.value = (listOf(id) + recent.value).distinct().take(24)
        app.platform
            .preferences("sharing-$account")
            .edit()
            .putString("recent", recent.value.joinToString(","))
            .apply()
    }

    private fun requireConnection() {
        check(telegram.auth.value.type == "authorizationStateReady") {
            tr(R.string.telegram_required)
        }
        check(telegram.connection.value.isEmpty()) { tr(R.string.share_offline) }
    }

    private fun chatList(folder: Int) =
        when (folder) {
            0 -> json("chatListMain")
            -1 -> json("chatListArchive")
            else -> json("chatListFolder", "chat_folder_id" to folder)
        }

    suspend fun list(
        folder: Int,
        query: String,
        limit: Int,
        document: Boolean,
        server: Boolean = false,
    ): SharePage {
        requireConnection()
        val ids =
            if (query.isNotBlank()) {
                telegram
                    .request(
                        json(
                            if (server) "searchChatsOnServer" else "searchChats",
                            "query" to query,
                            "limit" to limit,
                        ),
                        timeout = 8_000,
                    )
                    .getJSONArray("chat_ids")
            } else {
                var found =
                    telegram
                        .request(
                            json("getChats", "chat_list" to chatList(folder), "limit" to limit)
                        )
                        .getJSONArray("chat_ids")
                while (found.length() < limit) {
                    val previous = found.length()
                    try {
                        telegram.request(
                            json("loadChats", "chat_list" to chatList(folder), "limit" to 100)
                        )
                    } catch (e: TelegramException) {
                        if (e.code != 404) throw e
                        break
                    }
                    found =
                        telegram
                            .request(
                                json("getChats", "chat_list" to chatList(folder), "limit" to limit)
                            )
                            .getJSONArray("chat_ids")
                    if (found.length() <= previous) break
                    currentCoroutineContext().ensureActive()
                }
                found
            }
        val result = mutableListOf<ShareRecipient>()
        for (i in 0 until ids.length()) {
            currentCoroutineContext().ensureActive()
            recipient(ids.getLong(i), document)?.let(result::add)
        }
        return SharePage(result, query.isBlank() && ids.length() >= limit)
    }

    suspend fun quick(document: Boolean): List<ShareRecipient> {
        val result = mutableListOf<ShareRecipient>()
        val telegramPins = mutableListOf<Long>()
        val main =
            telegram
                .request(json("getChats", "chat_list" to json("chatListMain"), "limit" to 40))
                .getJSONArray("chat_ids")
        for (i in 0 until main.length()) {
            val chat = telegram.request(json("getChat", "chat_id" to main.getLong(i)))
            val positions = chat.optJSONArray("positions") ?: continue
            if (
                (0 until positions.length()).any {
                    val position = positions.getJSONObject(it)
                    position.getJSONObject("list").kind() == "chatListMain" &&
                        position.optBoolean("is_pinned")
                }
            )
                telegramPins += chat.getLong("id")
        }
        for (id in (pinned.value + recent.value + telegramPins + AppText.savedChatId).distinct()) {
            if (id == 0L) continue
            recipient(id, document)?.let(result::add)
            if (result.size == pinned.value.size.coerceIn(4, 8)) break
        }
        return result
    }

    suspend fun recipient(id: Long, document: Boolean, fresh: Boolean = false): ShareRecipient? {
        if (id in telegram.storageChats) return null
        val revision = telegram.recipientRevision.value
        if (permissionRevision != revision || permissionDocument != document) {
            permissions.clear()
            permissionRevision = revision
            permissionDocument = document
        }
        if (!fresh && permissions.containsKey(id)) {
            val cached = permissions[id] ?: return null
            return cached.copy(title = telegram.chats[id]?.title ?: cached.title)
        }
        val result =
            try {
                inspectRecipient(id, document)
            } catch (e: TelegramException) {
                if (e.code !in listOf(400, 403, 404)) throw e
                null
            }
        if (permissions.size >= 8192) permissions.clear()
        permissions[id] = result
        return result
    }

    private suspend fun inspectRecipient(id: Long, document: Boolean): ShareRecipient? {
        val chat = telegram.request(json("getChat", "chat_id" to id))
        telegram.rememberChat(chat)
        val type = chat.getJSONObject("type")
        val permission = if (document) "can_send_documents" else "can_send_audios"
        val title = chat.getString("title")
        when (type.kind()) {
            "chatTypePrivate" -> {
                if (id == AppText.savedChatId) return ShareRecipient(id, title, privateChat = true)
                if (chat.optJSONObject("block_list")?.kind() == "blockListMain") return null
                val user = telegram.request(json("getUser", "user_id" to type.getLong("user_id")))
                if (
                    user.getJSONObject("type").kind() == "userTypeDeleted" ||
                        !user.optBoolean("have_access", true)
                )
                    return null
                val allowed =
                    telegram.request(
                        json(
                            "canSendMessageToUser",
                            "user_id" to user.getLong("id"),
                            "only_local" to
                                !(user.optBoolean("restricts_new_chats") ||
                                    user.optLong("paid_message_star_count") > 0),
                        )
                    )
                if (allowed.kind() != "canSendMessageToUserResultOk") return null
                return ShareRecipient(id, title, privateChat = true)
            }
            "chatTypeBasicGroup",
            "chatTypeSupergroup" -> {
                val basic = type.kind() == "chatTypeBasicGroup"
                val group =
                    telegram.request(
                        json(
                            if (basic) "getBasicGroup" else "getSupergroup",
                            (if (basic) "basic_group_id" else "supergroup_id") to
                                type.getLong(if (basic) "basic_group_id" else "supergroup_id"),
                        )
                    )
                if (basic && !group.optBoolean("is_active", true)) return null
                if (group.optBoolean("is_direct_messages_group")) return null
                val status = group.getJSONObject("status")
                val creator = status.kind() == "chatMemberStatusCreator"
                val admin = creator || status.kind() == "chatMemberStatusAdministrator"
                val channel = !basic && type.optBoolean("is_channel")
                if (channel) {
                    if (
                        !creator &&
                            !(admin &&
                                status.getJSONObject("rights").optBoolean("can_post_messages"))
                    )
                        return null
                } else {
                    if (status.kind() == "chatMemberStatusBanned") return null
                    if (creator && !status.optBoolean("is_member", true)) return null
                    if (
                        status.kind() == "chatMemberStatusLeft" &&
                            (basic || group.optBoolean("join_to_send_messages", true))
                    )
                        return null
                    if (
                        status.kind() == "chatMemberStatusRestricted" &&
                            !status.optBoolean("is_member")
                    )
                        return null
                    if (!admin) {
                        if (!chat.getJSONObject("permissions").optBoolean(permission)) return null
                        if (
                            status.kind() == "chatMemberStatusRestricted" &&
                                !status.getJSONObject("permissions").optBoolean(permission)
                        )
                            return null
                        if (group.optLong("paid_message_star_count") > 0) return null
                    }
                }
                if (!channel) {
                    val sender = chat.optJSONObject("message_sender_id")
                    if (
                        sender != null &&
                            !(sender.kind() == "messageSenderUser" &&
                                sender.optLong("user_id") == account)
                    ) {
                        val senders =
                            telegram
                                .request(json("getChatAvailableMessageSenders", "chat_id" to id))
                                .getJSONArray("senders")
                        if (
                            !(0 until senders.length()).any {
                                val identity = senders.getJSONObject(it).getJSONObject("sender")
                                identity.kind() == "messageSenderUser" &&
                                    identity.optLong("user_id") == account
                            }
                        )
                            return null
                    }
                }
                return ShareRecipient(id, title, channel, forum = group.optBoolean("is_forum"))
            }
            else -> return null
        }
    }

    suspend fun topics(chat: Long): List<ShareTopic> {
        val result = mutableListOf<ShareTopic>()
        var date = 0
        var message = 0L
        var topic = 0
        do {
            val page =
                telegram.request(
                    json(
                        "getForumTopics",
                        "chat_id" to chat,
                        "query" to "",
                        "offset_date" to date,
                        "offset_message_id" to message,
                        "offset_forum_topic_id" to topic,
                        "limit" to 100,
                    )
                )
            val entries = page.getJSONArray("topics")
            if (entries.length() == 0) break
            for (i in 0 until entries.length()) {
                val info = entries.getJSONObject(i).getJSONObject("info")
                if (!info.optBoolean("is_closed") && !info.optBoolean("is_hidden"))
                    result += ShareTopic(info.getInt("forum_topic_id"), info.getString("name"))
            }
            val next = page.optInt("next_offset_forum_topic_id")
            if (next == 0 || next == topic) break
            date = page.optInt("next_offset_date")
            message = page.optLong("next_offset_message_id")
            topic = next
            currentCoroutineContext().ensureActive()
        } while (true)
        return result.distinctBy { it.id }
    }

    suspend fun recording(track: Track): ShareRecording {
        if (track.chatId == 0L) return ShareRecording(false, true, false)
        val message =
            telegram.request(
                json("getMessage", "chat_id" to track.chatId, "message_id" to track.messageId)
            )
        val properties =
            telegram.request(
                json(
                    "getMessageProperties",
                    "chat_id" to track.chatId,
                    "message_id" to track.messageId,
                )
            )
        val content = message.getJSONObject("content")
        check(content.kind() in listOf("messageAudio", "messageDocument")) {
            tr(R.string.message_audio_missing)
        }
        return ShareRecording(
            content.optJSONObject("caption")?.optString("text").orEmpty().isNotEmpty(),
            properties.optBoolean("can_be_copied"),
            properties.optBoolean("can_be_forwarded"),
        )
    }

    private fun fileContent(track: Track, file: JSONObject): JSONObject =
        if (track.document)
            json(
                "inputMessageDocument",
                "document" to json("inputDocument", "document" to file),
                "caption" to json("formattedText", "text" to ""),
            )
        else
            json(
                "inputMessageAudio",
                "audio" to
                    json(
                        "inputAudio",
                        "audio" to file,
                        "duration" to track.duration,
                        "title" to track.title,
                        "performer" to track.artist,
                    ),
                "caption" to json("formattedText", "text" to ""),
            )

    fun error(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("FORWARDS_RESTRICTED") ||
                message.contains("protected content", ignoreCase = true) ->
                tr(R.string.share_protected)
            message.contains("CHAT_WRITE_FORBIDDEN") ||
                message.contains("CHAT_SEND_") ||
                message.contains("USER_BANNED") -> tr(R.string.share_permission_lost)
            (error as? TelegramException)?.code == 408 -> tr(R.string.share_confirmation_missing)
            else -> friendly(error)
        }
    }

    /** One account-owned operation survives closing the chooser and cannot double-submit. */
    suspend fun send(
        track: Track,
        recipient: ShareRecipient,
        topic: ShareTopic? = null,
    ): JSONObject {
        requireConnection()
        check(edits.tryLock()) { tr(R.string.share_sending) }
        active = currentCoroutineContext().job
        sending.value = true
        try {
            app.platform.startAudioTransfer(app)
            if (account == 0L) prepare()
            app.awaitLibrary()
            val current = app.track(track.id) ?: track
            val source = recording(current)
            val settings = defaults.value
            val author = settings.author && current.chatId != 0L
            check(if (author) source.forward else source.copy) { tr(R.string.share_protected) }
            check(!author || settings.caption || !source.hasCaption) {
                tr(R.string.share_caption_conflict)
            }
            val target =
                this.recipient(recipient.id, current.document, fresh = true)
                    ?: error(tr(R.string.share_permission_lost))
            if (target.forum) {
                check(topic != null) { tr(R.string.share_choose_topic) }
                val info =
                    telegram
                        .request(
                            json(
                                "getForumTopic",
                                "chat_id" to target.id,
                                "forum_topic_id" to topic.id,
                            )
                        )
                        .getJSONObject("info")
                check(!info.optBoolean("is_closed") && !info.optBoolean("is_hidden")) {
                    tr(R.string.share_topic_closed)
                }
            }
            if (!target.channel) {
                val chat = telegram.request(json("getChat", "chat_id" to target.id))
                val sender = chat.optJSONObject("message_sender_id")
                if (
                    sender != null &&
                        !(sender.kind() == "messageSenderUser" &&
                            sender.optLong("user_id") == account)
                ) {
                    val senders =
                        telegram
                            .request(json("getChatAvailableMessageSenders", "chat_id" to target.id))
                            .getJSONArray("senders")
                    val canSendAsSelf =
                        (0 until senders.length()).any {
                            val identity = senders.getJSONObject(it).getJSONObject("sender")
                            identity.kind() == "messageSenderUser" &&
                                identity.optLong("user_id") == account
                        }
                    check(canSendAsSelf) { tr(R.string.share_self_unavailable) }
                    telegram.request(
                        json(
                            "setChatMessageSender",
                            "chat_id" to target.id,
                            "message_sender_id" to json("messageSenderUser", "user_id" to account),
                        )
                    )
                }
            }
            var remoteReuse = false
            val input =
                if (current.chatId != 0L) {
                    json(
                        "inputMessageForwarded",
                        "from_chat_id" to current.chatId,
                        "message_id" to current.messageId,
                        "copy_options" to
                            json(
                                "messageCopyOptions",
                                "send_copy" to !author,
                                "replace_caption" to !settings.caption,
                                "new_caption" to json("formattedText", "text" to ""),
                            ),
                    )
                } else {
                    val file =
                        when {
                            current.profileUserId != 0L ->
                                json("inputFileId", "id" to app.resolve(current))
                            current.remoteId.isNotEmpty() ->
                                try {
                                    val id =
                                        telegram
                                            .request(
                                                json(
                                                    "getRemoteFile",
                                                    "remote_file_id" to current.remoteId,
                                                    "file_type" to
                                                        json(
                                                            if (current.document) "fileTypeDocument"
                                                            else "fileTypeAudio"
                                                        ),
                                                )
                                            )
                                            .getInt("id")
                                    remoteReuse = true
                                    json("inputFileId", "id" to id)
                                } catch (e: TelegramException) {
                                    if (
                                        !current.imported ||
                                            e.code != 400 ||
                                            !validAudioCopy(current.path, current.size)
                                    )
                                        throw e
                                    json("inputFileLocal", "path" to current.path)
                                }
                            else -> {
                                check(validAudioCopy(current.path, current.size)) {
                                    tr(R.string.no_local_copy)
                                }
                                json("inputFileLocal", "path" to current.path)
                            }
                        }
                    fileContent(current, file)
                }
            val chat = telegram.request(json("getChat", "chat_id" to target.id))
            val query =
                json(
                    "sendMessage",
                    "chat_id" to target.id,
                    "options" to
                        json(
                            "messageSendOptions",
                            "disable_notification" to
                                chat.optBoolean("default_disable_notification"),
                        ),
                    "input_message_content" to input,
                )
            if (topic != null) {
                query.put("topic_id", json("messageTopicForum", "forum_topic_id" to topic.id))
            }
            val sent =
                try {
                    telegram.sendConfirmed(query, timeout = 3_600_000, cancelPending = true)
                } catch (e: TelegramException) {
                    if (
                        !current.imported ||
                            !remoteReuse ||
                            e.code != 400 ||
                            !e.message.orEmpty().contains("FILE_REFERENCE") ||
                            !validAudioCopy(current.path, current.size)
                    )
                        throw e
                    // Only a confirmed reference failure can retry with the original local file.
                    query.put(
                        "input_message_content",
                        fileContent(current, json("inputFileLocal", "path" to current.path)),
                    )
                    telegram.sendConfirmed(query, timeout = 3_600_000, cancelPending = true)
                }
            if (current.imported) {
                val content = sent.getJSONObject("content")
                val file =
                    content
                        .optJSONObject(if (current.document) "document" else "audio")
                        ?.optJSONObject(if (current.document) "document" else "audio")
                val remote = file?.optJSONObject("remote")
                if (
                    remote != null &&
                        remote.optString("id").isNotEmpty() &&
                        (remote.optString("id") != current.remoteId ||
                            remote.optString("unique_id") != current.fileKey)
                )
                    runCatching {
                        app.library.importedAudioReference(
                            current.id,
                            remote.optString("unique_id"),
                            remote.getString("id"),
                        )
                    }
                        .onFailure { app.notices.tryEmit(friendly(it)) }
            }
            rememberSend(target.id)
            return sent
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw TelegramException(408, tr(R.string.share_confirmation_missing))
        } finally {
            sending.value = false
            active = null
            edits.unlock()
        }
    }

    suspend fun stop() {
        active?.cancelAndJoin()
        account = 0
        pinned.value = emptyList()
        recent.value = emptyList()
        permissions.clear()
    }
}
