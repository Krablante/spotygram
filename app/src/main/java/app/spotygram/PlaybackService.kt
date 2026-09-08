package app.spotygram

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.*
import org.json.JSONArray

private val playbackRandom = SecureRandom()

fun randomTrack(tracks: List<Track>): Track? {
    val playable = tracks.filter { it.local || it.available }
    return if (playable.isEmpty()) null else playable[playbackRandom.nextInt(playable.size)]
}

@androidx.annotation.OptIn(UnstableApi::class)
fun playNext(player: Player?, track: Track) {
    if (player == null) return
    val next = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
    player.addMediaItem(next, track.mediaItem())
    if (player is ExoPlayer && player.shuffleModeEnabled) {
        val order = mutableListOf<Int>()
        val timeline = player.currentTimeline
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < player.mediaItemCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        order.remove(next)
        order.add((order.indexOf(player.currentMediaItemIndex) + 1).coerceAtLeast(0), next)
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), playbackRandom.nextLong()))
    }
}

fun Track.mediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse("spotygram://track/${Uri.encode(id)}"))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifBlank { source })
                .setArtworkUri(art.takeIf { it.isNotBlank() }?.let { Uri.fromFile(File(it)) })
                .build()
        )
        .build()

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var exo: ExoPlayer
    private var restoring = true
    private var positionSaving: Job? = null
    private val app
        get() = application as SpotygramApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        exo =
            ExoPlayer.Builder(this)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
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
                }

                override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                    if (enabled && !restoring) reshuffle()
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (
                        !restoring &&
                            exo.shuffleModeEnabled &&
                            reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
                    )
                        reshuffle()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Repeat-all starts another fresh pass, rather than looping one permutation
                    // forever.
                    if (
                        !restoring &&
                            exo.shuffleModeEnabled &&
                            exo.repeatMode == Player.REPEAT_MODE_ALL &&
                            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
                            exo.currentMediaItemIndex ==
                                exo.currentTimeline.getLastWindowIndex(true)
                    )
                        reshuffle()
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (
                        events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                            Player.EVENT_REPEAT_MODE_CHANGED,
                        )
                    )
                        save(events.contains(Player.EVENT_TIMELINE_CHANGED))
                }

                override fun onPlayerError(error: PlaybackException) {
                    app.notices.tryEmit(
                        error.cause?.message?.take(180)
                            ?: "Не удалось воспроизвести трек. Нажмите воспроизведение ещё раз."
                    )
                }
            }
        )
        scope.launch {
            app.library.reload()
            if (exo.mediaItemCount == 0) {
                val ids = runCatching {
                    JSONArray(app.prefs.getString("queue", "[]"))
                }
                    .getOrDefault(JSONArray())
                val retained =
                    (0 until ids.length()).mapNotNull { index ->
                        app.track(ids.optString(index))?.let { index to it }
                    }
                val tracks = retained.map { it.second }
                if (tracks.isNotEmpty()) {
                    val selected = app.prefs.getString("queue_current", "")
                    val shuffle = app.prefs.getBoolean("queue_shuffle", false)
                    val repeat = app.prefs.getInt("queue_repeat", Player.REPEAT_MODE_OFF)
                    val index =
                        retained
                            .indexOfFirst { it.first == app.prefs.getInt("queue_index", 0) }
                            .takeIf { it >= 0 }
                            ?: tracks.indexOfFirst { it.id == selected }.coerceAtLeast(0)
                    val duration =
                        app.prefs.getLong("queue_duration", 0).takeIf { it > 0 }
                            ?: tracks[index].duration * 1000L
                    val position =
                        app.prefs.getLong("queue_position", 0).let {
                            if (duration > 0 && it >= duration) 0L else it
                        }
                    exo.setMediaItems(
                        tracks.map { it.mediaItem() },
                        index,
                        position,
                    )
                    exo.shuffleModeEnabled = shuffle
                    exo.repeatMode = repeat
                    val order = runCatching {
                        JSONArray(app.prefs.getString("queue_order", "[]"))
                    }
                        .getOrDefault(JSONArray())
                    val indices = IntArray(order.length()) { order.optInt(it, -1) }
                    if (
                        retained.size == ids.length() &&
                            indices.size == tracks.size &&
                            indices.toSet() == tracks.indices.toSet()
                    )
                        exo.setShuffleOrder(DefaultShuffleOrder(indices, playbackRandom.nextLong()))
                    else if (shuffle) reshuffle()
                }
            }
            restoring = false
            save(true)
        }
    }

    private fun reshuffle() {
        val current = exo.currentMediaItemIndex
        if (current !in 0 until exo.mediaItemCount) return
        val rest = (0 until exo.mediaItemCount).filter { it != current }.toIntArray()
        for (i in rest.lastIndex downTo 1) {
            val j = playbackRandom.nextInt(i + 1)
            val temp = rest[i]
            rest[i] = rest[j]
            rest[j] = temp
        }
        exo.setShuffleOrder(
            DefaultShuffleOrder(intArrayOf(current) + rest, playbackRandom.nextLong())
        )
    }

    private fun save(queueChanged: Boolean = false) {
        if (restoring) return
        val edit = app.prefs.edit()
        if (queueChanged) {
            val queue = JSONArray()
            for (i in 0 until exo.mediaItemCount) queue.put(exo.getMediaItemAt(i).mediaId)
            val order = JSONArray()
            val timeline = exo.currentTimeline
            var index = timeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET && order.length() < exo.mediaItemCount) {
                order.put(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
            edit.putString("queue", queue.toString()).putString("queue_order", order.toString())
        }
        edit
            .putString("queue_current", exo.currentMediaItem?.mediaId)
            .putInt("queue_index", exo.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("queue_position", exo.currentPosition.coerceAtLeast(0))
            .putLong("queue_duration", exo.duration.coerceAtLeast(0))
            .putBoolean("queue_shuffle", exo.shuffleModeEnabled)
            .putInt("queue_repeat", exo.repeatMode)
            .apply()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        save()
        scope.cancel()
        app.player = null
        session.release()
        exo.release()
        super.onDestroy()
    }
}
