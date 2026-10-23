package app.spotygram

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class Library(private val platform: Platform) : AutoCloseable {
    val state = MutableStateFlow(LibraryState())
    val sync: LibrarySync = LibrarySync(platform, { writableDatabase }, { reload() })
    private val publishing = Mutex()
    private val storage = lazy {
        platform.openDatabase("library.db", 8, ::onCreate, ::onUpgrade, ::onOpen)
    }
    private val database
        get() = storage.value

    private val readableDatabase
        get() = database

    private val writableDatabase
        get() = database

    override fun close() {
        if (storage.isInitialized()) database.close()
    }

    private fun onCreate(db: SqlDatabase) {
        db.execSQL(
            "CREATE TABLE tracks(id TEXT PRIMARY KEY,chat INTEGER,message INTEGER,file INTEGER,title TEXT,artist TEXT,duration INTEGER,size INTEGER,source TEXT,date INTEGER,art TEXT DEFAULT '',path TEXT DEFAULT '',liked INTEGER DEFAULT 0,available INTEGER DEFAULT 1,document INTEGER DEFAULT 0,file_key TEXT DEFAULT '',remote_id TEXT DEFAULT '',saved INTEGER DEFAULT 0,profile_user INTEGER NOT NULL DEFAULT 0,profile_position INTEGER NOT NULL DEFAULT -1)"
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
        createTemporaryStorage(db)
        createPlaybackProgress(db)
        createProfileStorage(db)
        sync.install(db)
    }

    private fun onUpgrade(db: SqlDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) createPlaybackProgress(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN file_key TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN remote_id TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN saved INTEGER DEFAULT 0")
            db.execSQL(
                "UPDATE tracks SET saved=1 WHERE chat=0 OR path!='' OR id IN (SELECT track FROM downloads)"
            )
            createTemporaryStorage(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sources ADD COLUMN newest INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN document_newest INTEGER DEFAULT 0")
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN document INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN document_cursor INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE sources ADD COLUMN documents_complete INTEGER DEFAULT 0")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN profile_user INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tracks ADD COLUMN profile_position INTEGER NOT NULL DEFAULT -1")
            createProfileStorage(db)
        }
        if (oldVersion < 6) sync.install(db)
        if (oldVersion < 7) sync.migrate(db)
    }

    private fun createProfileStorage(db: SqlDatabase) {
        db.execSQL(
            "CREATE TABLE profiles(id INTEGER PRIMARY KEY,title TEXT NOT NULL,username TEXT NOT NULL DEFAULT '',photo TEXT NOT NULL DEFAULT '',selected INTEGER NOT NULL DEFAULT 0,audio_count INTEGER NOT NULL DEFAULT 0,loaded_at INTEGER NOT NULL DEFAULT 0,opened_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX tracks_profile ON tracks(profile_user,profile_position)")
    }

    private fun createPlaybackProgress(db: SqlDatabase) {
        db.execSQL(
            "CREATE TABLE playback_progress(track TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,position INTEGER NOT NULL,duration INTEGER NOT NULL,file_key TEXT NOT NULL,size INTEGER NOT NULL)"
        )
    }

    suspend fun playbackProgress(): Map<String, PlaybackProgress> =
        withContext(Dispatchers.IO) {
            buildMap {
                readableDatabase
                    .rawQuery(
                        "SELECT track,position,duration,file_key,size FROM playback_progress",
                        null,
                    )
                    .use { c ->
                        while (c.moveToNext()) put(
                            c.getString(0),
                            PlaybackProgress(
                                c.getLong(1),
                                c.getLong(2),
                                c.getString(3),
                                c.getLong(4),
                            ),
                        )
                    }
            }
        }

    suspend fun savePlaybackProgress(updates: Map<String, PlaybackProgress?>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                for ((id, progress) in updates) {
                    if (progress == null) delete("playback_progress", "track=?", arrayOf(id))
                    else
                        execSQL(
                            "INSERT OR REPLACE INTO playback_progress(track,position,duration,file_key,size) SELECT id,?,?,?,? FROM tracks WHERE id=?",
                            arrayOf<Any>(
                                progress.position,
                                progress.duration,
                                progress.fileKey,
                                progress.size,
                                id,
                            ),
                        )
                }
            }
        }

    private fun createTemporaryStorage(db: SqlDatabase) {
        db.execSQL("CREATE INDEX tracks_key ON tracks(file_key)")
        db.execSQL("CREATE INDEX tracks_path ON tracks(path)")
        db.execSQL(
            "CREATE TABLE temporary_audio(file_key TEXT PRIMARY KEY,remote_id TEXT NOT NULL,path TEXT NOT NULL)"
        )
    }

    private fun onOpen(db: SqlDatabase) {
        sync.opened(db)
        // TDLib file IDs belong to one client lifetime, not to the persisted catalog.
        // Clear old bindings before any new audio/thumbnail completion can match them.
        db.execSQL("UPDATE tracks SET file=0 WHERE (chat!=0 OR profile_user!=0) AND file!=0")
    }

    suspend fun clearFileBindings() =
        withContext(Dispatchers.IO) {
            onOpen(writableDatabase)
            reload()
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
            val sourceTitles = sources.associate { it.id to it.title }
            val profiles = mutableListOf<UserProfile>()
            db.rawQuery(
                    "SELECT id,title,username,photo,selected,audio_count,loaded_at,opened_at FROM profiles ORDER BY title",
                    null,
                )
                .use { c ->
                    while (c.moveToNext()) profiles +=
                        UserProfile(
                            c.getLong(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getInt(4) == 1,
                            c.getInt(5),
                            c.getLong(6),
                            c.getLong(7),
                        )
                }
            val profileTitles = profiles.associate { it.id to it.title }
            db.rawQuery(
                    "SELECT tracks.id,chat,message,file,tracks.title,artist,duration,size,source,date,art,tracks.path,liked,available,document,tracks.file_key,tracks.remote_id,saved,profile_user,profile_position,CASE WHEN temporary_audio.file_key IS NULL THEN 0 ELSE 1 END,CASE WHEN profile_user=0 OR liked=1 OR tracks.id IN(SELECT track FROM playlist_tracks) OR (profile_position>=0 AND profile_user IN(SELECT id FROM profiles WHERE selected=1)) THEN 1 ELSE 0 END FROM tracks LEFT JOIN temporary_audio ON tracks.file_key=temporary_audio.file_key WHERE profile_user!=0 OR chat=0 OR tracks.path!='' OR liked=1 OR tracks.id IN (SELECT track FROM playlist_tracks) OR chat IN (SELECT id FROM sources) ORDER BY date DESC,tracks.id DESC",
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
                            (if (c.getLong(18) != 0L) profileTitles[c.getLong(18)]
                            else sourceTitles[c.getLong(1)]) ?: c.getString(8),
                            c.getLong(9),
                            c.getString(10),
                            c.getString(11),
                            c.getInt(12) == 1,
                            c.getInt(13) == 1,
                            c.getInt(14) == 1,
                            c.getString(15),
                            c.getString(16),
                            c.getInt(17) == 1,
                            c.getInt(20) == 1,
                            c.getLong(18),
                            c.getInt(19),
                            c.getInt(21) == 1,
                        )
                }
            val lists = mutableListOf<Playlist>()
            val members = mutableMapOf<Long, MutableList<String>>()
            db.rawQuery(
                    "SELECT playlist,track FROM playlist_tracks ORDER BY playlist,position",
                    null,
                )
                .use { c ->
                    while (c.moveToNext()) members
                        .getOrPut(c.getLong(0)) { mutableListOf() }
                        .add(c.getString(1))
                }
            db.rawQuery(
                    "SELECT id,name FROM playlists WHERE account=? ORDER BY name",
                    arrayOf(sync.account.toString()),
                )
                .use { c ->
                    while (c.moveToNext()) {
                        lists +=
                            Playlist(c.getLong(0), c.getString(1), members[c.getLong(0)].orEmpty())
                    }
                }
            val snapshot = LibraryState(tracks, sources, lists, profiles)
            if (snapshot != state.value) {
                snapshot.byId
                snapshot.localGroups
                snapshot.localSize
                snapshot.sourceCounts
                snapshot.playlistCounts
                if (platform.preferences("settings").getBoolean("hide_duplicates", true)) {
                    snapshot.duplicates
                    snapshot.localRecordingCount
                }
                state.value = snapshot
            }
        }
    }

    suspend fun newest(id: Long, newest: Long, document: Boolean) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "sources",
                Values().apply {
                    put(if (document) "document_newest" else "newest", newest)
                },
                "id=?",
                arrayOf(id.toString()),
            )
        }

    suspend fun upsert(tracks: List<Track>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction { upsertRows(this, tracks) }
        }

    private fun upsertRows(db: SqlDatabase, tracks: List<Track>) {
        tracks.forEach { t ->
            if (t.fileKey.isNotEmpty())
                db.execSQL(
                    "UPDATE tracks SET path='' WHERE id=? AND file_key!='' AND file_key!=?",
                    arrayOf(t.id, t.fileKey),
                )
            val v =
                Values().apply {
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
                    put("profile_user", t.profileUserId)
                    put("profile_position", t.profilePosition)
                    if (t.fileKey.isNotEmpty()) put("file_key", t.fileKey)
                    if (t.remoteId.isNotEmpty()) put("remote_id", t.remoteId)
                    if (t.art.isNotEmpty()) put("art", t.art)
                    if (t.path.isNotEmpty())
                        put("path", t.path.takeIf { validAudioCopy(it, t.size) }.orEmpty())
                }
            if (db.update("tracks", v, "id=?", arrayOf(t.id)) == 0) {
                v.put("id", t.id)
                v.put("saved", if (t.saved) 1 else 0)
                db.insertOrThrow("tracks", null, v)
            }
        }
        val imports = tracks.filter { it.imported }.map { it.id }
        if (imports.isNotEmpty()) {
            val values = linkedMapOf<String, Any?>()
            sync.references(db, imports, values)
            sync.edit(db, values)
        }
    }

    suspend fun source(choice: ChatChoice, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                if (enabled)
                    insertWithOnConflict(
                        "sources",
                        null,
                        Values().apply {
                            put("id", choice.id)
                            put("title", choice.title)
                        },
                        SqlDatabase.CONFLICT_IGNORE,
                    )
                else delete("sources", "id=?", arrayOf(choice.id.toString()))
                sync.edit(
                    this,
                    mapOf(
                        "source/${choice.id}" to
                            JSONObject().put("enabled", enabled).put("title", choice.title)
                    ),
                )
            }
            reload()
        }

    suspend fun sourceTitle(id: Long, title: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                update(
                    "sources",
                    Values().apply { put("title", title) },
                    "id=?",
                    arrayOf(id.toString()),
                )
                update(
                    "tracks",
                    Values().apply { put("source", title) },
                    "chat=?",
                    arrayOf(id.toString()),
                )
            }
            reload()
        }

    suspend fun profileIdentity(profile: UserProfile, opened: Boolean = false) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                insertWithOnConflict(
                    "profiles",
                    null,
                    Values().apply {
                        put("id", profile.id)
                        put("title", profile.title)
                    },
                    SqlDatabase.CONFLICT_IGNORE,
                )
                update(
                    "profiles",
                    Values().apply {
                        put("title", profile.title)
                        put("username", profile.username)
                        put("photo", profile.photo)
                        if (opened) put("opened_at", System.currentTimeMillis())
                    },
                    "id=?",
                    arrayOf(profile.id.toString()),
                )
            }
            reload()
        }

    suspend fun profilePhoto(id: Long, photo: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "profiles",
                Values().apply { put("photo", photo) },
                "id=?",
                arrayOf(id.toString()),
            )
            reload()
        }

    suspend fun selectProfile(profile: UserProfile, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                insertWithOnConflict(
                    "profiles",
                    null,
                    Values().apply {
                        put("id", profile.id)
                        put("title", profile.title)
                        put("username", profile.username)
                    },
                    SqlDatabase.CONFLICT_IGNORE,
                )
                update(
                    "profiles",
                    Values().apply { put("selected", if (enabled) 1 else 0) },
                    "id=?",
                    arrayOf(profile.id.toString()),
                )
                sync.edit(
                    this,
                    mapOf(
                        "profile/${profile.id}" to
                            JSONObject()
                                .put("enabled", enabled)
                                .put("title", profile.title.take(256))
                                .put("username", profile.username)
                    ),
                )
            }
            reload()
        }

    suspend fun profileAudios(id: Long, tracks: List<Track>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                // Replace membership only after every page has arrived successfully.
                execSQL(
                    "UPDATE tracks SET profile_position=-1,available=0 WHERE profile_user=?",
                    arrayOf(id),
                )
                upsertRows(this, tracks)
                update(
                    "profiles",
                    Values().apply {
                        put("audio_count", tracks.size)
                        put("loaded_at", System.currentTimeMillis())
                    },
                    "id=?",
                    arrayOf(id.toString()),
                )
            }
            reload()
        }

    suspend fun importedAudioReference(id: String, key: String, remote: String) =
        withContext(Dispatchers.IO) {
            require(key.isNotEmpty() && remote.isNotEmpty())
            writableDatabase.update(
                "tracks",
                Values().apply {
                    put("file_key", key)
                    put("remote_id", remote)
                },
                "id=? AND chat=0 AND profile_user=0",
                arrayOf(id),
            )
            reload()
        }

    suspend fun pruneProfilePreviews(protectedUsers: Set<Long>): Set<String> =
        withContext(Dispatchers.IO) {
            val removed = mutableSetOf<String>()
            writableDatabase.transaction {
                val old =
                    rawQuery(
                            "SELECT id FROM profiles WHERE selected=0 AND loaded_at!=0 AND id NOT IN(SELECT id FROM profiles ORDER BY opened_at DESC LIMIT 12)",
                            null,
                        )
                        .use { c ->
                            buildList {
                                while (c.moveToNext()) if (c.getLong(0) !in protectedUsers)
                                    add(c.getLong(0))
                            }
                        }
                val metadataOnly =
                    "profile_user=? AND liked=0 AND path='' AND id NOT IN(SELECT track FROM playlist_tracks) AND id NOT IN(SELECT track FROM downloads) AND id NOT IN(SELECT track FROM playback_progress)"
                for (id in old) {
                    rawQuery("SELECT id FROM tracks WHERE $metadataOnly", arrayOf(id.toString()))
                        .use { c ->
                            while (c.moveToNext()) removed += c.getString(0)
                        }
                    delete("tracks", metadataOnly, arrayOf(id.toString()))
                    execSQL("UPDATE profiles SET loaded_at=0 WHERE id=?", arrayOf(id))
                }
            }
            if (removed.isNotEmpty()) reload()
            removed
        }

    suspend fun cursor(id: Long, cursor: Long, complete: Boolean, document: Boolean = false) =
        withContext(Dispatchers.IO) {
            writableDatabase.update(
                "sources",
                Values().apply {
                    put(if (document) "document_cursor" else "cursor", cursor)
                    put(if (document) "documents_complete" else "complete", if (complete) 1 else 0)
                },
                "id=?",
                arrayOf(id.toString()),
            )
        }

    suspend fun like(
        track: Track,
        sameFile: Boolean = false,
        aliases: List<String> = emptyList(),
    ): Pair<Boolean, List<String>> =
        withContext(Dispatchers.IO) {
            val ids =
                when {
                        aliases.isNotEmpty() -> aliases
                        sameFile && track.local ->
                            state.value.localGroups[track.path].orEmpty().map { it.id }
                        else -> listOf(track.id)
                    }
                    .ifEmpty { listOf(track.id) }
                    .distinct()
            val change = writableDatabase.transaction {
                val previous = mutableListOf<String>()
                for (chunk in ids.chunked(400)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    rawQuery(
                            "SELECT id FROM tracks WHERE id IN ($placeholders) AND liked=1",
                            chunk.toTypedArray(),
                        )
                        .use { c ->
                            while (c.moveToNext()) previous += c.getString(0)
                        }
                }
                val value = previous.isEmpty()
                compileStatement("UPDATE tracks SET liked=? WHERE id=?").use { statement ->
                    for (id in if (value) listOf(track.id) else previous) {
                        statement.bindLong(1, if (value) 1L else 0L)
                        statement.bindString(2, id)
                        statement.executeUpdateDelete()
                    }
                }
                val changed = if (value) listOf(track.id) else previous
                val values = changed.associate { "favorite/$it" to value as Any? }.toMutableMap()
                sync.references(this, changed, values)
                sync.edit(this, values)
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
                val values = ids.associate { "favorite/$it" to true as Any? }.toMutableMap()
                sync.references(this, ids, values)
                sync.edit(this, values)
            }
            reload()
        }

    suspend fun file(fileId: Int, path: String) =
        withContext(Dispatchers.IO) {
            if (fileId <= 0) return@withContext false
            val copy = File(path)
            val size = copy.length()
            if (!copy.isFile || size <= 0) return@withContext false
            val changed =
                writableDatabase.update(
                    "tracks",
                    Values().apply { put("path", path) },
                    "file=? AND (size=0 OR size=?)",
                    arrayOf(fileId.toString(), size.toString()),
                )
            if (changed > 0) reload()
            changed > 0
        }

    // Thumbnail bursts are published together by SpotygramApp, after the writes settle.
    suspend fun art(ids: Collection<String>, path: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                compileStatement("UPDATE tracks SET art=? WHERE id=?").use { statement ->
                    for (id in ids) {
                        statement.bindString(1, path)
                        statement.bindString(2, id)
                        statement.executeUpdateDelete()
                    }
                }
            }
        }

    suspend fun unavailable(chat: Long, messages: List<Long>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                compileStatement("UPDATE tracks SET available=0 WHERE id=?").use { statement ->
                    for (message in messages) {
                        statement.bindString(1, "$chat:$message")
                        statement.executeUpdateDelete()
                    }
                }
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
                        Values().apply {
                            put("name", name.trim().take(80))
                            put("account", sync.account)
                        },
                    )
                addTracks(this, created, tracks)
                val uid = UUID.randomUUID().toString()
                execSQL(
                    "UPDATE playlists SET sync_id=?,account=? WHERE id=?",
                    arrayOf<Any>(uid, sync.account, created),
                )
                val ids = sync.playlistTracks(this, created)
                val values =
                    linkedMapOf<String, Any?>(
                        "playlist/$uid/name" to name.trim().take(80),
                        "playlist/$uid/order" to JSONArray(ids),
                    )
                for (track in ids) values["playlist/$uid/member/$track"] = true
                sync.references(this, ids, values)
                sync.edit(this, values)
                created
            }
            reload()
            id
        }

    private fun addTracks(db: SqlDatabase, playlist: Long, tracks: List<String>) {
        check(
            db.rawQuery(
                    "SELECT id FROM playlists WHERE id=? AND account=?",
                    arrayOf(playlist.toString(), sync.account.toString()),
                )
                .use {
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
            writableDatabase.transaction {
                addTracks(this, playlist, tracks)
                val uid = sync.playlistId(this, playlist)
                val ids = sync.playlistTracks(this, playlist)
                val members = ids.toSet()
                val values = linkedMapOf<String, Any?>("playlist/$uid/order" to JSONArray(ids))
                for (track in tracks.filter { it in members }) values[
                    "playlist/$uid/member/$track"] = true
                sync.references(this, tracks, values)
                sync.edit(this, values)
            }
            reload()
        }

    suspend fun removeFromPlaylist(playlist: Long, track: String) =
        removeFromPlaylist(playlist, listOf(track))

    suspend fun removeFromPlaylist(playlist: Long, tracks: List<String>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                val uid = sync.playlistId(this, playlist)
                tracks.forEach { track ->
                    delete(
                        "playlist_tracks",
                        "playlist=? AND track=?",
                        arrayOf(playlist.toString(), track),
                    )
                }
                sync.edit(this, tracks.associate { "playlist/$uid/member/$it" to false })
            }
            reload()
        }

    suspend fun renamePlaylist(id: Long, name: String) =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { tr(R.string.playlist_name_required) }
            writableDatabase.transaction {
                val uid = sync.playlistId(this, id)
                update(
                    "playlists",
                    Values().apply { put("name", name.trim().take(80)) },
                    "id=?",
                    arrayOf(id.toString()),
                )
                sync.edit(this, mapOf("playlist/$uid/name" to name.trim().take(80)))
            }
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
                sync.edit(
                    this,
                    mapOf("playlist/${sync.playlistId(this, id)}/order" to JSONArray(merged)),
                )
            }
            reload()
        }

    suspend fun deletePlaylist(id: Long) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                val uid = sync.playlistId(this, id)
                delete("playlist_tracks", "playlist=?", arrayOf(id.toString()))
                delete("playlists", "id=?", arrayOf(id.toString()))
                sync.edit(this, mapOf("playlist/$uid/deleted" to true))
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
            if (track.imported) {
                val audio = File(track.path)
                check(!audio.exists() || audio.delete()) { tr(R.string.file_unavailable) }
                if (track.art.isNotEmpty()) {
                    val artwork = File(track.art)
                    check(!artwork.exists() || artwork.delete()) { tr(R.string.file_unavailable) }
                }
                writableDatabase.transaction {
                    val values = linkedMapOf<String, Any?>("favorite/${track.id}" to false)
                    sync.descriptor(this, track.id)?.let {
                        values["track/${track.id}"] = it.put("deleted", true)
                    }
                    rawQuery(
                            "SELECT playlists.sync_id FROM playlists JOIN playlist_tracks ON playlists.id=playlist_tracks.playlist WHERE playlist_tracks.track=? AND playlists.account=?",
                            arrayOf(track.id, sync.account.toString()),
                        )
                        .use { c ->
                            while (c.moveToNext()) values[
                                "playlist/${c.getString(0)}/member/${track.id}"] = false
                        }
                    delete("playlist_tracks", "track=?", arrayOf(track.id))
                    delete("tracks", "id=?", arrayOf(track.id))
                    sync.edit(this, values)
                }
            } else
                writableDatabase.transaction {
                    update(
                        "tracks",
                        Values().apply {
                            put("path", "")
                            put("saved", 0)
                        },
                        "(path!='' AND path=?) OR (file_key!='' AND file_key=?)",
                        arrayOf(track.path, track.fileKey),
                    )
                    delete("temporary_audio", "file_key=?", arrayOf(track.fileKey))
                }
            reload()
        }

    suspend fun verifyFiles() =
        withContext(Dispatchers.IO) {
            val missing = mutableListOf<Triple<String, String, Long>>()
            val checked = mutableMapOf<Pair<String, Long>, Boolean>()
            readableDatabase.rawQuery("SELECT id,path,size FROM tracks WHERE path!=''", null).use {
                c ->
                while (c.moveToNext()) {
                    val path = c.getString(1)
                    if (
                        !checked.getOrPut(path to c.getLong(2)) {
                            validAudioCopy(path, c.getLong(2))
                        }
                    )
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
            writableDatabase.transaction {
                execSQL(
                    "DELETE FROM playlist_tracks WHERE track IN (SELECT id FROM tracks WHERE chat!=0 OR profile_user!=0)"
                )
                execSQL("DELETE FROM tracks WHERE chat!=0 OR profile_user!=0")
                execSQL("DELETE FROM tracks WHERE cloud=1 AND path=''")
                delete("sources", null, null)
                delete("profiles", null, null)
                delete("downloads", null, null)
                delete("temporary_audio", null, null)
            }
            reload()
        }

    suspend fun temporaryFiles(): List<TemporaryAudio> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<TemporaryAudio>()
            readableDatabase
                .rawQuery("SELECT file_key,remote_id,path FROM temporary_audio", null)
                .use { c ->
                    while (c.moveToNext()) result +=
                        TemporaryAudio(c.getString(0), c.getString(1), c.getString(2))
                }
            result
        }

    suspend fun ownsTemporary(key: String): Boolean =
        withContext(Dispatchers.IO) {
            readableDatabase
                .rawQuery("SELECT 1 FROM temporary_audio WHERE file_key=?", arrayOf(key))
                .use { it.moveToFirst() }
        }

    suspend fun savedCopy(key: String, path: String): Boolean =
        withContext(Dispatchers.IO) {
            readableDatabase
                .rawQuery(
                    "SELECT 1 FROM tracks WHERE saved=1 AND ((file_key!='' AND file_key=?) OR (path!='' AND path=?)) LIMIT 1",
                    arrayOf(key, path),
                )
                .use { it.moveToFirst() }
        }

    suspend fun claimTemporary(file: TemporaryAudio) =
        withContext(Dispatchers.IO) {
            writableDatabase.insertWithOnConflict(
                "temporary_audio",
                null,
                Values().apply {
                    put("file_key", file.key)
                    put("remote_id", file.remote)
                    put("path", file.path)
                },
                SqlDatabase.CONFLICT_IGNORE,
            )
            Unit
        }

    suspend fun retainTracks(ids: List<String>) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                compileStatement(
                        "UPDATE tracks SET saved=1 WHERE id=? OR (file_key!='' AND file_key=(SELECT file_key FROM tracks WHERE id=?)) OR (path!='' AND path=(SELECT path FROM tracks WHERE id=?))"
                    )
                    .use { save ->
                        compileStatement(
                                "DELETE FROM temporary_audio WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.file_key=temporary_audio.file_key AND saved=1)"
                            )
                            .use { remove ->
                                for (id in ids.distinct()) {
                                    for (i in 1..3) save.bindString(i, id)
                                    save.executeUpdateDelete()
                                }
                                remove.executeUpdateDelete()
                            }
                    }
            }
        }

    suspend fun rememberTemporaryPath(key: String, path: String) =
        withContext(Dispatchers.IO) {
            if (path.isNotEmpty())
                writableDatabase.update(
                    "temporary_audio",
                    Values().apply { put("path", path) },
                    "file_key=?",
                    arrayOf(key),
                )
            Unit
        }

    suspend fun retainTemporaryFiles() =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                execSQL(
                    "UPDATE tracks SET saved=1 WHERE file_key IN (SELECT file_key FROM temporary_audio)"
                )
                delete("temporary_audio", null, null)
            }
        }

    suspend fun forgetTemporary(file: TemporaryAudio, path: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.transaction {
                delete("temporary_audio", "file_key=?", arrayOf(file.key))
                update(
                    "tracks",
                    Values().apply { put("path", "") },
                    "file_key=? OR (path!='' AND path=?)",
                    arrayOf(file.key, path),
                )
            }
        }
}
