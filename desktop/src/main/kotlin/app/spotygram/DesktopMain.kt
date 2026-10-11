package app.spotygram

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.awt.FileDialog
import java.awt.SystemTray
import java.io.File
import java.net.URI
import javax.swing.JOptionPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class DesktopLifecycle : LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this)
}

fun main(arguments: Array<String>) {
    if (arguments.firstOrNull() == "--apply-update") {
        try { DesktopUpdater.apply(File(arguments[1])) }
        catch (error: Throwable) { error.printStackTrace(); kotlin.system.exitProcess(1) }
        kotlin.system.exitProcess(0)
    }
    if (arguments.firstOrNull() == "--write-icons") {
        BrandIcons.write(File(arguments[1]))
        return
    }
    System.setProperty("nucleus.app.id", "app.spotygram")
    System.setProperty("nucleus.app.name", "Spotygram")
    System.setProperty("nucleus.app.version", BuildConfig.VERSION_NAME)
    System.setProperty("nucleus.app.aumid", "app.spotygram")
    val platform = try { DesktopPlatform() } catch (error: Exception) {
        JOptionPane.showMessageDialog(null, error.message, "Spotygram", JOptionPane.ERROR_MESSAGE)
        return
    }
    val instance = try { DesktopInstance(platform.filesDir) } catch (error: Exception) {
        platform.close()
        JOptionPane.showMessageDialog(null, error.message, "Spotygram", JOptionPane.ERROR_MESSAGE)
        return
    }
    if (!instance.primary) {
        if (arguments.firstOrNull() != "--update-job") instance.forward(arguments.toList())
        instance.close()
        platform.close()
        return
    }
    var started: SpotygramApp? = null
    try {
        prepareDesktopGraphics(platform.cacheDir)
        val model = SpotygramApp(platform).also { started = it }.apply { start() }
        // The updater may acknowledge only a catalog that actually opened and reconciled.
        kotlinx.coroutines.runBlocking { model.awaitLibrary() }
        application {
            val app = remember { model }
            val installer = remember { app.updates.installer as DesktopUpdateInstaller }
            val engine = remember { MpvPlayer(app) }
            val queue = remember { PlaybackQueue(app, engine) }
            val controls = remember { PlaybackControls(app, engine) }
            val owner = remember { DesktopLifecycle() }
            val scope = rememberCoroutineScope()
            var visible by remember { mutableStateOf(true) }
            var closing by remember { mutableStateOf(false) }
            val display = remember { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
            val windowState = rememberWindowState(
                width = (display.width - 80).coerceIn(360, 1100).dp,
                height = (display.height - 80).coerceIn(500, 780).dp,
                position = WindowPosition(androidx.compose.ui.Alignment.Center),
            )
            val traySupported = remember { SystemTray.isSupported() }
            var media by remember { mutableStateOf<DesktopMediaControls?>(null) }
            val theme by app.theme.collectAsState()
            val language by app.language.collectAsState()
            val locale = remember(language) { platform.locale }
            val restart by app.updateRestartRequested.collectAsState()
            val applyUpdate by app.updateApplyRequested.collectAsState()
            val active = visible && !windowState.isMinimized && !closing
            fun show() { visible = true; windowState.isMinimized = false }
            fun quit() {
                if (closing) return
                closing = true
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                scope.launch {
                    media?.close()
                    app.close()
                    withContext(Dispatchers.IO) { platform.close() }
                    instance.close()
                    exitApplication()
                }
            }
            fun importAddresses(addresses: List<String>) {
                app.action {
                    for (address in addresses) {
                        try {
                            val file = if (address.startsWith("file:")) File(URI(address)) else File(address)
                            require(file.isFile) { tr(R.string.file_unavailable) }
                            app.importAudio(file.name) { file.inputStream() }
                            app.localMode()
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            app.notices.emit(friendly(error))
                        }
                    }
                }
            }
            fun importFile(address: String) = importAddresses(listOf(address))
            fun importFiles() {
                FileDialog(null as java.awt.Frame?, tr(R.string.import_files), FileDialog.LOAD).apply {
                    isMultipleMode = true
                    isVisible = true
                    importAddresses(files.map { it.absolutePath })
                    dispose()
                }
            }
            fun activate(values: List<String>) {
                show()
                importAddresses(values.filterNot { it.startsWith("--") })
            }
            LaunchedEffect(Unit) {
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                instance.listen { values -> app.scope.launch { activate(values) } }
                media = DesktopMediaControls(app, controls, ::show, ::quit, ::importFile)
                val incoming = arguments.toList()
                if (incoming.firstOrNull() != "--update-job") activate(incoming)
                app.awaitLibrary()
                if (incoming.firstOrNull() == "--update-job") DesktopUpdater.acknowledge(File(incoming[1]))
                (app.updates.installer as DesktopUpdateInstaller).resume()
            }
            LaunchedEffect(active) {
                if (closing) return@LaunchedEffect
                owner.lifecycle.currentState = if (active) Lifecycle.State.STARTED else Lifecycle.State.CREATED
                if (active) app.updates.check()
                else { queue.cancelPendingStart(); queue.savePosition() }
            }
            LaunchedEffect(applyUpdate) { if (applyUpdate) quit() }
            Window(
                onCloseRequest = {
                    if (engine.playWhenReady && engine.playbackState != Player.STATE_ENDED || app.downloadService?.running == true ||
                        app.updates.installer.state.value.phase in setOf(InstallPhase.DOWNLOADING, InstallPhase.VERIFYING)) {
                        if (traySupported) visible = false else windowState.isMinimized = true
                    } else quit()
                },
                title = "Spotygram",
                state = windowState,
                visible = visible,
                icon = painterResource(R.drawable.spotygram_mark),
                onPreviewKeyEvent = { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when {
                        event.key == Key.Escape -> app.backActions.back()
                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.O -> { importFiles(); true }
                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Q -> { quit(); true }
                        else -> false
                    }
                },
            ) {
                window.minimumSize = java.awt.Dimension(360, 500)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalApp provides app,
                        LocalLifecycleOwner provides owner,
                        LocalConfiguration provides ViewConfiguration(maxWidth.value.toInt(), maxHeight.value.toInt(),
                            density.fontScale, listOf(locale)),
                    ) {
                            SpotygramTheme(theme) {
                                SpotygramUI(app, controls, false,
                                    onStartPlayback = { tracks, index, order -> queue.start(tracks, index, order ?: app.playbackMode.value.order) },
                                    onImport = ::importFiles,
                                    onDownload = { ids -> app.action { app.download(ids) } },
                                )
                                if (restart) GlassDialog(
                                    onDismissRequest = { app.updateRestartRequested.value = false },
                                    title = { Text(tr(if (installer.usesSystemInstaller) R.string.update_package_title else R.string.update_restart_title)) },
                                    text = { Text(tr(if (installer.usesSystemInstaller) R.string.update_package_help else R.string.update_restart_help)) },
                                    confirmButton = { TextButton(onClick = {
                                        installer.confirmInstall()
                                    }) { Text(tr(if (installer.usesSystemInstaller) R.string.update_package_open else R.string.update_restart)) } },
                                    dismissButton = { TextButton(onClick = { app.updateRestartRequested.value = false }) { Text(tr(R.string.cancel)) } },
                                )
                            }
                    }
                }
            }
            if (traySupported) Tray(icon = painterResource(R.drawable.spotygram_mark), tooltip = "Spotygram",
                onAction = ::show, menu = {
                    Item(tr(R.string.show_window), onClick = ::show)
                    Separator()
                    Item(tr(R.string.quit_app), onClick = ::quit)
                })
        }
    } catch (error: Throwable) {
        kotlinx.coroutines.runBlocking { started?.close() }
        instance.close()
        platform.close()
        JOptionPane.showMessageDialog(null, error.message ?: "Spotygram could not start", "Spotygram", JOptionPane.ERROR_MESSAGE)
        error.printStackTrace()
        kotlin.system.exitProcess(1)
    }
    // The settings, database and TDLib have settled; terminate native credential-service worker threads.
    kotlin.system.exitProcess(0)
}
