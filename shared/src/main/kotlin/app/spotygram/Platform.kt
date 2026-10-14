package app.spotygram

import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The host owns OS facilities; screens, catalog and playback rules use the same source. */
interface Platform {
    val filesDir: File
    val cacheDir: File
    val fullOptics: Boolean
    val deviceModel: String
    val systemVersion: String
    val locale: Locale
    val releaseAbi: String
    val testDc: Boolean get() = false
    val wifi: Boolean
    val tdlib: TdClient

    fun preferences(name: String): Preferences
    fun string(id: Int, vararg arguments: Any?): String
    fun plural(id: Int, quantity: Int, vararg arguments: Any?): String
    fun openDatabase(
        name: String,
        version: Int,
        create: (SqlDatabase) -> Unit,
        upgrade: (SqlDatabase, Int, Int) -> Unit,
        opened: (SqlDatabase) -> Unit,
    ): SqlDatabase
    fun databaseKey(): String
    fun observeNetwork(onType: (String) -> Unit): AutoCloseable
    fun metadata(path: String, name: String = ""): AudioMetadata
    fun openUrl(url: String)
    fun personalMessageLink(user: Long, message: Long, account: Long): String
    fun languageSettings(app: SpotygramApp)
    fun startDownloads(app: SpotygramApp)
    fun stopDownloads(app: SpotygramApp)
    fun startProfileAudioUpload(app: SpotygramApp) {}
    fun installer(app: SpotygramApp): UpdateInstallation
    fun assetName(version: String, abi: String = releaseAbi): String
    fun logPlaybackError(code: String)
}

data class AudioMetadata(
    val duration: Long,
    val title: String = "",
    val artist: String = "",
    val artwork: ByteArray? = null,
)

interface TdClient {
    fun createClientId(): Int
    fun send(client: Int, request: String)
    fun receive(timeout: Double): String?
    fun execute(request: String): String?
}

interface Preferences {
    fun contains(key: String): Boolean
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

class Values : LinkedHashMap<String, Any?>() {
    fun put(key: String, value: String?) { super.put(key, value) }
    fun put(key: String, value: Int) { super.put(key, value) }
    fun put(key: String, value: Long) { super.put(key, value) }
}

interface SqlDatabase : AutoCloseable {
    fun execSQL(sql: String, arguments: Array<out Any?> = emptyArray())
    fun rawQuery(sql: String, arguments: Array<out String>?): SqlCursor
    fun update(table: String, values: Values, where: String, arguments: Array<out String>): Int
    fun delete(table: String, where: String?, arguments: Array<out String>?): Int
    fun insertOrThrow(table: String, nullColumnHack: String?, values: Values): Long
    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: Values, conflict: Int): Long
    fun compileStatement(sql: String): SqlStatement
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()

    companion object { const val CONFLICT_IGNORE = 4 }
}

interface SqlCursor : AutoCloseable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getString(index: Int): String
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
}

interface SqlStatement : AutoCloseable {
    fun bindString(index: Int, value: String)
    fun bindLong(index: Int, value: Long)
    fun executeUpdateDelete(): Int
    fun executeInsert(): Long
}

inline fun <T> SqlDatabase.transaction(block: SqlDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally { endTransaction() }
}

/** Posts after a player callback has settled, preserving Media3 timeline ordering. */
class MainTasks(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<Runnable, Job>()

    fun post(task: Runnable) = postDelayed(task, 0)
    fun postDelayed(task: Runnable, milliseconds: Long) {
        removeCallbacks(task)
        jobs[task] = scope.launch(Dispatchers.Main) {
            if (milliseconds > 0) delay(milliseconds)
            jobs.remove(task)
            task.run()
        }
    }
    fun removeCallbacks(task: Runnable) { jobs.remove(task)?.cancel() }
}
