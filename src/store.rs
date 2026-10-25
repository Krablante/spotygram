use crate::{model::*, sync};
use anyhow::{Context, Result, bail, ensure};
use rusqlite::{
    Connection, OptionalExtension, Row, params, params_from_iter, types::Value as SqlValue,
};
use serde::{Serialize, de::DeserializeOwned};
use serde_json::{Value, json};
use std::{
    collections::{HashMap, HashSet},
    path::Path,
    sync::Arc,
    time::Duration,
};
use uuid::Uuid;

pub struct Store {
    pub(crate) db: Connection,
    pub account: i64,
    pub revision: u64,
}

const TRACK_COLUMNS: &str = "t.id,t.chat,t.message,t.file,t.title,t.artist,t.duration,t.size,t.source,t.date,t.art,t.path,t.liked,t.available,t.document,t.file_key,t.remote_id,t.saved,t.profile_user,t.profile_position,EXISTS(SELECT 1 FROM temporary_audio a WHERE a.file_key=t.file_key),COALESCE((SELECT username FROM profiles p WHERE p.id=t.profile_user),'')";

impl Store {
    pub fn connect(path: &Path) -> Result<Self> {
        let db = Connection::open(path)?;
        configure_connection(&db)?;
        db.busy_timeout(Duration::from_secs(5))?;
        db.execute_batch("PRAGMA foreign_keys=ON; PRAGMA cache_size=-2048;")?;
        let account = sync::meta(&db, "account")?.parse().unwrap_or(0);
        Ok(Self {
            db,
            account,
            revision: 1,
        })
    }

