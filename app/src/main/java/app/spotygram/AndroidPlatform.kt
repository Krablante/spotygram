package app.spotygram

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.ContextCompat
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.JsonClient

class AndroidPlatform(val context: AndroidApplication) : Platform {
    override val filesDir
        get() = context.filesDir

    override val cacheDir
        get() = context.cacheDir

    override val fullOptics
        get() = Build.VERSION.SDK_INT >= 33

    override val deviceModel
        get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    override val systemVersion
        get() = Build.VERSION.RELEASE

    override val locale
        get() =
            when (preferences("settings").getString("language", "system")) {
                "ru" -> Locale.forLanguageTag("ru")
                "en" -> Locale.ENGLISH
                else -> context.resources.configuration.locales[0]
            }

    @Volatile
    private var translated: Triple<Locale, Configuration, android.content.res.Resources>? = null
    private val textResources
        get(): android.content.res.Resources {
            val resources = context.resources
            val language = locale
            if (language == resources.configuration.locales[0]) return resources
            val cached = translated
            if (
                cached != null &&
                    cached.first == language &&
                    cached.second == resources.configuration
            )
                return cached.third
            val configuration = Configuration(resources.configuration)
            val localized =
                context
                    .createConfigurationContext(
                        Configuration(configuration).apply { setLocale(language) }
                    )
                    .resources
            translated = Triple(language, configuration, localized)
            return localized
        }

    override val releaseAbi
        get() =
            if (
                Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ==
                    "arm64-v8a"
            )
                "arm64"
            else "x86_64"

    override val testDc
        get() = BuildConfig.TELEGRAM_TEST_DC

    private val network
        get() = context.getSystemService(ConnectivityManager::class.java)

    override val wifi
        get() =
            network
                .getNetworkCapabilities(network.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    override val tdlib =
        object : TdClient {
            override fun createClientId() = JsonClient.createClientId()

            override fun send(client: Int, request: String) = JsonClient.send(client, request)

            override fun receive(timeout: Double) = JsonClient.receive(timeout)

            override fun execute(request: String) = JsonClient.execute(request)
        }

    override fun preferences(name: String): Preferences =
        AndroidPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun string(id: Int, vararg arguments: Any?) =
        if (arguments.isEmpty()) textResources.getString(id)
        else textResources.getString(id, *arguments)

    override fun plural(id: Int, quantity: Int, vararg arguments: Any?) =
        if (arguments.isEmpty()) textResources.getQuantityString(id, quantity)
        else textResources.getQuantityString(id, quantity, *arguments)

    override fun openDatabase(
        name: String,
        version: Int,
        create: (SqlDatabase) -> Unit,
        upgrade: (SqlDatabase, Int, Int) -> Unit,
        opened: (SqlDatabase) -> Unit,
    ): SqlDatabase {
        val helper =
            object : android.database.sqlite.SQLiteOpenHelper(context, name, null, version) {
                override fun onConfigure(db: android.database.sqlite.SQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }

                override fun onCreate(db: android.database.sqlite.SQLiteDatabase) =
                    create(AndroidDatabase(db))

                override fun onUpgrade(
                    db: android.database.sqlite.SQLiteDatabase,
                    old: Int,
                    new: Int,
                ) = upgrade(AndroidDatabase(db), old, new)

                override fun onOpen(db: android.database.sqlite.SQLiteDatabase) =
                    opened(AndroidDatabase(db))
            }
        return AndroidDatabase(helper.writableDatabase, helper::close)
    }

    override fun observeNetwork(onType: (String) -> Unit): AutoCloseable {
        fun report(capabilities: NetworkCapabilities?) {
            onType(
                when {
                    capabilities == null -> "networkTypeNone"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                        "networkTypeWiFi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        "networkTypeMobile"
                    else -> "networkTypeOther"
                }
            )
        }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = report(capabilities)

                override fun onLost(network: Network) = report(null)
            }
        network.registerDefaultNetworkCallback(callback)
        report(network.getNetworkCapabilities(network.activeNetwork))
        return AutoCloseable { network.unregisterNetworkCallback(callback) }
    }

    override fun metadata(path: String, name: String): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            AudioMetadata(
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                retriever.embeddedPicture?.takeIf { it.size < 8 * 1024 * 1024 },
            )
        } finally {
            retriever.release()
        }
    }

    override fun personalMessageLink(user: Long, message: Long, account: Long): String =
        "tg://openmessage?user_id=$user&message_id=$message" +
            if (account > 0) "&account_user_id=$account" else ""

    override fun openUrl(url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun languageSettings(app: SpotygramApp) {
        app.languagePicker.value = true
    }

    override fun startDownloads(app: SpotygramApp) {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    override fun startAudioTransfer(app: SpotygramApp) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, DownloadService::class.java).setAction("audioTransfer"),
        )
    }

    override fun stopDownloads(app: SpotygramApp) {
        if (app.downloadService != null)
            context.startService(Intent(context, DownloadService::class.java).setAction("cancel"))
        else app.action { app.library.clearDownloads() }
    }

    override fun installer(app: SpotygramApp): UpdateInstallation = UpdateInstaller(context)

    override fun assetName(version: String, abi: String) = "spotygram-$version-$abi.apk"

    override fun logPlaybackError(code: String) {
        android.util.Log.w("SpotygramPlayback", "Playback failed: $code")
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    override fun databaseKey(): String {
        val prefs = preferences("settings")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "spotygram.database"
        val saved = prefs.getString("database_key", null)
        val existing = store.getKey(alias, null) as? SecretKey
        check(
            (saved == null || existing != null) &&
                (saved != null || !java.io.File(filesDir, "telegram/db").isDirectory)
        ) {
            tr(R.string.database_key_error)
        }
        val key =
            existing
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
            tr(R.string.database_key_error)
        }
        return Base64.encodeToString(secret, Base64.NO_WRAP)
    }
}

private class AndroidPreferences(private val preferences: SharedPreferences) : Preferences {
    override fun contains(key: String) = preferences.contains(key)

    override fun getString(key: String, default: String?) = preferences.getString(key, default)

    override fun getBoolean(key: String, default: Boolean) = preferences.getBoolean(key, default)

    override fun getInt(key: String, default: Int) = preferences.getInt(key, default)

    override fun getLong(key: String, default: Long) = preferences.getLong(key, default)

    override fun getFloat(key: String, default: Float) = preferences.getFloat(key, default)

    override fun edit(): Preferences.Editor = Editor(preferences.edit())

    private class Editor(private val editor: SharedPreferences.Editor) : Preferences.Editor {
        override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }

        override fun putBoolean(key: String, value: Boolean) = apply {
            editor.putBoolean(key, value)
        }

        override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }

        override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }

        override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }

        override fun remove(key: String) = apply { editor.remove(key) }

        override fun clear() = apply { editor.clear() }

        override fun commit() = editor.commit()

        override fun apply() = editor.apply()
    }
}

suspend fun SpotygramApp.importAudio(uri: Uri) =
    withContext(Dispatchers.IO) {
        val context = (platform as AndroidPlatform).context
        val name =
            context.contentResolver
                .query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                .orEmpty()
        importAudio(name) {
            context.contentResolver.openInputStream(uri) ?: error(tr(R.string.file_unavailable))
        }
    }
