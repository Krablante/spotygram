package app.spotygram

import com.sun.jna.Pointer
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** libmpv owns decoding and audio output. Shared TrackReader owns Telegram byte ranges. */
class MpvPlayer(private val app: SpotygramApp) : Player {
    private val listeners = linkedSetOf<Player.Listener>()
    private val items = mutableListOf<MediaItem>()
    private var index = 0
    private var position = 0L
    private var length = 0L
    private var wanted = false
    private var prepared = false
    private var buffering = false
    private var state = Player.STATE_IDLE
    private var speed = 1f
    @Volatile private var released = false
    @Volatile private var generation = 0L
    @Volatile private var expectedUri = ""
    @Volatile private var failedGeneration = -1L
    private var nativeEntry = -1L
    private var readyGeneration = -1L
    private var pendingPosition: Long? = null
    private val tokens = AtomicLong()
    private class Stream(val id: String, val size: Long, val reader: TrackReader, val generation: Long) {
        val buffer = ByteArray(64 * 1024)
        @Volatile var opened = false
        @Volatile var cancelled = false
        fun prepare() {
            if (cancelled) throw java.io.IOException("Loading stopped")
            if (!opened) {
                reader.open(id)
                opened = true
                if (cancelled) { reader.cancel(); throw java.io.IOException("Loading stopped") }
            }
        }
    }
    private val streams = ConcurrentHashMap<Long, Stream>()
    private fun stream(cookie: Pointer?) = cookie?.let { streams[Pointer.nativeValue(it)] }
    private fun readFailed(stream: Stream?) {
        if (stream == null || stream.cancelled || released || stream.generation != generation) return
        failedGeneration = stream.generation
        dispatch { if (generation == stream.generation && prepared) failed(-13) }
    }
    private val readCallback = StreamRead { cookie, output, size ->
        val stream = stream(cookie)
        try {
            if (stream == null) return@StreamRead -1
            stream.prepare()
            val count = stream.reader.read(stream.buffer, 0, minOf(size, stream.buffer.size.toLong()).toInt())
            if (count < 0) 0 else {
                if (count > 0) output.write(0, stream.buffer, 0, count)
                count.toLong()
            }
        } catch (_: Exception) { readFailed(stream); -1 }
    }
    private val seekCallback = StreamSeek { cookie, offset ->
        val stream = stream(cookie)
        try {
            if (stream == null) return@StreamSeek -1
            if (stream.cancelled) return@StreamSeek -1
            if (offset < 0 || stream.size > 0 && offset > stream.size) return@StreamSeek -1
            stream.reader.close()
            stream.reader.open(stream.id, offset)
            stream.opened = true
            if (stream.cancelled) { stream.reader.cancel(); return@StreamSeek -1 }
            offset
        } catch (_: Exception) { readFailed(stream); -1 }
    }
    private val sizeCallback = StreamSize { cookie -> stream(cookie)?.size?.takeIf { it > 0 } ?: -18 }
    private val closeCallback = StreamClose { cookie ->
        cookie?.let { streams.remove(Pointer.nativeValue(it))?.reader?.close() }
    }
    private val cancelCallback = StreamCancel { cookie -> stream(cookie)?.let { it.cancelled = true; it.reader.cancel() } }
    private val openCallback = StreamOpen { _, uri, output ->
        var reader: TrackReader? = null
        try {
            if (released || uri != expectedUri) return@StreamOpen -13
            val id = URLDecoder.decode(uri.substringAfter("spotygram://track/").substringBefore('?'), "UTF-8")
            val track = app.track(id) ?: return@StreamOpen -13
            reader = TrackReader(app)
            val token = tokens.incrementAndGet()
            val size = track.size.takeIf { it > 0 } ?: if (track.local) File(track.path).length() else 0
            streams[token] = Stream(id, size, reader, generation)
            MpvStreamInfo(output).apply {
                cookie = Pointer.createConstant(token)
                read = readCallback
                seek = seekCallback
                sizeCallback = this@MpvPlayer.sizeCallback
                closeCallback = this@MpvPlayer.closeCallback
                cancel = cancelCallback
                write()
            }
            0
        } catch (_: Exception) { reader?.close(); -13 }
    }

