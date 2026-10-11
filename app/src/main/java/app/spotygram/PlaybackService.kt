package app.spotygram

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import androidx.media3.common.Player as NativePlayer

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var queue: PlaybackQueue
    private val app get() = (application as AndroidApplication).model

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 1000, 2000).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory { TelegramDataSource(app) })
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK).build()
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo,
                command: Int): Int = if (app.musicCache.state.value.clearing)
                    SessionResult.RESULT_ERROR_INVALID_STATE else SessionResult.RESULT_SUCCESS
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(NativePlayer.COMMAND_SET_SHUFFLE_MODE)
                        .remove(NativePlayer.COMMAND_SET_REPEAT_MODE)
                        .remove(NativePlayer.COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(NativePlayer.COMMAND_SET_MEDIA_ITEM).build()).build()
        }).setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        queue = PlaybackQueue(app, AndroidPlayer(player))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        session.release()
        queue.close()
        super.onDestroy()
    }
}
