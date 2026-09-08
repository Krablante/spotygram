package app.spotygram

import android.text.format.Formatter
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

data class MusicCacheState(
    val bytes: Long? = null,
    val files: Int = 0,
    val loading: Boolean = false,
    val clearing: Boolean = false,
    val error: String? = null,
    val freed: Long? = null,
)

/**
 * TDLib owns deletion of its files. Imported audio, artwork and account databases are untouched.
 */
class MusicCache(private val app: SpotygramApp) {
    val state = MutableStateFlow(MusicCacheState())
    private val types = setOf("fileTypeAudio", "fileTypeDocument")

    fun refresh() {
        if (state.value.loading || state.value.clearing) return
        state.value = state.value.copy(loading = true, error = null)
        app.scope.launch {
            try {
                val (bytes, files) = statistics()
                state.value = state.value.copy(bytes = bytes, files = files, loading = false)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                state.value = state.value.copy(error = cacheError(e))
            } finally {
                state.value = state.value.copy(loading = false)
            }
        }
    }

    private suspend fun statistics(): Pair<Long, Int> {
        if (!withContext(Dispatchers.IO) { File(app.filesDir, "telegram/files").exists() })
            return 0L to 0
        app.telegram.start()
        withTimeoutOrNull(30_000) {
            app.telegram.auth.first {
                it.type !in setOf("", "starting", "authorizationStateWaitTdlibParameters")
            }
        } ?: error(tr(R.string.telegram_timeout))
        val result = app.telegram.request(json("getStorageStatistics", "chat_limit" to 0), 120_000)
        return totals(result)
    }

    private fun totals(result: JSONObject): Pair<Long, Int> {
        var bytes = 0L
        var files = 0
        val chats = result.optJSONArray("by_chat") ?: JSONArray()
        for (i in 0 until chats.length()) {
            val entries = chats.getJSONObject(i).optJSONArray("by_file_type") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.getJSONObject(j)
                if (entry.getJSONObject("file_type").kind() in types) {
                    bytes += entry.optLong("size")
                    files += entry.optInt("count")
                }
            }
        }
        return bytes to files
    }

    fun clear() {
        if (state.value.loading || state.value.clearing) return
        state.value = state.value.copy(clearing = true, error = null, freed = null)
        app.scope.launch {
            try {
                check(app.telegram.auth.value.type == "authorizationStateReady") {
                    tr(R.string.telegram_required)
                }
                app.playback?.pauseForCacheCleanup()
                app.downloadService?.cancelForCacheCleanup()
                app.library.clearDownloads()
                // Playback and explicit downloads can leave an active TDLib range download behind.
                val active = app.telegram.files.filterValues { it.active }.keys.toList()
                for (id in active) app.telegram.request(
                    json("cancelDownloadFile", "file_id" to id, "only_if_pending" to false)
                )
                val deleted =
                    app.telegram.request(
                        json(
                            "optimizeStorage",
                            "size" to 0L,
                            "ttl" to 0,
                            "count" to 0,
                            "immunity_delay" to 0,
                            "file_types" to JSONArray(types.map { json(it) }),
                            "chat_ids" to JSONArray(),
                            "exclude_chat_ids" to JSONArray(),
                            "return_deleted_file_statistics" to true,
                            "chat_limit" to 0,
                        ),
                        120_000,
                    )
                state.value = state.value.copy(freed = deleted.optLong("size"))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                state.value = state.value.copy(error = cacheError(e))
            } finally {
                try {
                    app.library.verifyFiles()
                    val (bytes, files) = statistics()
                    state.value = state.value.copy(bytes = bytes, files = files)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    state.value = state.value.copy(error = state.value.error ?: cacheError(e))
                } finally {
                    state.value = state.value.copy(clearing = false)
                    app.playback?.finishCacheCleanup()
                }
            }
        }
    }

    private fun cacheError(error: Exception) =
        if (error is CancellationException) tr(R.string.action_failed) else friendly(error)
}

fun cacheBytes(bytes: Long) = Formatter.formatShortFileSize(AppText.context, bytes)
