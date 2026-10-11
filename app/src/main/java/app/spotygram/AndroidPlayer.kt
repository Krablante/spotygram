package app.spotygram

import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import java.io.File
import androidx.media3.common.Player as NativePlayer
import androidx.media3.common.MediaItem as NativeItem

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidPlayer(val native: NativePlayer) : Player {
    private val listeners = mutableMapOf<Player.Listener, NativePlayer.Listener>()
    override val isConnected get() = (native as? MediaController)?.isConnected ?: true
    override val currentMediaItem get() = native.currentMediaItem?.shared()
    override val currentMediaItemIndex get() = native.currentMediaItemIndex
    override val mediaItemCount get() = native.mediaItemCount
    override val currentPosition get() = native.currentPosition
    override val duration get() = native.duration
    override val isPlaying get() = native.isPlaying
    override val playbackState get() = native.playbackState
    override val playWhenReady get() = native.playWhenReady
    override val playbackParameters get() = PlaybackParameters(native.playbackParameters.speed)
    override val shuffleModeEnabled get() = native.shuffleModeEnabled
    override var repeatMode: Int
        get() = native.repeatMode
        set(value) { native.repeatMode = value }
    override var volume: Float
        get() = native.volume
        set(value) { native.volume = value.coerceIn(0f, 1f) }
    override fun prepare() = native.prepare()
    override fun play() = native.play()
    override fun pause() = native.pause()
    override fun stop() = native.stop()
    override fun seekTo(position: Long) = native.seekTo(position)
    override fun seekToDefaultPosition() = native.seekToDefaultPosition()
    override fun seekToPreviousMediaItem() = native.seekToPreviousMediaItem()
    override fun seekToNextMediaItem() = native.seekToNextMediaItem()
    override fun setPlaybackSpeed(speed: Float) = native.setPlaybackSpeed(speed)
    override fun isCommandAvailable(command: Int) = native.isCommandAvailable(
        if (command == Player.COMMAND_SET_SPEED_AND_PITCH) NativePlayer.COMMAND_SET_SPEED_AND_PITCH else command,
    )
    override fun setMediaItems(items: List<MediaItem>, index: Int, position: Long) =
        native.setMediaItems(items.map { it.native() }, index, position)
    override fun clearMediaItems() = native.clearMediaItems()
    override fun removeMediaItems(from: Int, to: Int) = native.removeMediaItems(from, to)
    override fun addMediaItem(index: Int, item: MediaItem) = native.addMediaItem(index, item.native())
    override fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        val forwarding = object : NativePlayer.Listener {
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) =
                listener.onPlaybackParametersChanged(PlaybackParameters(parameters.speed))
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
            override fun onMediaItemTransition(item: NativeItem?, reason: Int) =
                listener.onMediaItemTransition(item?.shared(), reason)
            override fun onPositionDiscontinuity(old: NativePlayer.PositionInfo,
                new: NativePlayer.PositionInfo, reason: Int) =
                listener.onPositionDiscontinuity(old.shared(), new.shared(), reason)
            override fun onEvents(player: NativePlayer, events: NativePlayer.Events) {
                val changed = buildList {
                    if (events.contains(NativePlayer.EVENT_POSITION_DISCONTINUITY))
                        add(Player.EVENT_POSITION_DISCONTINUITY)
                    if (events.contains(NativePlayer.EVENT_PLAY_WHEN_READY_CHANGED))
                        add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                    if (events.contains(NativePlayer.EVENT_PLAYBACK_STATE_CHANGED))
                        add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                }
                listener.onEvents(this@AndroidPlayer, Player.Events(*changed.toIntArray()))
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) =
                listener.onPlayerError(PlaybackException(error.errorCode, error.errorCodeName))
        }
        listeners[listener] = forwarding
        native.addListener(forwarding)
    }
    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let(native::removeListener)
    }
    override fun release() {
        listeners.values.forEach(native::removeListener)
        listeners.clear()
        native.release()
    }
}

private fun NativeItem.shared() = MediaItem(mediaId, mediaMetadata.title?.toString().orEmpty(),
    mediaMetadata.artist?.toString().orEmpty(), mediaMetadata.artworkUri?.path.orEmpty())
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun NativePlayer.PositionInfo.shared() = Player.PositionInfo(mediaItemIndex, mediaItem?.shared(), positionMs)
private fun MediaItem.native(): NativeItem = NativeItem.Builder()
    .setMediaId(mediaId)
    .setUri(Uri.parse("spotygram://track/${Uri.encode(mediaId)}"))
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
        .setArtworkUri(artwork.takeIf { it.isNotEmpty() }?.let { Uri.fromFile(File(it)) }).build())
    .build()
