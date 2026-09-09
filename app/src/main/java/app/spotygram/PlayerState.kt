package app.spotygram

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay

data class Playing(
    val id: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val random: Boolean = false,
    val repeat: Int = 0,
)

@Composable
fun observePlayer(player: Player?, positionUpdates: Boolean = false): Playing {
    var state by remember(player) { mutableStateOf(Playing()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
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
                    player.currentPosition.coerceAtLeast(0),
                    player.duration.coerceAtLeast(0),
                    player.shuffleModeEnabled,
                    false,
                    player.repeatMode,
                )
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = snapshot()
            }
        player?.addListener(listener)
        snapshot()
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, lifecycle, positionUpdates) {
        if (!positionUpdates) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
