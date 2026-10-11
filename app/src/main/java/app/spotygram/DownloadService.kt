package app.spotygram

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class DownloadService : Service() {
    private val app get() = (application as AndroidApplication).model
    private lateinit var downloads: Downloads

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", tr(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        downloads = Downloads(app, { text, progress ->
            getSystemService(NotificationManager::class.java).notify(20, notification(text, progress))
        }, {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        })
    }

    private fun notification(text: String, progress: Int = -1): Notification {
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, DownloadService::class.java).setAction("cancel"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.download_notification_title)).setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .addAction(0, tr(R.string.stop), cancel).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            downloads.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, 20, notification(tr(R.string.preparing_downloads)),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (app.musicCache.state.value.clearing) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else downloads.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { downloads.cancel(false); stopSelf() }
    override fun onDestroy() { downloads.close(); super.onDestroy() }
}
