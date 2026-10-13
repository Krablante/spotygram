package app.spotygram

data class MediaItem(
    val mediaId: String,
    val title: String = "",
    val artist: String = "",
    val artwork: String = "",
)

data class PlaybackParameters(val speed: Float = 1f)

class PlaybackException(val errorCode: Int, val errorCodeName: String) : Exception(errorCodeName) {
    companion object {
        const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3003
        const val ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3001
        const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005
        const val ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
    }
}

/** A bounded physical timeline. The complete queue belongs to PlaybackQueue. */
interface Player {
    val isConnected: Boolean get() = true
    val currentMediaItem: MediaItem?
    val currentMediaItemIndex: Int
    val mediaItemCount: Int
    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean
    val playbackState: Int
    val playWhenReady: Boolean
    val playbackParameters: PlaybackParameters
    val shuffleModeEnabled: Boolean get() = false
    var repeatMode: Int
    var volume: Float
    val supportsVolumeControl: Boolean get() = false
    fun toggleMute() { volume = if (volume > 0f) 0f else 1f }

    fun prepare()
    fun play()
    fun pause()
    fun stop()
    fun seekTo(position: Long)
    fun seekToDefaultPosition()
    fun seekToPreviousMediaItem()
    fun seekToNextMediaItem()
    fun setPlaybackSpeed(speed: Float)
    fun isCommandAvailable(command: Int): Boolean
    fun setMediaItems(items: List<MediaItem>, index: Int, position: Long)
    fun clearMediaItems()
    fun removeMediaItems(from: Int, to: Int)
    fun addMediaItem(index: Int, item: MediaItem)
    fun addMediaItem(item: MediaItem) = addMediaItem(mediaItemCount, item)
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun release()

    data class PositionInfo(val mediaItemIndex: Int, val mediaItem: MediaItem?, val positionMs: Long)
    class Events(private vararg val values: Int) {
        fun containsAny(vararg candidates: Int) = candidates.any { it in values }
    }
    interface Listener {
        fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onMediaItemTransition(item: MediaItem?, reason: Int) {}
        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {}
        fun onEvents(player: Player, events: Events) {}
        fun onPlayerError(error: PlaybackException) {}
    }

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT = 0
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 2
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3
        const val DISCONTINUITY_REASON_AUTO_TRANSITION = 0
        const val DISCONTINUITY_REASON_SEEK = 1
        const val EVENT_PLAYBACK_STATE_CHANGED = 4
        const val EVENT_PLAY_WHEN_READY_CHANGED = 5
        const val EVENT_POSITION_DISCONTINUITY = 11
        const val COMMAND_SET_SPEED_AND_PITCH = 13
    }
}
