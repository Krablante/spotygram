use crate::{
    model::*,
    store::{Store, write_members},
};
use anyhow::{Context, Result, bail, ensure};
use regex::Regex;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    sync::LazyLock,
};
use uuid::Uuid;

pub const FORMAT: i64 = 3;
pub const SETTINGS: &[&str] = &[
    "theme",
    "language",
    "hide_duplicates",
    "resume_long_audio",
    "resume_minutes",
    "wifi_only",
    "discard_listened",
];
static TRACK_ID: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"^(?:-?[0-9]{1,16}:[0-9]{1,16}|local:[a-f0-9-]{36}|profile:[0-9]{1,16}:[a-f0-9]{64})$",
    )
    .unwrap()
});

#[derive(Clone, Debug)]
pub struct Record {
    pub key: String,
    pub clock: i64,
    pub writer: String,
    pub value: Value,
}

impl Record {
    pub fn row(&self) -> Value {
        json!([self.key, self.clock, self.writer, self.value])
    }
    pub fn deleted(&self) -> bool {
        if self.key.ends_with("/deleted") {
            self.value == true
        } else {
            self.key.starts_with("track/") && boolean(&self.value, "deleted")
        }
    }
    fn supersedes(&self, old: Option<&Self>) -> bool {
        match old {
            None => true,
            Some(old) if self.deleted() != old.deleted() => self.deleted(),
            Some(old) => (self.clock, &self.writer) > (old.clock, &old.writer),
        }
    }
}

pub fn meta(db: &Connection, key: &str) -> Result<String> {
    Ok(db
        .query_row("SELECT value FROM sync_meta WHERE key=?", [key], |r| {
            r.get(0)
        })
        .optional()?
        .unwrap_or_default())
}

pub fn put_meta(db: &Connection, key: &str, value: &str) -> Result<()> {
    db.execute(
        "INSERT INTO sync_meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        params![key, value],
    )?;
    Ok(())
}

pub fn validate(key: &str, value: &Value) -> Result<()> {
    ensure!(key.len() <= 160, "Sync key is too long");
    let p: Vec<_> = key.split('/').collect();
    let id = |id: &str| -> Result<()> {
        ensure!(TRACK_ID.is_match(id), "Invalid recording identity");
        Ok(())
    };
    match p.as_slice() {
        ["track", track] => {
            id(track)?;
            ensure!(
                value.is_object() && value["id"] == *track,
                "Invalid recording descriptor"
            );
            let chat = value["chat"].as_i64().context("Missing chat identity")?;
            let message = value["message"]
                .as_i64()
                .context("Missing message identity")?;
            ensure!(
                (-9_007_199_254_740_991..=9_007_199_254_740_991).contains(&chat)
                    && (0..=9_007_199_254_740_991).contains(&message),
                "Invalid message identity"
            );
            let profile = number(value, "profile");
            if track.starts_with("profile:") {
                ensure!(
                    chat == 0 && message == 0 && (1..=9_007_199_254_740_991).contains(&profile),
                    "Invalid profile identity"
                );
                let key = value["key"].as_str().context("Missing remote identity")?;
                ensure!(
                    !key.is_empty() && profile_track_id(profile, key) == *track,
                    "Mismatching profile recording identity"
                );
            } else if track.starts_with("local:") {
                ensure!(
                    chat == 0 && profile == 0,
                    "Invalid imported recording identity"
                );
                Uuid::parse_str(&track[6..])?;
            } else {
                ensure!(
                    profile == 0 && format!("{chat}:{message}") == *track,
                    "Mismatching recording identity"
                );
            }
            for field in ["title", "artist", "source", "key", "remote"] {
                ensure!(
                    string(value, field).chars().count() <= 8192,
                    "Recording metadata is too long"
                );
            }
            ensure!(
                number(value, "size") >= 0
                    && number(value, "duration") >= 0
                    && string(value, "username").len() <= 64,
                "Invalid recording metadata"
            );
        }
        ["favorite", track] => {
            id(track)?;
            ensure!(value.is_boolean(), "Invalid favorite value");
        }
        ["source", source] | ["profile", source] => {
            let numeric = source.parse::<i64>()?;
            if p[0] == "profile" {
                ensure!(
                    (1..=9_007_199_254_740_991).contains(&numeric),
                    "Invalid profile identity"
                );
            }
            ensure!(
                value.is_object()
                    && value["enabled"].is_boolean()
                    && value["title"].is_string()
                    && string(value, "title").chars().count() <= 256
                    && string(value, "username").len() <= 64,
                "Invalid source descriptor"
            );
        }
        ["playlist", uid, field] => {
            ensure!(
                Uuid::parse_str(uid)?.to_string() == *uid,
                "Invalid playlist identity"
            );
            match *field {
                "name" => ensure!(
                    value
                        .as_str()
                        .is_some_and(|s| !s.trim().is_empty() && s.chars().count() <= 80),
                    "Invalid playlist name"
                ),
                "deleted" => ensure!(value.is_boolean(), "Invalid deletion value"),
                "order" => {
                    let order = value.as_array().context("Invalid playlist order")?;
                    ensure!(order.len() <= 100_000, "Playlist is too large");
                    for t in order {
                        id(t.as_str().context("Invalid playlist member")?)?;
                    }
                }
                _ => bail!("Unsupported playlist field"),
            }
        }
        ["playlist", uid, "member", track] => {
            ensure!(
                Uuid::parse_str(uid)?.to_string() == *uid,
                "Invalid playlist identity"
            );
            id(track)?;
            ensure!(value.is_boolean(), "Invalid playlist membership");
        }
        ["setting", setting] => {
            ensure!(SETTINGS.contains(setting), "Unsupported synced setting");
            match *setting {
                "theme" => ensure!(
                    [json!("system"), json!("dark"), json!("light")].contains(value),
                    "Invalid theme"
                ),
                "language" => ensure!(
                    [json!("system"), json!("en"), json!("ru")].contains(value),
                    "Invalid language"
                ),
                "resume_minutes" => ensure!(
                    value
                        .as_i64()
                        .is_some_and(|n| n > 0 && n <= i32::MAX as i64),
                    "Invalid bookmark threshold"
                ),
                _ => ensure!(value.is_boolean(), "Invalid synced setting"),
            }
        }
        _ => bail!("Unsupported sync field"),
    }
    Ok(())
}

