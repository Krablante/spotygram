use crate::{
    model::*,
    platform::Paths,
    store::Store,
    sync::{self, Record},
    telegram::Telegram,
};
use anyhow::{Context, Result, bail, ensure};
use flate2::{Compression, read::GzDecoder, write::GzEncoder};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    io::{Read, Write},
    sync::Arc,
    time::Duration,
};
use tokio::sync::mpsc;
use uuid::Uuid;

const COMPRESSED: usize = 8 * 1024 * 1024;
const EXPANDED: usize = 32 * 1024 * 1024;

pub struct Cloud {
    td: Arc<Telegram>,
    paths: Arc<Paths>,
    store: Store,
    rooms: Vec<i64>,
    heads: HashMap<i64, Vec<Value>>,
    notices: mpsc::Sender<Notice>,
    inspected: HashSet<i64>,
}

impl Cloud {
    pub async fn run(
        td: Arc<Telegram>,
        paths: Arc<Paths>,
        mut signals: mpsc::Receiver<()>,
        notices: mpsc::Sender<Notice>,
    ) -> Result<()> {
        let store = Store::connect(&paths.root.join("library.db"))?;
        let mut cloud = Self {
            td,
            paths,
            store,
            rooms: vec![],
            heads: HashMap::new(),
            notices,
            inspected: HashSet::new(),
        };
        let mut scheduled: Option<tokio::time::Instant> = None;
        let mut failures = 0_u32;
        loop {
            tokio::select! {
                signal=signals.recv()=>{if signal.is_none(){break;}},
                _=async{if let Some(deadline)=scheduled {tokio::time::sleep_until(deadline).await}else{std::future::pending::<()>().await}}=>{},
            }
            tokio::time::sleep(Duration::from_millis(800)).await;
            while signals.try_recv().is_ok() {}
            let _ = cloud
                .notices
                .send(Notice::Sync("Syncing library…".into()))
                .await;
            match cloud.synchronize().await {
                Ok(next) => {
                    failures = 0;
                    scheduled = next
                        .map(|seconds| tokio::time::Instant::now() + Duration::from_secs(seconds));
                    let _ = cloud
                        .notices
                        .send(Notice::Sync("Library up to date".into()))
                        .await;
                }
                Err(error) => {
                    failures = (failures + 1).min(5);
                    let text = error.to_string();
                    let flood = regex::Regex::new(r"(?:FLOOD_WAIT_|retry after )([0-9]+)")
                        .unwrap()
                        .captures(&text)
                        .and_then(|c| c[1].parse::<u64>().ok())
                        .unwrap_or(0);
                    let delay = (15 * (1 << failures)).min(900).max(flood);
                    if flood > 0 {
                        sync::put_meta(
                            &cloud.store.db,
                            &format!("flood/{}", cloud.store.account),
                            &((now() + flood) * 1000).to_string(),
                        )?;
                    }
                    scheduled = Some(tokio::time::Instant::now() + Duration::from_secs(delay));
                    let _ = cloud
                        .notices
                        .send(Notice::Sync(format!("Sync will retry: {text}")))
                        .await;
                }
            }
        }
        for room in &cloud.rooms {
            let _ = cloud.td.send(json!({"@type":"closeChat","chat_id":room}));
        }
        Ok(())
    }