    pub fn open(path: &Path) -> Result<Self> {
        let db = Connection::open(path).context("Cannot open the music library")?;
        configure_connection(&db)?;
        db.busy_timeout(Duration::from_secs(5))?;
        db.execute_batch("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA cache_size=-4096;")?;
        let old: i64 = db.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!(
            old <= 9,
            "This library needs a newer version of spotygram rust"
        );
        let tx = db.unchecked_transaction()?;
        if old == 0 {
            tx.execute_batch("CREATE TABLE tracks(id TEXT PRIMARY KEY,chat INTEGER,message INTEGER,file INTEGER,title TEXT,artist TEXT,duration INTEGER,size INTEGER,source TEXT,date INTEGER,art TEXT DEFAULT '',path TEXT DEFAULT '',liked INTEGER DEFAULT 0,available INTEGER DEFAULT 1,document INTEGER DEFAULT 0,file_key TEXT DEFAULT '',remote_id TEXT DEFAULT '',saved INTEGER DEFAULT 0,profile_user INTEGER NOT NULL DEFAULT 0,profile_position INTEGER NOT NULL DEFAULT -1,cloud INTEGER NOT NULL DEFAULT 0);
                CREATE INDEX tracks_file ON tracks(file); CREATE INDEX tracks_chat ON tracks(chat);
                CREATE INDEX tracks_key ON tracks(file_key); CREATE INDEX tracks_path ON tracks(path);
                CREATE INDEX tracks_profile ON tracks(profile_user,profile_position);
                CREATE TABLE sources(id INTEGER PRIMARY KEY,title TEXT,cursor INTEGER DEFAULT 0,complete INTEGER DEFAULT 0,document_cursor INTEGER DEFAULT 0,documents_complete INTEGER DEFAULT 0,newest INTEGER DEFAULT 0,document_newest INTEGER DEFAULT 0);
                CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,sync_id TEXT,account INTEGER NOT NULL DEFAULT 0);
                CREATE UNIQUE INDEX playlists_sync ON playlists(sync_id);
                CREATE TABLE playlist_tracks(playlist INTEGER,track TEXT,position INTEGER,PRIMARY KEY(playlist,track));
                CREATE TABLE downloads(track TEXT PRIMARY KEY,position INTEGER);
                CREATE TABLE temporary_audio(file_key TEXT PRIMARY KEY,remote_id TEXT NOT NULL,path TEXT NOT NULL);
                CREATE TABLE playback_progress(track TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,position INTEGER NOT NULL,duration INTEGER NOT NULL,file_key TEXT NOT NULL,size INTEGER NOT NULL);
                CREATE TABLE profiles(id INTEGER PRIMARY KEY,title TEXT NOT NULL,username TEXT NOT NULL DEFAULT '',photo TEXT NOT NULL DEFAULT '',selected INTEGER NOT NULL DEFAULT 0,audio_count INTEGER NOT NULL DEFAULT 0,loaded_at INTEGER NOT NULL DEFAULT 0,opened_at INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE sync_values(account INTEGER NOT NULL,key TEXT NOT NULL,clock INTEGER NOT NULL,writer TEXT NOT NULL,value TEXT NOT NULL,pending INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(account,key));
                CREATE INDEX sync_pending ON sync_values(account,pending);")?;
            for (key, value) in [
                ("device", Uuid::new_v4().to_string()),
                ("clock", "0".into()),
                ("account", "0".into()),
            ] {
                tx.execute("INSERT INTO sync_meta VALUES(?,?)", params![key, value])?;
            }
        } else if old < 8 {
            bail!(
                "Import this older library through the current stable Spotygram first; its data has not been changed"
            );
        }
        if old < 9 {
            tx.execute_batch("ALTER TABLE tracks ADD COLUMN search_text TEXT NOT NULL DEFAULT ''; ALTER TABLE tracks ADD COLUMN duplicate_group TEXT NOT NULL DEFAULT '';
                CREATE INDEX tracks_date ON tracks(date DESC,id DESC); CREATE INDEX tracks_title ON tracks(title COLLATE NOCASE,id);
                CREATE INDEX tracks_artist ON tracks(artist COLLATE NOCASE,id); CREATE INDEX tracks_duplicate ON tracks(duplicate_group);
                CREATE INDEX membership_track ON playlist_tracks(track,playlist);
                CREATE INDEX membership_position ON playlist_tracks(playlist,position);
                CREATE TABLE local_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE playback_queue(position INTEGER PRIMARY KEY,track TEXT NOT NULL);
                PRAGMA user_version=9;")?;
        }
        tx.execute_batch("CREATE TABLE IF NOT EXISTS local_thumbnails(track TEXT PRIMARY KEY,file INTEGER NOT NULL);")?;
        tx.execute_batch("CREATE TABLE IF NOT EXISTS source_photos(account INTEGER NOT NULL,id INTEGER NOT NULL,path TEXT NOT NULL,PRIMARY KEY(account,id));")?;
        tx.execute("DELETE FROM sync_values WHERE key IN ('queue','setting/speed','setting/order','setting/repeat') OR key LIKE 'progress/%'", [])?;
        tx.commit()?;
        db.execute_batch("DROP INDEX IF EXISTS tracks_title; DROP INDEX IF EXISTS tracks_artist;
            CREATE INDEX IF NOT EXISTS tracks_title_unicode ON tracks(title COLLATE UNICODE,id);
            CREATE INDEX IF NOT EXISTS tracks_artist_unicode ON tracks(artist COLLATE UNICODE,title COLLATE UNICODE,id);")?;
        let has_search: bool = db.query_row(
            "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='track_search')",
            [],
            |r| r.get(0),
        )?;
        if !has_search {
            let tx = db.unchecked_transaction()?;
            tx.execute_batch("CREATE VIRTUAL TABLE track_search USING fts5(search_text,content='tracks',content_rowid='rowid',tokenize='trigram');
                CREATE TRIGGER tracks_search_insert AFTER INSERT ON tracks BEGIN INSERT INTO track_search(rowid,search_text) VALUES(new.rowid,new.search_text); END;
                CREATE TRIGGER tracks_search_delete AFTER DELETE ON tracks BEGIN INSERT INTO track_search(track_search,rowid,search_text) VALUES('delete',old.rowid,old.search_text); END;
                CREATE TRIGGER tracks_search_update AFTER UPDATE OF search_text ON tracks WHEN old.search_text!=new.search_text BEGIN INSERT INTO track_search(track_search,rowid,search_text) VALUES('delete',old.rowid,old.search_text); INSERT INTO track_search(rowid,search_text) VALUES(new.rowid,new.search_text); END;
                INSERT INTO track_search(track_search) VALUES('rebuild');")?;
            tx.commit()?;
        }
        let account = sync::meta(&db, "account")?.parse().unwrap_or(0);
        let mut store = Self {
            db,
            account,
            revision: 1,
        };
        if old < 9 {
            store.prepare_search()?;
            store.rebuild_duplicates()?;
        }
        store.reconcile_files()?;
        Ok(store)
    }

    pub fn setting<T: DeserializeOwned>(&self, key: &str) -> Result<Option<T>> {
        let value: Option<String> = self
            .db
            .query_row("SELECT value FROM local_settings WHERE key=?", [key], |r| {
                r.get(0)
            })
            .optional()?;
        value
            .map(|s| serde_json::from_str(&s).map_err(Into::into))
            .transpose()
    }

    pub fn forget_native_ids(&self) -> Result<()> {
        let tx = self.db.unchecked_transaction()?;
        tx.execute(
            "UPDATE tracks SET file=0 WHERE file!=0 AND (chat!=0 OR profile_user!=0)",
            [],
        )?;
        tx.execute("DELETE FROM local_thumbnails", [])?;
        tx.commit()?;
        Ok(())
    }

    pub fn put_setting(&self, key: &str, value: &impl Serialize) -> Result<()> {
        self.db.execute("INSERT INTO local_settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", params![key, serde_json::to_string(value)?])?;
        Ok(())
    }

    pub fn settings(&self) -> Result<Value> {
        let mut values = json!({"theme":"system","language":"system","hide_duplicates":true,"resume_long_audio":true,"resume_minutes":30,"wifi_only":false,"discard_listened":false,"auto_updates":true});
        let mut statement = self
            .db
            .prepare("SELECT key,value FROM local_settings WHERE key NOT IN ('queue_state')")?;
        for row in
            statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        {
            let (key, value) = row?;
            values[&key] = serde_json::from_str(&value)?;
        }
        Ok(values)
    }

    pub fn set_preference(&mut self, key: &str, value: Value) -> Result<()> {
        let tx = self.db.unchecked_transaction()?;
        if sync::SETTINGS.contains(&key) {
            sync::validate(&format!("setting/{key}"), &value)?;
        }
        tx.execute("INSERT INTO local_settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", params![key, value.to_string()])?;
        if sync::SETTINGS.contains(&key) {
            sync::edit(&tx, self.account, [(format!("setting/{key}"), value)])?;
        }
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn track(&self, id: &str) -> Result<Option<Track>> {
        Ok(self
            .db
            .query_row(
                &format!("SELECT {TRACK_COLUMNS} FROM tracks t WHERE t.id=?"),
                [id],
                read_track,
            )
            .optional()?)
    }

    pub fn upsert(&mut self, tracks: &[Track]) -> Result<()> {
        if tracks.is_empty() {
            return Ok(());
        }
        let tx = self.db.unchecked_transaction()?;
        {
            let mut stmt = tx.prepare_cached("INSERT INTO tracks(id,chat,message,file,title,artist,duration,size,source,date,art,path,liked,available,document,file_key,remote_id,saved,profile_user,profile_position,search_text,duplicate_group) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET file=excluded.file,title=excluded.title,artist=excluded.artist,duration=excluded.duration,size=excluded.size,source=excluded.source,date=excluded.date,art=CASE WHEN excluded.art!='' THEN excluded.art WHEN tracks.file_key=excluded.file_key THEN tracks.art ELSE '' END,path=CASE WHEN excluded.path!='' THEN excluded.path WHEN tracks.file_key=excluded.file_key THEN tracks.path ELSE '' END,available=excluded.available,document=excluded.document,file_key=excluded.file_key,remote_id=excluded.remote_id,profile_position=excluded.profile_position,search_text=excluded.search_text")?;
            for t in tracks {
                stmt.execute(params![
                    t.id,
                    t.chat,
                    t.message,
                    t.file,
                    t.title,
                    t.artist,
                    t.duration,
                    t.size,
                    t.source,
                    t.date,
                    t.art,
                    t.path,
                    t.liked,
                    t.available,
                    t.document,
                    t.key,
                    t.remote,
                    t.saved,
                    t.profile,
                    t.profile_position,
                    t.search_text(),
                    t.id
                ])?;
            }
        }
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    fn prepare_search(&mut self) -> Result<()> {
        let rows = {
            let mut s = self
                .db
                .prepare("SELECT id,title,artist,source FROM tracks WHERE search_text=''")?;
            s.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    format!(
                        "{} {} {}",
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, String>(3)?
                    )
                    .to_lowercase(),
                ))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?
        };
        let tx = self.db.unchecked_transaction()?;
        for (id, search) in rows {
            tx.execute(
                "UPDATE tracks SET search_text=? WHERE id=?",
                params![search, id],
            )?;
        }
        tx.commit()?;
        Ok(())
    }

    pub fn rebuild_duplicates(&mut self) -> Result<()> {
        let mut ids = Vec::new();
        let mut parents = Vec::new();
        let mut known: HashMap<String, usize> = HashMap::new();
        let mut old_groups = Vec::new();
        {
            let mut stmt = self.db.prepare("SELECT id,file_key,path,title,artist,duration,size,duplicate_group FROM tracks ORDER BY id")?;
            let mut rows = stmt.query([])?;
            while let Some(r) = rows.next()? {
                let index = ids.len();
                ids.push(r.get::<_, String>(0)?);
                old_groups.push(r.get::<_, String>(7)?);
                parents.push(index);
                let key: String = r.get(1)?;
                let path: String = r.get(2)?;
                let duration: i64 = r.get(5)?;
                let size: i64 = r.get(6)?;
                let mut keys = Vec::with_capacity(3);
                if !key.is_empty() {
                    keys.push(format!("k:{key}"));
                }
                if !path.is_empty() {
                    keys.push(format!("p:{path}"));
                }
                if duration > 0 && size > 0 {
                    let title: String = r.get(3)?;
                    let artist: String = r.get(4)?;
                    keys.push(format!(
                        "m:{}|{}|{duration}|{size}",
                        title
                            .split_whitespace()
                            .collect::<Vec<_>>()
                            .join(" ")
                            .to_lowercase(),
                        artist
                            .split_whitespace()
                            .collect::<Vec<_>>()
                            .join(" ")
                            .to_lowercase()
                    ));
                }
                for key in keys {
                    if let Some(&previous) = known.get(&key) {
                        let a = root(&mut parents, index);
                        let b = root(&mut parents, previous);
                        parents[a.max(b)] = a.min(b);
                    } else {
                        known.insert(key, index);
                    }
                }
            }
        }
        drop(known);
        let tx = self.db.unchecked_transaction()?;
        for index in 0..ids.len() {
            let group = root(&mut parents, index);
            if old_groups[index] != ids[group] {
                tx.execute(
                    "UPDATE tracks SET duplicate_group=? WHERE id=?",
                    params![ids[group], ids[index]],
                )?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    fn query_sql(&self, query: &Query) -> (String, Vec<SqlValue>, String) {
        let library = format!(
            "(t.chat=0 AND t.profile_user=0 OR t.liked=1 OR EXISTS(SELECT 1 FROM playlist_tracks m JOIN playlists p ON p.id=m.playlist WHERE m.track=t.id AND p.account={}) OR EXISTS(SELECT 1 FROM sources s WHERE s.id=t.chat AND t.profile_user=0) OR t.profile_position>=0 AND EXISTS(SELECT 1 FROM profiles p WHERE p.id=t.profile_user AND p.selected=1))",
            self.account
        );
        let mut args = Vec::new();
        let mut clauses = match query.screen {
            Screen::Favorites => vec![format!("EXISTS(SELECT 1 FROM tracks f WHERE f.duplicate_group=t.duplicate_group AND f.liked=1) AND {library}")],
            Screen::OnDevice => vec!["t.path!='' AND NOT EXISTS(SELECT 1 FROM temporary_audio a WHERE a.file_key=t.file_key)".into()],
            Screen::Playlist(id) => { args.push(id.into()); vec![format!("EXISTS(SELECT 1 FROM playlist_tracks m JOIN playlists p ON p.id=m.playlist WHERE m.track=t.id AND m.playlist=? AND p.account={})",self.account)] },
            Screen::PlaylistEditor(Some(id))=>{args.push(id.into());vec![library.to_string(),"NOT EXISTS(SELECT 1 FROM playlist_tracks m WHERE m.track=t.id AND m.playlist=?)".into()]},
            Screen::Profile(id) => { args.push(id.into()); vec!["t.profile_user=? AND t.profile_position>=0".into()] },
            Screen::Queue => vec!["1".into()],
            _ => vec![library.to_string()],
        };
        if let Some((source, id)) = query.source.split_once(':') {
            if let Ok(id) = id.parse::<i64>() {
                clauses.push(
                    if source == "profile" {
                        "t.profile_user=?"
                    } else {
                        "t.profile_user=0 AND t.chat=?"
                    }
                    .into(),
                );
                args.push(id.into());
            }
        }
        if !query.search.trim().is_empty() {
            let search = query.search.trim().to_lowercase();
            if search.chars().count() >= 3 {
                clauses.push(
                    "t.rowid IN(SELECT rowid FROM track_search WHERE track_search MATCH ?)".into(),
                );
                args.push(format!("\"{}\"", search.replace('"', "\"\"")).into());
            } else {
                clauses.push("t.search_text LIKE ? ESCAPE '\\'".into());
                let escaped = search
                    .replace('\\', "\\\\")
                    .replace('%', "\\%")
                    .replace('_', "\\_");
                args.push(format!("%{escaped}%").into());
            }
        }
        let filter = clauses.join(" AND ");
        let dedup = query.duplicates
            && !matches!(
                query.screen,
                Screen::Playlist(_)
                    | Screen::Profile(_)
                    | Screen::Queue
                    | Screen::PlaylistEditor(_)
            );
        let cte = if dedup {
            format!(
                "WITH candidates AS (SELECT t.id,t.duplicate_group,t.liked,t.saved,t.path,t.available,EXISTS(SELECT 1 FROM temporary_audio a WHERE a.file_key=t.file_key) AS temporary FROM tracks t WHERE {filter}), selected AS (SELECT id,ROW_NUMBER() OVER(PARTITION BY duplicate_group ORDER BY liked DESC,(path!='' AND temporary=0) DESC,(path!='') DESC,available DESC,id) AS rank FROM candidates) SELECT {TRACK_COLUMNS} FROM tracks t JOIN selected ON selected.id=t.id AND selected.rank=1"
            )
        } else if query.screen == Screen::Queue {
            format!(
                "SELECT {TRACK_COLUMNS},q.position FROM playback_queue q JOIN tracks t ON t.id=q.track WHERE {filter}"
            )
        } else {
            format!("SELECT {TRACK_COLUMNS} FROM tracks t WHERE {filter}")
        };
        let sort = match query.screen {
            Screen::Playlist(id) => format!(
                "(SELECT position FROM playlist_tracks m WHERE m.track=t.id AND m.playlist={id})"
            ),
            Screen::Profile(_) => "t.profile_position".into(),
            Screen::Queue => "q.position".into(),
            _ => match query.sort {
                Sort::Newest => "t.date DESC,t.id DESC",
                Sort::Oldest => "t.date,t.id",
                Sort::Title => "t.title COLLATE UNICODE,t.id",
                Sort::Artist => "t.artist COLLATE UNICODE,t.title COLLATE UNICODE,t.id",
                Sort::Duration => "t.duration DESC,t.id",
            }
            .into(),
        };
        (cte, args, sort)
    }

    pub fn page(&self, query: &Query, offset: usize) -> Result<TrackPage> {
        let (sql, mut args, sort) = self.query_sql(query);
        let total: usize = self.db.query_row(
            &format!("SELECT COUNT(*) FROM ({sql})"),
            params_from_iter(&args),
            |r| r.get(0),
        )?;
        let offset = offset.min(total.saturating_sub(1)) / 128 * 128;
        args.push(256_i64.into());
        args.push((offset as i64).into());
        let mut stmt = self
            .db
            .prepare(&format!("{sql} ORDER BY {sort} LIMIT ? OFFSET ?"))?;
        let tracks = stmt
            .query_map(params_from_iter(&args), |r| {
                let mut track = read_track(r)?;
                if query.screen == Screen::Queue {
                    track.queue_position = Some(r.get(22)?);
                }
                Ok(track)
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(TrackPage {
            query: query.clone(),
            revision: self.revision,
            total,
            offset,
            tracks: Arc::new(tracks),
        })
    }

    pub fn query_ids(&self, query: &Query) -> Result<Vec<String>> {
        let (sql, args, sort) = self.query_sql(query);
        let mut stmt = self
            .db
            .prepare(&format!("SELECT id FROM ({sql} ORDER BY {sort})"))?;
        Ok(stmt
            .query_map(params_from_iter(&args), |r| r.get(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn sources(&self) -> Result<Vec<Source>> {
        let mut s = self.db.prepare("SELECT s.id,s.title,(SELECT COUNT(*) FROM tracks t WHERE t.chat=s.id AND t.profile_user=0),s.complete AND s.documents_complete,COALESCE((SELECT path FROM source_photos p WHERE p.account=? AND p.id=s.id),'') FROM sources s ORDER BY s.title COLLATE NOCASE")?;
        let mut result = s
            .query_map([self.account], |r| {
                Ok(Source {
                    id: r.get(0)?,
                    title: r.get(1)?,
                    count: r.get(2)?,
                    complete: r.get(3)?,
                    selected: true,
                    photo: r.get(4)?,
                    ..Default::default()
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let mut s = self.db.prepare("SELECT id,title,username,photo,selected,audio_count FROM profiles WHERE selected=1 OR opened_at>0 OR id=? ORDER BY selected DESC,title COLLATE NOCASE")?;
        for row in s.query_map([self.account], |r| {
            Ok(Source {
                id: r.get(0)?,
                title: r.get(1)?,
                username: r.get(2)?,
                photo: r.get(3)?,
                selected: r.get(4)?,
                count: r.get(5)?,
                profile: true,
                complete: true,
                ..Default::default()
            })
        })? {
            result.push(row?);
        }
        Ok(result)
    }

    pub fn playlists(&self) -> Result<Vec<Playlist>> {
        let mut s = self.db.prepare("SELECT p.id,p.sync_id,p.name,(SELECT COUNT(*) FROM playlist_tracks m JOIN tracks t ON t.id=m.track WHERE m.playlist=p.id),(SELECT json_group_array(json_object('title',title,'path',art)) FROM (SELECT t.title,t.art FROM playlist_tracks m JOIN tracks t ON t.id=m.track WHERE m.playlist=p.id ORDER BY m.position LIMIT 4)) FROM playlists p WHERE p.account=? ORDER BY p.name COLLATE NOCASE")?;
        Ok(s.query_map([self.account], |r| {
            Ok(Playlist {
                id: r.get(0)?,
                uid: r.get(1)?,
                name: r.get(2)?,
                count: r.get(3)?,
                covers: serde_json::from_str(&r.get::<_, String>(4)?).unwrap_or_default(),
            })
        })?
        .collect::<rusqlite::Result<_>>()?)
    }

    pub fn select_source(&mut self, source: &Source, selected: bool) -> Result<()> {
        let tx = self.db.unchecked_transaction()?;
        let key = if source.profile {
            tx.execute("INSERT INTO profiles(id,title,username,selected) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET selected=excluded.selected,title=excluded.title,username=excluded.username",params![source.id,source.title,source.username,selected])?;
            format!("profile/{}", source.id)
        } else {
            if selected {
                tx.execute("INSERT INTO sources(id,title) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title",params![source.id,source.title])?;
            } else {
                tx.execute("DELETE FROM sources WHERE id=?", [source.id])?;
            }
            format!("source/{}", source.id)
        };
        sync::edit(
            &tx,
            self.account,
            [(
                key,
                json!({"enabled":selected,"title":source.title,"username":source.username}),
            )],
        )?;
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn favorite(&mut self, ids: &[String], liked: bool) -> Result<()> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let mut affected: std::collections::HashSet<String> = ids.iter().cloned().collect();
        if !liked && self.setting::<bool>("hide_duplicates")?.unwrap_or(true) {
            let mut statement=tx.prepare("SELECT id FROM tracks WHERE duplicate_group=(SELECT duplicate_group FROM tracks WHERE id=?)")?;
            for id in ids {
                for row in statement.query_map([id], |r| r.get::<_, String>(0))? {
                    affected.insert(row?);
                }
            }
        }
        let mut edits = Vec::new();
        for id in &affected {
            let t = self.track(id)?.context("Recording no longer exists")?;
            edits.push((format!("track/{id}"), t.descriptor()));
            edits.push((format!("favorite/{id}"), json!(liked)));
            tx.execute("UPDATE tracks SET liked=? WHERE id=?", params![liked, id])?;
        }
        sync::edit(&tx, self.account, edits)?;
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn playlist_create(&mut self, name: &str, ids: &[String]) -> Result<i64> {
        ensure!(
            !name.trim().is_empty() && name.chars().count() <= 80,
            "Enter a playlist name of up to 80 characters"
        );
        let uid = Uuid::new_v4().to_string();
        let tx = self.db.unchecked_transaction()?;
        tx.execute(
            "INSERT INTO playlists(name,sync_id,account) VALUES(?,?,?)",
            params![name.trim(), uid, self.account],
        )?;
        let id = tx.last_insert_rowid();
        let mut edits = vec![
            (format!("playlist/{uid}/name"), json!(name.trim())),
            (format!("playlist/{uid}/order"), json!(ids)),
        ];
        for track in ids {
            let track = self.track(track)?.context("Recording no longer exists")?;
            edits.push((format!("track/{}", track.id), track.descriptor()));
            edits.push((format!("playlist/{uid}/member/{}", track.id), json!(true)));
        }
        write_members(&tx, id, ids)?;
        sync::edit(&tx, self.account, edits)?;
        tx.commit()?;
        self.revision += 1;
        Ok(id)
    }

    fn playlist_uid(&self, id: i64) -> Result<String> {
        Ok(self.db.query_row(
            "SELECT sync_id FROM playlists WHERE id=? AND account=?",
            params![id, self.account],
            |r| r.get(0),
        )?)
    }

    pub fn playlist_rename(&mut self, id: i64, name: &str) -> Result<()> {
        ensure!(
            !name.trim().is_empty() && name.chars().count() <= 80,
            "Enter a playlist name of up to 80 characters"
        );
        let uid = self.playlist_uid(id)?;
        let tx = self.db.unchecked_transaction()?;
        tx.execute(
            "UPDATE playlists SET name=? WHERE id=?",
            params![name.trim(), id],
        )?;
        sync::edit(
            &tx,
            self.account,
            [(format!("playlist/{uid}/name"), json!(name.trim()))],
        )?;
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn playlist_delete(&mut self, id: i64) -> Result<()> {
        let uid = self.playlist_uid(id)?;
        let tx = self.db.unchecked_transaction()?;
        tx.execute("DELETE FROM playlist_tracks WHERE playlist=?", [id])?;
        tx.execute("DELETE FROM playlists WHERE id=?", [id])?;
        sync::edit(
            &tx,
            self.account,
            [(format!("playlist/{uid}/deleted"), json!(true))],
        )?;
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn playlist_members(&self, id: i64) -> Result<Vec<String>> {
        let mut s = self
            .db
            .prepare("SELECT track FROM playlist_tracks WHERE playlist=? ORDER BY position")?;
        Ok(s.query_map([id], |r| r.get(0))?
            .collect::<rusqlite::Result<_>>()?)
    }

    pub fn playlist_edit(&mut self, id: i64, ids: &[String], add: bool) -> Result<()> {
        let uid = self.playlist_uid(id)?;
        let mut members = self.playlist_members(id)?;
        let mut member_ids: HashSet<_> = if add {
            members.iter().cloned().collect()
        } else {
            HashSet::new()
        };
        if !add {
            let removed: HashSet<_> = ids.iter().collect();
            members.retain(|id| !removed.contains(id));
        }
        let tx = self.db.unchecked_transaction()?;
        let mut edits = Vec::new();
        for track in ids {
            if add {
                let t = self.track(track)?.context("Recording no longer exists")?;
                if member_ids.insert(track.clone()) {
                    members.push(track.clone());
                }
                edits.push((format!("track/{track}"), t.descriptor()));
            }
            edits.push((format!("playlist/{uid}/member/{track}"), json!(add)));
        }
        write_members(&tx, id, &members)?;
        edits.push((format!("playlist/{uid}/order"), json!(members)));
        sync::edit(&tx, self.account, edits)?;
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn playlist_move(&mut self, id: i64, from: usize, to: usize) -> Result<()> {
        let uid = self.playlist_uid(id)?;
        let mut members = self.playlist_members(id)?;
        ensure!(
            from < members.len() && to < members.len(),
            "Playlist changed; try again"
        );
        let t = members.remove(from);
        members.insert(to, t);
        let tx = self.db.unchecked_transaction()?;
        write_members(&tx, id, &members)?;
        sync::edit(
            &tx,
            self.account,
            [(format!("playlist/{uid}/order"), json!(members))],
        )?;
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn save_queue(&self, queue: &Queue, structural: bool) -> Result<()> {
        let tx = self.db.unchecked_transaction()?;
        if structural {
            tx.execute("DELETE FROM playback_queue", [])?;
            let mut s = tx.prepare_cached("INSERT INTO playback_queue VALUES(?,?)")?;
            for (index, id) in queue.ids.iter().enumerate() {
                s.execute(params![index as i64, id])?;
            }
        }
        tx.execute("INSERT INTO local_settings VALUES('queue_state',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",[serde_json::to_string(queue)?])?;
        tx.commit()?;
        Ok(())
    }

    pub fn load_queue(&self) -> Result<Queue> {
        let mut queue = self.setting::<Queue>("queue_state")?.unwrap_or_default();
        let mut s = self
            .db
            .prepare("SELECT track FROM playback_queue ORDER BY position")?;
        queue.ids = s
            .query_map([], |r| r.get(0))?
            .collect::<rusqlite::Result<_>>()?;
        queue.index = queue.index.min(queue.ids.len().saturating_sub(1));
        queue.speed = queue.speed.clamp(0.3, 4.);
        queue.volume = queue.volume.clamp(0., 100.);
        if queue.shuffled.len() != queue.ids.len() {
            queue.shuffled.clear();
        }
        Ok(queue)
    }

    pub fn bookmark(&self, t: &Track, position: f64, duration: f64) -> Result<()> {
        let threshold = self.setting::<u64>("resume_minutes")?.unwrap_or(30) as f64 * 60.;
        let enabled = self.setting::<bool>("resume_long_audio")?.unwrap_or(true);
        if enabled && duration >= threshold && position > 0. && position < duration - 2. {
            self.db.execute("INSERT INTO playback_progress VALUES(?,?,?,?,?) ON CONFLICT(track) DO UPDATE SET position=excluded.position,duration=excluded.duration,file_key=excluded.file_key,size=excluded.size",params![t.id,(position*1000.) as i64,(duration*1000.) as i64,t.key,t.size])?;
        } else {
            self.db
                .execute("DELETE FROM playback_progress WHERE track=?", [&t.id])?;
        }
        Ok(())
    }

    pub fn resume_position(&self, t: &Track) -> Result<f64> {
        if !self.setting::<bool>("resume_long_audio")?.unwrap_or(true) {
            return Ok(0.);
        }
        let row: Option<(i64, i64, String, i64)> = self
            .db
            .query_row(
                "SELECT position,duration,file_key,size FROM playback_progress WHERE track=?",
                [&t.id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
            )
            .optional()?;
        let threshold = self.setting::<u64>("resume_minutes")?.unwrap_or(30) as i64 * 60_000;
        Ok(row
            .filter(|(p, d, k, s)| {
                *p > 0 && p < d && *d >= threshold && k == &t.key && *s == t.size
            })
            .map(|r| r.0 as f64 / 1000.)
            .unwrap_or(0.))
    }

    pub fn enqueue_downloads(&mut self, ids: &[String]) -> Result<()> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let mut position: i64 =
            tx.query_row("SELECT COALESCE(MAX(position),0) FROM downloads", [], |r| {
                r.get(0)
            })?;
        for id in ids {
            let track = self.track(id)?.context("Recording no longer exists")?;
            if track.local() {
                tx.execute(
                    "UPDATE tracks SET saved=1 WHERE id=? OR (file_key!='' AND file_key=?)",
                    params![id, track.key],
                )?;
                tx.execute("DELETE FROM temporary_audio WHERE file_key=?", [&track.key])?;
                tx.execute("DELETE FROM downloads WHERE track=?", [id])?;
            } else {
                ensure!(
                    !track.imported(),
                    "This imported recording is only available on its original device"
                );
                position += 1;
                tx.execute(
                    "INSERT OR IGNORE INTO downloads VALUES(?,?)",
                    params![id, position],
                )?;
            }
        }
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn downloads(&self) -> Result<Vec<String>> {
        let mut s = self
            .db
            .prepare("SELECT track FROM downloads ORDER BY position")?;
        Ok(s.query_map([], |r| r.get(0))?
            .collect::<rusqlite::Result<_>>()?)
    }

    pub fn file_complete(&mut self, f: &FileState) -> Result<bool> {
        if !valid_copy(&f.path, f.size) {
            return Ok(false);
        }
        let changed = self.db.execute(
            "UPDATE tracks SET path=? WHERE file=? OR (file_key!='' AND file_key=?)",
            params![f.path, f.id, f.key],
        )? > 0;
        if changed {
            self.revision += 1;
        }
        Ok(changed)
    }

    pub fn storage(&self) -> Result<(i64, usize)> {
        Ok(self.db.query_row("SELECT COALESCE(SUM(size),0),COUNT(*) FROM (SELECT path,MAX(size) size FROM tracks WHERE path!='' AND NOT EXISTS(SELECT 1 FROM temporary_audio a WHERE a.file_key=tracks.file_key) GROUP BY path)",[],|r|Ok((r.get(0)?,r.get(1)?)))?)
    }

    pub fn delete_import(&mut self, t: &Track) -> Result<()> {
        ensure!(t.imported(), "This recording is not a local import");
        let root = Path::new(
            self.db
                .path()
                .context("Cannot resolve the library directory")?,
        )
        .parent()
        .context("Cannot resolve the library directory")?
        .join("imports");
        ensure!(
            t.path.is_empty() || crate::platform::is_owned(Path::new(&t.path), &root),
            "The imported recording is outside app-owned storage; its file has been preserved"
        );
        if !t.path.is_empty() && Path::new(&t.path).exists() {
            std::fs::remove_file(&t.path).context("Cannot remove the imported file")?;
        }
        let tx = self.db.unchecked_transaction()?;
        let mut descriptor = t.descriptor();
        descriptor["deleted"] = json!(true);
        let mut edits = vec![
            (format!("track/{}", t.id), descriptor),
            (format!("favorite/{}", t.id), json!(false)),
        ];
        {
            let mut statement = tx.prepare("SELECT p.sync_id FROM playlists p JOIN playlist_tracks m ON m.playlist=p.id WHERE m.track=? AND p.account=?")?;
            for uid in
                statement.query_map(params![t.id, self.account], |r| r.get::<_, String>(0))?
            {
                edits.push((format!("playlist/{}/member/{}", uid?, t.id), json!(false)));
            }
        }
        sync::edit(&tx, self.account, edits)?;
        tx.execute("DELETE FROM playlist_tracks WHERE track=?", [&t.id])?;
        tx.execute("DELETE FROM downloads WHERE track=?", [&t.id])?;
        tx.execute("DELETE FROM tracks WHERE id=?", [&t.id])?;
        tx.commit()?;
        if !t.art.is_empty()
            && crate::platform::is_owned(Path::new(&t.art), &root.with_file_name("artwork"))
        {
            let referenced: bool = self.db.query_row(
                "SELECT EXISTS(SELECT 1 FROM tracks WHERE art=?)",
                [&t.art],
                |r| r.get(0),
            )?;
            if !referenced {
                let _ = std::fs::remove_file(&t.art);
            }
        }
        self.reconcile_files()?;
        self.revision += 1;
        Ok(())
    }

    pub fn remove_copy(&mut self, t: &Track) -> Result<()> {
        let tx = self.db.unchecked_transaction()?;
        tx.execute("DELETE FROM temporary_audio WHERE file_key IN(SELECT file_key FROM tracks WHERE (path!='' AND path=?) OR (file_key!='' AND file_key=?))", params![t.path,t.key])?;
        tx.execute("UPDATE tracks SET path='',saved=0 WHERE (path!='' AND path=?) OR (file_key!='' AND file_key=?)", params![t.path,t.key])?;
        tx.commit()?;
        self.revision += 1;
        Ok(())
    }

    pub fn reconcile_files(&mut self) -> Result<()> {
        let mut invalid = Vec::new();
        {
            let mut s = self
                .db
                .prepare("SELECT DISTINCT path,size FROM tracks WHERE path!=''")?;
            for row in s.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))? {
                let (path, size) = row?;
                if !valid_copy(&path, size) {
                    invalid.push(path);
                }
            }
        }
        for path in invalid {
            self.db
                .execute("UPDATE tracks SET path='' WHERE path=?", [path])?;
        }
        Ok(())
    }

    pub fn prune_profiles(&mut self, current: i64) -> Result<()> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let old = {
            let mut statement=tx.prepare("SELECT id FROM profiles WHERE selected=0 AND loaded_at>0 AND id!=? AND id NOT IN(SELECT id FROM profiles ORDER BY opened_at DESC LIMIT 12)")?;
            statement
                .query_map([current], |r| r.get::<_, i64>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        for user in old {
            tx.execute("DELETE FROM local_thumbnails WHERE track IN(SELECT id FROM tracks WHERE profile_user=? AND liked=0 AND path='' AND id NOT IN(SELECT track FROM playlist_tracks UNION SELECT track FROM playback_queue UNION SELECT track FROM downloads))",[user])?;
            tx.execute("DELETE FROM tracks WHERE profile_user=? AND liked=0 AND path='' AND id NOT IN(SELECT track FROM playlist_tracks UNION SELECT track FROM playback_queue UNION SELECT track FROM downloads)",[user])?;
            tx.execute(
                "UPDATE tracks SET profile_position=-1 WHERE profile_user=?",
                [user],
            )?;
            tx.execute(
                "UPDATE profiles SET loaded_at=0,opened_at=0,audio_count=0 WHERE id=?",
                [user],
            )?;
        }
        tx.commit()?;
        Ok(())
    }
}

pub(crate) fn read_track(r: &Row<'_>) -> rusqlite::Result<Track> {
    Ok(Track {
        id: r.get(0)?,
        chat: r.get(1)?,
        message: r.get(2)?,
        file: r.get(3)?,
        title: r.get(4)?,
        artist: r.get(5)?,
        duration: r.get(6)?,
        size: r.get(7)?,
        source: r.get(8)?,
        date: r.get(9)?,
        art: r.get(10)?,
        path: r.get(11)?,
        liked: r.get(12)?,
        available: r.get(13)?,
        document: r.get(14)?,
        key: r.get(15)?,
        remote: r.get(16)?,
        saved: r.get(17)?,
        profile: r.get(18)?,
        profile_position: r.get(19)?,
        temporary: r.get(20)?,
        username: r.get(21)?,
        queue_position: None,
    })
}

pub(crate) fn write_members(db: &Connection, id: i64, members: &[String]) -> Result<()> {
    db.execute("DELETE FROM playlist_tracks WHERE playlist=?", [id])?;
    let mut s = db.prepare_cached("INSERT INTO playlist_tracks VALUES(?,?,?)")?;
    for (position, track) in members.iter().enumerate() {
        s.execute(params![id, track, position as i64])?;
    }
    Ok(())
}

fn root(parents: &mut [usize], mut index: usize) -> usize {
    while parents[index] != index {
        parents[index] = parents[parents[index]];
        index = parents[index];
    }
    index
}

fn configure_connection(db: &Connection) -> Result<()> {
    db.create_collation("UNICODE", |a, b| {
        a.chars()
            .flat_map(char::to_lowercase)
            .cmp(b.chars().flat_map(char::to_lowercase))
    })?;
    Ok(())
}
