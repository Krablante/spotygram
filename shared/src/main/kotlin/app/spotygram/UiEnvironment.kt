package app.spotygram

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

val LocalApp = staticCompositionLocalOf<SpotygramApp> { error("Spotygram host is missing") }

data class ViewConfiguration(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val fontScale: Float = 1f,
    val locales: List<Locale> = listOf(Locale.getDefault()),
)

val LocalConfiguration = staticCompositionLocalOf { ViewConfiguration(400, 800) }

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
