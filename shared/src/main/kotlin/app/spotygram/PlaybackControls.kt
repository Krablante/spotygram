package app.spotygram

/** External commands use the same cleanup exclusion as Android's media session. */
class PlaybackControls(private val app: SpotygramApp, private val engine: Player) : Player by engine {
    override var volume: Float
        get() = engine.volume
        set(value) { command { engine.volume = value } }
    private inline fun command(action: () -> Unit) {
        if (!app.musicCache.state.value.clearing) action()
    }
    override fun prepare() = command { engine.prepare() }
    override fun play() = command { engine.play() }
    override fun pause() = command { engine.pause() }
    override fun stop() = command { engine.stop() }
    override fun seekTo(position: Long) = command { engine.seekTo(position) }
    override fun seekToDefaultPosition() = command { engine.seekToDefaultPosition() }
    override fun seekToPreviousMediaItem() = command { engine.seekToPreviousMediaItem() }
    override fun seekToNextMediaItem() = command { engine.seekToNextMediaItem() }
    override fun setPlaybackSpeed(speed: Float) = command { engine.setPlaybackSpeed(speed) }
    override fun isCommandAvailable(command: Int) = !app.musicCache.state.value.clearing && engine.isCommandAvailable(command)
}
