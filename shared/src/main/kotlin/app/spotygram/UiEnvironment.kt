package app.spotygram

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntRect
import java.util.Locale

val LocalApp = staticCompositionLocalOf<SpotygramApp> { error("Spotygram host is missing") }

data class ViewConfiguration(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val fontScale: Float = 1f,
    val locales: List<Locale> = listOf(Locale.getDefault()),
)

val LocalConfiguration = staticCompositionLocalOf { ViewConfiguration(400, 800) }

// Compose prepares a few rows ahead while keeping retained UI proportional to the viewport.
@OptIn(ExperimentalFoundationApi::class)
internal val MusicListCacheWindow = LazyLayoutCacheWindow(aheadFraction = 0.5f, behindFraction = 0.25f)

/** Resolve the window position when opening a menu, rather than on every scroll placement. */
internal fun LayoutCoordinates?.popupBounds(): IntRect {
    if (this == null || !isAttached) return IntRect.Zero
    val bounds = boundsInWindow()
    return IntRect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
}

class BackActions {
    private val handlers = linkedMapOf<Any, () -> Unit>()
    fun register(key: Any, action: () -> Unit) { handlers[key] = action }
    fun remove(key: Any) { handlers.remove(key) }
    fun back(): Boolean {
        val action = handlers.values.lastOrNull() ?: return false
        action()
        return true
    }
}
