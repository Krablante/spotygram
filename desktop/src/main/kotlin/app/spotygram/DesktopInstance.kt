package app.spotygram

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.util.UUID
import org.json.JSONArray

/** File notifications forward a second launch without an idle poller or local server. */
class DesktopInstance(private val directory: File) : AutoCloseable {
    private val channel = FileChannel.open(File(directory, "application.lock").toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private val lock: FileLock? = runCatching { channel.tryLock() }.getOrNull()
    val primary get() = lock != null
    private val requests = File(directory, "requests").apply { mkdirs() }
    private var watcher: java.nio.file.WatchService? = null
    private var thread: Thread? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    fun forward(arguments: List<String>) {
        val id = UUID.randomUUID().toString()
        val partial = File(requests, "$id.part")
        partial.writeText(JSONArray(arguments).toString())
        Files.move(partial.toPath(), File(requests, "$id.json").toPath())
    }
    fun listen(receive: (List<String>) -> Unit) {
        check(primary)
        val service = requests.toPath().fileSystem.newWatchService()
        requests.toPath().register(service, StandardWatchEventKinds.ENTRY_CREATE)
        watcher = service
        fun pending() {
            requests.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
                try {
                    require(file.length() <= 128 * 1024)
                    val values = JSONArray(file.readText())
                    require(values.length() <= 500)
                    receive((0 until values.length()).map { values.getString(it) })
                } catch (_: Exception) {
                    System.err.println("Spotygram could not read an activation request")
                } finally { file.delete() }
            }
        }
        thread = Thread({
            try {
                pending()
                while (true) {
                    val key = service.take()
                    key.pollEvents()
                    pending()
                    if (!key.reset()) break
                }
            } catch (_: java.nio.file.ClosedWatchServiceException) {
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }, "Spotygram activation").apply { isDaemon = true; start() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher?.close()
        thread?.join(1000)
        lock?.release()
        channel.close()
    }
}
