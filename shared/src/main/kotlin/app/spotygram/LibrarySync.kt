package app.spotygram

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Durable, account-scoped user data. Versions survive journal compaction and offline edits. */
class LibrarySync(
    private val platform: Platform,
    private val database: () -> SqlDatabase,
    private val reload: suspend () -> Unit,
) {
    val changes = Channel<Unit>(Channel.CONFLATED)
    @Volatile
    var account = 0L
        private set

    data class Record(val key: String, val clock: Long, val writer: String, val value: String) {
        fun json() = JSONArray().put(key).put(clock).put(writer).put(JSONObject(value).get("v"))

        fun removesIdentity(): Boolean {
            val v = JSONObject(value)
            return key.endsWith("/deleted") && v.optBoolean("v") ||
                key.startsWith("track/") && v.optJSONObject("v")?.optBoolean("deleted") == true
        }

        fun supersedes(old: Record?): Boolean =
            old?.removesIdentity() != true &&
                (removesIdentity() ||
                    old == null ||
                    clock > old.clock ||
                    clock == old.clock && writer > old.writer)
    }

    companion object {
        const val FORMAT = 1
        val settings =
            setOf(
                "theme",
                "language",
                "hide_duplicates",
                "resume_long_audio",
                "resume_minutes",
                "wifi_only",
                "discard_listened",
                "speed",
                "order",
                "repeat",
            )
        private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val trackId = Regex("(?:-?[0-9]{1,16}:[0-9]{1,16}|local:[a-f0-9-]{36})")

        private fun wrapped(value: Any?) =
            JSONObject().put("v", value ?: JSONObject.NULL).toString()

        fun decode(array: JSONArray): List<Record> {
            require(array.length() <= 300_000) { "Sync collection is too large" }
            val seen = HashSet<String>()
            return List(array.length()) { i ->
                val row = array.getJSONArray(i)
                require(row.length() == 4)
                val key = row.getString(0)
                val clock = row.getLong(1)
                val writer = row.getString(2)
                val value = row.get(3)
                require(clock in 1..Long.MAX_VALUE / 4 && uuid.matches(writer) && seen.add(key))
                validate(key, value)
                Record(key, clock, writer, wrapped(value))
            }
        }

        private fun validate(key: String, value: Any) {
            require(key.length <= 160)
            val parts = key.split('/')
            when (parts.first()) {
                "track" -> {
                    require(parts.size == 2 && trackId.matches(parts[1]) && value is JSONObject)
                    require(value.getString("id") == parts[1])
                    require(
                        value.getLong("chat") in -9_007_199_254_740_991L..9_007_199_254_740_991L
                    )
                    require(value.getLong("message") in 0..9_007_199_254_740_991L)
                    require(
                        if (parts[1].startsWith("local:")) value.getLong("chat") == 0L
                        else parts[1] == "${value.getLong("chat")}:${value.getLong("message")}"
                    )
                    for (field in listOf("title", "artist", "source", "key", "remote")) require(
                        value.optString(field).length <= 8192
                    )
                    require(value.optLong("size") >= 0 && value.optInt("duration") >= 0)
                }
                "favorite" ->
                    require(parts.size == 2 && trackId.matches(parts[1]) && value is Boolean)
                "source" -> {
                    require(
                        parts.size == 2 && parts[1].toLongOrNull() != null && value is JSONObject
                    )
                    require(
                        value.get("enabled") is Boolean && value.getString("title").length <= 256
                    )
                }
                "playlist" -> {
                    require(parts.size >= 3 && uuid.matches(parts[1]))
                    when (parts[2]) {
                        "name" ->
                            require(
                                parts.size == 3 &&
                                    value is String &&
                                    value.isNotBlank() &&
                                    value.length <= 80
                            )
                        "deleted" -> require(parts.size == 3 && value is Boolean)
                        "member" ->
                            require(
                                parts.size == 4 && trackId.matches(parts[3]) && value is Boolean
                            )
                        "order" -> {
                            require(parts.size == 3 && value is JSONArray)
                            validateIds(value)
                        }
                        else -> error("Unsupported sync field")
                    }
                }
                "progress" -> {
                    require(parts.size == 2 && trackId.matches(parts[1]))
                    if (value != JSONObject.NULL) {
                        require(
                            value is JSONObject &&
                                value.getLong("position") >= 0 &&
                                value.getLong("duration") > 0
                        )
                        require(
                            value.getLong("position") <= value.getLong("duration") &&
                                value.getLong("size") >= 0
                        )
                        require(value.getString("key").length <= 8192)
                    }
                }
                "setting" -> {
                    require(parts.size == 2 && parts[1] in settings)
                    when (parts[1]) {
                        "theme" -> require(value in listOf("system", "light", "dark"))
                        "language" -> require(value in listOf("system", "en", "ru"))
                        "order" -> require(value in PlaybackOrder.entries.map { it.name })
                        "speed" ->
                            require(
                                value is Number &&
                                    value.toFloat() in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED
                            )
                        "repeat" -> require(value is Number && value.toInt() in 0..2)
                        "resume_minutes" -> require(value is Number && value.toInt() > 0)
                        else -> require(value is Boolean)
                    }
                }
                "queue" -> {
                    require(parts.size == 1 && value is JSONObject)
                    validateIds(value.getJSONArray("ids"))
                    val path = value.getJSONArray("path")
                    require(path.length() <= 200_000 && value.getLong("position") >= 0)
                    val count = value.getJSONArray("ids").length()
                    for (i in 0 until path.length()) require(path.getInt(i) in 0 until count)
                    require(value.getInt("cursor") in 0..maxOf(0, path.length() - 1))
                }
                else -> error("Unsupported sync field")
            }
        }

        private fun validateIds(ids: JSONArray) {
            require(ids.length() <= 100_000)
            for (i in 0 until ids.length()) require(trackId.matches(ids.getString(i)))
        }
    }

    fun install(db: SqlDatabase) {
        db.execSQL("CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE sync_values(account INTEGER NOT NULL,key TEXT NOT NULL,clock INTEGER NOT NULL,writer TEXT NOT NULL,value TEXT NOT NULL,pending INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(account,key))"
        )
        db.execSQL("CREATE INDEX sync_pending ON sync_values(account,pending)")
        db.execSQL("ALTER TABLE playlists ADD COLUMN sync_id TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN account INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE UNIQUE INDEX playlists_sync ON playlists(sync_id)")
        db.execSQL("ALTER TABLE tracks ADD COLUMN cloud INTEGER NOT NULL DEFAULT 0")
        putMeta(db, "device", UUID.randomUUID().toString())
        putMeta(db, "clock", "0")
        putMeta(db, "account", "0")
        val seed = linkedMapOf<String, Any?>()
        db.rawQuery("SELECT id,title FROM sources", null).use { c ->
            while (c.moveToNext()) seed["source/${c.getLong(0)}"] =
                JSONObject().put("enabled", true).put("title", c.getString(1))
        }
        db.rawQuery(
                "SELECT id,liked FROM tracks WHERE liked=1 OR chat=0 OR id IN(SELECT track FROM playlist_tracks) OR id IN(SELECT track FROM playback_progress)",
                null,
            )
            .use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    seed["track/$id"] = descriptor(db, id)
                    if (c.getInt(1) == 1) seed["favorite/$id"] = true
                }
            }
        db.rawQuery("SELECT id,name FROM playlists", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uid = UUID.randomUUID().toString()
                db.execSQL("UPDATE playlists SET sync_id=? WHERE id=?", arrayOf<Any>(uid, id))
                seed["playlist/$uid/name"] = c.getString(1)
                val ids = playlistTracks(db, id)
                seed["playlist/$uid/order"] = JSONArray(ids)
                for (track in ids) seed["playlist/$uid/member/$track"] = true
            }
        }
        db.rawQuery("SELECT track,position,duration,file_key,size FROM playback_progress", null)
            .use { c ->
                while (c.moveToNext()) seed["progress/${c.getString(0)}"] =
                    JSONObject()
                        .put("position", c.getLong(1))
                        .put("duration", c.getLong(2))
                        .put("key", c.getString(3))
                        .put("size", c.getLong(4))
            }
        val prefs = platform.preferences("settings")
        for (key in settings - setOf("speed", "order", "repeat")) if (prefs.contains(key))
            seed["setting/$key"] =
                when (key) {
                    "theme",
                    "language" -> prefs.getString(key, "system")
                    "resume_minutes" -> prefs.getInt(key, 30)
                    else -> prefs.getBoolean(key, false)
                }
        val position = platform.preferences("playback_position")
        if (position.contains("speed")) seed["setting/speed"] = position.getFloat("speed", 1f)
        val queue = platform.preferences("playback_queue")
        if (queue.contains("ids")) {
            val ids = JSONArray(queue.getString("ids", "[]"))
            val path = JSONArray(queue.getString("path", "[]"))
            val current =
                if (position.getString("version", "") == queue.getString("version", "?")) position
                else queue
            seed["queue"] =
                JSONObject()
                    .put("ids", ids)
                    .put("path", path)
                    .put("cursor", current.getInt("cursor", 0))
                    .put("position", current.getLong("position", 0).coerceAtLeast(0))
            val order =
                current.getString("order", PlaybackOrder.ORDERED.name) ?: PlaybackOrder.ORDERED.name
            seed["setting/order"] = order
            seed["setting/repeat"] = current.getInt("repeat", 0)
            references(db, List(ids.length()) { ids.getString(it) }, seed)
        }
        edit(db, seed)
    }

    fun opened(db: SqlDatabase) {
        account = meta(db, "account").toLongOrNull() ?: 0L
    }

    private fun meta(db: SqlDatabase, key: String): String =
        db.rawQuery("SELECT value FROM sync_meta WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }

    private fun putMeta(db: SqlDatabase, key: String, value: String) =
        db.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES(?,?)", arrayOf(key, value))

    suspend fun meta(key: String): String = withContext(Dispatchers.IO) { meta(database(), key) }

    suspend fun meta(key: String, value: String) =
        withContext(Dispatchers.IO) { putMeta(database(), key, value) }

    /** Must share the caller's transaction with the local user action. */
    fun edit(db: SqlDatabase, values: Map<String, Any?>) {
        if (values.isEmpty()) return
        val encoded = values.mapValues { wrapped(it.value) }.toMutableMap()
        for (chunk in encoded.keys.toList().chunked(400)) {
            db.rawQuery(
                    "SELECT key,value FROM sync_values WHERE account=? AND key IN(${chunk.joinToString(",") { "?" }})",
                    (listOf(account.toString()) + chunk).toTypedArray(),
                )
                .use { c ->
                    while (c.moveToNext()) {
                        val key = c.getString(0)
                        val old = c.getString(1)
                        if (encoded[key] == old || Record(key, 0, "", old).removesIdentity())
                            encoded.remove(key)
                    }
                }
        }
        if (encoded.isEmpty()) return
        val clock = meta(db, "clock").toLong() + 1
        val writer = meta(db, "device")
        putMeta(db, "clock", clock.toString())
        db.compileStatement(
                "INSERT OR REPLACE INTO sync_values(account,key,clock,writer,value,pending) VALUES(?,?,?,?,?,1)"
            )
            .use { s ->
                for ((key, value) in encoded) {
                    s.bindLong(1, account)
                    s.bindString(2, key)
                    s.bindLong(3, clock)
                    s.bindString(4, writer)
                    s.bindString(5, value)
                    s.executeInsert()
                }
            }
        changes.trySend(Unit)
    }

    fun descriptor(db: SqlDatabase, id: String): JSONObject? =
        db.rawQuery(
                "SELECT id,chat,message,title,artist,duration,size,source,date,document,file_key,remote_id FROM tracks WHERE id=?",
                arrayOf(id),
            )
            .use { c ->
                if (!c.moveToFirst()) null else descriptor(c)
            }

    private fun descriptor(c: SqlCursor) =
        JSONObject()
            .put("id", c.getString(0))
            .put("chat", c.getLong(1))
            .put("message", c.getLong(2))
            .put("title", c.getString(3).take(8192))
            .put("artist", c.getString(4).take(8192))
            .put("duration", c.getInt(5))
            .put("size", c.getLong(6))
            .put("source", c.getString(7).take(8192))
            .put("date", c.getLong(8))
            .put("document", c.getInt(9) == 1)
            .put("key", c.getString(10))
            .put("remote", c.getString(11))

    fun references(db: SqlDatabase, ids: Collection<String>, values: MutableMap<String, Any?>) {
        for (chunk in ids.distinct().chunked(400)) db.rawQuery(
                "SELECT id,chat,message,title,artist,duration,size,source,date,document,file_key,remote_id FROM tracks WHERE id IN(${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray(),
            )
            .use { c -> while (c.moveToNext()) values["track/${c.getString(0)}"] = descriptor(c) }
    }

    fun playlistId(db: SqlDatabase, id: Long): String =
        db.rawQuery(
                "SELECT sync_id FROM playlists WHERE id=? AND account=?",
                arrayOf(id.toString(), account.toString()),
            )
            .use {
                check(it.moveToFirst())
                it.getString(0)
            }

    fun playlistTracks(db: SqlDatabase, id: Long): List<String> =
        db.rawQuery(
                "SELECT track FROM playlist_tracks WHERE playlist=? ORDER BY position",
                arrayOf(id.toString()),
            )
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    suspend fun bind(user: Long) =
        withContext(Dispatchers.IO) {
            val db = database()
            db.transaction {
                if (account != user) {
                    val unbound = records(this, false, user = 0)
                    val existing = records(this, false, user = user).associateBy { it.key }
                    compileStatement(
                            "INSERT OR REPLACE INTO sync_values(account,key,clock,writer,value,pending) VALUES(?,?,?,?,?,1)"
                        )
                        .use { s ->
                            for (r in unbound) {
                                if (!r.supersedes(existing[r.key])) continue
                                s.bindLong(1, user)
                                s.bindString(2, r.key)
                                s.bindLong(3, r.clock)
                                s.bindString(4, r.writer)
                                s.bindString(5, r.value)
                                s.executeInsert()
                            }
                        }
                    execSQL("DELETE FROM sync_values WHERE account=0")
                    execSQL("UPDATE playlists SET account=? WHERE account=0", arrayOf(user))
                    putMeta(this, "account", user.toString())
                    account = user
                    execSQL("UPDATE tracks SET liked=0")
                }
                materialize(this, records(this, false).map { it.key }.toSet())
            }
            reload()
        }

    suspend fun detach() =
        withContext(Dispatchers.IO) {
            database().transaction {
                putMeta(this, "account", "0")
                account = 0L
                execSQL("UPDATE tracks SET liked=0")
                materialize(this, records(this, false).map { it.key }.toSet())
            }
            reload()
        }

    private fun records(
        db: SqlDatabase,
        pending: Boolean,
        limit: Int = Int.MAX_VALUE,
        user: Long = account,
    ): List<Record> =
        db.rawQuery(
                "SELECT key,clock,writer,value FROM sync_values WHERE account=?${if (pending) " AND pending=1" else ""} ORDER BY key LIMIT ?",
                arrayOf(user.toString(), limit.toString()),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Record(c.getString(0), c.getLong(1), c.getString(2), c.getString(3))
                    )
                }
            }

    suspend fun records(pending: Boolean = false, limit: Int = Int.MAX_VALUE) =
        withContext(Dispatchers.IO) { records(database(), pending, limit) }

    suspend fun acknowledge(sent: List<Record>) =
        withContext(Dispatchers.IO) {
            database().transaction {
                compileStatement(
                        "UPDATE sync_values SET pending=0 WHERE account=? AND key=? AND clock=? AND writer=?"
                    )
                    .use { s ->
                        for (r in sent) {
                            s.bindLong(1, account)
                            s.bindString(2, r.key)
                            s.bindLong(3, r.clock)
                            s.bindString(4, r.writer)
                            s.executeUpdateDelete()
                        }
                    }
            }
        }

    suspend fun merge(incoming: List<Record>): Set<String> =
        withContext(Dispatchers.IO) {
            val changed = linkedSetOf<String>()
            val db = database()
            db.transaction {
                val existing = records(this, false).associateBy { it.key }
                var clock = meta(this, "clock").toLong()
                compileStatement(
                        "INSERT OR REPLACE INTO sync_values(account,key,clock,writer,value,pending) VALUES(?,?,?,?,?,0)"
                    )
                    .use { s ->
                        for (r in incoming) {
                            clock = maxOf(clock, r.clock)
                            if (!r.supersedes(existing[r.key])) continue
                            s.bindLong(1, account)
                            s.bindString(2, r.key)
                            s.bindLong(3, r.clock)
                            s.bindString(4, r.writer)
                            s.bindString(5, r.value)
                            s.executeInsert()
                            changed += r.key
                        }
                    }
                putMeta(this, "clock", clock.toString())
                materialize(this, changed)
            }
            if (changed.isNotEmpty()) reload()
            changed
        }

    suspend fun setting(key: String, value: Any) =
        withContext(Dispatchers.IO) {
            require(key in settings)
            database().transaction { edit(this, mapOf("setting/$key" to value)) }
        }

    suspend fun playback(
        ids: List<String>,
        path: List<Int>,
        cursor: Int,
        position: Long,
        mode: PlaybackMode,
        progress: Map<String, PlaybackProgress?>,
    ) =
        withContext(Dispatchers.IO) {
            database().transaction {
                val values =
                    linkedMapOf<String, Any?>(
                        "queue" to
                            JSONObject()
                                .put("ids", JSONArray(ids))
                                .put("path", JSONArray(path))
                                .put("cursor", cursor)
                                .put("position", position.coerceAtLeast(0)),
                        "setting/order" to mode.order.name,
                        "setting/repeat" to mode.repeat,
                    )
                references(this, ids, values)
                progressValues(progress, values)
                edit(this, values)
            }
        }

    fun progressValues(progress: Map<String, PlaybackProgress?>, values: MutableMap<String, Any?>) {
        for ((id, p) in progress) values["progress/$id"] = p?.let {
            JSONObject()
                .put("position", it.position)
                .put("duration", it.duration)
                .put("key", it.fileKey)
                .put("size", it.size)
        }
    }

    private fun materialize(db: SqlDatabase, changed: Set<String>) {
        if (changed.isEmpty()) return
        val rows = records(db, false).associateBy { it.key }
        fun value(key: String) = rows[key]?.let { JSONObject(it.value).get("v") }
        for (key in changed.filter { it.startsWith("track/") }) {
            val t = value(key) as? JSONObject ?: continue
            val id = t.getString("id")
            val chat = t.getLong("chat")
            if (t.optBoolean("deleted")) {
                db.execSQL(
                    "DELETE FROM playlist_tracks WHERE track=? AND playlist IN(SELECT id FROM playlists WHERE account=?)",
                    arrayOf<Any>(id, account),
                )
                db.execSQL("DELETE FROM tracks WHERE id=? AND path=''", arrayOf(id))
                continue
            }
            db.execSQL(
                "INSERT OR IGNORE INTO tracks(id,chat,message,file,title,artist,duration,size,source,date,document,file_key,remote_id,available,saved,cloud) VALUES(?,?,?,0,?,?,?,?,?,?,?,?,?,?,0,1)",
                arrayOf<Any>(
                    id,
                    chat,
                    t.getLong("message"),
                    t.optString("title"),
                    t.optString("artist"),
                    t.optInt("duration"),
                    t.optLong("size"),
                    t.optString("source"),
                    t.optLong("date"),
                    if (t.optBoolean("document")) 1 else 0,
                    t.optString("key"),
                    t.optString("remote"),
                    if (chat == 0L) 0 else 1,
                ),
            )
            db.execSQL(
                "UPDATE tracks SET liked=? WHERE id=?",
                arrayOf<Any>(if (value("favorite/$id") == true) 1 else 0, id),
            )
        }
        for (key in changed.filter { it.startsWith("favorite/") }) db.execSQL(
            "UPDATE tracks SET liked=? WHERE id=?",
            arrayOf<Any>(if (value(key) == true) 1 else 0, key.removePrefix("favorite/")),
        )
        for (key in changed.filter { it.startsWith("source/") }) {
            val v = value(key) as? JSONObject ?: continue
            val id = key.removePrefix("source/").toLong()
            if (v.getBoolean("enabled"))
                db.execSQL(
                    "INSERT OR IGNORE INTO sources(id,title) VALUES(?,?)",
                    arrayOf<Any>(id, v.getString("title")),
                )
            else db.execSQL("DELETE FROM sources WHERE id=?", arrayOf(id))
        }
        val playlists =
            changed.filter { it.startsWith("playlist/") }.map { it.split('/')[1] }.toSet()
        val playlistRows =
            rows.values.filter { it.key.startsWith("playlist/") }.groupBy { it.key.split('/')[1] }
        for (uid in playlists) {
            val id =
                db.rawQuery("SELECT id FROM playlists WHERE sync_id=?", arrayOf(uid)).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
            if (value("playlist/$uid/deleted") == true) {
                if (id != 0L) {
                    db.execSQL("DELETE FROM playlist_tracks WHERE playlist=?", arrayOf(id))
                    db.execSQL("DELETE FROM playlists WHERE id=?", arrayOf(id))
                }
                continue
            }
            val name = value("playlist/$uid/name") as? String ?: continue
            val local =
                if (id != 0L) id
                else
                    db.insertOrThrow(
                        "playlists",
                        null,
                        Values().apply {
                            put("name", name)
                            put("sync_id", uid)
                            put("account", account)
                        },
                    )
            db.execSQL("UPDATE playlists SET name=? WHERE id=?", arrayOf<Any>(name, local))
            val prefix = "playlist/$uid/member/"
            val members =
                playlistRows[uid]
                    .orEmpty()
                    .filter { it.key.startsWith(prefix) && JSONObject(it.value).get("v") == true }
                    .sortedWith(
                        compareBy<Record> { it.clock }.thenBy { it.writer }.thenBy { it.key }
                    )
                    .map { it.key.removePrefix(prefix) }
            val set = members.toSet()
            val order = value("playlist/$uid/order") as? JSONArray
            val desired =
                if (order == null) emptyList()
                else List(order.length()) { order.getString(it) }.distinct().filter { it in set }
            val desiredSet = desired.toSet()
            val ordered = desired + members.filter { it !in desiredSet }
            db.execSQL("DELETE FROM playlist_tracks WHERE playlist=?", arrayOf(local))
            db.compileStatement(
                    "INSERT INTO playlist_tracks(playlist,track,position) VALUES(?,?,?)"
                )
                .use { s ->
                    ordered.forEachIndexed { index, track ->
                        s.bindLong(1, local)
                        s.bindString(2, track)
                        s.bindLong(3, index.toLong())
                        s.executeInsert()
                    }
                }
        }
        val bookmarks =
            changed.filter { it.startsWith("progress/") }.toSet() +
                changed
                    .filter { it.startsWith("track/") }
                    .map { "progress/${it.removePrefix("track/")}" }
                    .filter { it in rows }
        for (key in bookmarks) {
            val id = key.removePrefix("progress/")
            val p = value(key) as? JSONObject
            if (p == null) db.execSQL("DELETE FROM playback_progress WHERE track=?", arrayOf(id))
            else
                db.execSQL(
                    "INSERT OR REPLACE INTO playback_progress(track,position,duration,file_key,size) SELECT id,?,?,?,? FROM tracks WHERE id=?",
                    arrayOf<Any>(
                        p.getLong("position"),
                        p.getLong("duration"),
                        p.getString("key"),
                        p.getLong("size"),
                        id,
                    ),
                )
        }
    }

    suspend fun preferences(): Map<String, Any> =
        withContext(Dispatchers.IO) {
            records(database(), false)
                .filter { it.key.startsWith("setting/") || it.key == "queue" }
                .associate { it.key.removePrefix("setting/") to JSONObject(it.value).get("v") }
        }
}
