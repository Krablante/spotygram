package app.spotygram

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One SQLite connection; cursor and transaction lifetimes hold the same reentrant lock. */
class DesktopDatabase(file: File) : SqlDatabase {
    private val lock = ReentrantLock()
    private val connection: Connection
    private var successful = false

    init {
        file.parentFile.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        execSQL("PRAGMA foreign_keys=ON")
        execSQL("PRAGMA busy_timeout=5000")
        execSQL("PRAGMA journal_mode=WAL")
    }

    override fun execSQL(sql: String, arguments: Array<out Any?>) = lock.withLock {
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.execute()
            Unit
        }
    }
    override fun rawQuery(sql: String, arguments: Array<out String>?): SqlCursor {
        lock.lock()
        try {
            val statement = connection.prepareStatement(sql)
            arguments?.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            val rows = statement.executeQuery()
            return object : SqlCursor {
                private var index = -1
                private var valid = false
                private var closed = false
                override fun moveToFirst(): Boolean {
                    if (index >= 0) return valid && index == 0
                    return moveToNext()
                }
                override fun moveToNext(): Boolean {
                    valid = rows.next()
                    index++
                    return valid
                }
                override fun getString(index: Int) = rows.getString(index + 1).orEmpty()
                override fun getInt(index: Int) = rows.getInt(index + 1)
                override fun getLong(index: Int) = rows.getLong(index + 1)
                override fun close() {
                    if (closed) return
                    closed = true
                    try { rows.close(); statement.close() } finally { lock.unlock() }
                }
            }
        } catch (error: Throwable) { lock.unlock(); throw error }
    }
    override fun update(table: String, values: Values, where: String, arguments: Array<out String>): Int =
        execute("UPDATE $table SET ${values.keys.joinToString(",") { "$it=?" }} WHERE $where",
            values.values.toList() + arguments.toList())
    override fun delete(table: String, where: String?, arguments: Array<out String>?): Int =
        execute("DELETE FROM $table${where?.let { " WHERE $it" }.orEmpty()}", arguments?.toList().orEmpty())
    override fun insertOrThrow(table: String, nullColumnHack: String?, values: Values): Long =
        insert(table, values, "")
    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: Values, conflict: Int): Long {
        require(conflict == SqlDatabase.CONFLICT_IGNORE)
        return insert(table, values, " OR IGNORE")
    }
    private fun insert(table: String, values: Values, conflict: String): Long = lock.withLock {
        val changed = execute("INSERT$conflict INTO $table(${values.keys.joinToString(",")}) VALUES(" +
            values.keys.joinToString(",") { "?" } + ")", values.values.toList())
        if (changed == 0) -1 else lastInsert()
    }
    private fun execute(sql: String, arguments: List<Any?>): Int = lock.withLock {
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }
    private fun lastInsert(): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rows -> rows.next(); rows.getLong(1) }
        }
    override fun compileStatement(sql: String): SqlStatement {
        lock.lock()
        try {
            val statement = connection.prepareStatement(sql)
            return object : SqlStatement {
                private var closed = false
                override fun bindString(index: Int, value: String) = statement.setString(index, value)
                override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)
                override fun executeUpdateDelete() = statement.executeUpdate()
                override fun executeInsert(): Long = if (statement.executeUpdate() == 0) -1 else lastInsert()
                override fun close() {
                    if (closed) return
                    closed = true
                    try { statement.close() } finally { lock.unlock() }
                }
            }
        } catch (error: Throwable) { lock.unlock(); throw error }
    }
    override fun beginTransaction() {
        lock.lock()
        try {
            check(connection.autoCommit)
            connection.autoCommit = false
            successful = false
        } catch (error: Throwable) { lock.unlock(); throw error }
    }
    override fun setTransactionSuccessful() { successful = true }
    override fun endTransaction() {
        try {
            if (successful) connection.commit() else connection.rollback()
        } finally { connection.autoCommit = true; lock.unlock() }
    }
    override fun close() = lock.withLock { connection.close() }
}
