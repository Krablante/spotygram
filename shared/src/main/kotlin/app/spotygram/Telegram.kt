package app.spotygram

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject

class Telegram(
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val clearFileBindings: suspend () -> Unit,
) {
    val auth = MutableStateFlow(AuthState())
    val connection = MutableStateFlow("")
    val files = ConcurrentHashMap<Int, FileState>()
    val chats = ConcurrentHashMap<Long, ChatChoice>()
    internal val avatarSlots = Semaphore(2)
    val chatPhotos = ChatPhotos(this)
    val chatRevision = MutableStateFlow(0L)
    val recipientRevision = MutableStateFlow(0L)
    val chatFolders = MutableStateFlow<List<ShareFolder>>(emptyList())
    val fileRevision = MutableStateFlow(0L)
    val updates = Channel<JSONObject>(Channel.UNLIMITED)
    val cloudUpdates = Channel<Unit>(Channel.CONFLATED)
    val storageChats: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val channelChats: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val sendResults = ConcurrentHashMap<Long, JSONObject>()
    private val sendRevision = MutableStateFlow(0L)
    private val requests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    private val authenticating = AtomicBoolean()
    @Volatile private var client = 0
    private var receiving: Job? = null
    private val prefs = platform.preferences("settings")
    private val native
        get() = platform.tdlib

    private var networkObserver: AutoCloseable? = null

    private fun reportNetwork(type: String) {
        scope.launch { runCatching { request(json("setNetworkType", "type" to json(type))) } }
    }

    @Synchronized
    fun start() {
        if (client != 0) return
        auth.value = AuthState("starting", busy = true)
        native.execute(json("setLogVerbosityLevel", "new_verbosity_level" to 0).toString())
        client = native.createClientId()
        networkObserver = platform.observeNetwork(::reportNetwork)
        receiving =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val response = native.receive(0.5) ?: continue
                    val obj = JSONObject(response)
                    if (obj.optInt("@client_id", client) != client) continue
                    val extra = obj.optString("@extra")
                    if (extra.isNotEmpty()) requests.remove(extra)?.complete(obj)
                    when (obj.kind()) {
                        "updateChatFolders" -> {
                            val folders = obj.getJSONArray("chat_folders")
                            chatFolders.value =
                                (0 until folders.length()).map { i ->
                                    val folder = folders.getJSONObject(i)
                                    val name =
                                        folder
                                            .optJSONObject("name")
                                            ?.optJSONObject("text")
                                            ?.optString("text") ?: folder.optString("title")
                                    ShareFolder(folder.getInt("id"), name)
                                }
                        }
                        "updateAuthorizationState" ->
                            authorization(obj.getJSONObject("authorization_state"))
                        "updateConnectionState" ->
                            connection.value =
                                when (obj.getJSONObject("state").kind()) {
                                    "connectionStateReady" -> ""
                                    else -> obj.getJSONObject("state").kind()
                                }
                        "updateFile" -> updateFile(obj.getJSONObject("file"))
                        "updateNewChat" -> {
                            val chat = obj.getJSONObject("chat")
                            val type = chat.getJSONObject("type")
                            if (
                                type.kind() == "chatTypeSupergroup" && type.optBoolean("is_channel")
                            ) {
                                channelChats.add(chat.getLong("id"))
                                cloudUpdates.trySend(Unit)
                            }
                            if (chat.getJSONObject("type").kind() != "chatTypeSecret") {
                                rememberChat(chat)
                                updates.send(
                                    json(
                                        "updateChatTitle",
                                        "chat_id" to chat.getLong("id"),
                                        "title" to chat.getString("title"),
                                    )
                                )
                            }
                            if (chat.optString("title").startsWith("spotygram-"))
                                cloudUpdates.trySend(Unit)
                        }
                        "updateChatTitle" -> {
                            val id = obj.getLong("chat_id")
                            chats.computeIfPresent(id) { _, chat ->
                                chat.copy(title = obj.getString("title"))
                            }
                            updates.send(obj)
                            if (
                                id in storageChats ||
                                    obj.optString("title").startsWith("spotygram-")
                            )
                                cloudUpdates.trySend(Unit)
                        }
                        "updateChatPhoto" ->
                            chatPhotos.changed(obj.getLong("chat_id"), obj.optJSONObject("photo"))
                        "updateNewMessage" -> {
                            val message = obj.getJSONObject("message")
                            val content = message.optJSONObject("content")?.kind()
                            if (
                                (content == "messageAudio" || content == "messageDocument") &&
                                    message.optJSONObject("sending_state") == null
                            )
                                updates.send(obj)
                            if (obj.getJSONObject("message").optLong("chat_id") in storageChats)
                                cloudUpdates.trySend(Unit)
                        }
                        "updateMessageSendSucceeded",
                        "updateMessageSendFailed" -> {
                            if (sendResults.size > 64)
                                sendResults.keys.take(32).forEach(sendResults::remove)
                            sendResults[obj.getLong("old_message_id")] = obj
                            sendRevision.update { it + 1 }
                            if (obj.kind() == "updateMessageSendSucceeded") {
                                val message = obj.getJSONObject("message")
                                val content = message.optJSONObject("content")?.kind()
                                if (content == "messageAudio" || content == "messageDocument")
                                    updates.send(json("updateNewMessage", "message" to message))
                            }
                            cloudUpdates.trySend(Unit)
                        }
                        "updateMessageContent",
                        "updateDeleteMessages" -> {
                            updates.send(obj)
                            if (obj.optLong("chat_id") in storageChats) cloudUpdates.trySend(Unit)
                        }
                        "updateChatLastMessage",
                        "updateChatNotificationSettings",
                        "updateChatPosition" ->
                            if (obj.optLong("chat_id") in storageChats) cloudUpdates.trySend(Unit)
                    }
                    when (obj.kind()) {
                        "updateNewChat",
                        "updateChatTitle",
                        "updateChatPosition",
                        "updateChatLastMessage",
                        "updateChatDraftMessage",
                        "updateChatAddedToList",
                        "updateChatRemovedFromList" -> chatRevision.update { it + 1 }
                    }
                    when (obj.kind()) {
                        "updateChatPermissions",
                        "updateChatBlockList",
                        "updateChatMessageSender",
                        "updateBasicGroup",
                        "updateSupergroup",
                        "updateSupergroupFullInfo",
                        "updateUser",
                        "updateUserFullInfo",
                        "updateForumTopicInfo",
                        "updateChatHasProtectedContent",
                        "updateMessageContent",
                        "updateDeleteMessages" -> recipientRevision.update { it + 1 }
                    }
                }
            }
        scope.launch { runCatching { request(json("getAuthorizationState")) } }
    }

    suspend fun request(obj: JSONObject, timeout: Long = 30_000): JSONObject {
        check(client != 0) { tr(R.string.telegram_required) }
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>()
        requests[id] = result
        try {
            native.send(client, obj.put("@extra", id).toString())
            val value =
                withTimeoutOrNull(timeout) { result.await() }
                    ?: throw TelegramException(
                        408,
                        tr(R.string.telegram_timeout),
                    )
            if (value.kind() == "error")
                throw TelegramException(value.optInt("code"), value.optString("message"))
            if (value.kind() == "file") updateFile(value)
            return value
        } finally {
            requests.remove(id)
        }
    }

    fun rememberChat(chat: JSONObject): ChatChoice {
        val choice = ChatChoice(chat.getLong("id"), chat.getString("title"))
        chats[choice.id] = choice
        chatPhotos.changed(choice.id, chat.optJSONObject("photo"))
        return choice
    }

    suspend fun sendConfirmed(
        query: JSONObject,
        timeout: Long = 120_000,
        cancelPending: Boolean = false,
    ): JSONObject {
        val message = request(query)
        val sending = message.optJSONObject("sending_state")
        if (sending == null || sending == JSONObject.NULL) return message
        if (sending.kind() == "messageSendingStateFailed") {
            val error = sending.getJSONObject("error")
            throw TelegramException(error.getInt("code"), error.getString("message"))
        }
        val temporary = message.getLong("id")
        try {
            val result =
                withTimeout(timeout) {
                    sendRevision.first { sendResults.containsKey(temporary) }
                    sendResults.getValue(temporary)
                }
            if (result.kind() == "updateMessageSendFailed") {
                val error = result.getJSONObject("error")
                if (cancelPending)
                    withContext(NonCancellable) {
                        runCatching {
                            request(
                                json(
                                    "deleteMessages",
                                    "chat_id" to message.getLong("chat_id"),
                                    "message_ids" to org.json.JSONArray(listOf(temporary)),
                                    "revoke" to true,
                                ),
                                timeout = 5_000,
                            )
                        }
                    }
                throw TelegramException(error.getInt("code"), error.getString("message"))
            }
            return result.getJSONObject("message")
        } catch (e: CancellationException) {
            if (cancelPending)
                withContext(NonCancellable) {
                    runCatching {
                        request(
                            json(
                                "deleteMessages",
                                "chat_id" to message.getLong("chat_id"),
                                "message_ids" to listOf(temporary),
                                "revoke" to true,
                            ),
                            timeout = 5_000,
                        )
                    }
                }
            throw e
        } finally {
            sendResults.remove(temporary)
        }
    }

    private suspend fun authorization(state: JSONObject) {
        val type = state.kind()
        val detail =
            when (type) {
                "authorizationStateWaitPassword" -> state.optString("password_hint")
                "authorizationStateWaitCode" ->
                    state.optJSONObject("code_info")?.optJSONObject("type")?.kind().orEmpty()
                "authorizationStateWaitEmailCode" ->
                    state.optJSONObject("code_info")?.optString("email_address_pattern").orEmpty()
                "authorizationStateWaitOtherDeviceConfirmation" -> state.optString("link")
                else -> ""
            }
        if (type != "authorizationStateClosed") {
            val code =
                state.optJSONObject("code_info").takeIf { type == "authorizationStateWaitCode" }
            auth.value =
                AuthState(
                    type,
                    detail,
                    busy = authenticating.get(),
                    phoneNumber = code?.optString("phone_number").orEmpty(),
                    nextCodeType = code?.optJSONObject("next_type")?.kind().orEmpty(),
                    resendAt =
                        System.nanoTime() +
                            (code?.optInt("timeout") ?: 0).coerceAtLeast(0) * 1_000_000_000L,
                    codeLink = code?.optJSONObject("type")?.optString("url").orEmpty(),
                    codeLength = code?.optJSONObject("type")?.optInt("length") ?: 0,
                )
        }
        when (type) {
            "authorizationStateWaitTdlibParameters" ->
                scope.launch {
                    auth.value = auth.value.copy(busy = true)
                    runCatching {
                        // Read the key before creating the database directory: platform key stores
                        // must still distinguish a new profile from an existing database without
                        // its key.
                        val parameters =
                            withContext(Dispatchers.IO) {
                                val key = platform.databaseKey()
                                val database = File(platform.filesDir, "telegram/db").canonicalFile
                                val files = File(platform.filesDir, "telegram/files").canonicalFile
                                Files.createDirectories(database.toPath())
                                Files.createDirectories(files.toPath())
                                Triple(database.path, files.path, key)
                            }
                        request(
                            json(
                                "setTdlibParameters",
                                "database_directory" to parameters.first,
                                "files_directory" to parameters.second,
                                "database_encryption_key" to parameters.third,
                                "use_file_database" to true,
                                "use_chat_info_database" to true,
                                "use_message_database" to true,
                                "use_secret_chats" to false,
                                "use_test_dc" to platform.testDc,
                                "api_id" to prefs.getInt("api_id", BuildConfig.TELEGRAM_API_ID),
                                "api_hash" to
                                    prefs.getString("api_hash", BuildConfig.TELEGRAM_API_HASH),
                                "system_language_code" to platform.locale.language,
                                "device_model" to platform.deviceModel,
                                "system_version" to platform.systemVersion,
                                "application_version" to BuildConfig.VERSION_NAME,
                            )
                        )
                    }
                        .onFailure {
                            auth.value = auth.value.copy(busy = false, error = friendly(it))
                        }
                }
            "authorizationStateReady" -> {
                prefs.edit().putBoolean("telegram_connected", true).apply()
                scope.launch {
                    runCatching {
                        request(
                            json(
                                "setOption",
                                "name" to "use_storage_optimizer",
                                "value" to json("optionValueBoolean", "value" to false),
                            )
                        )
                        AppText.rememberSavedChat(request(json("getMe")).getLong("id"))
                    }
                }
            }
            "authorizationStateClosed" -> {
                requests.values.forEach {
                    it.completeExceptionally(TelegramException(401, tr(R.string.telegram_required)))
                }
                requests.clear()
                files.clear()
                sendResults.clear()
                sendRevision.update { it + 1 }
                chats.clear()
                chatFolders.value = emptyList()
                recipientRevision.update { it + 1 }
                chatPhotos.reset()
                channelChats.clear()
                networkObserver?.close()
                networkObserver = null
                // A new native client may reuse numeric IDs even within this application process.
                withContext(NonCancellable) { clearFileBindings() }
                client = 0
                auth.value = AuthState(type)
                receiving?.cancel()
            }
        }
    }

    fun submit(value: String, expected: AuthState) {
        val state = auth.value
        // A delayed frame must not submit a phone number as a code after TDLib advances.
        if (
            state.busy ||
                state.type != expected.type ||
                state.editingPhone != expected.editingPhone ||
                state.phoneNumber != expected.phoneNumber
        )
            return
        val type = if (state.editingPhone) "authorizationStateWaitPhoneNumber" else state.type
        val phone =
            if (type == "authorizationStateWaitPhoneNumber") {
                val compact = value.trim().filterNot { it.isWhitespace() || it in "()-." }
                val international = if (compact.startsWith("00")) "+" + compact.drop(2) else compact
                if (
                    !international.startsWith('+') ||
                        international.length !in 6..16 ||
                        international.drop(1).any { it !in '0'..'9' } ||
                        international.getOrNull(1) == '0'
                ) {
                    auth.value = state.copy(error = tr(R.string.phone_invalid))
                    return
                }
                internationalPhoneNumber(international)
            } else ""
        val request =
            when (type) {
                "authorizationStateWaitPhoneNumber" ->
                    json(
                        "setAuthenticationPhoneNumber",
                        "phone_number" to phone,
                        // Missed calls can be read manually; flash-call/Firebase flows need mobile
                        // facilities.
                        "settings" to
                            json("phoneNumberAuthenticationSettings", "allow_missed_call" to true),
                    )
                "authorizationStateWaitCode" ->
                    json("checkAuthenticationCode", "code" to value.trim())
                "authorizationStateWaitPassword" ->
                    json("checkAuthenticationPassword", "password" to value)
                "authorizationStateWaitEmailAddress" ->
                    json("setAuthenticationEmailAddress", "email_address" to value.trim())
                "authorizationStateWaitEmailCode" ->
                    json(
                        "checkAuthenticationEmailCode",
                        "code" to json("emailAddressAuthenticationCode", "code" to value.trim()),
                    )
                else -> null
            }
        if (request != null) authenticate(request)
    }

    fun resendCode() {
        val state = auth.value
        if (
            state.type != "authorizationStateWaitCode" ||
                state.editingPhone ||
                state.nextCodeType.isEmpty() ||
                state.resendSeconds() > 0
        )
            return
        authenticate(
            json("resendAuthenticationCode", "reason" to json("resendCodeReasonUserRequest"))
        )
    }

    fun useQrAuthentication() {
        if (auth.value.type == "authorizationStateWaitPhoneNumber" || auth.value.canChangePhone)
            authenticate(
                json("requestQrCodeAuthentication", "other_user_ids" to org.json.JSONArray())
            )
    }

    private fun authenticate(query: JSONObject) {
        if (auth.value.busy || !authenticating.compareAndSet(false, true)) return
        auth.update { it.copy(busy = true, error = "") }
        scope.launch {
            try {
                request(query)
                auth.update { it.copy(editingPhone = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val retry =
                    Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE)
                        .find(error.message.orEmpty())
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                auth.update {
                    it.copy(
                        error = friendly(error),
                        resendAt =
                            if (it.type == "authorizationStateWaitCode" && retry != null)
                                System.nanoTime() +
                                    retry.coerceIn(0, Int.MAX_VALUE.toLong()) * 1_000_000_000L
                            else it.resendAt,
                    )
                }
            } finally {
                authenticating.set(false)
                auth.update { it.copy(busy = false) }
            }
        }
    }

    suspend fun usePhoneAuthentication() {
        if (!auth.value.canChangePhone || auth.value.busy) return
        if (auth.value.type != "authorizationStateWaitOtherDeviceConfirmation") {
            // TDLib accepts another phone number at the code, password and email stages.
            // Keep its real authorization state until the user submits the new number.
            auth.value = auth.value.copy(editingPhone = true, error = "")
            return
        }
        // TDLib cannot replace a pending QR login with setAuthenticationPhoneNumber.
        // End only this unfinished authorization; the Spotygram catalog stays intact.
        if (!authenticating.compareAndSet(false, true)) return
        auth.update { it.copy(busy = true, error = "") }
        val previousReceiver = receiving
        try {
            request(json("logOut"))
            withTimeout(10_000) { auth.first { it.type == "authorizationStateClosed" } }
            previousReceiver?.join()
        } catch (error: Throwable) {
            auth.value = auth.value.copy(busy = false, error = friendly(error))
            throw error
        } finally {
            authenticating.set(false)
            auth.update { it.copy(busy = false) }
        }
        start()
    }

    fun updateFile(file: JSONObject) {
        val local = file.optJSONObject("local") ?: return
        val f =
            FileState(
                file.getInt("id"),
                local.optString("path"),
                file.optLong("size").takeIf { it > 0 } ?: file.optLong("expected_size"),
                local.optLong("downloaded_size"),
                local.optLong("download_offset"),
                local.optLong("downloaded_prefix_size"),
                local.optBoolean("is_downloading_completed"),
                local.optBoolean("is_downloading_active"),
                file.optJSONObject("remote")?.optString("unique_id").orEmpty(),
                file.optJSONObject("remote")?.optString("id").orEmpty(),
            )
        val previous = files.put(f.id, f)
        fileRevision.value = sequence.incrementAndGet()
        if (f.complete && previous?.complete != true)
            updates.trySend(json("spotygramFileComplete", "id" to f.id, "path" to f.path))
    }

    suspend fun download(id: Int, offset: Long = 0, priority: Int = 16) =
        request(
            json(
                "downloadFile",
                "file_id" to id,
                "priority" to priority,
                "offset" to offset,
                "limit" to 0,
                "synchronous" to false,
            )
        )

    suspend fun close() {
        if (client == 0) {
            networkObserver?.close()
            networkObserver = null
            return
        }
        if (client != 0) runCatching { request(json("close"), timeout = 5_000) }
        withTimeoutOrNull(5_000) { auth.first { it.type == "authorizationStateClosed" } }
        receiving?.cancelAndJoin()
        networkObserver?.close()
        networkObserver = null
    }
}

