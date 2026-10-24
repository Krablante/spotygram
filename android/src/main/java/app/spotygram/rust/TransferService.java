package app.spotygram.rust;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class TransferService extends Service {
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("transfers", "Audio transfers", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "transfers").setContentTitle("spotygram rust")
            .setContentText("Transferring audio").setSmallIcon(R.drawable.ic_notification).setOngoing(true).build();
        startForeground(320, notification);
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_NOT_STICKY; }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        // Android 15+ limits background data-sync service time per day.
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
