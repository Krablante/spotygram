package app.spotygram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Reads TDLib's on-disk bytes directly; no localhost server or duplicate media cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TelegramDataSource(private val app: SpotygramApp) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var input: RandomAccessFile? = null
    private var fileId = 0
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    @Volatile private var closed = false

    override fun getUri() = uri

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val track =
            app.track(Uri.decode(dataSpec.uri.lastPathSegment ?: ""))
                ?: throw IOException(tr(R.string.track_missing))
        position = dataSpec.position
        remaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else if (track.size > 0) (track.size - position).coerceAtLeast(0)
            else C.LENGTH_UNSET.toLong()
        try {
            if (track.local && validAudioCopy(track.path, track.size)) {
                input = RandomAccessFile(track.path, "r")
                remaining =
                    if (dataSpec.length != C.LENGTH_UNSET.toLong())
                        minOf(dataSpec.length, (input!!.length() - position).coerceAtLeast(0))
                    else (input!!.length() - position).coerceAtLeast(0)
                fileId = 0
            } else {
                if (!track.available) throw IOException(tr(R.string.message_deleted_no_copy))
                fileId = runBlocking(Dispatchers.IO) { app.resolve(track) }
                runBlocking(Dispatchers.IO) { app.telegram.download(fileId, position, 32) }
            }
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            close()
            throw IOException(friendly(e), e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val started = System.nanoTime()
        var available = 0L
        while (!closed && !Thread.currentThread().isInterrupted) {
            val revision = app.telegram.fileRevision.value
            if (fileId == 0) {
                available = (input?.length() ?: 0) - position
                break
            }
            val f = app.telegram.files[fileId]
            if (f != null) {
                if (f.complete) available = (File(f.path).length() - position).coerceAtLeast(0)
                else if (position >= f.offset && position < f.offset + f.prefix)
                    available = f.offset + f.prefix - position
                if (available > 0 || f.complete) {
                    if (input == null && f.path.isNotBlank()) input = RandomAccessFile(f.path, "r")
                    break
                }
            }
            if ((System.nanoTime() - started) / 1_000_000 > 60_000)
                throw IOException(tr(R.string.track_download_failed))
            runBlocking {
                withTimeoutOrNull(500) { app.telegram.fileRevision.first { it != revision } }
            }
        }
        if (closed || Thread.currentThread().isInterrupted)
            throw IOException(tr(R.string.loading_stopped))
        if (available <= 0) return C.RESULT_END_OF_INPUT
        val count =
            minOf(length.toLong(), available, if (remaining >= 0) remaining else Long.MAX_VALUE)
                .toInt()
        val file = input ?: throw IOException(tr(R.string.audio_unavailable))
        file.seek(position)
        val read = file.read(buffer, offset, count)
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        if (remaining >= 0) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        closed = true
        input?.close()
        input = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
