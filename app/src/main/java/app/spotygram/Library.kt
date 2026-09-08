package app.spotygram

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class Library(context: Context) : SQLiteOpenHelper(context, "library.db", null, 2) {
    val state = MutableStateFlow(LibraryState())

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tracks(id TEXT PRIMARY KEY,chat INTEGER,message INTEGER,file INTEGER,title TEXT,artist TEXT,duration INTEGER,size INTEGER,source TEXT,date INTEGER,art TEXT DEFAULT '',path TEXT DEFAULT '',liked INTEGER DEFAULT 0,available INTEGER DEFAULT 1,document INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX tracks_file ON tracks(file)")
        db.execSQL("CREATE INDEX tracks_chat ON tracks(chat)")
        db.execSQL(
            "CREATE TABLE sources(id INTEGER PRIMARY KEY,title TEXT,cursor INTEGER DEFAULT 0,complete INTEGER DEFAULT 0,document_cursor INTEGER DEFAULT 0,documents_complete INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT)")
        db.execSQL(
            "CREATE TABLE playlist_tracks(playlist INTEGER,track TEXT,position INTEGER,PRIMARY KEY(playlist,track))"
        )
        db.execSQL("CREATE TABLE downloads(track TEXT PRIMARY KEY,position INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN document INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN document_cursor INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN documents_complete INTEGER DEFAULT 0")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    suspend fun reload() =
        withContext(Dispatchers.IO) {
            val db = readableDatabase
            val sources = mutableListOf<Source>()
            db.rawQuery(
                    "SELECT id,title,cursor,complete,document_cursor,documents_complete FROM sources ORDER BY title",
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
                        )
                }
            val tracks = mutableListOf<Track>()
            db.rawQuery(
                    "SELECT * FROM tracks WHERE chat=0 OR path!='' OR chat IN (SELECT id FROM sources) ORDER BY date DESC,id DESC",
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
                            if (t.path.isNotEmpty()) put("path", t.path)
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

    suspend fun like(track: Track) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "tracks",
                ContentValues().apply { put("liked", if (track.liked) 0 else 1) },
                "id=?",
                arrayOf(track.id),
            )
            reload()
        }

    suspend fun file(fileId: Int, path: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "tracks",
                ContentValues().apply { put("path", path) },
                "file=?",
                arrayOf(fileId.toString()),
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

    suspend fun createPlaylist(name: String): Long =
        withContext(Dispatchers.IO) {
            val id =
                writableDatabase.insertOrThrow(
                    "playlists",
                    null,
                    ContentValues().apply { put("name", name.trim().take(80)) },
                )
            reload()
            id
        }

    suspend fun addToPlaylist(playlist: Long, track: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO playlist_tracks VALUES(?,?,(SELECT COALESCE(MAX(position),0)+1 FROM playlist_tracks WHERE playlist=?))",
                arrayOf<Any>(playlist, track, playlist),
            )
            reload()
        }

    suspend fun removeFromPlaylist(playlist: Long, track: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.delete(
                "playlist_tracks",
                "playlist=? AND track=?",
                arrayOf(playlist.toString(), track),
            )
            reload()
        }

    suspend fun deletePlaylist(id: Long) =
        withContext(Dispatchers.IO) {
            writableDatabase.delete("playlist_tracks", "playlist=?", arrayOf(id.toString()))
            writableDatabase.delete("playlists", "id=?", arrayOf(id.toString()))
            reload()
        }

    suspend fun enqueue(ids: List<String>) =
        withContext(Dispatchers.IO) {
            ids.forEach {
                writableDatabase.execSQL(
                    "INSERT OR IGNORE INTO downloads VALUES(?,(SELECT COALESCE(MAX(position),0)+1 FROM downloads))",
                    arrayOf(it),
                )
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
                    "file=?",
                    arrayOf(track.fileId.toString()),
                )
            reload()
        }

    suspend fun verifyFiles() =
        withContext(Dispatchers.IO) {
            state.value.tracks
                .filter { it.local && !File(it.path).isFile }
                .forEach { t ->
                    writableDatabase.update(
                        "tracks",
                        ContentValues().apply { put("path", "") },
                        "id=?",
                        arrayOf(t.id),
                    )
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
