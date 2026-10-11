package app.spotygram

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class Telegram(private val platform: Platform, private val scope: CoroutineScope) {
    val auth = MutableStateFlow(AuthState())
    val connection = MutableStateFlow("")
    val files = ConcurrentHashMap<Int, FileState>()
    val chats = ConcurrentHashMap<Long, ChatChoice>()
    val chatRevision = MutableStateFlow(0L)
    val fileRevision = MutableStateFlow(0L)
    val updates = Channel<JSONObject>(Channel.UNLIMITED)
    private val requests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    private var client = 0
    private var receiving: Job? = null
    private val prefs = platform.preferences("settings")
    private val native get() = platform.tdlib
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
                            if (chat.getJSONObject("type").kind() != "chatTypeSecret")
                                chats[chat.getLong("id")] =
                                    ChatChoice(chat.getLong("id"), chat.getString("title"))
                        }
                        "updateChatTitle" -> {
                            val id = obj.getLong("chat_id")
                            chats.computeIfPresent(id) { _, chat ->
                                chat.copy(title = obj.getString("title"))
                            }
                        }
                        else -> if (obj.kind().startsWith("update")) updates.send(obj)
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

    private fun authorization(state: JSONObject) {
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
        auth.value = AuthState(type, detail)
        when (type) {
            "authorizationStateWaitTdlibParameters" ->
                scope.launch {
                    auth.value = auth.value.copy(busy = true)
                    runCatching {
                        request(
                            json(
                                "setTdlibParameters",
                                "database_directory" to "${platform.filesDir}/telegram/db",
                                "files_directory" to "${platform.filesDir}/telegram/files",
                                "database_encryption_key" to
                                    withContext(Dispatchers.IO) { platform.databaseKey() },
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
                client = 0
                receiving?.cancel()
                requests.values.forEach { it.cancel() }
                requests.clear()
                files.clear()
                chats.clear()
                networkObserver?.close()
                networkObserver = null
            }
        }
    }

    fun submit(value: String) {
        val type = auth.value.type
        scope.launch {
            auth.value = auth.value.copy(busy = true, error = "")
            val request =
                when (type) {
                    "authorizationStateWaitPhoneNumber" ->
                        json(
                            "setAuthenticationPhoneNumber",
                            "phone_number" to value,
                            "settings" to null,
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
                            "code" to
                                json("emailAddressAuthenticationCode", "code" to value.trim()),
                        )
                    else -> null
                }
            if (request != null)
                runCatching { request(request) }
                    .onFailure { auth.value = auth.value.copy(error = friendly(it)) }
            auth.value = auth.value.copy(busy = false)
        }
    }

    suspend fun usePhoneAuthentication() {
        if (auth.value.type != "authorizationStateWaitOtherDeviceConfirmation" || auth.value.busy) return
        // TDLib cannot replace a pending QR login with setAuthenticationPhoneNumber.
        // End only this unfinished authorization; the Spotygram catalog stays intact.
        auth.value = auth.value.copy(busy = true, error = "")
        val previousReceiver = receiving
        try {
            request(json("logOut"))
            withTimeout(10_000) { auth.first { it.type == "authorizationStateClosed" } }
            previousReceiver?.join()
            start()
        } catch (error: Throwable) {
            auth.value = auth.value.copy(busy = false, error = friendly(error))
            throw error
        }
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
