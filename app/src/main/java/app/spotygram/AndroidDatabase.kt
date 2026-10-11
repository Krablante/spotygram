package app.spotygram

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class AndroidDatabase(private val db: SQLiteDatabase, private val closeDatabase: () -> Unit = {}) : SqlDatabase {
    private fun Values.native() = ContentValues().also { result ->
        forEach { (key, value) -> when (value) {
            null -> result.putNull(key)
            is Int -> result.put(key, value)
            is Long -> result.put(key, value)
            is String -> result.put(key, value)
            else -> error("Unsupported SQL value")
        } }
    }
    override fun execSQL(sql: String, arguments: Array<out Any?>) {
        if (arguments.isEmpty()) db.execSQL(sql) else db.execSQL(sql, arguments)
    }
    override fun rawQuery(sql: String, arguments: Array<out String>?): SqlCursor {
        val cursor = db.rawQuery(sql, arguments)
        return object : SqlCursor {
            override fun moveToFirst() = cursor.moveToFirst()
            override fun moveToNext() = cursor.moveToNext()
            override fun getString(index: Int) = cursor.getString(index).orEmpty()
            override fun getInt(index: Int) = cursor.getInt(index)
            override fun getLong(index: Int) = cursor.getLong(index)
            override fun close() = cursor.close()
        }
    }
    override fun update(table: String, values: Values, where: String, arguments: Array<out String>) =
        db.update(table, values.native(), where, arguments)
    override fun delete(table: String, where: String?, arguments: Array<out String>?) =
        db.delete(table, where, arguments)
    override fun insertOrThrow(table: String, nullColumnHack: String?, values: Values) =
        db.insertOrThrow(table, nullColumnHack, values.native())
    override fun insertWithOnConflict(table: String, nullColumnHack: String?, values: Values, conflict: Int) =
        db.insertWithOnConflict(table, nullColumnHack, values.native(), conflict)
    override fun compileStatement(sql: String): SqlStatement {
        val statement = db.compileStatement(sql)
        return object : SqlStatement {
            override fun bindString(index: Int, value: String) = statement.bindString(index, value)
            override fun bindLong(index: Int, value: Long) = statement.bindLong(index, value)
            override fun executeUpdateDelete() = statement.executeUpdateDelete()
            override fun executeInsert() = statement.executeInsert()
            override fun close() = statement.close()
        }
    }
    override fun beginTransaction() = db.beginTransaction()
    override fun setTransactionSuccessful() = db.setTransactionSuccessful()
    override fun endTransaction() = db.endTransaction()
    override fun close() = closeDatabase()
}