pub fn decode(rows: &Value, legacy: bool) -> Result<Vec<Record>> {
    let rows = rows.as_array().context("Invalid sync collection")?;
    ensure!(rows.len() <= 300_000, "Sync collection is too large");
    let mut seen = HashSet::new();
    let mut result = Vec::with_capacity(rows.len());
    for row in rows {
        let row = row.as_array().context("Invalid sync record")?;
        ensure!(row.len() == 4, "Invalid sync record");
        let key = row[0].as_str().context("Invalid sync key")?.to_owned();
        let clock = row[1].as_i64().context("Invalid sync clock")?;
        let writer = row[2].as_str().context("Invalid sync writer")?.to_owned();
        ensure!(
            (1..=i64::MAX / 4).contains(&clock)
                && Uuid::parse_str(&writer)?.to_string() == writer
                && seen.insert(key.clone()),
            "Invalid sync version"
        );
        if ["setting/speed", "setting/order", "setting/repeat"].contains(&key.as_str())
            || legacy && (key == "queue" || key.starts_with("progress/"))
        {
            continue;
        }
        validate(&key, &row[3])?;
        result.push(Record {
            key,
            clock,
            writer,
            value: row[3].clone(),
        });
    }
    Ok(result)
}

pub fn records(db: &Connection, account: i64, pending: bool) -> Result<Vec<Record>> {
    let mut s = db.prepare(if pending {
        "SELECT key,clock,writer,value FROM sync_values WHERE account=? AND pending=1 ORDER BY key"
    } else {
        "SELECT key,clock,writer,value FROM sync_values WHERE account=? ORDER BY key"
    })?;
    let rows = s.query_map([account], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, i64>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, String>(3)?,
        ))
    })?;
    let mut result = Vec::new();
    for row in rows {
        let (key, clock, writer, encoded) = row?;
        let wrapped: Value = serde_json::from_str(&encoded)?;
        result.push(Record {
            key,
            clock,
            writer,
            value: wrapped["v"].clone(),
        });
    }
    Ok(result)
}

