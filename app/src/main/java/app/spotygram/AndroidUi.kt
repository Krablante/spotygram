package app.spotygram

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun imeVisible(): Boolean = WindowInsets.isImeVisible

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
fun painterResource(id: Int): Painter = androidx.compose.ui.res.painterResource(id)

@Composable
fun MatchDialogSystemBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    LaunchedEffect(view, light) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}
