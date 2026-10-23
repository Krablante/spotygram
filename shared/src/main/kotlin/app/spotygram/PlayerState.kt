package app.spotygram

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

const val MIN_PLAYBACK_SPEED = 0.3f
const val MAX_PLAYBACK_SPEED = 4f
const val PLAYBACK_SPEED_STEP = 0.05f

fun playbackSpeedStep(value: Float): Float =
    ((value / PLAYBACK_SPEED_STEP).roundToInt() * PLAYBACK_SPEED_STEP).coerceIn(
        MIN_PLAYBACK_SPEED,
        MAX_PLAYBACK_SPEED,
    )

data class Playing(
    val id: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val order: PlaybackOrder = PlaybackOrder.ORDERED,
    val repeat: Int = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
) {
    val shuffle
        get() = order.shuffled

    val random
        get() = order == PlaybackOrder.RANDOM
}

@Composable
fun observePlayer(player: Player?, positionUpdates: Boolean = false): Playing {
    var state by remember(player) { mutableStateOf(Playing()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, lifecycle, positionUpdates) {
        fun snapshot() {
            if (player == null) {
                state = Playing()
                return
            }
            state =
                Playing(
                    player.currentMediaItem?.mediaId.orEmpty(),
                    player.isPlaying,
                    player.playbackState == Player.STATE_BUFFERING,
                    if (positionUpdates) player.currentPosition.coerceAtLeast(0) else 0,
                    player.duration.coerceAtLeast(0),
                    if (player.shuffleModeEnabled) PlaybackOrder.SHUFFLE else PlaybackOrder.ORDERED,
                    player.repeatMode,
                    player.playbackParameters.speed,
                    player.volume,
                )
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = snapshot()
            }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            player?.addListener(listener)
            snapshot()
            try {
                if (!positionUpdates) awaitCancellation()
                snapshotFlow { state.playing || state.loading }
                    .collectLatest { active ->
                        if (active)
                            while (true) {
                                delay(500)
                                if (player != null)
                                    state =
                                        state.copy(
                                            position = player.currentPosition.coerceAtLeast(0),
                                            duration = player.duration.coerceAtLeast(0),
                                        )
                            }
                    }
            } finally {
                player?.removeListener(listener)
            }
        }
    }
    return state
}

fun togglePlayback(player: Player?) {
    if (player == null) return
    if (player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        player.pause()
    else {
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }
}
