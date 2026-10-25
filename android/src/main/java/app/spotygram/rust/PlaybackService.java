package app.spotygram.rust;

import android.app.PendingIntent;
import android.content.Intent;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import java.io.File;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession session;
    private long generation;
    private boolean ready;
    private boolean ended;
    private boolean releasing;
    private boolean preparing;
    private String preparingTitle = "Preparing audio…";
    private int reportedState = -1;
    private Boolean reportedPlaying;
    private long reportedDuration = C.TIME_UNSET;
    private float volume=1.0f;
    private boolean muted;
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (!player.isPlaying()) return;
            PlatformBridge.playerEvent(generation, 1, player.getCurrentPosition() / 1000.0, "");
            PlatformBridge.main.postDelayed(this, PlatformBridge.visible ? 250 : 5000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        ensureForeground("Preparing audio…");
        DefaultMediaNotificationProvider notifications = new DefaultMediaNotificationProvider(this);
        notifications.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(notifications);
        setListener(new MediaSessionService.Listener() {
            @androidx.annotation.RequiresApi(31)
            @Override public void onForegroundServiceStartNotAllowedException() {
                if (!releasing && generation != 0)
                    PlatformBridge.playerEvent(generation, 6, 0, "Audio playback failed. Press Play to try again.");
            }
        });
        player = new ExoPlayer.Builder(this)
            .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(15000, 30000, 1000, 2000).build())
            // The native range reader already waits for recoverable network stalls.
            // Repeating that full timeout hides a permanent failure for several minutes.
            .setMediaSourceFactory(new ProgressiveMediaSource.Factory(TelegramDataSource::new)
                // A 1-MiB loading interval can buffer minutes of low-bitrate speech
                // before checking the duration limit again.
                .setContinueLoadingCheckIntervalBytes(32768)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0)))
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build();
        player.addListener(new Player.Listener() {
            @Override public void onEvents(Player current, Player.Events events) {
                long eventGeneration = currentGeneration();
                if (releasing || eventGeneration == 0) return;
                int state = player.getPlaybackState();
                if (reportedState != state) {
                    reportedState = state;
                    PlatformBridge.playerEvent(eventGeneration, 3, state == Player.STATE_BUFFERING ? 1 : 0, "");
                }
                if (state == Player.STATE_READY && !ready) {
                    ready = true;
                    PlatformBridge.playerEvent(eventGeneration, 0, 0, "");
                }
                long duration = player.getDuration();
                if (duration != C.TIME_UNSET && duration != reportedDuration) {
                    reportedDuration = duration;
                    PlatformBridge.playerEvent(eventGeneration, 2, duration / 1000.0, "");
                }
                boolean playing = player.isPlaying();
                if (reportedPlaying == null || reportedPlaying != playing) {
                    reportedPlaying = playing;
                    PlatformBridge.main.removeCallbacks(clock);
                    PlatformBridge.playerEvent(eventGeneration, 1, player.getCurrentPosition() / 1000.0, "");
                    if (playing) PlatformBridge.playerEvent(eventGeneration, 4, 0, "");
                    else PlatformBridge.playerEvent(eventGeneration, 9, player.getCurrentPosition() / 1000.0, "");
                    if (playing) PlatformBridge.main.post(clock);
                } else if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                    PlatformBridge.playerEvent(eventGeneration, 1, player.getCurrentPosition() / 1000.0, "");
                }
                if (state == Player.STATE_ENDED && !ended) {
                    ended = true;
                    PlatformBridge.playerEvent(eventGeneration, 5, 0, "");
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                long eventGeneration = currentGeneration();
                if (!releasing && eventGeneration != 0 && player.getPlayerError() == error) {
                    android.util.Log.e("spotygram", error.getErrorCodeName(), error);
                    String message = error.errorCode >= 2000 && error.errorCode < 3000
                        ? "Could not read audio. Check the file or connection and press Play to retry."
                        : error.errorCode >= 3000 && error.errorCode < 5000
                        ? "This audio could not be decoded. Try another recording."
                        : "Audio playback failed. Press Play to try again.";
                    PlatformBridge.playerEvent(eventGeneration, 6, 0, message);
                }
            }
            @Override public void onPlayWhenReadyChanged(boolean playing, int reason) {
                long eventGeneration = currentGeneration();
                if (!releasing && eventGeneration != 0 && !playing && !player.getPlayWhenReady() && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
                    PlatformBridge.playerEvent(eventGeneration, 8, player.getCurrentPosition() / 1000.0, "");
            }
        });
        Player controls = new ForwardingPlayer(player) {
            @Override public Commands getAvailableCommands() { return super.getAvailableCommands().buildUpon().add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build(); }
            @Override public boolean isCommandAvailable(int command) { return getAvailableCommands().contains(command); }
            @Override public void play() { PlatformBridge.command("play", 0); }
            @Override public void pause() { PlatformBridge.command("pause", 0); }
            @Override public void stop() { PlatformBridge.command("pause", 0); }
            @Override public void setPlayWhenReady(boolean ready) { PlatformBridge.command(ready ? "play" : "pause", 0); }
            @Override public void seekToNextMediaItem() { PlatformBridge.command("next", 0); }
            @Override public void seekToPreviousMediaItem() { PlatformBridge.command("previous", 0); }
            @Override public void seekToNext() { PlatformBridge.command("next", 0); }
            @Override public void seekToPrevious() { PlatformBridge.command("previous", 0); }
            @Override public void seekTo(long position) { PlatformBridge.command("seek", position / 1000.0); }
            @Override public void seekTo(int index, long position) { PlatformBridge.command("seek", position / 1000.0); }
            @Override public void setPlaybackSpeed(float speed) { PlatformBridge.command("speed", speed); }
        };
        PendingIntent activity = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, controls).setSessionActivity(activity).build();
        PlatformBridge.playback = this;
        PlatformBridge.playbackStarting = false;
        while (!PlatformBridge.pending.isEmpty()) apply(PlatformBridge.pending.removeFirst());
        addSession(session);
        if (!preparing && player.getMediaItemCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    void apply(JSONObject action) {
        try {
            switch (action.getString("action")) {
                case "begin" -> {
                    PlatformBridge.main.removeCallbacks(clock);
                    generation = action.getLong("generation");
                    preparing = true;
                    preparingTitle = action.optString("title", "Preparing audio…");
                    ensureForeground(preparingTitle);
                }
                case "load" -> {
                    ensureForeground(action.optString("title", "Preparing audio…"));
                    player.setPlayWhenReady(false);
                    generation = action.getLong("generation"); ready = false; ended = false;
                    reportedState = -1; reportedPlaying = null; reportedDuration = C.TIME_UNSET;
                    PlatformBridge.main.removeCallbacks(clock);
                    MediaMetadata.Builder metadata = new MediaMetadata.Builder().setTitle(action.optString("title")).setArtist(action.optString("artist"));
                    String art = action.optString("art"); if (!art.isEmpty()) metadata.setArtworkUri(android.net.Uri.fromFile(new File(art)));
                    MediaItem item = new MediaItem.Builder().setUri(action.getString("uri")).setMediaId(action.getString("uri")).setMediaMetadata(metadata.build()).build();
                    player.setPlaybackParameters(new PlaybackParameters((float)action.optDouble("speed", 1), 1));
                    volume = (float)action.optDouble("volume", 100) / 100;
                    muted = action.optBoolean("muted");
                    player.setVolume(muted ? 0 : volume);
                    player.setMediaItem(item, (long)(action.optDouble("position") * 1000));
                    player.setPlayWhenReady(action.optBoolean("playing"));
                    player.prepare();
                    preparing = false;
                }
                case "seek" -> { if (action.optLong("generation") == generation) player.seekTo((long)(action.getDouble("position") * 1000)); }
                case "stop" -> {
                    boolean wasPreparing = preparing;
                    preparing = false;
                    player.stop(); player.clearMediaItems();
                    if (wasPreparing) stopForeground(STOP_FOREGROUND_REMOVE);
                }
                case "property" -> {
                    if (action.optLong("generation") != generation) return;
                    String value = action.getString("value");
                    switch (action.getString("key")) {
                        case "pause" -> {
                            if (!value.equals("yes")) ensureForeground(player.getMediaMetadata().title == null ? "spotygram rust" : player.getMediaMetadata().title.toString());
                            player.setPlayWhenReady(!value.equals("yes"));
                        }
                        case "speed" -> player.setPlaybackParameters(new PlaybackParameters(Float.parseFloat(value), 1.0f));
                        case "volume" -> { volume=Float.parseFloat(value)/100.0f; player.setVolume(muted ? 0 : volume); }
                        case "mute" -> { muted=value.equals("yes"); player.setVolume(muted ? 0 : volume); }
                    }
                }
            }
        } catch (Exception error) { PlatformBridge.playerEvent(action.optLong("generation"), 6, 0, error.getMessage()); }
    }
    @Override public void onUpdateNotification(MediaSession current, boolean foregroundRequired) {
        // Rust may still be resolving the file. Keep the user-started service in
        // foreground until an actual media item can take over Media3's lifetime.
        // Media3's notification controller receives the timeline asynchronously.
        // Do not let its still-idle snapshot remove the user-started foreground
        // service between setMediaItem/prepare and the buffering notification.
        if (!releasing && (preparing || (player.getPlayWhenReady() && !foregroundRequired))) ensureForeground(preparingTitle);
        else super.onUpdateNotification(current, foregroundRequired);
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // A loading/temporarily buffering recording still has playback intent.
        if (!preparing && !player.getPlayWhenReady()) super.onTaskRemoved(rootIntent);
    }
    private long currentGeneration() {
        MediaItem item = player.getCurrentMediaItem();
        return item != null && item.mediaId.equals("spotygram://" + generation) ? generation : 0;
    }
    private void ensureForeground(String title) {
        var manager=getSystemService(android.app.NotificationManager.class);
        String channel=DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID;
        manager.createNotificationChannel(new android.app.NotificationChannel(channel,"Music playback",android.app.NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        var notification=new android.app.Notification.Builder(this,channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("spotygram rust").setContentText(title).setContentIntent(open).setOngoing(true).build();
        startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,notification);
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller) { return session; }
    @Override public void onDestroy() {
        releasing = true;
        double position = player == null || preparing || currentGeneration() == 0 ? -1 : player.getCurrentPosition() / 1000.0;
        // Cancel native range waits before ExoPlayer.release waits for its loader.
        PlatformBridge.cancelAudio(generation);
        PlatformBridge.main.removeCallbacks(clock); PlatformBridge.playback = null;
        if (session != null) session.release(); if (player != null) player.release(); super.onDestroy();
        if (generation != 0) PlatformBridge.playerEvent(generation, 7, position, "");
    }
}
