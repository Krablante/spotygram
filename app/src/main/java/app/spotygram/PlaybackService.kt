package app.spotygram

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray

private val playbackRandom = SecureRandom()

fun randomTrack(tracks: List<Track>): Track? {
    val playable = tracks.filter { it.local || it.available }
    return playable.takeIf { it.isNotEmpty() }?.let { it[playbackRandom.nextInt(it.size)] }
}

fun Track.mediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse("spotygram://track/${Uri.encode(id)}"))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(art.takeIf { it.isNotBlank() }?.let { Uri.fromFile(File(it)) })
                .build()
        )
        .build()

enum class PlaybackOrder {
    ORDERED,
    SHUFFLE,
    RANDOM;

    fun next() = entries[(ordinal + 1) % entries.size]
}

data class PlaybackMode(
    val order: PlaybackOrder = PlaybackOrder.ORDERED,
    val repeat: Int = Player.REPEAT_MODE_OFF,
) {
    val shuffle
        get() = order != PlaybackOrder.ORDERED

    val random
        get() = order == PlaybackOrder.RANDOM
}

data class QueueSnapshot(
    val ids: List<String>,
    val order: List<Int>,
    val current: Int,
    val next: Int = -1,
)

/** The full queue stays in-process; only previous/current/next enter Media3 and Binder. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var exo: ExoPlayer
    private val app
        get() = application as SpotygramApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ids: List<String> = emptyList()
    private var path: List<Int> = emptyList()
    private var poolIndices: List<Int> = emptyList()
    private var cursor = 0
    private var anchor = 0
    private var editing = false
    private var restoring = true
    private var generation = 0L
    private var version = UUID.randomUUID().toString()
    private var positionSaving: Job? = null
    private var restoringJob: Job? = null
    private var cleanupPosition: Long? = null
    private lateinit var writer: Job
    private val mode
        get() = app.playbackMode.value

    private val current
        get() = path.getOrNull(cursor) ?: -1

    private data class Saved(
        val version: String,
        val ids: List<String>,
        val path: List<Int>,
        val cursor: Int,
        val position: Long,
        val mode: PlaybackMode,
    )

    private val saves = Channel<Saved>(Channel.CONFLATED)
    private val handler = Handler(Looper.getMainLooper())
    private val slide = Runnable {
        window(preserve = true)
        changed(false)
    }

    override fun onCreate() {
        super.onCreate()
        exo =
            ExoPlayer.Builder(this)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 1000, 2000)
                        .build()
                )
                .setMediaSourceFactory(DefaultMediaSourceFactory { TelegramDataSource(app) })
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        session =
            MediaSession.Builder(this, exo)
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onPlayerCommandRequest(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            playerCommand: Int,
                        ): Int =
                            if (app.musicCache.state.value.clearing)
                                SessionResult.RESULT_ERROR_INVALID_STATE
                            else SessionResult.RESULT_SUCCESS

                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult =
                            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                                .setAvailablePlayerCommands(
                                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                                        .buildUpon()
                                        .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                                        .remove(Player.COMMAND_SET_REPEAT_MODE)
                                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                                        .build()
                                )
                                .build()
                    }
                )
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()
        app.player = exo
        app.playback = this
        // One IO writer serializes immutable snapshots. Position ticks never rewrite the ID list.
        writer =
            scope.launch(Dispatchers.IO) {
                var written = ""
                val queuePrefs = getSharedPreferences("playback_queue", MODE_PRIVATE)
                val positionPrefs = getSharedPreferences("playback_position", MODE_PRIVATE)
                for (s in saves) {
                    if (written != s.version) {
                        queuePrefs
                            .edit()
                            .putString("version", s.version)
                            .putString("ids", JSONArray(s.ids).toString())
                            .putString("path", JSONArray(s.path).toString())
                            .putInt("cursor", s.cursor)
                            .putLong("position", s.position)
                            .putBoolean("shuffle", s.mode.shuffle)
                            .putString("order", s.mode.order.name)
                            .putInt("repeat", s.mode.repeat)
                            .commit()
                        written = s.version
                    }
                    positionPrefs
                        .edit()
                        .putString("version", s.version)
                        .putInt("cursor", s.cursor)
                        .putLong("position", s.position)
                        .putBoolean("shuffle", s.mode.shuffle)
                        .putString("order", s.mode.order.name)
                        .putString(
                            "random_path",
                            if (s.mode.random) JSONArray(s.path).toString() else null,
                        )
                        .putInt("repeat", s.mode.repeat)
                        .commit()
                }
            }
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    positionSaving?.cancel()
                    positionSaving =
                        if (isPlaying)
                            scope.launch {
                                while (isActive) {
                                    delay(5000)
                                    save()
                                }
                            }
                        else null
                    save()
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (editing || restoring || ids.isEmpty()) return
                    if (
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                    ) {
                        cursor =
                            (cursor + exo.currentMediaItemIndex - anchor).coerceIn(path.indices)
                        // MediaSession must finish observing this transition before timeline edits.
                        anchor = exo.currentMediaItemIndex
                        handler.removeCallbacks(slide)
                        handler.post(slide)
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (
                        events.containsAny(
                            Player.EVENT_POSITION_DISCONTINUITY,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                        )
                    )
                        save()
                }

                override fun onPlayerError(error: PlaybackException) {
                    app.notices.tryEmit(
                        error.cause?.message?.take(180) ?: tr(R.string.playback_failed)
                    )
                }
            }
        )
        val initialGeneration = generation
        restoringJob = scope.launch {
            app.library.reload()
            val restored = withContext(Dispatchers.IO) { restore() }
            if (generation == initialGeneration && restored != null) {
                ids = restored.ids
                path = restored.path
                cursor = restored.cursor
                app.playbackMode.value = restored.mode
                if (app.musicCache.state.value.clearing) cleanupPosition = restored.position
                else window(position = restored.position)
            }
            restoring = false
            changed()
        }
    }

    fun start(tracks: List<Track>, index: Int, order: PlaybackOrder = mode.order) {
        if (blockedByCleanup()) return
        if (index !in tracks.indices) return
        generation++
        restoring = false
        ids = tracks.map { it.id }
        app.playbackMode.value =
            mode.copy(order = order, repeat = normalizedRepeat(order, mode.repeat))
        resetOrder(index)
        window()
        exo.prepare()
        exo.play()
        changed()
    }

    private fun permutation(): List<Int> {
        val result = ids.indices.toMutableList()
        if (mode.shuffle)
            for (i in result.lastIndex downTo 1) {
                val j = playbackRandom.nextInt(i + 1)
                val old = result[i]
                result[i] = result[j]
                result[j] = old
            }
        return result
    }

    private fun resetOrder(selected: Int) {
        if (mode.random) {
            path = if (selected in ids.indices) listOf(selected) else emptyList()
            cursor = 0
            return
        }
        val order = permutation().toMutableList()
        if (mode.shuffle && selected in ids.indices) {
            order.remove(selected)
            order.add(0, selected)
        }
        path = order
        cursor = path.indexOf(selected).coerceAtLeast(0)
    }

    private fun window(preserve: Boolean = false, position: Long = 0) {
        handler.removeCallbacks(slide)
        if (ids.isEmpty()) {
            editing = true
            exo.clearMediaItems()
            editing = false
            return
        }
        val n = ids.size
        if (mode.random) {
            // Keep 32 real previous selections, the current item and one independent draw.
            if (cursor > 32) {
                path = path.drop(cursor - 32)
                cursor = 32
            }
            if (cursor == path.lastIndex) path = path + playbackRandom.nextInt(n)
        } else if (
            mode.repeat == Player.REPEAT_MODE_ALL && cursor % n == n - 1 && cursor == path.lastIndex
        ) {
            val last = current
            if (cursor >= n) {
                path = path.drop(n)
                cursor -= n
            }
            val next = permutation().toMutableList()
            if (mode.shuffle && n > 1 && next[0] == last) {
                val other = 1 + playbackRandom.nextInt(n - 1)
                val first = next[0]
                next[0] = next[other]
                next[other] = first
            }
            path = path + next
            version = UUID.randomUUID().toString()
        }
        val previous = path.getOrNull(cursor - 1)?.let { app.track(ids[it])?.mediaItem() }
        val next =
            if (mode.random || cursor % n < n - 1 || mode.repeat == Player.REPEAT_MODE_ALL)
                path.getOrNull(cursor + 1)?.let { app.track(ids[it])?.mediaItem() }
            else null
        val item = app.track(ids[current])?.mediaItem() ?: return
        editing = true
        try {
            exo.repeatMode =
                if (mode.repeat == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE
                else Player.REPEAT_MODE_OFF
            if (preserve && exo.currentMediaItem?.mediaId == item.mediaId) {
                val index = exo.currentMediaItemIndex
                if (index > 0) exo.removeMediaItems(0, index)
                if (exo.mediaItemCount > 1) exo.removeMediaItems(1, exo.mediaItemCount)
                if (previous != null) exo.addMediaItem(0, previous)
                if (next != null) exo.addMediaItem(next)
            } else {
                exo.setMediaItems(
                    listOfNotNull(previous, item, next),
                    if (previous == null) 0 else 1,
                    position,
                )
            }
            anchor = if (previous == null) 0 else 1
        } finally {
            editing = false
        }
    }

    private fun normalizedRepeat(order: PlaybackOrder, repeat: Int) =
        if (order == PlaybackOrder.RANDOM && repeat == Player.REPEAT_MODE_ALL)
            Player.REPEAT_MODE_OFF
        else repeat

    fun setMode(order: PlaybackOrder = mode.order, repeat: Int = mode.repeat) {
        if (blockedByCleanup()) return
        val selected = current
        val reshuffle = mode.order != order
        app.playbackMode.value = PlaybackMode(order, normalizedRepeat(order, repeat))
        if (reshuffle) resetOrder(selected)
        window(preserve = true)
        changed()
    }

    fun snapshot(): QueueSnapshot {
        if (mode.random) {
            if (poolIndices.size != ids.size) poolIndices = ids.indices.toList()
            return QueueSnapshot(ids, poolIndices, current, path.getOrNull(cursor + 1) ?: -1)
        }
        val start = if (ids.isEmpty()) 0 else cursor / ids.size * ids.size
        return QueueSnapshot(
            ids,
            path.subList(start, (start + ids.size).coerceAtMost(path.size)),
            current,
        )
    }

    fun select(index: Int) {
        if (blockedByCleanup()) return
        if (index !in ids.indices) return
        if (mode.random) resetOrder(index)
        else {
            val start = cursor / ids.size * ids.size
            cursor = path.subList(start, start + ids.size).indexOf(index) + start
        }
        window()
        exo.prepare()
        exo.play()
        changed(false)
    }

    fun addNext(track: Track) {
        if (blockedByCleanup()) return
        if (ids.isEmpty()) {
            start(listOf(track), 0)
            return
        }
        val selected = current
        if (!mode.shuffle) {
            ids = ids.toMutableList().apply { add(selected + 1, track.id) }
            resetOrder(selected)
            window(preserve = true)
            changed()
            return
        }
        ids = ids + track.id
        resetOrder(selected)
        if (mode.random) {
            path = path + ids.lastIndex
            window(preserve = true)
            changed()
            return
        }
        val order = path.toMutableList()
        order.remove(ids.lastIndex)
        order.add(cursor + 1, ids.lastIndex)
        path = order
        window(preserve = true)
        changed()
    }

    fun remove(index: Int) = removeIndices(setOf(index))

    fun removeTrack(id: String) = removeIndices(ids.indices.filter { ids[it] == id }.toSet())

    private fun removeIndices(removed: Set<Int>) {
        if (blockedByCleanup()) return
        if (removed.isEmpty()) return
        val selected = current
        val retained = ids.indices.filter { it !in removed }
        val replacement = path.drop(cursor).firstOrNull { it !in removed } ?: retained.firstOrNull()
        ids = retained.map { ids[it] }
        if (ids.isEmpty()) {
            clear()
            return
        }
        resetOrder(
            retained.indexOf(if (selected in removed) replacement else selected).coerceAtLeast(0)
        )
        window(preserve = selected !in removed)
        changed()
    }

    fun moveUp(index: Int) {
        if (blockedByCleanup()) return
        if (mode.shuffle || index !in 1 until ids.size) return
        val selected = current
        val updated = ids.toMutableList()
        val item = updated.removeAt(index)
        updated.add(index - 1, item)
        ids = updated
        resetOrder(
            when (selected) {
                index -> index - 1
                index - 1 -> index
                else -> selected
            }
        )
        window(preserve = true)
        changed()
    }

    fun clear() {
        generation++
        restoring = false
        ids = emptyList()
        path = emptyList()
        poolIndices = emptyList()
        cursor = 0
        exo.stop()
        window()
        changed()
    }

    private fun blockedByCleanup(): Boolean {
        val blocked = app.musicCache.state.value.clearing
        if (blocked) app.notices.tryEmit(tr(R.string.cache_clearing))
        return blocked
    }

    fun pauseForCacheCleanup() {
        cleanupPosition = exo.currentPosition.coerceAtLeast(0)
        exo.pause()
        exo.stop()
        handler.removeCallbacks(slide)
        editing = true
        try {
            exo.clearMediaItems()
        } finally {
            editing = false
        }
        save()
    }

    fun finishCacheCleanup() {
        val position = cleanupPosition ?: return
        val selected = ids.getOrNull(current)
        cleanupPosition = null
        val missing = ids.indices.filter { app.track(ids[it]) == null }.toSet()
        if (missing.isNotEmpty()) removeIndices(missing)
        window(position = if (selected == ids.getOrNull(current)) position else 0)
        changed(false)
    }

    private fun changed(structural: Boolean = true) {
        if (structural) version = UUID.randomUUID().toString()
        app.queueRevision.value++
        save()
    }

    private fun save() {
        if (!restoring && !editing)
            saves.trySend(
                Saved(
                    version,
                    ids,
                    path,
                    cursor,
                    (cleanupPosition ?: exo.currentPosition.coerceAtLeast(0)).let {
                        if (exo.duration > 0 && it >= exo.duration) 0 else it
                    },
                    mode,
                )
            )
    }

    private fun restore(): Saved? {
        val queue = getSharedPreferences("playback_queue", MODE_PRIVATE)
        val position = getSharedPreferences("playback_position", MODE_PRIVATE)
        val legacy = !queue.contains("ids")
        val source = if (legacy) app.prefs else queue
        val progress =
            if (legacy) source
            else if (position.getString("version", "") == queue.getString("version", "?")) position
            else queue
        fun array(key: String) = runCatching {
            JSONArray(source.getString(key, "[]"))
        }
            .getOrDefault(JSONArray())
        val raw = array(if (legacy) "queue" else "ids")
        val retained = (0 until raw.length()).filter { app.track(raw.optString(it)) != null }
        if (retained.isEmpty()) return null
        val restoredIds = retained.map { raw.optString(it) }
        val remap = retained.withIndex().associate { it.value to it.index }
        val restoredOrder =
            runCatching { PlaybackOrder.valueOf(progress.getString("order", "")!!) }.getOrNull()
                ?: if (progress.getBoolean(if (legacy) "queue_shuffle" else "shuffle", false))
                    PlaybackOrder.SHUFFLE
                else PlaybackOrder.ORDERED
        val random = restoredOrder == PlaybackOrder.RANDOM
        val rawOrder =
            if (random)
                runCatching {
                        JSONArray(progress.getString("random_path", source.getString("path", "[]")))
                    }
                    .getOrDefault(JSONArray())
            else array(if (legacy) "queue_order" else "path")
        var restoredPath = (0 until rawOrder.length()).mapNotNull { remap[rawOrder.optInt(it, -1)] }
        val n = restoredIds.size
        if (random) {
            if (restoredPath.isEmpty()) restoredPath = listOf(0)
            restoredPath = restoredPath.take(34)
        } else if (
            restoredPath.isEmpty() ||
                restoredPath.size % n != 0 ||
                restoredPath.chunked(n).any { it.toSet().size != n }
        )
            restoredPath = restoredIds.indices.toList()
        val oldCursor =
            if (legacy) {
                restoredPath.indexOf(remap[source.getInt("queue_index", 0)]).coerceAtLeast(0)
            } else {
                val rawCursor = progress.getInt("cursor", 0)
                if (retained.size == raw.length()) rawCursor
                else if (random)
                    (0 until rawCursor.coerceAtMost(rawOrder.length())).count {
                        rawOrder.optInt(it, -1) in remap
                    }
                else restoredPath.indexOf(remap[rawOrder.optInt(rawCursor, -1)]).coerceAtLeast(0)
            }
        val c = oldCursor.coerceIn(restoredPath.indices)
        val duration = (app.track(restoredIds[restoredPath[c]])?.duration ?: 0) * 1000L
        val pos = progress.getLong(if (legacy) "queue_position" else "position", 0).coerceAtLeast(0)
        return Saved(
            version,
            restoredIds,
            restoredPath,
            c,
            if (duration > 0 && pos >= duration) 0 else pos,
            PlaybackMode(
                restoredOrder,
                normalizedRepeat(
                    restoredOrder,
                    progress.getInt(
                        if (legacy) "queue_repeat" else "repeat",
                        Player.REPEAT_MODE_OFF,
                    ),
                ),
            ),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        handler.removeCallbacks(slide)
        save()
        saves.close()
        positionSaving?.cancel()
        restoringJob?.cancel()
        scope.launch {
            writer.join()
            scope.cancel()
        }
        app.player = null
        app.playback = null
        session.release()
        exo.release()
        super.onDestroy()
    }
}
