package app.spotygram

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TemporaryAudio(val key: String, val remote: String, val path: String)

data class ListeningCacheState(
    val enabled: Boolean = false,
    val changing: Boolean = false,
    val deferred: Boolean = false,
)

/** Owns only playback-created temporary files. No directory scan or periodic cleanup. */
class ListeningCache(private val app: SpotygramApp) {
    val state =
        MutableStateFlow(ListeningCacheState(app.prefs.getBoolean("discard_listened", false)))
    private val guard = Any()

    private class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val gates = mutableMapOf<String, Gate>()
    private val readers = mutableSetOf<Lease>()
    private val readerCount = MutableStateFlow(0)
    private var window = emptySet<String>()
    private var playbackReady = false
    private val pendingPins = mutableMapOf<String, Int>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val cleanup = Mutex()
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        app.scope.launch(Dispatchers.IO) {
            for (signal in signals) {
                try {
                    cleanup.withLock { sweep() }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    state.update { it.copy(deferred = true) }
                }
            }
        }
    }

    fun signal() {
        signals.trySend(Unit)
    }

    fun retry() {
        failures.clear()
        signal()
    }

    fun setWindow(ids: List<String>) {
        synchronized(guard) { window = ids.toSet() }
        signal()
    }

    fun ready() {
        synchronized(guard) { playbackReady = true }
        signal()
    }

    fun setEnabled(enabled: Boolean) {
        if (state.value.changing || state.value.enabled == enabled) return
        val before = state.value.enabled
        state.update { it.copy(enabled = enabled, changing = true) }
        app.scope.launch {
            try {
                cleanup.withLock {
                    if (!enabled) app.library.retainTemporaryFiles()
                    withContext(Dispatchers.IO) {
                        check(app.prefs.edit().putBoolean("discard_listened", enabled).commit())
                    }
                    app.library.reload()
                }
                state.update { it.copy(changing = false, deferred = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(enabled = before, changing = false, deferred = true) }
            }
            signal()
        }
    }

    private suspend fun <T> gated(key: String, block: suspend () -> T): T {
        val gate = synchronized(guard) { gates.getOrPut(key) { Gate() }.also { it.users++ } }
        try {
            return gate.mutex.withLock { block() }
        } finally {
            synchronized(guard) { if (--gate.users == 0) gates.remove(key) }
        }
    }

    inner class Lease(val fileId: Int, val path: String, val key: String) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (key.isEmpty() || !released.compareAndSet(false, true)) return
            synchronized(guard) {
                readers.remove(this)
                readerCount.value = readers.size
            }
            signal()
        }
    }

    private fun lease(id: Int, path: String, key: String): Lease =
        synchronized(guard) {
            check(!app.musicCache.state.value.clearing) { tr(R.string.cache_clearing) }
            val lease = Lease(id, path, key)
            if (key.isNotEmpty()) {
                readers.add(lease)
                readerCount.value = readers.size
            }
            lease
        }

    /** Called on the loader's IO coroutine; there is no suspension after registering the lease. */
    suspend fun acquire(track: Track): Lease {
        check(!app.musicCache.state.value.clearing) { tr(R.string.cache_clearing) }
        if (track.chatId == 0L) return lease(0, track.path, "")
        if (track.fileKey.isNotEmpty()) {
            val local =
                gated(track.fileKey) {
                    if (!validAudioCopy(track.path, track.size)) return@gated null
                    classify(track, track.fileKey, track.remoteId, track.path, preexisting = true)
                    lease(0, track.path, track.fileKey)
                }
            if (local != null) return local
        } else if (validAudioCopy(track.path, track.size)) {
            // A legacy local copy has no trustworthy file identity: never delete it automatically.
            return lease(0, track.path, "legacy:${track.id}")
        }
        check(track.available) { tr(R.string.message_deleted_no_copy) }
        val id = app.resolve(track)
        val snapshot =
            app.telegram.files[id]
                ?: run {
                    val result = app.telegram.request(json("getFile", "file_id" to id))
                    app.telegram.updateFile(result)
                    checkNotNull(app.telegram.files[id])
                }
        if (snapshot.key.isEmpty() || snapshot.remote.isEmpty()) {
            app.library.retainTracks(listOf(track.id))
            return lease(id, "", "unidentified:${track.id}")
        }
        return gated(snapshot.key) {
            val file = app.telegram.files[id] ?: snapshot
            check(file.key == snapshot.key)
            val latest = app.track(track.id) ?: track
            classify(latest, file.key, file.remote, file.path, file.downloaded > 0 || file.complete)
            val path = file.path.takeIf { file.complete && validAudioCopy(it, file.size) }.orEmpty()
            lease(if (path.isEmpty()) id else 0, path, file.key)
        }
    }

    private suspend fun classify(
        track: Track,
        key: String,
        remote: String,
        path: String,
        preexisting: Boolean,
    ) {
        val temporary = app.library.ownsTemporary(key)
        val pin = synchronized(guard) { pendingPins.keys.any { matches(it, key, path) } }
        if (
            !state.value.enabled ||
                state.value.changing ||
                pin ||
                track.saved ||
                app.library.savedCopy(key, path) ||
                (!temporary && preexisting) ||
                remote.isEmpty()
        ) {
            if (temporary || !track.saved) app.library.retainTracks(listOf(track.id))
        } else if (!temporary) {
            app.library.claimTemporary(TemporaryAudio(key, remote, path))
            // A pin or mode change may have completed while the journal write was suspended.
            val saved = app.library.savedCopy(key, path)
            val retaining = synchronized(guard) { pendingPins.keys.any { matches(it, key, path) } }
            if (saved || retaining || !state.value.enabled || state.value.changing)
                app.library.retainTracks(listOf(track.id))
        }
    }

    private fun matches(id: String, key: String, path: String): Boolean {
        val track = app.track(id) ?: return false
        return (key.isNotEmpty() && track.fileKey == key) ||
            (path.isNotEmpty() && track.path == path)
    }

    private fun isProtected(key: String, path: String): Boolean =
        synchronized(guard) {
            readers.any { it.key == key || (path.isNotEmpty() && it.path == path) } ||
                window.any { matches(it, key, path) } ||
                pendingPins.keys.any { matches(it, key, path) }
        }

    fun protectsFile(id: Int): Boolean {
        val file = app.telegram.files[id] ?: return false
        return isProtected(file.key, file.path) ||
            synchronized(guard) {
                readers.any { it.fileId == id } || window.any { app.track(it)?.fileId == id }
            }
    }

    suspend fun retain(ids: List<String>) {
        if (ids.isEmpty()) return
        synchronized(guard) { ids.forEach { pendingPins[it] = (pendingPins[it] ?: 0) + 1 } }
        try {
            cleanup.withLock {
                app.library.retainTracks(ids)
                app.library.reload()
            }
        } finally {
            synchronized(guard) {
                ids.forEach { id ->
                    val n = pendingPins.getValue(id) - 1
                    if (n == 0) pendingPins.remove(id) else pendingPins[id] = n
                }
            }
            signal()
        }
    }

    suspend fun awaitCleanup() {
        cleanup.withLock {}
    }

    suspend fun awaitReaders() {
        // Clearing is already set. Drain any lease registration that passed its check.
        synchronized(guard) {}
        withTimeout(10_000) { readerCount.first { it == 0 } }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = cleanup.withLock { block() }

    suspend fun removeCopy(track: Track) {
        val id = app.resolve(track)
        val resolved = app.telegram.files[id]
        val key = resolved?.key?.takeIf { it.isNotEmpty() } ?: "legacy:${track.id}"
        cleanup.withLock {
            gated(key) {
                val file = app.telegram.files[id] ?: resolved
                check(
                    !protectsFile(id) &&
                        !isProtected(key, file?.path?.ifEmpty { track.path } ?: track.path)
                ) {
                    tr(R.string.audio_in_use)
                }
                if (track.fileKey.isNotEmpty())
                    check(file?.key == track.fileKey) { tr(R.string.file_unavailable) }
                if (file?.active == true)
                    app.telegram.request(
                        json("cancelDownloadFile", "file_id" to id, "only_if_pending" to false)
                    )
                app.telegram.request(json("deleteFile", "file_id" to id))
                app.telegram.updateFile(app.telegram.request(json("getFile", "file_id" to id)))
                val after = checkNotNull(app.telegram.files[id])
                val path = file?.path?.ifEmpty { track.path } ?: track.path
                check(!after.complete && !after.active && after.downloaded == 0L) {
                    tr(R.string.file_unavailable)
                }
                check(path.isEmpty() || !File(path).exists()) { tr(R.string.file_unavailable) }
                app.library.removeLocal(
                    track.copy(
                        fileKey = file?.key ?: track.fileKey,
                        path = path,
                    )
                )
            }
        }
        signal()
    }

    private fun allowed(): Boolean =
        state.value.enabled &&
            !state.value.changing &&
            synchronized(guard) { playbackReady } &&
            !app.musicCache.state.value.clearing &&
            app.telegram.auth.value.type == "authorizationStateReady"

    private suspend fun sweep() {
        if (!state.value.enabled && !state.value.changing) {
            // Recover a process death between a concurrent claim and disabling the mode.
            if (app.library.temporaryFiles().isNotEmpty()) {
                app.library.retainTemporaryFiles()
                app.library.reload()
            }
            failures.clear()
            return
        }
        if (!allowed()) return
        val entries = app.library.temporaryFiles()
        failures.keys.retainAll(entries.map { it.key }.toSet())
        var changed = false
        var attempted = 0
        var more = false
        for (entry in entries) {
            if (!allowed()) break
            if (
                isProtected(entry.key, entry.path) ||
                    (failures[entry.key] ?: 0) > android.os.SystemClock.elapsedRealtime()
            )
                continue
            if (attempted++ == 4) {
                more = true
                break
            }
            try {
                val result =
                    app.telegram.request(
                        json(
                            "getRemoteFile",
                            "remote_file_id" to entry.remote,
                            "file_type" to null,
                        ),
                        5_000,
                    )
                app.telegram.updateFile(result)
                val file = checkNotNull(app.telegram.files[result.getInt("id")])
                check(file.key == entry.key)
                gated(entry.key) {
                    if (!allowed() || isProtected(entry.key, file.path)) return@gated
                    if (app.library.savedCopy(entry.key, file.path)) {
                        val ids =
                            app.library.state.value.tracks
                                .filter { it.fileKey == entry.key }
                                .map { it.id }
                        app.library.retainTracks(ids)
                        changed = true
                        return@gated
                    }
                    if (file.path.isEmpty() && entry.path.isNotEmpty() && File(entry.path).exists())
                        error("Temporary file is not known to TDLib")
                    // TDLib drops its location before unlink, and its unlink result ignores errors.
                    // Keep the actual path durable until both native state and disk agree.
                    app.library.rememberTemporaryPath(entry.key, file.path)
                    if (!allowed() || isProtected(entry.key, file.path)) return@gated
                    if (file.active)
                        app.telegram.request(
                            json(
                                "cancelDownloadFile",
                                "file_id" to file.id,
                                "only_if_pending" to false,
                            ),
                            5_000,
                        )
                    if (!allowed() || isProtected(entry.key, file.path)) return@gated
                    if (file.downloaded > 0 || file.complete || file.path.isNotEmpty())
                        app.telegram.request(json("deleteFile", "file_id" to file.id), 5_000)
                    val after = app.telegram.request(json("getFile", "file_id" to file.id), 5_000)
                    app.telegram.updateFile(after)
                    val cleared = checkNotNull(app.telegram.files[file.id])
                    check(
                        cleared.key == entry.key &&
                            !cleared.complete &&
                            !cleared.active &&
                            cleared.downloaded == 0L
                    )
                    check(file.path.isEmpty() || !File(file.path).exists())
                    app.library.forgetTemporary(entry, file.path)
                    failures.remove(entry.key)
                    changed = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failures[entry.key] = android.os.SystemClock.elapsedRealtime() + 60_000
            }
        }
        if (changed) app.library.reload()
        state.update { it.copy(deferred = failures.isNotEmpty()) }
        if (more) signal()
    }
}
