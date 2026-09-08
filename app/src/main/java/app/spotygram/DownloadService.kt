package app.spotygram

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DownloadService : Service() {
    private val app
        get() = application as SpotygramApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var activeFile = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    "downloads",
                    "Сохранение музыки",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    private fun notification(text: String, progress: Int = -1): Notification {
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadService::class.java).setAction("cancel"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Spotygram · На телефон")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .addAction(0, "Остановить", cancel)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            job?.cancel()
            app.action { app.library.clearDownloads() }
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            20,
            notification("Подготовка загрузок…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive != true)
            job = scope.launch {
                try {
                    app.library.reload()
                    while (isActive) {
                        val pending = app.library.pending()
                        val id = pending.firstOrNull() ?: break
                        val track = app.track(id)
                        if (track == null || track.local) {
                            app.library.dequeue(id)
                            continue
                        }
                        val network = getSystemService(ConnectivityManager::class.java)
                        if (
                            app.prefs.getBoolean("wifi_only", false) &&
                                network
                                    .getNetworkCapabilities(network.activeNetwork)
                                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
                        )
                            error(
                                "Загрузки приостановлены: требуется Wi-Fi. Возобновите их в настройках."
                            )
                        val fid = app.resolve(track)
                        activeFile = fid
                        if (app.telegram.files[fid]?.active != true)
                            app.telegram.download(fid, priority = 4)
                        withTimeout(15 * 60_000L) {
                            while (isActive) {
                                if (
                                    app.prefs.getBoolean("wifi_only", false) &&
                                        network
                                            .getNetworkCapabilities(network.activeNetwork)
                                            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) !=
                                            true
                                )
                                    error("Wi-Fi отключён. Загрузки приостановлены.")
                                val f = app.telegram.files[fid]
                                if (f?.complete == true) {
                                    app.library.file(fid, f.path)
                                    break
                                }
                                val fraction =
                                    if (f != null && f.size > 0)
                                        (f.downloaded.toFloat() / f.size).coerceIn(0f, 1f)
                                    else 0f
                                app.downloading.value = DownloadState(id, fraction, pending.size)
                                getSystemService(NotificationManager::class.java)
                                    .notify(20, notification(track.title, (fraction * 100).toInt()))
                                val revision = app.telegram.fileRevision.value
                                withTimeoutOrNull(1000) {
                                    app.telegram.fileRevision.first { it != revision }
                                }
                                delay(500)
                            }
                        }
                        app.library.dequeue(id)
                        activeFile = 0
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) app.notices.emit(friendly(e))
                } finally {
                    val fid = activeFile
                    if (
                        fid != 0 &&
                            app.track(app.player?.currentMediaItem?.mediaId.orEmpty())?.fileId !=
                                fid
                    )
                        withContext(NonCancellable) {
                            runCatching {
                                app.telegram.request(
                                    json(
                                        "cancelDownloadFile",
                                        "file_id" to fid,
                                        "only_if_pending" to false,
                                    )
                                )
                            }
                        }
                    activeFile = 0
                    app.downloading.value = DownloadState()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