    async fn synchronize(&mut self) -> Result<Option<u64>> {
        self.store.account = sync::meta(&self.store.db, "account")?.parse()?;
        ensure!(
            self.store.account > 0,
            "Connect Telegram to sync the library"
        );
        let flood = sync::meta(&self.store.db, &format!("flood/{}", self.store.account))?
            .parse::<u64>()
            .unwrap_or(0)
            / 1000;
        ensure!(flood <= now(), "FLOOD_WAIT_{}", flood.saturating_sub(now()));
        let new_channel =
            self.td.directory.lock().unwrap().iter().any(|(id, chat)| {
                boolean(&chat["type"], "is_channel") && !self.inspected.contains(id)
            });
        if self.rooms.is_empty() || new_channel {
            self.rooms = self.discover().await?;
            for room in &self.rooms {
                self.quiet(*room).await?;
                self.td
                    .request(json!({"@type":"openChat","chat_id":room}))
                    .await?;
            }
        }
        self.heads.clear();
        let sources = self.index_sources()?;
        let rooms = self.rooms.clone();
        for room in &rooms {
            self.read(*room).await?;
        }
        self.notices
            .send(Notice::CloudLibrary {
                sources_changed: sources != self.index_sources()?,
            })
            .await?;
        for (index, room) in rooms.iter().enumerate() {
            self.finish_cleanup(*room).await?;
            let through = self.meta_number(&format!("through2/{room}"))?;
            let checkpoint = self.meta_number(&format!("snapshot2/{room}"))?;
            let tail = self
                .heads
                .get(room)
                .into_iter()
                .flatten()
                .filter(|m| {
                    number(m, "id") > through && number(m, "id") != checkpoint && self.protocol(m)
                })
                .count();
            if tail > 0 && self.meta_number(&format!("dirty2/{room}"))? == 0 {
                sync::put_meta(
                    &self.store.db,
                    &format!("dirty2/{room}"),
                    &(now() * 1000).to_string(),
                )?;
            }
            let dirty = self.meta_number(&format!("dirty2/{room}"))?;
            let pending = if index == 0 {
                sync::records(&self.store.db, self.store.account, true)?
            } else {
                vec![]
            };
            if checkpoint == 0
                || pending.len() >= 1500
                || tail >= 16
                || dirty > 0 && now() * 1000 >= dirty as u64 + 60_000
            {
                let all = self.store.portable_records()?;
                let boundary = self.meta_number(&format!("cursor/{room}"))?;
                let sent = self.send(*room, &all, true, boundary).await?;
                self.store.acknowledge(&all)?;
                self.checkpoint(*room, number(&sent, "id"), boundary)?;
                self.finish_cleanup(*room).await?;
            } else if !pending.is_empty() {
                self.send(*room, &pending, false, 0).await?;
                self.store.acknowledge(&pending)?;
                sync::put_meta(
                    &self.store.db,
                    &format!("dirty2/{room}"),
                    &(now() * 1000).to_string(),
                )?;
            }
        }
        sync::put_meta(
            &self.store.db,
            &format!("last_sync/{}", self.store.account),
            &(now() * 1000).to_string(),
        )?;
        Ok(rooms
            .iter()
            .filter_map(|room| self.meta_number(&format!("dirty2/{room}")).ok())
            .filter(|n| *n > 0)
            .map(|n| (n as u64 / 1000 + 60).saturating_sub(now()))
            .min())
    }

    fn meta_number(&self, key: &str) -> Result<i64> {
        Ok(sync::meta(&self.store.db, key)?.parse().unwrap_or(0))
    }

    fn index_sources(&self) -> Result<HashSet<(i64, bool)>> {
        let mut statement = self.store.db.prepare(
            "SELECT id,0 FROM sources UNION ALL SELECT id,1 FROM profiles WHERE selected=1",
        )?;
        Ok(statement
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?
            .collect::<rusqlite::Result<_>>()?)
    }

    fn protocol(&self, message: &Value) -> bool {
        let caption = message["content"]["caption"]["text"]
            .as_str()
            .unwrap_or_default();
        (1..=sync::FORMAT).any(|format| {
            caption.starts_with(&format!("spotygram-sync/{format} {} ", self.store.account))
        })
    }