pub fn edit(
    db: &Connection,
    account: i64,
    values: impl IntoIterator<Item = (String, Value)>,
) -> Result<()> {
    let mut changes = BTreeMap::new();
    for (key, value) in values {
        validate(&key, &value)?;
        let old: Option<String> = db
            .query_row(
                "SELECT value FROM sync_values WHERE account=? AND key=?",
                params![account, key],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(old) = old {
            let old: Value = serde_json::from_str(&old)?;
            let previous = Record {
                key: key.clone(),
                clock: 0,
                writer: String::new(),
                value: old["v"].clone(),
            };
            if previous.value == value || previous.deleted() {
                continue;
            }
        }
        changes.insert(key, value);
    }
    if changes.is_empty() {
        return Ok(());
    }
    let clock = meta(db, "clock")?
        .parse::<i64>()?
        .checked_add(1)
        .context("Sync clock overflow")?;
    ensure!(clock <= i64::MAX / 4, "Sync clock overflow");
    let writer = meta(db, "device")?;
    put_meta(db, "clock", &clock.to_string())?;
    let mut s=db.prepare_cached("INSERT INTO sync_values(account,key,clock,writer,value,pending) VALUES(?,?,?,?,?,1) ON CONFLICT(account,key) DO UPDATE SET clock=excluded.clock,writer=excluded.writer,value=excluded.value,pending=1")?;
    for (key, value) in changes {
        s.execute(params![
            account,
            key,
            clock,
            writer,
            json!({"v":value}).to_string()
        ])?;
    }
    Ok(())
}

impl Store {
    pub fn bind_account(&mut self, user: i64) -> Result<()> {
        ensure!(user > 0, "Invalid Telegram account");
        if self.account != 0 && self.account != user {
            self.detach_account()?;
        }
        let tx = self.db.unchecked_transaction()?;
        if self.account != user {
            let existing: HashMap<_, _> = records(&tx, user, false)?
                .into_iter()
                .map(|r| (r.key.clone(), r))
                .collect();
            for r in records(&tx, 0, false)? {
                if r.supersedes(existing.get(&r.key)) {
                    tx.execute("INSERT INTO sync_values VALUES(?,?,?,?,?,1) ON CONFLICT(account,key) DO UPDATE SET clock=excluded.clock,writer=excluded.writer,value=excluded.value,pending=1",params![user,r.key,r.clock,r.writer,json!({"v":r.value}).to_string()])?;
                }
            }
            tx.execute("DELETE FROM sync_values WHERE account=0", [])?;
            tx.execute("UPDATE playlists SET account=? WHERE account=0", [user])?;
            tx.execute("UPDATE tracks SET liked=0", [])?;
            put_meta(&tx, "account", &user.to_string())?;
        }
        let all = records(&tx, user, false)?;
        materialize(
            &tx,
            user,
            &all,
            &all.iter().map(|r| r.key.clone()).collect(),
        )?;
        tx.commit()?;
        self.account = user;
        self.revision += 1;
        Ok(())
    }

    pub fn detach_account(&mut self) -> Result<()> {
        let tx = self.db.unchecked_transaction()?;
        put_meta(&tx, "account", "0")?;
        tx.execute("UPDATE tracks SET liked=0", [])?;
        tx.execute("DELETE FROM playlist_tracks WHERE track IN(SELECT id FROM tracks WHERE chat!=0 OR profile_user!=0) AND playlist IN(SELECT id FROM playlists WHERE account=0)",[])?;
        tx.execute("DELETE FROM tracks WHERE chat!=0 OR profile_user!=0", [])?;
        tx.execute("DELETE FROM sources", [])?;
        tx.execute("DELETE FROM profiles", [])?;
        tx.execute(
            "DELETE FROM downloads WHERE track NOT IN(SELECT id FROM tracks)",
            [],
        )?;
        tx.commit()?;
        self.account = 0;
        self.revision += 1;
        Ok(())
    }

    pub fn merge_records(&mut self, incoming: &[Record]) -> Result<()> {
        if incoming.is_empty() {
            return Ok(());
        }
        for r in incoming {
            validate(&r.key, &r.value)?;
        }
        let tx = self.db.unchecked_transaction()?;
        let old: HashMap<_, _> = records(&tx, self.account, false)?
            .into_iter()
            .map(|r| (r.key.clone(), r))
            .collect();
        let mut changed = HashSet::new();
        let mut clock = meta(&tx, "clock")?.parse::<i64>()?;
        for r in incoming {
            clock = clock.max(r.clock);
            if !r.supersedes(old.get(&r.key)) {
                continue;
            }
            tx.execute("INSERT INTO sync_values VALUES(?,?,?,?,?,0) ON CONFLICT(account,key) DO UPDATE SET clock=excluded.clock,writer=excluded.writer,value=excluded.value,pending=0",params![self.account,r.key,r.clock,r.writer,json!({"v":r.value}).to_string()])?;
            changed.insert(r.key.clone());
        }
        put_meta(&tx, "clock", &clock.to_string())?;
        let all = records(&tx, self.account, false)?;
        materialize(&tx, self.account, &all, &changed)?;
        tx.commit()?;
        if !changed.is_empty() {
            self.revision += 1;
            self.rebuild_duplicates()?;
        }
        Ok(())
    }

    pub fn acknowledge(&self, sent: &[Record]) -> Result<()> {
        let tx = self.db.unchecked_transaction()?;
        for r in sent {
            tx.execute("UPDATE sync_values SET pending=0 WHERE account=? AND key=? AND clock=? AND writer=?",params![self.account,r.key,r.clock,r.writer])?;
        }
        tx.commit()?;
        Ok(())
    }

    pub fn portable_records(&self) -> Result<Vec<Record>> {
        let all = records(&self.db, self.account, false)?;
        let deleted: HashSet<_> = all
            .iter()
            .filter(|r| r.key.starts_with("playlist/") && r.deleted())
            .filter_map(|r| r.key.split('/').nth(1))
            .collect();
        let mut referenced = HashSet::new();
        for r in &all {
            if r.value != true {
                continue;
            }
            if let Some(id) = r.key.strip_prefix("favorite/") {
                referenced.insert(id);
            }
            let p: Vec<_> = r.key.split('/').collect();
            if p.len() == 4 && p[0] == "playlist" && p[2] == "member" && !deleted.contains(p[1]) {
                referenced.insert(p[3]);
            }
        }
        Ok(all
            .iter()
            .filter(|r| {
                !r.key.starts_with("track/")
                    || r.deleted()
                    || referenced.contains(r.key.strip_prefix("track/").unwrap_or(""))
                    || number(&r.value, "chat") == 0 && number(&r.value, "profile") == 0
            })
            .cloned()
            .collect())
    }
}

fn materialize(
    db: &Connection,
    account: i64,
    all: &[Record],
    changed: &HashSet<String>,
) -> Result<()> {
    if changed.is_empty() {
        return Ok(());
    }
    let rows: HashMap<_, _> = all.iter().map(|r| (r.key.as_str(), r)).collect();
    let value = |key: &str| rows.get(key).map(|r| &r.value);
    let deleted: HashSet<_> = all
        .iter()
        .filter(|r| r.key.starts_with("track/") && r.deleted())
        .filter_map(|r| r.key.strip_prefix("track/"))
        .collect();
    for key in changed {
        let Some(v) = value(key) else {
            continue;
        };
        if let Some(id) = key.strip_prefix("track/") {
            if boolean(v, "deleted") {
                db.execute("UPDATE tracks SET liked=0 WHERE id=?", [id])?;
                db.execute("DELETE FROM playlist_tracks WHERE track=? AND playlist IN(SELECT id FROM playlists WHERE account=?)",params![id,account])?;
                db.execute("DELETE FROM tracks WHERE id=? AND path=''", [id])?;
                continue;
            }
            let profile = number(v, "profile");
            if profile != 0 {
                db.execute(
                    "INSERT OR IGNORE INTO profiles(id,title,username) VALUES(?,?,?)",
                    params![profile, string(v, "source"), string(v, "username")],
                )?;
            }
            let search = format!(
                "{} {} {}",
                string(v, "title"),
                string(v, "artist"),
                string(v, "source")
            )
            .to_lowercase();
            db.execute("INSERT OR IGNORE INTO tracks(id,chat,message,file,title,artist,duration,size,source,date,document,file_key,remote_id,available,saved,cloud,profile_user,search_text,duplicate_group) VALUES(?,?,?,0,?,?,?,?,?,?,?,?,?,?,0,1,?,?,?)",
                params![id,number(v,"chat"),number(v,"message"),string(v,"title"),string(v,"artist"),number(v,"duration"),number(v,"size"),string(v,"source"),number(v,"date"),boolean(v,"document"),string(v,"key"),string(v,"remote"),number(v,"chat")!=0 || profile!=0,profile,search,id])?;
            db.execute(
                "UPDATE tracks SET liked=? WHERE id=?",
                params![
                    value(&format!("favorite/{id}")) == Some(&json!(true)) && !deleted.contains(id),
                    id
                ],
            )?;
        } else if let Some(id) = key.strip_prefix("favorite/") {
            db.execute(
                "UPDATE tracks SET liked=? WHERE id=?",
                params![v == true && !deleted.contains(id), id],
            )?;
        } else if let Some(id) = key.strip_prefix("source/") {
            let id = id.parse::<i64>()?;
            if boolean(v, "enabled") {
                db.execute(
                    "INSERT OR IGNORE INTO sources(id,title) VALUES(?,?)",
                    params![id, string(v, "title")],
                )?;
            } else {
                db.execute("DELETE FROM sources WHERE id=?", [id])?;
            }
        } else if let Some(id) = key.strip_prefix("profile/") {
            db.execute("INSERT INTO profiles(id,title,username,selected) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,username=excluded.username,selected=excluded.selected",params![id.parse::<i64>()?,string(v,"title"),string(v,"username"),boolean(v,"enabled")])?;
        } else if let Some(key) = key.strip_prefix("setting/") {
            db.execute("INSERT INTO local_settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",params![key,v.to_string()])?;
        }
    }
    let playlists: HashSet<_> = changed
        .iter()
        .filter(|k| k.starts_with("playlist/"))
        .filter_map(|k| k.split('/').nth(1))
        .collect();
    for uid in playlists {
        let id: Option<i64> = db
            .query_row("SELECT id FROM playlists WHERE sync_id=?", [uid], |r| {
                r.get(0)
            })
            .optional()?;
        if value(&format!("playlist/{uid}/deleted")) == Some(&json!(true)) {
            if let Some(id) = id {
                db.execute("DELETE FROM playlist_tracks WHERE playlist=?", [id])?;
                db.execute("DELETE FROM playlists WHERE id=?", [id])?;
            }
            continue;
        }
        let Some(name) = value(&format!("playlist/{uid}/name")).and_then(Value::as_str) else {
            continue;
        };
        let id = if let Some(id) = id {
            id
        } else {
            db.execute(
                "INSERT INTO playlists(name,sync_id,account) VALUES(?,?,?)",
                params![name, uid, account],
            )?;
            db.last_insert_rowid()
        };
        db.execute("UPDATE playlists SET name=? WHERE id=?", params![name, id])?;
        let prefix = format!("playlist/{uid}/member/");
        let mut members: Vec<_> = all
            .iter()
            .filter(|r| {
                r.key.starts_with(&prefix)
                    && r.value == true
                    && !deleted.contains(&r.key[prefix.len()..])
            })
            .collect();
        members.sort_by(|a, b| (a.clock, &a.writer, &a.key).cmp(&(b.clock, &b.writer, &b.key)));
        let set: HashSet<_> = members.iter().map(|r| &r.key[prefix.len()..]).collect();
        let mut ordered = Vec::with_capacity(members.len());
        let mut seen = HashSet::new();
        if let Some(order) = value(&format!("playlist/{uid}/order")).and_then(Value::as_array) {
            for id in order.iter().filter_map(Value::as_str) {
                if set.contains(id) && seen.insert(id.to_owned()) {
                    ordered.push(id.to_owned());
                }
            }
        }
        for r in members {
            let id = &r.key[prefix.len()..];
            if seen.insert(id.to_owned()) {
                ordered.push(id.to_owned());
            }
        }
        write_members(db, id, &ordered)?;
    }
    Ok(())
}
