package app.spotygram

import java.util.Locale

/** Snapshot-only grouping. Never changes catalog records or deletes physical copies. */
class TrackDuplicates(tracks: List<Track>) {
    private data class Recording(
        val title: String,
        val artist: String,
        val duration: Int,
        val size: Long,
    )

    private val groups: Map<String, List<Track>>

    init {
        val parents = IntArray(tracks.size) { it }
        fun root(index: Int): Int {
            var i = index
            while (parents[i] != i) {
                parents[i] = parents[parents[i]]
                i = parents[i]
            }
            return i
        }
        val owners = HashMap<Any, Int>()
        fun connect(key: Any, index: Int) {
            val other = owners.putIfAbsent(key, index) ?: return
            val a = root(index)
            val b = root(other)
            parents[maxOf(a, b)] = minOf(a, b)
        }
        val whitespace = Regex("\\s+")
        fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(whitespace, " ")
        tracks.forEachIndexed { index, track ->
            if (track.fileKey.isNotEmpty()) connect("file:${track.fileKey}", index)
            if (track.path.isNotEmpty()) connect("path:${track.path}", index)
            val title = normalize(track.title)
            if (title.isNotEmpty() && track.duration > 0 && track.size > 0)
                connect(
                    Recording(title, normalize(track.artist), track.duration, track.size),
                    index,
                )
        }
        val members =
            tracks.indices.groupBy(::root).values.map { indices -> indices.map { tracks[it] } }
        groups = buildMap { members.forEach { group -> group.forEach { put(it.id, group) } } }
    }

    fun aliases(id: String): List<Track> = groups[id].orEmpty()

    fun key(id: String): String = groups[id]?.firstOrNull()?.id ?: id

    fun display(track: Track): Track {
        val liked = aliases(track.id).any { it.liked } || track.liked
        return if (liked == track.liked) track else track.copy(liked = liked)
    }

    /** Filter first: a representative must belong to the selected source/search results. */
    fun view(candidates: List<Track>): List<Track> =
        candidates
            .groupBy { key(it.id) }
            .values
            .map { group ->
                display(
                    group.maxBy {
                        if (it.local && !it.temporary) 3
                        else if (it.local) 2 else if (it.available) 1 else 0
                    }
                )
            }
}