    async fn discover(&mut self) -> Result<Vec<i64>> {
        let remembered = self.meta_number(&format!("channel/{}", self.store.account))?;
        for list in ["chatListMain", "chatListArchive"] {
            loop {
                match self
                    .td
                    .request(json!({"@type":"loadChats","chat_list":{"@type":list},"limit":100}))
                    .await
                {
                    Ok(_) => {}
                    Err(error) if error.to_string().starts_with("404:") => break,
                    Err(error) => return Err(error),
                }
            }
        }
        let found = self
            .td
            .request(json!({"@type":"searchChatsOnServer","query":"spotygram","limit":100}))
            .await?;
        let mut candidates: HashSet<i64> = found["chat_ids"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_i64)
            .collect();
        if remembered != 0 {
            candidates.insert(remembered);
        }
        for (&id, chat) in self.td.directory.lock().unwrap().iter() {
            if boolean(&chat["type"], "is_channel") {
                candidates.insert(id);
            }
        }
        let mut rooms = Vec::new();
        for &id in &candidates {
            let chat = match self
                .td
                .request(json!({"@type":"getChat","chat_id":id}))
                .await
            {
                Ok(v) => v,
                Err(e) if inaccessible(&e) => continue,
                Err(e) => return Err(e),
            };
            if kind(&chat["type"]) != "chatTypeSupergroup" || !boolean(&chat["type"], "is_channel")
            {
                continue;
            }
            let group = number(&chat["type"], "supergroup_id");
            let info = match self
                .td
                .request(json!({"@type":"getSupergroup","supergroup_id":group}))
                .await
            {
                Ok(value) => value,
                Err(error) if inaccessible(&error) => continue,
                Err(error) => return Err(error),
            };
            if kind(&info["status"]) != "chatMemberStatusCreator" {
                continue;
            }
            let full = match self
                .td
                .request(json!({"@type":"getSupergroupFullInfo","supergroup_id":group}))
                .await
            {
                Ok(value) => value,
                Err(error) if inaccessible(&error) => continue,
                Err(error) => return Err(error),
            };
            let description = string(&full, "description");
            if !description.starts_with("spotygram-storage/") {
                continue;
            }
            let Some(identity) = description.strip_prefix("spotygram-storage/1\n") else {
                bail!("A newer Spotygram version is required for this sync storage");
            };
            let identity: Value = serde_json::from_str(identity)?;
            if number(&identity, "account") == self.store.account
                && Uuid::parse_str(&string(&identity, "id")).is_ok()
            {
                rooms.push(id);
            }
        }
        self.inspected.extend(candidates);
        if rooms.is_empty() {
            ensure!(
                remembered == 0,
                "The remembered library storage is missing; it has not been replaced"
            );
            let uid = Uuid::new_v4().to_string();
            let room=self.td.request(json!({"@type":"createNewSupergroupChat","title":format!("spotygram-{}",&uid.replace('-',"")[..12]),"is_forum":false,"is_channel":true,"description":format!("spotygram-storage/1\n{}",json!({"account":self.store.account,"id":uid})),"message_auto_delete_time":0,"for_import":false})).await?;
            rooms.push(number(&room, "id"));
        }
        rooms.sort_by(|a, b| b.cmp(a));
        rooms.dedup();
        sync::put_meta(
            &self.store.db,
            &format!("channel/{}", self.store.account),
            &rooms[0].to_string(),
        )?;
        Ok(rooms)
    }

    async fn quiet(&self, room: i64) -> Result<()> {
        let chat = self
            .td
            .request(json!({"@type":"getChat","chat_id":room}))
            .await?;
        let mut settings = chat["notification_settings"].clone();
        if boolean(&settings, "use_default_mute_for") || number(&settings, "mute_for") < 1_000_000 {
            settings["use_default_mute_for"] = json!(false);
            settings["mute_for"] = json!(i32::MAX);
            settings["use_default_disable_pinned_message_notifications"] = json!(false);
            settings["disable_pinned_message_notifications"] = json!(true);
            settings["use_default_disable_mention_notifications"] = json!(false);
            settings["disable_mention_notifications"] = json!(true);
            self.td.request(json!({"@type":"setChatNotificationSettings","chat_id":room,"notification_settings":settings})).await?;
        }
        if !chat["positions"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|p| kind(&p["list"]) == "chatListArchive")
        {
            self.td.request(json!({"@type":"addChatToList","chat_id":room,"chat_list":{"@type":"chatListArchive"}})).await?;
        }
        if number(&chat, "message_auto_delete_time") != 0 {
            self.td.request(json!({"@type":"setChatMessageAutoDeleteTime","chat_id":room,"message_auto_delete_time":0})).await?;
        }
        Ok(())
    }

    async fn history(&self, room: i64, from: i64) -> Result<Vec<Value>> {
        let page=self.td.request(json!({"@type":"getChatHistory","chat_id":room,"from_message_id":from,"offset":0,"limit":100,"only_local":false})).await?;
        Ok(page["messages"]
            .as_array()
            .context("Invalid Telegram history")?
            .clone())
    }

