package app.spotygram

import dev.nucleusframework.media.control.MediaControlEvent
import dev.nucleusframework.media.control.MediaControlService
import dev.nucleusframework.media.control.MediaMetadata
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import java.io.File
import kotlinx.coroutines.launch

class DesktopMediaControls(private val app: SpotygramApp, private val player: Player,
    private val show: () -> Unit, private val quit: () -> Unit, private val open: (String) -> Unit) : AutoCloseable {
    private var metadata: MediaItem? = null
    private var duration = -1L
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }
    private val updates = app.scope.launch { app.playbackCheckpoints.collect { publish() } }
    init {
        MediaControlService.configure("org.mpris.MediaPlayer2.app.spotygram", "Spotygram")
        MediaControlService.attach { event -> when (event) {
            MediaControlEvent.Play -> if (!player.isPlaying) togglePlayback(player)
            MediaControlEvent.Pause -> player.pause()
            MediaControlEvent.Toggle -> togglePlayback(player)
            MediaControlEvent.Next -> player.seekToNextMediaItem()
            MediaControlEvent.Previous -> player.seekToPreviousMediaItem()
            MediaControlEvent.Stop -> player.stop()
            is MediaControlEvent.SeekBy -> player.seekTo(player.currentPosition + event.offsetMs)
            is MediaControlEvent.SetPosition -> player.seekTo(event.positionMs)
            is MediaControlEvent.SetVolume -> player.volume = event.volume.toFloat()
            is MediaControlEvent.OpenUri -> open(event.uri)
            MediaControlEvent.Raise -> show()
            MediaControlEvent.Quit -> quit()
        } }
        player.addListener(listener)
        publish()
    }
    private fun publish() {
        val item = player.currentMediaItem
        if (item != metadata || duration != player.duration) {
            metadata = item
            duration = player.duration
            MediaControlService.setMetadata(MediaMetadata(title = item?.title, artist = item?.artist,
                coverUrl = item?.artwork?.takeIf(String::isNotEmpty)?.let { File(it).toURI().toString() },
                duration = duration.takeIf { it > 0 }))
        }
        MediaControlService.setPlaybackState(MediaPlaybackState(when {
            player.isPlaying -> MediaPlaybackStatus.PLAYING
            item != null -> MediaPlaybackStatus.PAUSED
            else -> MediaPlaybackStatus.STOPPED
        }, player.currentPosition))
        MediaControlService.setVolume(player.volume.toDouble())
    }
    override fun close() {
        updates.cancel()
        player.removeListener(listener)
        MediaControlService.detach()
    }
}
