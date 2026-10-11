package app.spotygram

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import org.json.JSONObject

/** Small atomic preference files; asynchronous edits share one host-owned writer. */
class DesktopPreferences(private val file: File, private val writer: ExecutorService) : Preferences {
    private val lock = Any()
    private val writeLock = Any()
    private val values = mutableMapOf<String, Any>()
    private var revision = 0L

    init {
        if (file.isFile) {
            val data = JSONObject(file.readText())
            data.keys().forEach { key -> values[key] = data.get(key) }
        }
    }
    override fun contains(key: String) = synchronized(lock) { key in values }
    override fun getString(key: String, default: String?) = synchronized(lock) { values[key] as? String ?: default }
    override fun getBoolean(key: String, default: Boolean) = synchronized(lock) { values[key] as? Boolean ?: default }
    override fun getInt(key: String, default: Int) = synchronized(lock) { (values[key] as? Number)?.toInt() ?: default }
    override fun getLong(key: String, default: Long) = synchronized(lock) { (values[key] as? Number)?.toLong() ?: default }
    override fun getFloat(key: String, default: Float) = synchronized(lock) { (values[key] as? Number)?.toFloat() ?: default }
    override fun edit(): Preferences.Editor = Editor()

    private fun persist(ticket: Long, snapshot: Map<String, Any>): Boolean = synchronized(writeLock) {
        if (synchronized(lock) { revision != ticket }) return@synchronized true
        try {
            file.parentFile.mkdirs()
            val partial = File(file.parentFile, "${file.name}.part")
            FileOutputStream(partial).use { output ->
                output.write(JSONObject(snapshot).toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Exception) { false }
    }

    private inner class Editor : Preferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var cleared = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { cleared = true }
        private fun snapshot(): Pair<Long, Map<String, Any>> = synchronized(lock) {
            if (cleared) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            ++revision to values.toMap()
        }
        override fun commit(): Boolean {
            val (ticket, copy) = snapshot()
            return persist(ticket, copy)
        }
        override fun apply() {
            val (ticket, copy) = snapshot()
            writer.execute { if (!persist(ticket, copy)) System.err.println("Spotygram preferences could not be saved") }
        }
    }
}
