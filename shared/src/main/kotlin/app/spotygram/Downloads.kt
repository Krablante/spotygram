package app.spotygram

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** One persisted download queue, with host-owned foreground notification/lifecycle. */
class Downloads(
    private val app: SpotygramApp,
    private val progress: (String, Int) -> Unit = { _, _ -> },
    private val finished: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var activeFile = 0
    private var queueChanged = false
    val running get() = job?.isActive == true

    fun start() {
        if (running) { queueChanged = true; return }
        if (app.musicCache.state.value.clearing) return
        app.downloadService = this
        job = scope.launch {
            try {
                app.awaitLibrary()
                var pending = emptyList<String>()
                var cursor = 0
                queueChanged = true
                while (isActive) {
                    if (queueChanged || cursor == pending.size) {
                        queueChanged = false
                        pending = app.library.pending()
                        cursor = 0
                    }
                    val id = pending.getOrNull(cursor)
                    if (id == null) {
                        if (queueChanged) continue
                        break
                    }
                    val queued = pending.size - cursor++
                    app.downloading.value = DownloadState(id, queued = queued)
                    val track = app.track(id)
                    if (track == null || track.local) {
                        app.library.dequeue(id)
                        continue
                    }
                    if (app.prefs.getBoolean("wifi_only", false) && !app.platform.wifi)
                        error(tr(R.string.wifi_downloads_paused))
                    val fid = app.resolve(track)
                    activeFile = fid
                    if (app.telegram.files[fid]?.active != true)
                        app.telegram.download(fid, priority = 4)
                    withTimeout(15 * 60_000L) {
                        while (isActive) {
                            if (app.prefs.getBoolean("wifi_only", false) && !app.platform.wifi)
                                error(tr(R.string.wifi_disconnected))
                            val f = app.telegram.files[fid]
                            if (f?.complete == true) {
                                app.library.file(fid, f.path)
                                break
                            }
                            val fraction = if (f != null && f.size > 0)
                                (f.downloaded.toFloat() / f.size).coerceIn(0f, 1f) else 0f
                            app.downloading.value = DownloadState(id, fraction, queued)
                            progress(track.title, (fraction * 100).toInt())
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
                if (e !is CancellationException || e is TimeoutCancellationException) app.notices.emit(friendly(e))
            } finally {
                val fid = activeFile
                if (fid != 0 && !app.listeningCache.protectsFile(fid))
                    withContext(NonCancellable) {
                        runCatching {
                            app.telegram.request(json("cancelDownloadFile", "file_id" to fid,
                                "only_if_pending" to false))
                        }
                    }
                activeFile = 0
                app.downloading.value = DownloadState()
                if (app.downloadService === this@Downloads) app.downloadService = null
                finished()
            }
        }
    }

    fun cancel(clearQueue: Boolean = true) {
        job?.cancel()
        if (clearQueue) app.action { app.library.clearDownloads() }
    }

    suspend fun cancelForCacheCleanup() { job?.cancelAndJoin() }
    fun close() { job?.cancel(); scope.cancel() }
}
