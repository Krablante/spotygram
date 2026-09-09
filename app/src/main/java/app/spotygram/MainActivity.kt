package app.spotygram

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class MainActivity : ComponentActivity() {
    private val app
        get() = application as SpotygramApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveShared(intent)
        setContent {
            val mode by app.theme.collectAsStateWithLifecycle()
            val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            var controller by remember { mutableStateOf<MediaController?>(null) }
            DisposableEffect(Unit) {
                val future =
                    MediaController.Builder(
                            this@MainActivity,
                            SessionToken(
                                this@MainActivity,
                                ComponentName(this@MainActivity, PlaybackService::class.java),
                            ),
                        )
                        .buildAsync()
                future.addListener(
                    {
                        runCatching { controller = future.get() }
                            .onFailure {
                                app.notices.tryEmit(tr(R.string.player_start_failed))
                            }
                    },
                    ContextCompat.getMainExecutor(this@MainActivity),
                )
                onDispose { MediaController.releaseFuture(future) }
            }
            val picker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    app.action {
                        uris.forEach { app.importAudio(it) }
                        app.localMode()
                    }
                }
            val permission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            SpotygramTheme(mode) {
                SpotygramUI(
                    app,
                    controller,
                    onImport = { picker.launch(arrayOf("audio/*")) },
                    onDownload = { ids ->
                        if (Build.VERSION.SDK_INT >= 33)
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        app.action { app.download(ids) }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.updates.check()
    }

    override fun onResume() {
        super.onResume()
        app.updates.installer.resume(this)
    }

    override fun onPause() {
        app.updates.installer.pause(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShared(intent)
    }

    private fun receiveShared(intent: Intent?) {
        if (intent?.action == "app.spotygram.SHOW_UPDATES") app.updates.showSettings.value = true
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            uri?.let {
                app.localMode()
                app.action { app.importAudio(it) }
            }
        }
    }
}
