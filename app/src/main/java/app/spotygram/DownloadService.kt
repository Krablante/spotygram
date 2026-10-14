package app.spotygram

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class DownloadService : Service() {
    private val app get() = (application as AndroidApplication).model
    private lateinit var downloads: Downloads
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var uploadObserver: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", tr(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        downloads = Downloads(app, { text, progress ->
            getSystemService(NotificationManager::class.java).notify(20, notification(text, progress))
        }, {
            if (app.profiles.uploadingAudio.value)
                getSystemService(NotificationManager::class.java).notify(20,
                    notification(tr(R.string.profile_audio_uploading), upload = true))
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        })
    }

    private fun stopIfIdle() {
        if (!downloads.running && !app.profiles.uploadingAudio.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(text: String, progress: Int = -1, upload: Boolean = false): Notification {
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, DownloadService::class.java).setAction("cancel"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(if (upload) R.string.profile_audio_uploading else R.string.download_notification_title)).setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
        if (!upload) builder.addAction(0, tr(R.string.stop), cancel)
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            downloads.cancel()
            stopIfIdle()
            return START_NOT_STICKY
        }
        val upload = intent?.action == "profileUpload"
        ServiceCompat.startForeground(this, 20,
            notification(tr(if (upload) R.string.profile_audio_uploading else R.string.preparing_downloads), upload = upload),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (!upload) {
            if (app.musicCache.state.value.clearing) stopIfIdle()
            else downloads.start()
        }
        if (uploadObserver == null) uploadObserver = scope.launch {
            app.profiles.uploadingAudio.collect { active ->
                if (!active) stopIfIdle()
                else if (!downloads.running)
                    getSystemService(NotificationManager::class.java).notify(20,
                        notification(tr(R.string.profile_audio_uploading), upload = true))
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { downloads.cancel(false); stopSelf() }
    override fun onDestroy() { scope.cancel(); downloads.close(); super.onDestroy() }
}
