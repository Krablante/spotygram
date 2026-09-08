package app.spotygram

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject

class Telegram(private val context: Context, private val scope: CoroutineScope) {
    val auth = MutableStateFlow(AuthState())
    val connection = MutableStateFlow("")
    val files = ConcurrentHashMap<Int, FileState>()
    val fileRevision = MutableStateFlow(0L)
    val updates = Channel<JSONObject>(Channel.UNLIMITED)
    private val requests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    private var client = 0
    private var receiving: Job? = null
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Synchronized
    fun start() {
        if (client != 0) return
        auth.value = AuthState("starting", busy = true)
        JsonClient.execute(json("setLogVerbosityLevel", "new_verbosity_level" to 0).toString())
        client = JsonClient.createClientId()
        receiving =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val response = JsonClient.receive(0.5) ?: continue
                    val obj = JSONObject(response)
                    val extra = obj.optString("@extra")
                    if (extra.isNotEmpty()) requests.remove(extra)?.complete(obj)
                    when (obj.kind()) {
                        "updateAuthorizationState" ->
                            authorization(obj.getJSONObject("authorization_state"))
                        "updateConnectionState" ->
                            connection.value =
                                when (obj.getJSONObject("state").kind()) {
                                    "connectionStateReady" -> ""
                                    "connectionStateWaitingForNetwork" -> "Нет подключения к сети"
                                    "connectionStateUpdating" -> "Обновление Telegram…"
                                    else -> "Подключение к Telegram…"
                                }
                        "updateFile" -> updateFile(obj.getJSONObject("file"))
                        else -> if (obj.kind().startsWith("update")) updates.send(obj)
                    }
                }
            }
        scope.launch { runCatching { request(json("getAuthorizationState")) } }
    }

    suspend fun request(obj: JSONObject, timeout: Long = 30_000): JSONObject {
        check(client != 0) { "Подключите Telegram" }
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>()
        requests[id] = result
        try {
            JsonClient.send(client, obj.put("@extra", id).toString())
            val value = withTimeout(timeout) { result.await() }
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
                                "database_directory" to "${context.filesDir}/telegram/db",
                                "files_directory" to "${context.filesDir}/telegram/files",
                                "database_encryption_key" to
                                    withContext(Dispatchers.IO) { databaseKey() },
                                "use_file_database" to true,
                                "use_chat_info_database" to true,
                                "use_message_database" to true,
                                "use_secret_chats" to false,
                                "use_test_dc" to BuildConfig.TELEGRAM_TEST_DC,
                                "api_id" to prefs.getInt("api_id", BuildConfig.TELEGRAM_API_ID),
                                "api_hash" to
                                    prefs.getString("api_hash", BuildConfig.TELEGRAM_API_HASH),
                                "system_language_code" to "ru",
                                "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                                "system_version" to Build.VERSION.RELEASE,
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
                    }
                }
            }
            "authorizationStateClosed" -> {
                client = 0
                receiving?.cancel()
                requests.values.forEach { it.cancel() }
                requests.clear()
                files.clear()
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
                            "code" to json("emailAddressAuthenticationCode", "code" to value.trim()),
                        )
                    else -> null
                }
            if (request != null)
                runCatching { request(request) }
                    .onFailure { auth.value = auth.value.copy(error = friendly(it)) }
            auth.value = auth.value.copy(busy = false)
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

    @android.annotation.SuppressLint(
        "ApplySharedPref"
    ) // Key must be durable before TDLib opens its database; runs on IO.
    private fun databaseKey(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "spotygram.database"
        val key =
            (store.getKey(alias, null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build()
                        )
                    }
                    .generateKey()
        val saved = prefs.getString("database_key", null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (saved != null) {
            val data = Base64.decode(saved, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            return Base64.encodeToString(
                cipher.doFinal(data.copyOfRange(12, data.size)),
                Base64.NO_WRAP,
            )
        }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(
            prefs
                .edit()
                .putString(
                    "database_key",
                    Base64.encodeToString(cipher.iv + cipher.doFinal(secret), Base64.NO_WRAP),
                )
                .commit()
        ) {
            "Не удалось сохранить ключ базы. Освободите место на телефоне."
        }
        return Base64.encodeToString(secret, Base64.NO_WRAP)
    }
}

class TelegramException(val code: Int, message: String) : Exception(message)

fun friendly(error: Throwable): String =
    when {
        error is TimeoutCancellationException ->
            "Telegram не ответил. Проверьте соединение и повторите."
        error.message.orEmpty().contains("PHONE_CODE_INVALID") ->
            "Неверный код. Проверьте сообщение от Telegram."
        error.message.orEmpty().contains("PHONE_CODE_EXPIRED") -> "Код истёк. Запросите новый."
        error.message.orEmpty().contains("PASSWORD_HASH_INVALID") ->
            "Неверный пароль двухэтапной аутентификации."
        error.message.orEmpty().contains("PHONE_NUMBER_INVALID") ->
            "Укажите номер с кодом страны, например +7…"
        error.message.orEmpty().contains("FLOOD_WAIT") ||
            (error as? TelegramException)?.code == 429 ->
            "Telegram просит подождать перед следующим запросом. ${error.message.orEmpty().take(120)}"
        else -> error.message?.take(180) ?: "Не удалось выполнить действие. Попробуйте ещё раз."
    }
