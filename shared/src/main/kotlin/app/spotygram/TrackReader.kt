package app.spotygram

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Reads TDLib's on-disk bytes directly; no localhost server or duplicate media cache. */
class TrackReader(private val app: SpotygramApp) : AutoCloseable {
    // NIO allows TDLib to rename a completed download while it is open on Windows.
    private var input: FileChannel? = null
    private var lease: ListeningCache.Lease? = null
    private var fileId = 0
    private var position = 0L
    private var remaining = -1L
    @Volatile private var closed = false
    private val cancelled = MutableStateFlow(false)
    @Volatile private var opening: Job? = null

    fun open(trackId: String, position: Long = 0, length: Long = -1): Long {
        closed = false
        cancelled.value = false
        val track =
            app.track(trackId)
                ?: throw IOException(tr(R.string.track_missing))
        this.position = position
        remaining =
            if (length >= 0) length
            else if (track.size > 0) (track.size - position).coerceAtLeast(0)
            else -1L
        try {
            runBlocking(Dispatchers.IO) {
                opening = currentCoroutineContext().job
                try {
                    if (closed) throw CancellationException("Reader closed")
                    lease = app.listeningCache.acquire(track)
                    val acquired = checkNotNull(lease)
                    fileId = acquired.fileId
                    if (fileId != 0) app.telegram.download(fileId, position, 32)
                } finally { opening = null }
            }
            val localPath = checkNotNull(lease).path
            if (fileId == 0) {
                input = FileChannel.open(File(localPath).toPath(), StandardOpenOption.READ)
                remaining =
                    if (length >= 0)
                        minOf(length, (input!!.size() - position).coerceAtLeast(0))
                    else (input!!.size() - position).coerceAtLeast(0)
            }
            return remaining
        } catch (e: Exception) {
            close()
            throw IOException(friendly(e), e)
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val started = System.nanoTime()
        var available = 0L
        while (!closed && !Thread.currentThread().isInterrupted) {
            val revision = app.telegram.fileRevision.value
            if (fileId == 0) {
                available = (input?.size() ?: 0) - position
                break
            }
            val f = app.telegram.files[fileId]
            if (f != null) {
                if (f.complete) available = (File(f.path).length() - position).coerceAtLeast(0)
                else if (position >= f.offset && position < f.offset + f.prefix)
                    available = f.offset + f.prefix - position
                if (available > 0 || f.complete) {
                    if (input == null && f.path.isNotBlank())
                        input = FileChannel.open(File(f.path).toPath(), StandardOpenOption.READ)
                    break
                }
            }
            if ((System.nanoTime() - started) / 1_000_000 > 60_000)
                throw IOException(tr(R.string.track_download_failed))
            runBlocking {
                withTimeoutOrNull(500) {
                    combine(app.telegram.fileRevision, cancelled) { value, stopped -> value != revision || stopped }
                        .first { it }
                }
            }
        }
        if (closed || Thread.currentThread().isInterrupted)
            throw IOException(tr(R.string.loading_stopped))
        if (available <= 0) return -1
        val count =
            minOf(length.toLong(), available, if (remaining >= 0) remaining else Long.MAX_VALUE)
                .toInt()
        val file = input ?: throw IOException(tr(R.string.audio_unavailable))
        val read = file.read(ByteBuffer.wrap(buffer, offset, count), position)
        if (read < 0) return -1
        position += read
        if (remaining >= 0) remaining -= read
        return read
    }

    fun cancel() { closed = true; cancelled.value = true; opening?.cancel() }

    override fun close() {
        cancel()
        try {
            input?.close()
        } finally {
            input = null
            lease?.release()
            lease = null
        }
    }
}
