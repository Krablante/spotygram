package app.spotygram

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TelegramDataSource(app: SpotygramApp) : BaseDataSource(true) {
    private val reader = TrackReader(app)
    private var uri: Uri? = null
    private var opened = false

    override fun getUri() = uri
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val length = reader.open(
            Uri.decode(dataSpec.uri.lastPathSegment.orEmpty()), dataSpec.position, dataSpec.length,
        )
        opened = true
        transferStarted(dataSpec)
        return length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader.read(buffer, offset, length).also { if (it > 0) bytesTransferred(it) }
    override fun close() {
        reader.close()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
