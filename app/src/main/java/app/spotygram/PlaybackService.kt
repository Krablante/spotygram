package app.spotygram

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray

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
    private val app
        get() = application as SpotygramApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        exo =
            ExoPlayer.Builder(this)
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
                override fun onEvents(player: Player, events: Player.Events) {
                    if (
                        events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                            Player.EVENT_REPEAT_MODE_CHANGED,
                        )
                    )
                        save()
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
                }.getOrDefault(JSONArray())
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
                    exo.setMediaItems(
                        tracks.map { it.mediaItem() },
                        index,
                        app.prefs.getLong("queue_position", 0),
                    )
                    exo.shuffleModeEnabled = shuffle
                    exo.repeatMode = repeat
                }
            }
            restoring = false
            while (isActive) {
                delay(5000)
                if (exo.isPlaying) save()
            }
        }
    }

    private fun save() {
        if (restoring) return
        val queue = JSONArray()
        for (i in 0 until exo.mediaItemCount) queue.put(exo.getMediaItemAt(i).mediaId)
        app.prefs
            .edit()
            .putString("queue", queue.toString())
            .putString("queue_current", exo.currentMediaItem?.mediaId)
            .putInt("queue_index", exo.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("queue_position", exo.currentPosition.coerceAtLeast(0))
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
