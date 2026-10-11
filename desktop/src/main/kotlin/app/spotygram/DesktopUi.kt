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
    require(id == R.drawable.spotygram_mark)
    return androidx.compose.ui.res.painterResource("drawable/spotygram_mark.xml")
}

@Composable
fun MatchDialogSystemBars() = Unit
