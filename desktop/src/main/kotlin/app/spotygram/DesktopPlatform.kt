package app.spotygram

import com.github.javakeyring.Keyring
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DesktopPlatform : Platform, AutoCloseable {
    override val filesDir = System.getProperty("spotygram.data.dir")?.let(::File) ?: when (DesktopTarget.os) {
        "windows" -> File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Spotygram")
        "macos" -> File(System.getProperty("user.home"), "Library/Application Support/Spotygram")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "spotygram")
    }
    override val cacheDir = if (System.getProperty("spotygram.data.dir") != null) File(filesDir, "cache")
        else when (DesktopTarget.os) {
            "windows" -> File(filesDir, "cache")
            "macos" -> File(System.getProperty("user.home"), "Library/Caches/Spotygram")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache", "spotygram")
        }
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Spotygram settings").apply { isDaemon = true }
    }
    private val preferences = ConcurrentHashMap<String, DesktopPreferences>()
    private val text = DesktopText()
    private val network = DesktopNetwork()
    private val keyring by lazy { Keyring.create(when (DesktopTarget.os) {
        "windows" -> com.github.javakeyring.KeyringStorageType.WINDOWS_CREDENTIAL_STORE
        "macos" -> com.github.javakeyring.KeyringStorageType.OSX_KEYCHAIN
        else -> com.github.javakeyring.KeyringStorageType.GNOME_KEYRING
    }) }
    override val tdlib: TdClient by lazy { DesktopTdClient() }
    override val fullOptics = true
    override val deviceModel get() = "${DesktopTarget.os} ${DesktopTarget.arch}"
    override val systemVersion get() = System.getProperty("os.version")
    override val releaseAbi get() = DesktopTarget.id
    override val testDc get() = BuildConfig.DEBUG && java.lang.Boolean.getBoolean("spotygram.telegram.test")
    override val locale get() = when (preferences("settings").getString("language", "system")) {
        "ru" -> Locale.forLanguageTag("ru")
        "en" -> Locale.ENGLISH
        else -> Locale.getDefault()
    }
    override val wifi get() = network.wifi()

    init {
        listOf(filesDir, cacheDir).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) { "Spotygram storage could not be created" }
            if (DesktopTarget.os != "windows") Files.setPosixFilePermissions(directory.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        }
    }
    override fun preferences(name: String): Preferences = preferences.computeIfAbsent(name) {
        DesktopPreferences(File(filesDir, "preferences/$name.json"), writer)
    }
    override fun string(id: Int, vararg arguments: Any?) = text.string(id, locale, *arguments)
    override fun plural(id: Int, quantity: Int, vararg arguments: Any?) = text.plural(id, quantity, locale, *arguments)
    override fun openDatabase(name: String, version: Int, create: (SqlDatabase) -> Unit,
        upgrade: (SqlDatabase, Int, Int) -> Unit, opened: (SqlDatabase) -> Unit): SqlDatabase {
        val db = DesktopDatabase(File(filesDir, name))
        try {
            val old = db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
            check(old <= version) { "This library needs a newer Spotygram version" }
            if (old < version) db.transaction {
                if (old == 0) create(this) else upgrade(this, old, version)
                execSQL("PRAGMA user_version=$version")
            }
            opened(db)
            return db
        } catch (error: Throwable) { db.close(); throw error }
    }
    override fun databaseKey(): String {
        val account = MessageDigest.getInstance("SHA-256").digest(filesDir.canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val existing = runCatching { keyring.getPassword("app.spotygram", account) }.getOrNull()
        if (!existing.isNullOrBlank()) {
            check(Base64.getDecoder().decode(existing).size == 32) { tr(R.string.database_key_error) }
            return existing
        }
        check(!preferences("settings").getBoolean("database_key_created", false) &&
            !File(filesDir, "telegram/db").isDirectory) { tr(R.string.database_key_error) }
        val secret = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        keyring.setPassword("app.spotygram", account, secret)
        check(keyring.getPassword("app.spotygram", account) == secret &&
            preferences("settings").edit().putBoolean("database_key_created", true).commit()) {
            tr(R.string.database_key_error)
        }
        return secret
    }
    override fun observeNetwork(onType: (String) -> Unit): AutoCloseable {
        // TDLib owns desktop reconnection; never park it in networkTypeNone without an OS observer.
        onType("networkTypeOther")
        return AutoCloseable {}
    }
    override fun metadata(path: String, name: String) = DesktopMetadata.read(path, name)
    override fun personalMessageLink(user: Long, message: Long, account: Long): String =
        // Telegram Desktop has no openmessage handler; Web K accepts server message IDs for users.
        "https://web.telegram.org/k/#$user?message=$message"
    override fun openUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https", "tg"))
        val desktop = Desktop.getDesktop()
        desktop.browse(uri)
    }
    override fun languageSettings(app: SpotygramApp) { app.languagePicker.value = true }
    override fun startDownloads(app: SpotygramApp) {
        val downloads = app.downloadService ?: Downloads(app)
        downloads.start()
    }
    override fun stopDownloads(app: SpotygramApp) {
        app.downloadService?.cancel() ?: app.action { app.library.clearDownloads() }
    }
    override fun installer(app: SpotygramApp): UpdateInstallation = DesktopUpdateInstaller(app)
    override fun assetName(version: String, abi: String) =
        "spotygram-$version-$abi.${if (abi.startsWith("linux")) "tar.gz" else "zip"}"
    override fun logPlaybackError(code: String) { System.err.println("Spotygram playback failed: $code") }
    override fun close() {
        writer.shutdown()
        writer.awaitTermination(5, TimeUnit.SECONDS)
    }
}
