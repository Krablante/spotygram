package app.spotygram

import android.content.ComponentName
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Foreground Activity connection. Playback itself remains owned by PlaybackService. */
class PlayerConnection(private val activity: ComponentActivity, private val app: SpotygramApp) {
    val controller = MutableStateFlow<MediaController?>(null)
    val waiting = MutableStateFlow(false)
    private var future: ListenableFuture<MediaController>? = null
    private var deadline: Job? = null
    private var retry: Job? = null
    private var started = false
    private var generation = 0
    private var retries = 1

    private data class Request(
        val ids: List<String>,
        val selected: String,
        val order: PlaybackOrder?,
    )

    private var pending: Request? = null

    fun start() {
        if (started) return
        started = true
        retries = 1
        connect()
    }

    fun stop() {
        started = false
        pending = null
        waiting.value = false
        retry?.cancel()
        retry = null
        release()
    }

    fun play(tracks: List<Track>, index: Int, order: PlaybackOrder? = null) {
        if (!started || index !in tracks.indices) return
        pending = Request(tracks.map { it.id }, tracks[index].id, order)
        retries = 1
        if (dispatch()) return
        waiting.value = true
        if (controller.value != null) failed(generation, "unavailable session")
        else if (future == null && retry == null) connect()
    }

    private fun dispatch(): Boolean {
        if (controller.value?.isConnected != true) return false
        val service = app.playback ?: return false
        val request = pending ?: return true
        val tracks = request.ids.mapNotNull { app.track(it) }.filter { it.local || it.available }
        val index = tracks.indexOfFirst { it.id == request.selected }
        pending = null
        waiting.value = false
        if (index < 0) app.notices.tryEmit(tr(R.string.no_local_copy))
        else service.start(tracks, index, request.order ?: app.playbackMode.value.order)
        return true
    }

    private fun connect() {
        if (!started || future != null) return
        val ticket = ++generation
        try {
            val connecting =
                MediaController.Builder(
                        activity,
                        SessionToken(
                            activity,
                            ComponentName(activity, PlaybackService::class.java),
                        ),
                    )
                    .setListener(
                        object : MediaController.Listener {
                            override fun onDisconnected(controller: MediaController) {
                                failed(ticket, "disconnected")
                            }
                        }
                    )
                    .buildAsync()
            if (!started || generation != ticket) {
                MediaController.releaseFuture(connecting)
                return
            }
            future = connecting
            deadline =
                activity.lifecycleScope.launch {
                    delay(10_000)
                    failed(ticket, "timeout")
                }
            connecting.addListener(
                {
                    if (!started || generation != ticket) return@addListener
                    try {
                        val ready = connecting.get()
                        if (!ready.isConnected) {
                            failed(ticket, "disconnected before ready")
                            return@addListener
                        }
                        deadline?.cancel()
                        deadline = null
                        controller.value = ready
                        if (!dispatch()) failed(ticket, "missing service")
                    } catch (e: Exception) {
                        failed(ticket, e.javaClass.simpleName)
                    }
                },
                ContextCompat.getMainExecutor(activity),
            )
        } catch (e: Exception) {
            failed(ticket, e.javaClass.simpleName)
        }
    }

    private fun failed(ticket: Int, reason: String) {
        if (!started || generation != ticket) return
        Log.w("SpotygramConnection", "Player connection failed: $reason")
        release()
        if (retries > 0) {
            retries--
            retry =
                activity.lifecycleScope.launch {
                    delay(750)
                    retry = null
                    connect()
                }
        } else {
            pending = null
            waiting.value = false
            app.notices.tryEmit(tr(R.string.player_start_failed))
        }
    }

    private fun release() {
        generation++
        deadline?.cancel()
        deadline = null
        controller.value = null
        val previous = future
        future = null
        previous?.let { MediaController.releaseFuture(it) }
    }
}
