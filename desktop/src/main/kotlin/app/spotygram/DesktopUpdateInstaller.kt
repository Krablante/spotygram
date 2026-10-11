package app.spotygram

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** A user-started transfer survives hiding the window and resumes after a process restart. */
class DesktopUpdateInstaller(private val app: SpotygramApp) : UpdateInstallation {
    private val prefs = app.getSharedPreferences("update_install")
    private val directory = File(app.cacheDir, "updates")
    private val staging = File(directory, "staging")
    private var work: Job? = null
    private var installAfterDownload = false
    override val state = MutableStateFlow(InstallState())

    private fun saved() = UpdateAsset.fromJson(prefs.getString("asset", null))
        ?.takeIf { it.abi == app.platform.releaseAbi && it.newerThanInstalled() }
    private fun archive(asset: UpdateAsset) = File(directory, app.platform.assetName(asset.version, asset.abi))
    private fun partial(asset: UpdateAsset) = File(directory, "${app.platform.assetName(asset.version, asset.abi)}.part")

    fun resume() {
        if (work?.isActive == true) return
        work = app.scope.launch {
            try {
                cleanHelper()
                val asset = saved()
                if (asset == null) {
                    withContext(Dispatchers.IO) { clearDownload() }
                    return@launch
                }
                val complete = withContext(Dispatchers.IO) { archive(asset).isFile }
                if (complete) {
                    state.value = InstallState(InstallPhase.VERIFYING, asset.version, total = asset.size)
                    withContext(Dispatchers.IO) { verify(asset) }
                    state.value = InstallState(InstallPhase.READY, asset.version, asset.size, asset.size)
                } else transfer(asset)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.value = InstallState(error = R.string.update_verification_failed)
            }
        }
    }
    override fun start(asset: UpdateAsset?) {
        if (work?.isActive == true) return
        if (state.value.phase == InstallPhase.READY) {
            app.updateRestartRequested.value = true
            return
        }
        if (asset == null || !asset.valid() || asset.abi != app.platform.releaseAbi || !asset.newerThanInstalled()) {
            state.value = state.value.copy(error = R.string.update_check_first)
            return
        }
        installAfterDownload = true
        state.value = InstallState(InstallPhase.DOWNLOADING, asset.version, total = asset.size)
        work = app.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    clearDownload()
                    check(prefs.edit().putString("asset", asset.toJson()).commit())
                }
                transfer(asset)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.value = state.value.copy(phase = InstallPhase.IDLE,
                    error = if (state.value.phase == InstallPhase.VERIFYING) R.string.update_verification_failed
                        else R.string.update_download_failed)
            }
        }
    }
    private suspend fun transfer(asset: UpdateAsset) {
        state.value = InstallState(InstallPhase.DOWNLOADING, asset.version, total = asset.size)
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val file = partial(asset)
            if (file.length() > asset.size) file.delete()
            var offset = file.length()
            if (offset < asset.size) {
                val connection = connection(asset.url, offset)
                try {
                    val code = connection.responseCode
                    require(code == 200 || code == 206)
                    if (code == 206) require(connection.getHeaderField("Content-Range")
                        .startsWith("bytes $offset-") && connection.getHeaderField("Content-Range").endsWith("/${asset.size}"))
                    else offset = 0
                    FileOutputStream(file, offset > 0).use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val length = input.read(buffer)
                                if (length < 0) break
                                offset += length
                                require(offset <= asset.size)
                                output.write(buffer, 0, length)
                                val now = System.nanoTime()
                                if (now - reported > 200_000_000) {
                                    state.value = state.value.copy(downloaded = offset)
                                    reported = now
                                }
                            }
                        }
                        output.fd.sync()
                    }
                } finally { connection.disconnect() }
            }
            require(file.length() == asset.size)
            java.nio.file.Files.move(file.toPath(), archive(asset).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        state.value = InstallState(InstallPhase.VERIFYING, asset.version, asset.size, asset.size)
        withContext(Dispatchers.IO) { verify(asset) }
        state.value = InstallState(InstallPhase.READY, asset.version, asset.size, asset.size)
        if (installAfterDownload) app.updateRestartRequested.value = true
    }
    private fun connection(address: String, offset: Long): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.host in setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"))
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Spotygram/${BuildConfig.VERSION_NAME}")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
            val next = connection.getHeaderField("Location")
            connection.disconnect()
            url = URL(url, next)
        }
        error("Too many update redirects")
    }
    private fun verify(asset: UpdateAsset) {
        val file = archive(asset)
        require(file.length() == asset.size && SignedBundle.sha256(file) == asset.sha256)
        SignedBundle.extract(file, staging, asset.version, asset.abi)
    }
    override fun cancel() {
        if (state.value.phase == InstallPhase.CANCELLING) return
        installAfterDownload = false
        app.updateRestartRequested.value = false
        val previous = work
        state.value = state.value.copy(phase = InstallPhase.CANCELLING)
        work = app.scope.launch {
            previous?.cancelAndJoin()
            try {
                withContext(Dispatchers.IO) { clearDownload() }
                state.value = InstallState()
            } catch (_: Exception) { state.value = InstallState(error = R.string.update_download_failed) }
        }
    }
    private fun clearDownload() {
        val old = UpdateAsset.fromJson(prefs.getString("asset", null))
        if (old != null) {
            java.nio.file.Files.deleteIfExists(archive(old).toPath())
            java.nio.file.Files.deleteIfExists(partial(old).toPath())
        }
        SignedBundle.remove(staging)
        check(prefs.edit().remove("asset").commit())
    }
    fun confirmInstall() {
        if (work?.isActive == true || state.value.phase != InstallPhase.READY) return
        app.updateRestartRequested.value = false
        work = app.scope.launch {
            var helper: DesktopUpdater.Helper? = null
            var applying = false
            try {
                val asset = checkNotNull(saved())
                val installed = DesktopUpdater.installedRoot()
                if (installed == null || !java.nio.file.Files.isWritable(installed.parentFile.toPath())) {
                    state.value = state.value.copy(error = R.string.update_install_location)
                    return@launch
                }
                state.value = state.value.copy(phase = InstallPhase.VERIFYING)
                withContext(Dispatchers.IO) {
                    SignedBundle.verify(staging, asset.version, asset.abi)
                    helper = DesktopUpdater.launchHelper(app, staging, installed, asset)
                }
                val ready = checkNotNull(helper)
                withTimeout(30_000) {
                    while (!File(ready.directory, "ready").isFile) {
                        check(ready.process.isAlive) { "Update helper exited before becoming ready" }
                        delay(100)
                    }
                }
                check(prefs.edit().putString("helper_job", ready.directory.absolutePath).commit())
                applying = true
                app.updateApplyRequested.value = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                error.printStackTrace()
                helper?.let { failed ->
                    val log = File(failed.directory, "helper.log")
                    if (log.isFile) System.err.print(log.readText())
                }
                state.value = state.value.copy(phase = InstallPhase.READY, error = R.string.update_installer_failed)
            } finally {
                if (!applying) helper?.let { failed ->
                    withContext(NonCancellable + Dispatchers.IO) { DesktopUpdater.abort(failed) }
                }
            }
        }
    }
    private suspend fun cleanHelper() {
        val path = prefs.getString("helper_job", null) ?: return
        val job = File(path)
        require(job.canonicalFile.parentFile == directory.canonicalFile && job.name.startsWith("helper-"))
        if (withContext(Dispatchers.IO) { DesktopUpdater.finishHelper(job) })
            prefs.edit().remove("helper_job").commit()
    }
}
