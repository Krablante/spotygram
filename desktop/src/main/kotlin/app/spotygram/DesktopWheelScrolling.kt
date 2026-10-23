package app.spotygram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.channels.Channel
import org.jetbrains.skiko.SkiaLayer

/** One frame-driven wheel gesture for all Compose windows, including popups and dialogs. */
internal class DesktopWheelScrolling : EventQueue(), AutoCloseable {
    private val installed =
        DesktopTarget.os != "macos" &&
            System.getProperty("compose.scrolling.smooth.enabled") != "false"
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var active = false
    private var closed = false
    private var event: MouseWheelEvent? = null
    private var position = 0.0
    private var emitted = 0.0
    private var target = 0.0
    private var velocity = 0.0
    private var lastFrame = 0L

    init {
        if (installed) {
            // Compose's short linear wheel animation would otherwise animate each generated step.
            // Set this before any scrollable initializes its platform configuration.
            System.setProperty("compose.scrolling.smooth.enabled", "false")
            SwingUtilities.invokeAndWait { Toolkit.getDefaultToolkit().systemEventQueue.push(this) }
        }
    }

    @Composable
    fun Observe(visible: Boolean) {
        LaunchedEffect(visible) {
            if (!installed || !visible) return@LaunchedEffect
            val motion = coroutineContext[MotionDurationScale]
            active = true
            try {
                for (signal in signals) {
                    while (event != null) {
                        withFrameNanos { now ->
                            val reduced =
                                motion?.scaleFactor == 0f ||
                                    Toolkit.getDefaultToolkit()
                                        .getDesktopProperty("win.clientAreaAnimation") == false
                            advance(now, reduced)
                        }
                    }
                }
            } finally {
                active = false
                cancel()
            }
        }
    }

    override fun dispatchEvent(next: AWTEvent) {
        when {
            next is Step -> Unit
            next is MouseWheelEvent -> {
                // AWT can address either the native canvas or its listener-bearing container.
                val compose =
                    generateSequence(next.component) { it.parent }.any { it is SkiaLayer } ||
                        containsCanvas(next.component)
                val discrete =
                    next.wheelRotation != 0 &&
                        next.preciseWheelRotation == next.wheelRotation.toDouble()
                if (
                    active &&
                        compose &&
                        discrete &&
                        Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation") !=
                            false
                ) {
                    val previous = event
                    if (
                        previous == null ||
                            previous.component !== next.component ||
                            previous.point != next.point ||
                            previous.modifiersEx != next.modifiersEx ||
                            previous.scrollType != next.scrollType ||
                            previous.scrollAmount != next.scrollAmount ||
                            (target - emitted) * next.preciseWheelRotation < 0
                    ) {
                        cancel()
                        lastFrame = System.nanoTime()
                    }
                    event = next
                    target += next.preciseWheelRotation
                    signals.trySend(Unit)
                    return
                }
                // High-resolution wheels and touchpads already describe the user's motion.
                cancel()
            }
            next is MouseEvent &&
                (next.id == MouseEvent.MOUSE_PRESSED ||
                    next.id == MouseEvent.MOUSE_MOVED ||
                    next.id == MouseEvent.MOUSE_DRAGGED) -> cancel()
            next is KeyEvent && next.id == KeyEvent.KEY_PRESSED -> cancel()
        }
        super.dispatchEvent(next)
    }

    private fun advance(now: Long, immediate: Boolean) {
        val original = event ?: return
        if (!original.component.isShowing) {
            cancel()
            return
        }
        val dt = ((now - lastFrame).coerceAtLeast(0) / 1_000_000_000.0)
        lastFrame = now
        // Exact critically damped spring: continuous velocity when another notch extends the
        // target.
        val omega = 32.0
        val error = position - target
        val change = (velocity + omega * error) * dt
        val decay = exp(-omega * dt)
        position = target + (error + change) * decay
        velocity = (velocity - omega * change) * decay
        val amount = original.scrollAmount.coerceAtLeast(1).toDouble()
        // A delayed frame must not force a lazy list to compose several viewports at once.
        val budget =
            (if (original.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) 0.125 else 3.0) / amount
        val remaining = target - emitted
        val settled =
            immediate ||
                abs(target - position) < 0.01 && abs(velocity) < 0.2 && abs(remaining) <= budget
        val delta = if (settled) remaining else (position - emitted).coerceIn(-budget, budget)
        // Coalesce tiny tails; Compose discards subpixel wheel deltas rather than accumulating
        // them.
        if (abs(delta) >= 0.05 / amount || settled) {
            emitted += delta
            super.dispatchEvent(Step(original, delta))
        }
        if (settled) cancel()
    }

    private fun cancel() {
        event = null
        position = 0.0
        emitted = 0.0
        target = 0.0
        velocity = 0.0
    }

    private fun containsCanvas(component: Component): Boolean =
        component is SkiaLayer ||
            component is Container && component.components.any(::containsCanvas)

    override fun close() {
        if (closed) return
        closed = true
        signals.close()
        if (installed)
            SwingUtilities.invokeAndWait {
                active = false
                cancel()
                pop()
            }
    }

    private class Step(original: MouseWheelEvent, delta: Double) :
        MouseWheelEvent(
            original.component,
            MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            original.modifiersEx,
            original.x,
            original.y,
            original.xOnScreen,
            original.yOnScreen,
            0,
            false,
            original.scrollType,
            original.scrollAmount,
            0,
            delta,
        )
}
