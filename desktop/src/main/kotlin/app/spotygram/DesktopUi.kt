package app.spotygram

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity

@Composable
fun imeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val app = LocalApp.current
    val key = remember { Any() }
    val current by rememberUpdatedState(onBack)
    DisposableEffect(app, enabled) {
        if (enabled) app.backActions.register(key) { current() }
        onDispose { app.backActions.remove(key) }
    }
}

@Composable
fun painterResource(id: Int): Painter {
    val name = R.drawable.names[id] ?: error("Unknown drawable: $id")
    return androidx.compose.ui.res.painterResource("drawable-nodpi/$name.png")
}

@Composable
fun MatchDialogSystemBars() = Unit