/**
 * Keep exceptional Telegram numbers, while normalizing known countries without dropping significant
 * zeros.
 */
internal fun internationalPhoneNumber(value: String): String =
    try {
        val phones = PhoneNumberUtil.getInstance()
        val parsed = phones.parse(value, "ZZ")
        if (phones.isPossibleNumber(parsed))
            phones.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        else value
    } catch (_: NumberParseException) {
        value
    }

class TelegramException(val code: Int, message: String) : Exception(message)

fun friendly(error: Throwable): String =
    when {
        error is TimeoutCancellationException -> tr(R.string.telegram_timeout)
        error.message.orEmpty().contains("PHONE_CODE_INVALID") -> tr(R.string.code_invalid)
        error.message.orEmpty().contains("PHONE_CODE_EXPIRED") -> tr(R.string.code_expired)
        error.message.orEmpty().contains("PASSWORD_HASH_INVALID") -> tr(R.string.password_invalid)
        error.message.orEmpty().contains("PHONE_NUMBER_INVALID") -> tr(R.string.phone_invalid)
        error.message.orEmpty().contains("FLOOD_WAIT") ||
            (error as? TelegramException)?.code == 429 ->
            tr(R.string.flood_wait, error.message.orEmpty().take(120))
        else -> error.message?.take(180) ?: tr(R.string.action_failed)
    }
