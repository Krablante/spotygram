package app.spotygram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag

/** Tag readers do not decode audio; libmpv supplies duration for remaining formats. */
object DesktopMetadata {
    private const val MAX_ART = 8 * 1024 * 1024
    init { java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF }

    fun read(path: String, name: String): AudioMetadata {
        val file = File(path)
        val first = file.inputStream().use { it.readNBytes(16) }
        val extension = name.substringAfterLast('.', "").lowercase().ifEmpty {
            when {
                first.take(4).toByteArray().contentEquals("fLaC".toByteArray()) -> "flac"
                first.take(4).toByteArray().contentEquals("RIFF".toByteArray()) -> "wav"
                first.take(4).toByteArray().contentEquals("FORM".toByteArray()) -> "aiff"
                first.size >= 8 && String(first, 4, 4, Charsets.US_ASCII) == "ftyp" -> "m4a"
                first.size >= 3 && String(first, 0, 3, Charsets.US_ASCII) == "ID3" -> "mp3"
                first.take(4).toByteArray().contentEquals("OggS".toByteArray()) -> "ogg"
                else -> "mp3"
            }
        }
        val parsed = runCatching { AudioFileIO.readAs(file, extension) }.getOrNull()
        val tag = parsed?.tag ?: id3(file, first)
        val ogg = if (first.take(4).toByteArray().contentEquals("OggS".toByteArray())) ogg(file) else null
        val duration = ((parsed?.audioHeader?.preciseTrackLength ?: 0.0) * 1000).toLong()
        val fallback = if (duration > 0) null else probe(path)
        return AudioMetadata(
            duration.takeIf { it > 0 } ?: fallback!!.duration,
            tag?.getFirst(FieldKey.TITLE).orEmpty().ifEmpty { ogg?.title.orEmpty() }.ifEmpty { fallback?.title.orEmpty() },
            tag?.getFirst(FieldKey.ARTIST).orEmpty().ifEmpty { ogg?.artist.orEmpty() }.ifEmpty { fallback?.artist.orEmpty() },
            tag?.firstArtwork?.binaryData?.takeIf { it.size < MAX_ART } ?: ogg?.artwork,
        )
    }

    private fun probe(path: String): AudioMetadata {
        val handle = Mpv.create(mapOf("terminal" to "no", "idle" to "yes", "pause" to "yes",
            "video" to "no", "ao" to "null"))
        try {
            check(Mpv.command(handle, "loadfile", File(path).absolutePath) >= 0)
            val deadline = System.nanoTime() + 15_000_000_000L
            var loaded = false
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                val event = MpvEvent(Mpv.api.mpv_wait_event(handle, 0.1))
                if (event.id == Mpv.EVENT_FILE_LOADED) { loaded = true; break }
                if (event.id == Mpv.EVENT_END_FILE && (event.data?.getInt(4) ?: 0) < 0) break
            }
            require(loaded) { tr(R.string.audio_read_failed) }
            fun text(key: String) = listOf(key, key.uppercase(), key.replaceFirstChar { it.uppercase() })
                .firstNotNullOfOrNull { Mpv.string(handle, "metadata/by-key/$it")?.takeIf(String::isNotBlank) }.orEmpty()
            return AudioMetadata((Mpv.double(handle, "duration") * 1000).toLong(), text("title"), text("artist"))
        } finally { Mpv.api.mpv_terminate_destroy(handle) }
    }

    private fun id3(file: File, header: ByteArray): Tag? = runCatching {
        if (header.size < 10 || String(header, 0, 3, Charsets.US_ASCII) != "ID3") return null
        val size = (6..9).fold(0) { value, i -> (value shl 7) or (header[i].toInt() and 127) }
        if (size !in 1 until MAX_ART || size.toLong() + 10 > file.length()) return null
        val bytes = file.inputStream().use { it.readNBytes(size + 10) }
        val buffer = ByteBuffer.wrap(bytes)
        when (header[3].toInt()) {
            2 -> ID3v22Tag(buffer)
            3 -> ID3v23Tag(buffer)
            4 -> ID3v24Tag(buffer)
            else -> null
        }
    }.getOrNull()

    private data class OggTags(val title: String, val artist: String, val artwork: ByteArray?)
    private fun ogg(file: File): OggTags? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val packet = ByteArrayOutputStream()
            var consumed = 0
            while (consumed < MAX_ART + 1024 * 1024) {
                val header = ByteArray(27)
                input.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "OggS") return null
                val segments = ByteArray(header[26].toInt() and 255)
                input.readFully(segments)
                consumed += header.size + segments.size
                for (lace in segments) {
                    val size = lace.toInt() and 255
                    if (packet.size() + size > MAX_ART + 65536) return null
                    packet.write(input.readNBytes(size))
                    consumed += size
                    if (size < 255) {
                        val bytes = packet.toByteArray()
                        packet.reset()
                        val offset = when {
                            bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == "OpusTags" -> 8
                            bytes.size >= 7 && bytes[0] == 3.toByte() && String(bytes, 1, 6, Charsets.US_ASCII) == "vorbis" -> 7
                            else -> -1
                        }
                        if (offset >= 0) return comments(bytes, offset)
                    }
                }
            }
        }
        null
    }.getOrNull()

    private fun comments(bytes: ByteArray, offset: Int): OggTags {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(offset)
        val vendor = buffer.int
        require(vendor in 0..buffer.remaining())
        buffer.position(buffer.position() + vendor)
        val count = buffer.int
        require(count in 0..65536)
        var title = ""
        var artist = ""
        var artwork: ByteArray? = null
        repeat(count) {
            val size = buffer.int
            require(size in 0..buffer.remaining())
            val text = ByteArray(size).also(buffer::get).toString(Charsets.UTF_8)
            val key = text.substringBefore('=').uppercase()
            val value = text.substringAfter('=', "")
            when (key) {
                "TITLE" -> if (title.isEmpty()) title = value
                "ARTIST" -> if (artist.isEmpty()) artist = value
                "METADATA_BLOCK_PICTURE" -> if (artwork == null) artwork = picture(value)
            }
        }
        return OggTags(title, artist, artwork)
    }
    private fun picture(encoded: String): ByteArray? = runCatching {
        if (encoded.length > MAX_ART * 4 / 3 + 65536) return null
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            input.readInt()
            val mime = input.readInt()
            require(mime in 0..1024)
            input.skipNBytes(mime.toLong())
            val description = input.readInt()
            require(description in 0..65536)
            input.skipNBytes(description.toLong() + 16)
            val size = input.readInt()
            require(size in 1 until MAX_ART)
            input.readNBytes(size).takeIf { it.size == size }
        }
    }.getOrNull()
}
