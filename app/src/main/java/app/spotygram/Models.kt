package app.spotygram

import org.json.JSONObject

data class Track(
    val id: String,
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val title: String,
    val artist: String,
    val duration: Int,
    val size: Long,
    val source: String,
    val date: Long,
    val art: String = "",
    val path: String = "",
    val liked: Boolean = false,
    val available: Boolean = true,
    val document: Boolean = false,
) {
    val local
        get() = path.isNotEmpty()

    val subtitle
        get() = artist.ifBlank { source }
}

data class Source(
    val id: Long,
    val title: String,
    val cursor: Long = 0,
    val complete: Boolean = false,
    val documentCursor: Long = 0,
    val documentsComplete: Boolean = false,
    val newest: Long = 0,
    val documentNewest: Long = 0,
) {
    val fullyIndexed
        get() = complete && documentsComplete
}

data class ChatChoice(val id: Long, val title: String)

data class Playlist(val id: Long, val name: String, val tracks: List<String>)

data class LibraryState(
    val tracks: List<Track> = emptyList(),
    val sources: List<Source> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val byId by lazy { tracks.associateBy { it.id } }
}

data class AuthState(
    val type: String = "welcome",
    val detail: String = "",
    val busy: Boolean = false,
    val error: String = "",
)

data class FileState(
    val id: Int,
    val path: String,
    val size: Long,
    val downloaded: Long,
    val offset: Long,
    val prefix: Long,
    val complete: Boolean,
    val active: Boolean,
)

data class DownloadState(val trackId: String = "", val progress: Float = 0f, val queued: Int = 0)

fun json(type: String, vararg fields: Pair<String, Any?>) =
    JSONObject().put("@type", type).apply {
        fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }

fun JSONObject.kind() = optString("@type")

fun seconds(value: Long): String =
    "%d:%02d".format(value.coerceAtLeast(0) / 60, value.coerceAtLeast(0) % 60)

fun bytes(value: Long): String =
    if (value >= 1_073_741_824) "%.1f ГБ".format(value / 1_073_741_824.0)
    else "%.0f МБ".format(value / 1_048_576.0)

fun trackCount(value: Int): String =
    "$value " +
        when {
            value % 100 in 11..14 -> "треков"
            value % 10 == 1 -> "трек"
            value % 10 in 2..4 -> "трека"
            else -> "треков"
        }
