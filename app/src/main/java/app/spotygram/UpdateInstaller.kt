package app.spotygram

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class UpdateAsset(val version: String, val size: Long, val sha256: String, val abi: String) {
    val url
        get() =
            "https://github.com/Krablante/spotygram/releases/download/v$version/spotygram-$version-$abi.apk"

    fun valid() =
        Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(version) &&
            size in 1..268_435_456L &&
            Regex("[0-9a-f]{64}").matches(sha256) &&
            abi in setOf("arm64", "x86_64")

    fun newerThanInstalled(): Boolean {
        val a = version.split('.').map { it.toInt() }
        val b = BuildConfig.VERSION_NAME.split('.').map { it.toInt() }
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    fun toJson() =
        JSONObject()
            .put("version", version)
            .put("size", size)
            .put("sha256", sha256)
            .put("abi", abi)
            .toString()

    companion object {
        fun fromJson(text: String?): UpdateAsset? = runCatching {
            val j = JSONObject(text ?: return null)
            UpdateAsset(
                    j.getString("version"),
                    j.getLong("size"),
                    j.getString("sha256"),
                    j.getString("abi"),
                )
                .takeIf { it.valid() }
        }
            .getOrNull()
    }
}

enum class InstallPhase {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    READY,
    CANCELLING,
}

data class InstallState(
    val phase: InstallPhase = InstallPhase.IDLE,
    val version: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: Int = 0,
)

/** DownloadManager owns background transfer. Only the resumed activity polls progress. */
class UpdateInstaller(private val app: SpotygramApp) {
    private val prefs = app.getSharedPreferences("update_install", Context.MODE_PRIVATE)
    private val downloads = app.getSystemService(DownloadManager::class.java)
    private val directory = File(app.cacheDir, "updates")
    private val apk = File(directory, "update.apk")
    private val partial = File(directory, "update.part.apk")
    private var activity = WeakReference<Activity>(null)
    private var work: Job? = null
    private var monitor: Job? = null
    private var restored = false
    private var installAfterDownload = false
    val state = MutableStateFlow(InstallState())

    fun resume(owner: Activity) {
        activity = WeakReference(owner)
        if (work?.isActive == true) return
        if (!restored) {
            restored = true
            work =
                app.scope.launch {
                    try {
                        val saved =
                            withContext(Dispatchers.IO) {
                                val a = UpdateAsset.fromJson(prefs.getString("asset", null))
                                if (a == null || !a.newerThanInstalled()) {
                                    clearFiles()
                                    null
                                } else a
                            }
                        if (saved != null) {
                            val phase =
                                when {
                                    prefs.getLong("download_id", 0) != 0L ->
                                        InstallPhase.DOWNLOADING
                                    withContext(Dispatchers.IO) { apk.isFile } -> InstallPhase.READY
                                    else -> InstallPhase.IDLE
                                }
                            state.value = InstallState(phase, saved.version, total = saved.size)
                        }
                        resumePermission()
                        startMonitor()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        state.value = InstallState(error = R.string.update_download_failed)
                    }
                }
        } else {
            resumePermission()
            startMonitor()
        }
    }

    fun pause(owner: Activity) {
        if (activity.get() === owner) activity.clear()
        monitor?.cancel()
        monitor = null
    }

    fun start(asset: UpdateAsset?) {
        if (
            work?.isActive == true ||
                state.value.phase in
                    setOf(InstallPhase.DOWNLOADING, InstallPhase.VERIFYING, InstallPhase.CANCELLING)
        )
            return
        if (state.value.phase == InstallPhase.READY) {
            installReady()
            return
        }
        if (asset == null || !asset.valid() || !asset.newerThanInstalled()) {
            state.value = state.value.copy(error = R.string.update_check_first)
            return
        }
        installAfterDownload = true
        state.value = InstallState(InstallPhase.DOWNLOADING, asset.version, total = asset.size)
        work =
            app.scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        clearFiles()
                        check(prefs.edit().putString("asset", asset.toJson()).commit())
                        val request =
                            DownloadManager.Request(Uri.parse(asset.url))
                                .setTitle("Spotygram ${asset.version}")
                                .setDescription(tr(R.string.update_downloading))
                                .setMimeType("application/vnd.android.package-archive")
                                .setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE
                                )
                        val id = downloads.enqueue(request)
                        if (!prefs.edit().putLong("download_id", id).commit()) {
                            downloads.remove(id)
                            error("Could not retain download")
                        }
                    }
                    startMonitor()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(R.string.update_download_failed)
                }
            }
    }

    fun cancel() {
        if (state.value.phase == InstallPhase.CANCELLING) return
        installAfterDownload = false
        val previousMonitor = monitor
        previousMonitor?.cancel()
        monitor = null
        val previous = work
        state.value = state.value.copy(phase = InstallPhase.CANCELLING, error = 0)
        work =
            app.scope.launch {
                previousMonitor?.join()
                previous?.cancelAndJoin()
                try {
                    withContext(Dispatchers.IO) { clearFiles() }
                    state.value = InstallState()
                } catch (_: Exception) {
                    state.value = InstallState(error = R.string.update_download_failed)
                }
            }
    }

    private fun startMonitor() {
        if (
            activity.get() == null ||
                prefs.getLong("download_id", 0) == 0L ||
                monitor?.isActive == true
        )
            return
        monitor =
            app.scope.launch {
                while (isActive && activity.get() != null) {
                    val id = prefs.getLong("download_id", 0)
                    if (id == 0L) return@launch
                    try {
                        val status =
                            withContext(Dispatchers.IO) {
                                downloads.query(DownloadManager.Query().setFilterById(id)).use { c
                                    ->
                                    check(c.moveToFirst())
                                    c.getInt(
                                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                                    ) to
                                        c.getLong(
                                            c.getColumnIndexOrThrow(
                                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                            )
                                        )
                                }
                            }
                        if (
                            status.first == DownloadManager.STATUS_FAILED ||
                                status.second > state.value.total
                        ) {
                            fail(R.string.update_download_failed)
                            return@launch
                        }
                        if (status.first == DownloadManager.STATUS_SUCCESSFUL) {
                            finishDownload(id)
                            return@launch
                        }
                        state.value = state.value.copy(downloaded = status.second)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        fail(R.string.update_download_failed)
                        return@launch
                    }
                    delay(1000)
                }
            }
    }

    private fun finishDownload(id: Long) {
        if (state.value.phase != InstallPhase.DOWNLOADING) return
        state.value = state.value.copy(phase = InstallPhase.VERIFYING)
        work =
            app.scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val a = checkNotNull(UpdateAsset.fromJson(prefs.getString("asset", null)))
                        directory.mkdirs()
                        val uri = checkNotNull(downloads.getUriForDownloadedFile(id))
                        app.contentResolver.openInputStream(uri)!!.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var count = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    count += n
                                    check(count <= a.size)
                                    output.write(buffer, 0, n)
                                }
                                check(count == a.size)
                            }
                        }
                        verify(partial, a)
                        check(partial.renameTo(apk))
                        downloads.remove(id)
                        check(prefs.edit().remove("download_id").commit())
                    }
                    state.value = state.value.copy(phase = InstallPhase.READY, error = 0)
                    if (installAfterDownload && activity.get() != null) launchInstaller(true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(R.string.update_verification_failed)
                }
            }
    }

    private fun installReady(allowPermissionPrompt: Boolean = true) {
        if (state.value.phase != InstallPhase.READY) return
        state.value = state.value.copy(phase = InstallPhase.VERIFYING, error = 0)
        work =
            app.scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        verify(
                            apk,
                            checkNotNull(UpdateAsset.fromJson(prefs.getString("asset", null))),
                        )
                    }
                    state.value = state.value.copy(phase = InstallPhase.READY)
                    launchInstaller(allowPermissionPrompt)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(R.string.update_verification_failed)
                }
            }
    }

    private fun resumePermission() {
        val waiting = prefs.getBoolean("awaiting_permission", false)
        if (waiting) {
            prefs.edit().remove("awaiting_permission").apply()
            installReady(allowPermissionPrompt = false)
        } else if (installAfterDownload && state.value.phase == InstallPhase.READY) {
            installReady()
        }
    }

    private fun launchInstaller(allowPermissionPrompt: Boolean) {
        val owner = activity.get() ?: return
        installAfterDownload = false
        try {
            if (!app.packageManager.canRequestPackageInstalls()) {
                if (allowPermissionPrompt) {
                    prefs.edit().putBoolean("awaiting_permission", true).apply()
                    owner.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${app.packageName}"),
                        )
                    )
                } else state.value = state.value.copy(error = R.string.update_permission_needed)
                return
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
            owner.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .apply {
                        clipData = ClipData.newRawUri("Spotygram update", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
            )
        } catch (_: Exception) {
            prefs.edit().remove("awaiting_permission").apply()
            state.value = state.value.copy(error = R.string.update_installer_failed)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun verify(file: File, asset: UpdateAsset) {
        check(asset.newerThanInstalled() && file.isFile && file.length() == asset.size)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        check(hash.digest().joinToString("") { "%02x".format(it) } == asset.sha256)
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val archive = checkNotNull(app.packageManager.getPackageArchiveInfo(file.path, flags))
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        val code =
            if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode
            else archive.versionCode.toLong()
        check(
            archive.packageName == app.packageName &&
                archive.versionName == asset.version &&
                code > BuildConfig.VERSION_CODE
        )
        fun certificates(info: PackageInfo): Set<String> {
            val signatures =
                if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                else info.signatures
            return signatures
                .orEmpty()
                .map { s ->
                    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
                        "%02x".format(it)
                    }
                }
                .toSet()
        }
        val signing = certificates(archive)
        check(signing.isNotEmpty() && signing == certificates(installed))
    }

    private suspend fun fail(message: Int) {
        installAfterDownload = false
        try {
            withContext(Dispatchers.IO) { clearFiles() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        state.value = InstallState(error = message)
    }

    private fun clearFiles() {
        val id = prefs.getLong("download_id", 0)
        if (id != 0L) downloads.remove(id)
        apk.delete()
        partial.delete()
        prefs.edit().clear().commit()
    }
}
