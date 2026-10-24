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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
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
        player = new ExoPlayer.Builder(this)
            .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(15000, 30000, 1000, 2000).build())
            .setMediaSourceFactory(new DefaultMediaSourceFactory(TelegramDataSource::new))
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                PlatformBridge.playerEvent(generation, 3, state == Player.STATE_BUFFERING ? 1 : 0, "");
                if (state == Player.STATE_READY && !ready) {
                    ready = true;
                    PlatformBridge.playerEvent(generation, 0, 0, "");
                    long duration = player.getDuration();
                    if (duration != C.TIME_UNSET) PlatformBridge.playerEvent(generation, 2, duration / 1000.0, "");
                }
                if (state == Player.STATE_ENDED) PlatformBridge.playerEvent(generation, 5, 0, "");
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                PlatformBridge.main.removeCallbacks(clock);
                PlatformBridge.playerEvent(generation, 4, playing ? 0 : 1, "");
                if (playing) PlatformBridge.main.post(clock);
            }
            @Override public void onPlayerError(PlaybackException error) { PlatformBridge.playerEvent(generation, 6, 0, error.getErrorCodeName()); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo old, Player.PositionInfo current, int reason) { PlatformBridge.playerEvent(generation, 1, player.getCurrentPosition() / 1000.0, ""); }
            @Override public void onPlayWhenReadyChanged(boolean playing, int reason) {
                if (!playing && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) PlatformBridge.command("pause", 0);
            }
        });
        Player controls = new ForwardingPlayer(player) {
            @Override public Commands getAvailableCommands() { return super.getAvailableCommands().buildUpon().add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build(); }
            @Override public boolean isCommandAvailable(int command) { return getAvailableCommands().contains(command); }
            @Override public void play() { PlatformBridge.command("play", 0); }
            @Override public void pause() { PlatformBridge.command("pause", 0); }
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
        while (!PlatformBridge.pending.isEmpty()) apply(PlatformBridge.pending.removeFirst());
    }

    void apply(JSONObject action) {
        try {
            switch (action.getString("action")) {
                case "load" -> {
                    ensureForeground(action.optString("title", "Preparing audio…"));
                    generation = action.getLong("generation"); ready = false;
                    player.setPlayWhenReady(false);
                    MediaMetadata.Builder metadata = new MediaMetadata.Builder().setTitle(action.optString("title")).setArtist(action.optString("artist"));
                    String art = action.optString("art"); if (!art.isEmpty()) metadata.setArtworkUri(android.net.Uri.fromFile(new File(art)));
                    MediaItem item = new MediaItem.Builder().setUri(action.getString("uri")).setMediaId(action.getString("uri")).setMediaMetadata(metadata.build()).build();
                    player.setMediaItem(item, (long)(action.optDouble("position") * 1000)); player.prepare();
                }
                case "seek" -> player.seekTo((long)(action.getDouble("position") * 1000));
                case "stop" -> { player.stop(); player.clearMediaItems(); }
                case "property" -> {
                    String value = action.getString("value");
                    switch (action.getString("key")) {
                        case "pause" -> player.setPlayWhenReady(!value.equals("yes"));
                        case "speed" -> player.setPlaybackParameters(new PlaybackParameters(Float.parseFloat(value), 1.0f));
                        case "volume" -> { volume=Float.parseFloat(value)/100.0f; player.setVolume(muted ? 0 : volume); }
                        case "mute" -> { muted=value.equals("yes"); player.setVolume(muted ? 0 : volume); }
                    }
                }
            }
        } catch (Exception error) { PlatformBridge.playerEvent(generation, 6, 0, error.getMessage()); }
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
        PlatformBridge.main.removeCallbacks(clock); PlatformBridge.playback = null;
        if (session != null) session.release(); if (player != null) player.release(); super.onDestroy();
    }
}
