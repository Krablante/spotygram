package app.spotygram

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Library(context: Context) : SQLiteOpenHelper(context, "library.db", null, 3) {
    val state = MutableStateFlow(LibraryState())
    private val publishing = Mutex()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tracks(id TEXT PRIMARY KEY,chat INTEGER,message INTEGER,file INTEGER,title TEXT,artist TEXT,duration INTEGER,size INTEGER,source TEXT,date INTEGER,art TEXT DEFAULT '',path TEXT DEFAULT '',liked INTEGER DEFAULT 0,available INTEGER DEFAULT 1,document INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX tracks_file ON tracks(file)")
        db.execSQL("CREATE INDEX tracks_chat ON tracks(chat)")
        db.execSQL(
            "CREATE TABLE sources(id INTEGER PRIMARY KEY,title TEXT,cursor INTEGER DEFAULT 0,complete INTEGER DEFAULT 0,document_cursor INTEGER DEFAULT 0,documents_complete INTEGER DEFAULT 0,newest INTEGER DEFAULT 0,document_newest INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT)")
        db.execSQL(
            "CREATE TABLE playlist_tracks(playlist INTEGER,track TEXT,position INTEGER,PRIMARY KEY(playlist,track))"
        )
        db.execSQL("CREATE TABLE downloads(track TEXT PRIMARY KEY,position INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sources ADD COLUMN newest INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN document_newest INTEGER DEFAULT 0")
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN document INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN document_cursor INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN documents_complete INTEGER DEFAULT 0")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // TDLib file IDs belong to one client lifetime, not to the persisted catalog.
        // Clear old bindings before any new audio/thumbnail completion can match them.
        db.execSQL("UPDATE tracks SET file=0 WHERE chat!=0 AND file!=0")
    }

    suspend fun reload() = publishing.withLock {
        withContext(Dispatchers.IO) {
            val db = readableDatabase
            val sources = mutableListOf<Source>()
            db.rawQuery(
                    "SELECT id,title,cursor,complete,document_cursor,documents_complete,newest,document_newest FROM sources ORDER BY title",
                    null,
                )
                .use { c ->
                    while (c.moveToNext()) sources +=
                        Source(
                            c.getLong(0),
                            c.getString(1),
                            c.getLong(2),
                            c.getInt(3) == 1,
                            c.getLong(4),
                            c.getInt(5) == 1,
                            c.getLong(6),
                            c.getLong(7),
                        )
                }
            val tracks = mutableListOf<Track>()
            db.rawQuery(
                    "SELECT * FROM tracks WHERE chat=0 OR path!='' OR liked=1 OR id IN (SELECT track FROM playlist_tracks) OR chat IN (SELECT id FROM sources) ORDER BY date DESC,id DESC",
                    null,
                )
                .use { c ->
                    while (c.moveToNext()) tracks +=
                        Track(
                            c.getString(0),
                            c.getLong(1),
                            c.getLong(2),
                            c.getInt(3),
                            c.getString(4),
                            c.getString(5),
                            c.getInt(6),
                            c.getLong(7),
                            c.getString(8),
                            c.getLong(9),
                            c.getString(10),
                            c.getString(11),
                            c.getInt(12) == 1,
                            c.getInt(13) == 1,
                            c.getInt(14) == 1,
                        )
                }
            val lists = mutableListOf<Playlist>()
            db.rawQuery("SELECT id,name FROM playlists ORDER BY name", null).use { c ->
                while (c.moveToNext()) {
                    val ids = mutableListOf<String>()
                    db.rawQuery(
                            "SELECT track FROM playlist_tracks WHERE playlist=? ORDER BY position",
                            arrayOf(c.getLong(0).toString()),
                        )
                        .use { p -> while (p.moveToNext()) ids += p.getString(0) }
                    lists += Playlist(c.getLong(0), c.getString(1), ids)
                }
            }
            state.value = LibraryState(tracks, sources, lists)
        }
    }

    suspend fun newest(id: Long, newest: Long, document: Boolean) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "sources",
                ContentValues().apply {
                    put(if (document) "document_newest" else "newest", newest)
                },
                "id=?",
                arrayOf(id.toString()),
            )
        }

    suspend fun upsert(tracks: List<Track>) =
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                tracks.forEach { t ->
                    val v =
                        ContentValues().apply {
                            put("chat", t.chatId)
                            put("message", t.messageId)
                            put("file", t.fileId)
                            put("title", t.title)
                            put("artist", t.artist)
                            put("duration", t.duration)
                            put("size", t.size)
                            put("source", t.source)
                            put("date", t.date)
                            put("available", if (t.available) 1 else 0)
                            put("document", if (t.document) 1 else 0)
                            if (t.art.isNotEmpty()) put("art", t.art)
                            if (t.path.isNotEmpty())
                                put("path", t.path.takeIf { validAudioCopy(it, t.size) }.orEmpty())
                        }
                    if (db.update("tracks", v, "id=?", arrayOf(t.id)) == 0) {
                        v.put("id", t.id)
                        db.insertOrThrow("tracks", null, v)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

    suspend fun source(choice: ChatChoice, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            if (enabled)
                writableDatabase.insertWithOnConflict(
                    "sources",
                    null,
                    ContentValues().apply {
                        put("id", choice.id)
                        put("title", choice.title)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            else writableDatabase.delete("sources", "id=?", arrayOf(choice.id.toString()))
            reload()
        }

    suspend fun cursor(id: Long, cursor: Long, complete: Boolean, document: Boolean = false) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "sources",
                ContentValues().apply {
                    put(if (document) "document_cursor" else "cursor", cursor)
                    put(if (document) "documents_complete" else "complete", if (complete) 1 else 0)
                },
                "id=?",
                arrayOf(id.toString()),
            )
        }

    suspend fun like(track: Track, sameFile: Boolean = false): Pair<Boolean, List<String>> =
        withContext(Dispatchers.IO) {
            val column = if (sameFile && track.local) "path" else "id"
            val key = if (sameFile && track.local) track.path else track.id
            val change = writableDatabase.transaction {
                val previous = mutableListOf<String>()
                rawQuery("SELECT id FROM tracks WHERE $column=? AND liked=1", arrayOf(key)).use { c
                    ->
                    while (c.moveToNext()) previous += c.getString(0)
                }
                val value = previous.isEmpty()
                update(
                    "tracks",
                    ContentValues().apply { put("liked", if (value) 1 else 0) },
                    if (value) "id=?" else "$column=?",
                    arrayOf(if (value) track.id else key),
                )
                value to previous
            }
            reload()
            change
        }

    suspend fun restoreLikes(ids: List<String>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                compileStatement("UPDATE tracks SET liked=1 WHERE id=?").use { statement ->
                    for (id in ids) {
                        statement.bindString(1, id)
                        statement.executeUpdateDelete()
                    }
                }
            }
            reload()
        }

    suspend fun file(fileId: Int, path: String) =
        withContext(Dispatchers.IO) {
            if (fileId <= 0) return@withContext
            val copy = File(path)
            val size = copy.length()
            if (!copy.isFile || size <= 0) return@withContext
            writableDatabase.update(
                "tracks",
                ContentValues().apply { put("path", path) },
                "file=? AND (size=0 OR size=?)",
                arrayOf(fileId.toString(), size.toString()),
            )
            reload()
        }

    suspend fun art(id: String, path: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "tracks",
                ContentValues().apply { put("art", path) },
                "id=?",
                arrayOf(id),
            )
            reload()
        }

    suspend fun unavailable(chat: Long, messages: List<Long>) =
        withContext(Dispatchers.IO) {
            messages.forEach {
                writableDatabase.execSQL(
                    "UPDATE tracks SET available=0 WHERE chat=? AND message=?",
                    arrayOf(chat, it),
                )
            }
            reload()
        }

    suspend fun createPlaylist(name: String, tracks: List<String> = emptyList()): Long =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { tr(R.string.playlist_name_required) }
            val id = writableDatabase.transaction {
                val created =
                    insertOrThrow(
                        "playlists",
                        null,
                        ContentValues().apply { put("name", name.trim().take(80)) },
                    )
                addTracks(this, created, tracks)
                created
            }
            reload()
            id
        }

    private fun addTracks(db: SQLiteDatabase, playlist: Long, tracks: List<String>) {
        check(
            db.rawQuery("SELECT id FROM playlists WHERE id=?", arrayOf(playlist.toString())).use {
                it.moveToFirst()
            }
        ) {
            tr(R.string.playlist_deleted)
        }
        var position =
            db.rawQuery(
                    "SELECT COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist=?",
                    arrayOf(playlist.toString()),
                )
                .use {
                    it.moveToFirst()
                    it.getInt(0)
                }
        db.compileStatement(
                "INSERT OR IGNORE INTO playlist_tracks(playlist,track,position) SELECT ?,id,? FROM tracks WHERE id=?"
            )
            .use { statement ->
                tracks.distinct().forEach { id ->
                    statement.bindLong(1, playlist)
                    statement.bindLong(2, position++.toLong())
                    statement.bindString(3, id)
                    statement.executeInsert()
                }
            }
    }

    suspend fun addToPlaylist(playlist: Long, track: String) =
        addToPlaylist(playlist, listOf(track))

    suspend fun addToPlaylist(playlist: Long, tracks: List<String>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction { addTracks(this, playlist, tracks) }
            reload()
        }

    suspend fun removeFromPlaylist(playlist: Long, track: String) =
        removeFromPlaylist(playlist, listOf(track))

    suspend fun removeFromPlaylist(playlist: Long, tracks: List<String>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                tracks.forEach { track ->
                    delete(
                        "playlist_tracks",
                        "playlist=? AND track=?",
                        arrayOf(playlist.toString(), track),
                    )
                }
            }
            reload()
        }

    suspend fun renamePlaylist(id: Long, name: String) =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { tr(R.string.playlist_name_required) }
            writableDatabase.update(
                "playlists",
                ContentValues().apply { put("name", name.trim().take(80)) },
                "id=?",
                arrayOf(id.toString()),
            )
            reload()
        }

    suspend fun reorderPlaylist(id: Long, order: List<String>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                val current = mutableListOf<String>()
                rawQuery(
                        "SELECT track FROM playlist_tracks WHERE playlist=? ORDER BY position",
                        arrayOf(id.toString()),
                    )
                    .use { c ->
                        while (c.moveToNext()) current += c.getString(0)
                    }
                val members = current.toSet()
                val requested = order.toSet()
                val merged =
                    order.distinct().filter { it in members } + current.filter { it !in requested }
                compileStatement(
                        "UPDATE playlist_tracks SET position=? WHERE playlist=? AND track=?"
                    )
                    .use { statement ->
                        merged.forEachIndexed { index, track ->
                            statement.bindLong(1, index.toLong())
                            statement.bindLong(2, id)
                            statement.bindString(3, track)
                            statement.executeUpdateDelete()
                        }
                    }
            }
            reload()
        }

    suspend fun deletePlaylist(id: Long) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                delete("playlist_tracks", "playlist=?", arrayOf(id.toString()))
                delete("playlists", "id=?", arrayOf(id.toString()))
            }
            reload()
        }

    suspend fun enqueue(ids: List<String>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                var position =
                    rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM downloads", null).use {
                        it.moveToFirst()
                        it.getLong(0)
                    }
                compileStatement("INSERT OR IGNORE INTO downloads(track,position) VALUES(?,?)")
                    .use { statement ->
                        ids.distinct().forEach { id ->
                            statement.bindString(1, id)
                            statement.bindLong(2, position++)
                            statement.executeInsert()
                        }
                    }
            }
        }

    suspend fun pending(): List<String> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<String>()
            readableDatabase.rawQuery("SELECT track FROM downloads ORDER BY position", null).use { c
                ->
                while (c.moveToNext()) result += c.getString(0)
            }
            result
        }

    suspend fun dequeue(id: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.delete("downloads", "track=?", arrayOf(id))
            Unit
        }

    suspend fun clearDownloads() =
        withContext(Dispatchers.IO) {
            writableDatabase.delete("downloads", null, null)
            Unit
        }

    suspend fun removeLocal(track: Track) =
        withContext(Dispatchers.IO) {
            if (track.chatId == 0L) {
                File(track.path).delete()
                if (track.art.isNotEmpty()) File(track.art).delete()
                writableDatabase.delete("tracks", "id=?", arrayOf(track.id))
                writableDatabase.delete("playlist_tracks", "track=?", arrayOf(track.id))
            } else
                writableDatabase.update(
                    "tracks",
                    ContentValues().apply { put("path", "") },
                    "path=?",
                    arrayOf(track.path),
                )
            reload()
        }

    suspend fun verifyFiles() =
        withContext(Dispatchers.IO) {
            val missing = mutableListOf<Triple<String, String, Long>>()
            readableDatabase.rawQuery("SELECT id,path,size FROM tracks WHERE path!=''", null).use {
                c ->
                while (c.moveToNext()) {
                    val path = c.getString(1)
                    if (!validAudioCopy(path, c.getLong(2)))
                        missing += Triple(c.getString(0), path, c.getLong(2))
                }
            }
            if (missing.isNotEmpty())
                writableDatabase.transaction {
                    compileStatement("UPDATE tracks SET path='' WHERE id=? AND path=? AND size=?")
                        .use { statement ->
                            for ((id, path, size) in missing) {
                                statement.bindString(1, id)
                                statement.bindString(2, path)
                                statement.bindLong(3, size)
                                statement.executeUpdateDelete()
                            }
                        }
                }
            reload()
        }

    suspend fun clearTelegram() =
        withContext(Dispatchers.IO) {
            writableDatabase.execSQL(
                "DELETE FROM playlist_tracks WHERE track IN (SELECT id FROM tracks WHERE chat!=0)"
            )
            writableDatabase.execSQL("DELETE FROM tracks WHERE chat!=0")
            writableDatabase.delete("sources", null, null)
            writableDatabase.delete("downloads", null, null)
            reload()
        }
}