    async fn read(&mut self, room: i64) -> Result<()> {
        let previous = self.meta_number(&format!("cursor/{room}"))?;
        let mut covered = previous;
        let mut newest = previous;
        let mut from = 0;
        let mut stalled = 0;
        let mut checkpoint = 0;
        let mut boundary = 0;
        let mut incoming: HashMap<String, Record> = HashMap::new();
        'history: loop {
            let messages = self.history(room, from).await?;
            if messages.is_empty() {
                break;
            }
            if from == 0 {
                self.heads.insert(room, messages.clone());
            }
            let mut next = from;
            for message in messages {
                if !message["sending_state"].is_null() {
                    continue;
                }
                let id = number(&message, "id");
                newest = newest.max(id);
                if id <= covered {
                    break 'history;
                }
                if from != 0 && id >= from {
                    continue;
                }
                next = id;
                let content = &message["content"];
                if kind(content) == "messageSupergroupChatCreate" {
                    break 'history;
                }
                let caption = content["caption"]["text"].as_str().unwrap_or_default();
                if !caption.starts_with("spotygram-sync/") {
                    continue;
                }
                let (packet, rows) = self.download(content, caption).await?;
                let through = number(&packet, "through");
                ensure!(
                    through >= 0 && through < id && (packet["kind"] == "snapshot" || through == 0),
                    "Invalid sync boundary"
                );
                for row in rows {
                    let replace = incoming.get(&row.key).is_none_or(|old| {
                        if row.deleted() != old.deleted() {
                            row.deleted()
                        } else {
                            (row.clock, &row.writer) > (old.clock, &old.writer)
                        }
                    });
                    if replace {
                        incoming.insert(row.key.clone(), row);
                    }
                }
                if packet["kind"] == "snapshot" {
                    covered = covered.max(through);
                    if number(&packet, "format") == sync::FORMAT {
                        checkpoint = checkpoint.max(id);
                        boundary = boundary.max(through);
                    }
                }
            }
            if next == from {
                stalled += 1;
                ensure!(stalled <= 3, "Telegram returned incomplete sync history");
                tokio::time::sleep(Duration::from_millis(200)).await;
            } else {
                from = next;
                stalled = 0;
            }
        }
        self.store
            .merge_records(&incoming.into_values().collect::<Vec<_>>())?;
        if checkpoint != 0 {
            self.checkpoint(room, checkpoint, boundary)?;
        }
        sync::put_meta(
            &self.store.db,
            &format!("cursor/{room}"),
            &newest.to_string(),
        )?;
        Ok(())
    }

    async fn download(&self, content: &Value, caption: &str) -> Result<(Value, Vec<Record>)> {
        let parts: Vec<_> = caption.split(' ').collect();
        ensure!(parts.len() == 6, "Invalid sync caption");
        let format = parts[0]
            .strip_prefix("spotygram-sync/")
            .context("Invalid sync format")?
            .parse::<i64>()?;
        ensure!(
            (1..=sync::FORMAT).contains(&format),
            "A newer Spotygram version is required"
        );
        ensure!(
            parts[1].parse::<i64>()? == self.store.account
                && ["delta", "snapshot"].contains(&parts[2]),
            "Invalid sync account"
        );
        let file = &content["document"]["document"];
        ensure!(
            (1..=COMPRESSED as i64).contains(&number(file, "size")),
            "Invalid sync file size"
        );
        let id = number(file, "id") as i32;
        self.td.files.remember(file);
        let f = self.td.wait_file(id).await?;
        let result = (|| {
            let mut bytes = Vec::new();
            std::fs::File::open(&f.path)?
                .take(COMPRESSED as u64 + 1)
                .read_to_end(&mut bytes)?;
            ensure!(
                bytes.len() == number(file, "size") as usize
                    && format!("{:x}", Sha256::digest(&bytes)) == parts[4],
                "Sync file digest does not match"
            );
            let mut plain = Vec::new();
            GzDecoder::new(bytes.as_slice())
                .take(EXPANDED as u64 + 1)
                .read_to_end(&mut plain)?;
            ensure!(plain.len() <= EXPANDED, "Sync document is too large");
            let packet: Value = serde_json::from_slice(&plain)?;
            ensure!(
                number(&packet, "format") == format
                    && number(&packet, "account") == self.store.account
                    && packet["kind"] == parts[2]
                    && number(&packet, "through") == parts[5].parse::<i64>()?,
                "Sync document does not match its caption"
            );
            let rows = sync::decode(&packet["records"], format == 1)?;
            Ok((packet, rows))
        })();
        let _ = self
            .td
            .request(json!({"@type":"deleteFile","file_id":id}))
            .await;
        result
    }

    async fn send(
        &self,
        room: i64,
        records: &[Record],
        snapshot: bool,
        through: i64,
    ) -> Result<Value> {
        let kind = if snapshot { "snapshot" } else { "delta" };
        let token = Uuid::new_v4().to_string();
        let packet = json!({"format":sync::FORMAT,"account":self.store.account,"kind":kind,"through":through,"records":records.iter().map(Record::row).collect::<Vec<_>>()});
        let plain = serde_json::to_vec(&packet)?;
        ensure!(plain.len() <= EXPANDED, "Sync document is too large");
        let mut gzip = GzEncoder::new(Vec::new(), Compression::default());
        gzip.write_all(&plain)?;
        let bytes = gzip.finish()?;
        ensure!(bytes.len() <= COMPRESSED, "Sync document is too large");
        let file = self
            .paths
            .cache
            .join(format!("spotygram-{token}.spotygram"));
        std::fs::write(&file, &bytes)?;
        let digest = format!("{:x}", Sha256::digest(&bytes));
        let result=self.td.send_confirmed(json!({"@type":"sendMessage","chat_id":room,"options":{"@type":"messageSendOptions","disable_notification":true,"from_background":true},"input_message_content":{"@type":"inputMessageDocument","document":{"@type":"inputDocument","document":{"@type":"inputFileLocal","path":file},"disable_content_type_detection":true},"caption":{"@type":"formattedText","text":format!("spotygram-sync/3 {} {kind} {token} {digest} {through}",self.store.account)}}})).await;
        let _ = std::fs::remove_file(file);
        result
    }

    fn checkpoint(&self, room: i64, id: i64, through: i64) -> Result<()> {
        let tx = self.store.db.unchecked_transaction()?;
        for (key, value) in [
            (format!("snapshot2/{room}"), id),
            (
                format!("through2/{room}"),
                through.max(self.meta_number(&format!("through2/{room}"))?),
            ),
            (
                format!("compact2/{room}"),
                through.max(self.meta_number(&format!("compact2/{room}"))?),
            ),
            (format!("dirty2/{room}"), 0),
        ] {
            sync::put_meta(&tx, &key, &value.to_string())?;
        }
        tx.commit()?;
        Ok(())
    }

    async fn finish_cleanup(&self, room: i64) -> Result<()> {
        let through = self.meta_number(&format!("compact2/{room}"))?;
        if through == 0 {
            return Ok(());
        }
        let mut from = through;
        let mut stalled = 0;
        let mut ids = Vec::new();
        loop {
            let page = self.history(room, from).await?;
            if page.is_empty() {
                break;
            }
            let mut next = from;
            let mut beginning = false;
            for message in page {
                let id = number(&message, "id");
                next = next.min(id);
                beginning |= kind(&message["content"]) == "messageSupergroupChatCreate";
                if id <= through && self.protocol(&message) {
                    ids.push(id);
                }
            }
            if beginning {
                break;
            }
            if next == from {
                stalled += 1;
                ensure!(stalled <= 3, "Telegram returned incomplete cleanup history");
                tokio::time::sleep(Duration::from_millis(200)).await;
            } else {
                from = next;
                stalled = 0;
            }
        }
        ids.sort_unstable();
        ids.dedup();
        for chunk in ids.chunks(100) {
            self.td.request(json!({"@type":"deleteMessages","chat_id":room,"message_ids":chunk,"revoke":true})).await?;
        }
        sync::put_meta(&self.store.db, &format!("compact2/{room}"), "0")?;
        Ok(())
    }
}

fn inaccessible(error: &anyhow::Error) -> bool {
    ["400:", "404:", "406:"]
        .iter()
        .any(|p| error.to_string().starts_with(p))
}
