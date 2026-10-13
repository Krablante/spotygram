package app.spotygram

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ChatPhoto(val fileId: Int = 0, val path: String = "")

/** Small chat photos stay in TDLib's cache; only visible rows request a download. */
class ChatPhotos(private val telegram: Telegram) {
    val state = MutableStateFlow(emptyMap<Long, ChatPhoto>())
    val refresh = MutableStateFlow(0L)
    private val tracked = ConcurrentHashMap.newKeySet<Long>()
    private val loading = ConcurrentHashMap.newKeySet<Long>()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var generation = 0L

    fun changed(id: Long, photo: JSONObject?) {
        if (id !in tracked) return
        val small = photo?.optJSONObject("small")
        small?.let(telegram::updateFile)
        val local = small?.optJSONObject("local")
        val value =
            ChatPhoto(
                small?.optInt("id") ?: 0,
                if (local?.optBoolean("is_downloading_completed") == true) local.optString("path")
                else "",
            )
        state.update { it + (id to value) }
    }

    suspend fun load(id: Long) {
        if (id == 0L || !loading.add(id)) return
        tracked.add(id)
        val job = currentCoroutineContext().job
        val version = generation
        jobs.add(job)
        try {
            withContext(Dispatchers.IO) {
                telegram.avatarSlots.withPermit {
                    if (
                        version != generation ||
                            telegram.auth.value.type != "authorizationStateReady"
                    )
                        return@withPermit
                    if (id !in state.value)
                        telegram.rememberChat(telegram.request(json("getChat", "chat_id" to id)))
                    val photo = state.value[id] ?: return@withPermit
                    if (photo.fileId == 0) return@withPermit
                    val cached = telegram.files[photo.fileId]
                    val path = photo.path.ifEmpty { cached?.takeIf { it.complete }?.path.orEmpty() }
                    if (path.isNotEmpty() && File(path).isFile) {
                        publish(id, photo.fileId, path, version)
                        return@withPermit
                    }
                    publish(id, photo.fileId, "", version)
                    if (telegram.connection.value.isNotEmpty()) return@withPermit
                    val response =
                        telegram.request(
                            json(
                                "downloadFile",
                                "file_id" to photo.fileId,
                                "priority" to 1,
                                "offset" to 0,
                                "limit" to 0,
                                "synchronous" to true,
                            )
                        )
                    val local = response.getJSONObject("local")
                    if (local.optBoolean("is_downloading_completed"))
                        publish(id, photo.fileId, local.getString("path"), version)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            loading.remove(id)
            jobs.remove(job)
        }
    }

    private fun publish(id: Long, file: Int, path: String, version: Long) {
        state.update { photos ->
            val current = photos[id]
            if (version == generation && current?.fileId == file)
                photos + (id to current.copy(path = path))
            else photos
        }
    }

    fun reset() {
        generation++
        jobs.toList().forEach { it.cancel() }
        tracked.clear()
        state.value = emptyMap()
    }

    fun retry() {
        refresh.update { it + 1 }
    }
}