    private val handle = Mpv.create(buildMap {
        put("terminal", "no")
        put("input-terminal", "no")
        put("input-default-bindings", "no")
        put("idle", "yes")
        put("keep-open", "no")
        put("pause", "yes")
        put("video", "no")
        put("audio-client-name", "Spotygram")
        put("audio-pitch-correction", "yes")
        put("demuxer-readahead-secs", "30")
        put("demuxer-max-bytes", "8MiB")
        put("cache-pause-initial", "yes")
        put("cache-pause-wait", "1")
        System.getProperty("spotygram.audio.driver")?.let { put("ao", it) }
    })
    private val events: Thread

    init {
        check(Mpv.api.mpv_stream_cb_add_ro(handle, "spotygram", null, openCallback) >= 0)
        listOf("pause" to Mpv.FLAG, "paused-for-cache" to Mpv.FLAG, "seeking" to Mpv.FLAG,
            "duration" to Mpv.DOUBLE, "speed" to Mpv.DOUBLE).forEachIndexed { i, (name, format) ->
            check(Mpv.api.mpv_observe_property(handle, i.toLong() + 1, name, format) >= 0)
        }
        events = Thread({ receive() }, "Spotygram audio events").apply { isDaemon = true; start() }
    }

    override val currentMediaItem get() = items.getOrNull(index)
    override val currentMediaItemIndex get() = index
    override val mediaItemCount get() = items.size
    override val currentPosition get(): Long {
        if (released || !prepared || readyGeneration != generation) return position
        pendingPosition?.let { return it }
        if (state == Player.STATE_ENDED) return length
        position = (Mpv.double(handle, "time-pos", position / 1000.0) * 1000).toLong().coerceAtLeast(0)
        return position
    }
    override val duration get() = length
    override val isPlaying get() = wanted && prepared && state == Player.STATE_READY && !buffering
    override val playbackState get() = state
    override val playWhenReady get() = wanted
    override val playbackParameters get() = PlaybackParameters(speed)
    override var repeatMode = Player.REPEAT_MODE_OFF
    override var volume = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            check(Mpv.api.mpv_set_property_string(handle, "volume", (field * 100).toString()) >= 0)
            emit()
        }

    private fun emit(vararg changed: Int) {
        val playing = isPlaying
        if (playing != lastPlaying) {
            lastPlaying = playing
            listeners.toList().forEach { it.onIsPlayingChanged(playing) }
        }
        listeners.toList().forEach { it.onEvents(this, Player.Events(*changed)) }
    }
    private var lastPlaying = false
    private fun dispatch(action: () -> Unit) {
        app.scope.launch(Dispatchers.Main) { if (!released) action() }
    }
    private fun receive() {
        var eventEntry = -1L
        try {
            while (!released) {
                val event = MpvEvent(Mpv.api.mpv_wait_event(handle, -1.0))
                when (event.id) {
                    Mpv.EVENT_SHUTDOWN -> break
                    Mpv.EVENT_START_FILE -> eventEntry = event.data?.getLong(0) ?: -1L
                    Mpv.EVENT_FILE_LOADED -> {
                        val entry = eventEntry
                        val duration = (Mpv.double(handle, "duration") * 1000).toLong()
                        dispatch {
                            if (entry != nativeEntry || !prepared) return@dispatch
                            readyGeneration = generation
                            buffering = Mpv.flag(handle, "paused-for-cache") || Mpv.flag(handle, "seeking")
                            length = duration.takeIf { it > 0 } ?: (app.track(currentMediaItem?.mediaId.orEmpty())?.duration ?: 0) * 1000L
                            state = if (buffering) Player.STATE_BUFFERING else Player.STATE_READY
                            pendingPosition?.let { Mpv.command(handle, "seek", (it / 1000.0).toString(), "absolute+exact") }
                            Mpv.api.mpv_set_property_string(handle, "pause", if (wanted) "no" else "yes")
                            emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
                        }
                    }
                    Mpv.EVENT_PLAYBACK_RESTART -> {
                        val entry = eventEntry
                        // Capture at the native event, before a busy UI thread can advance beyond the seek tolerance.
                        val actual = (Mpv.double(handle, "time-pos") * 1000).toLong()
                        dispatch {
                            if (entry != nativeEntry || readyGeneration != generation) return@dispatch
                            if (pendingPosition == null || kotlin.math.abs(actual - pendingPosition!!) <= 500) {
                                pendingPosition = null
                                position = actual
                                buffering = Mpv.flag(handle, "paused-for-cache") || Mpv.flag(handle, "seeking")
                                state = if (buffering) Player.STATE_BUFFERING else Player.STATE_READY
                                emit(Player.EVENT_POSITION_DISCONTINUITY)
                            }
                        }
                    }
                    Mpv.EVENT_END_FILE -> {
                        val data = event.data ?: continue
                        val reason = data.getInt(0)
                        val error = data.getInt(4)
                        val entry = data.getLong(8)
                        val ticket = generation
                        dispatch {
                            if (ticket != generation || !prepared || entry != nativeEntry) return@dispatch
                            if (failedGeneration == ticket) failed(-13)
                            else if (reason == 0) completed()
                            else if (reason == 4 && error < 0) failed(error)
                        }
                    }
                    Mpv.EVENT_PROPERTY_CHANGE -> {
                        val property = event.data?.let(::MpvProperty) ?: continue
                        val name = property.name?.getString(0, "UTF-8") ?: continue
                        val value = property.data?.let { data -> when (property.format) {
                            Mpv.FLAG -> if (data.getInt(0) != 0) 1.0 else 0.0
                            Mpv.DOUBLE -> data.getDouble(0)
                            else -> null
                        } } ?: continue
                        val ticket = generation
                        dispatch {
                            if (ticket != generation) return@dispatch
                            when (name) {
                                "duration" -> if (readyGeneration == generation) {
                                    val current = Mpv.double(handle, "duration")
                                    if (current > 0) length = (current * 1000).toLong()
                                }
                                "speed" -> if (Mpv.double(handle, "speed", speed.toDouble()).toFloat() != speed) {
                                    speed = Mpv.double(handle, "speed", speed.toDouble()).toFloat()
                                    listeners.toList().forEach { it.onPlaybackParametersChanged(playbackParameters) }
                                }
                                "pause" -> if (readyGeneration == generation && state == Player.STATE_READY)
                                    wanted = !Mpv.flag(handle, "pause")
                                "paused-for-cache", "seeking" -> {
                                    if (readyGeneration != generation) return@dispatch
                                    buffering = pendingPosition != null || Mpv.flag(handle, "paused-for-cache") || Mpv.flag(handle, "seeking")
                                    if (prepared && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
                                        state = if (buffering) Player.STATE_BUFFERING else Player.STATE_READY
                                }
                            }
                            emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
                        }
                    }
                }
            }
        } finally {
            streams.values.forEach { it.reader.cancel() }
            Mpv.api.mpv_terminate_destroy(handle)
            streams.values.forEach { it.reader.close() }
            streams.clear()
        }
    }

    private fun failed(code: Int) {
        position = currentPosition
        state = Player.STATE_IDLE
        prepared = false
        wanted = false
        streams.values.forEach { it.cancelled = true; it.reader.cancel() }
        Mpv.command(handle, "stop")
        listeners.toList().forEach { it.onPlayerError(PlaybackException(
            if (code == -17 || code == -18) PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED else 2000,
            "MPV_$code")) }
        emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
    }
    private fun load() {
        val item = currentMediaItem ?: return
        generation++
        failedGeneration = -1
        readyGeneration = -1
        pendingPosition = null
        expectedUri = "spotygram://track/${URLEncoder.encode(item.mediaId, "UTF-8")}?generation=$generation"
        nativeEntry = -1
        buffering = true
        state = Player.STATE_BUFFERING
        Mpv.api.mpv_set_property_string(handle, "pause", "yes")
        val result = Mpv.commandSync(handle, "loadfile", expectedUri, "replace", "-1", "start=${position / 1000.0}")
        if (result < 0) { failed(result); return }
        nativeEntry = Mpv.integer(handle, "playlist/0/id")
        emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
    }
    override fun prepare() {
        if (released || prepared || currentMediaItem == null) return
        prepared = true
        load()
    }
    override fun play() {
        wanted = true
        if (prepared && state == Player.STATE_READY) Mpv.api.mpv_set_property_string(handle, "pause", "no")
        emit(Player.EVENT_PLAY_WHEN_READY_CHANGED)
    }
    override fun pause() {
        position = currentPosition
        wanted = false
        Mpv.api.mpv_set_property_string(handle, "pause", "yes")
        emit(Player.EVENT_PLAY_WHEN_READY_CHANGED)
    }
    override fun stop() {
        position = currentPosition
        generation++
        expectedUri = ""
        streams.values.forEach { it.reader.cancel() }
        Mpv.command(handle, "stop")
        prepared = false
        buffering = false
        state = Player.STATE_IDLE
        emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
    }
    private fun transition(selected: Int, reason: Int, discontinuity: Int) {
        val old = Player.PositionInfo(index, currentMediaItem, currentPosition)
        index = selected
        position = 0
        prepared = false
        length = (app.track(currentMediaItem?.mediaId.orEmpty())?.duration ?: 0) * 1000L
        val next = Player.PositionInfo(index, currentMediaItem, 0)
        listeners.toList().forEach { it.onPositionDiscontinuity(old, next, discontinuity) }
        listeners.toList().forEach { it.onMediaItemTransition(currentMediaItem, reason) }
        prepared = true
        load()
        emit(Player.EVENT_POSITION_DISCONTINUITY)
    }
    private fun completed() {
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            transition(index, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        } else if (index + 1 < items.size) {
            transition(index + 1, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        } else {
            state = Player.STATE_ENDED
            position = length
            emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
        }
    }
    override fun seekTo(position: Long) {
        val old = Player.PositionInfo(index, currentMediaItem, currentPosition)
        this.position = position.coerceAtLeast(0).let { if (length > 0) it.coerceAtMost(length) else it }
        pendingPosition = this.position
        if (prepared) {
            if (state == Player.STATE_ENDED) load()
            else if (readyGeneration == generation) {
                buffering = true
                state = Player.STATE_BUFFERING
                Mpv.command(handle, "seek", (this.position / 1000.0).toString(), "absolute+exact")
            }
        }
        listeners.toList().forEach { it.onPositionDiscontinuity(old,
            Player.PositionInfo(index, currentMediaItem, this.position), Player.DISCONTINUITY_REASON_SEEK) }
        emit(Player.EVENT_POSITION_DISCONTINUITY)
    }
    override fun seekToDefaultPosition() = seekTo(0)
    override fun seekToPreviousMediaItem() {
        if (index > 0) transition(index - 1, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, Player.DISCONTINUITY_REASON_SEEK)
        else seekTo(0)
    }
    override fun seekToNextMediaItem() {
        if (index + 1 < items.size) transition(index + 1, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, Player.DISCONTINUITY_REASON_SEEK)
    }
    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        check(Mpv.api.mpv_set_property_string(handle, "speed", this.speed.toString()) >= 0)
        listeners.toList().forEach { it.onPlaybackParametersChanged(playbackParameters) }
        emit()
    }
    override fun isCommandAvailable(command: Int) = !released
    override fun setMediaItems(items: List<MediaItem>, index: Int, position: Long) {
        val reload = prepared
        this.items.clear()
        this.items.addAll(items)
        this.index = index
        this.position = position.coerceAtLeast(0)
        length = (app.track(currentMediaItem?.mediaId.orEmpty())?.duration ?: 0) * 1000L
        listeners.toList().forEach { it.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        if (reload) load() else emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
    }
    override fun clearMediaItems() {
        stop()
        items.clear()
        index = 0
        position = 0
        length = 0
        wanted = false
        listeners.toList().forEach { it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        emit(Player.EVENT_PLAYBACK_STATE_CHANGED)
    }
    override fun removeMediaItems(from: Int, to: Int) {
        if (from == to) return
        items.subList(from, to).clear()
        if (index >= to) index -= to - from
        else if (index >= from) index = from.coerceAtMost(items.lastIndex).coerceAtLeast(0)
        emit()
    }
    override fun addMediaItem(index: Int, item: MediaItem) {
        items.add(index, item)
        if (index <= this.index && items.size > 1) this.index++
        emit()
    }
    override fun addListener(listener: Player.Listener) { listeners += listener }
    override fun removeListener(listener: Player.Listener) { listeners -= listener }
    override fun release() {
        if (released) return
        released = true
        streams.values.forEach { it.reader.cancel() }
        Mpv.api.mpv_wakeup(handle)
        events.join(5_000)
        listeners.clear()
    }
}
