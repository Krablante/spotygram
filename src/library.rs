use crate::{model::*, platform::Paths, store::Store, telegram::Telegram};
use anyhow::{Context, Result, ensure};
use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};
use std::{collections::HashSet, path::PathBuf, sync::Arc, time::Duration};
use tokio::sync::mpsc;
use uuid::Uuid;

pub fn parse_message(message: &Value, source: &str) -> Option<(Track, i32)> {
    let content = &message["content"];
    let document = kind(content) == "messageDocument";
    if !document && kind(content) != "messageAudio" {
        return None;
    }
    let audio = &content[if document { "document" } else { "audio" }];
    let filename = string(audio, "file_name");
    let extension = filename.rsplit('.').next().unwrap_or("").to_lowercase();
    if document
        && !string(audio, "mime_type").starts_with("audio/")
        && ![
            "mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "aiff", "alac",
        ]
        .contains(&extension.as_str())
    {
        return None;
    }
    let file = FileState::parse(&audio[if document { "document" } else { "audio" }]);
    let thumbnail = &audio[if document {
        "thumbnail"
    } else {
        "album_cover_thumbnail"
    }]["file"];
    let mut title = string(audio, "title");
    if title.is_empty() {
        title = filename
            .rsplit_once('.')
            .map(|(name, _)| name)
            .unwrap_or(&filename)
            .to_owned();
    }
    if title.is_empty() {
        title = "Audio recording".into();
    }
    let chat = number(message, "chat_id");
    let msg = number(message, "id");
    Some((
        Track {
            id: format!("{chat}:{msg}"),
            chat,
            message: msg,
            file: file.id,
            title,
            artist: string(audio, "performer"),
            duration: number(audio, "duration"),
            size: file.size,
            source: source.into(),
            date: number(message, "date"),
            art: if boolean(&thumbnail["local"], "is_downloading_completed") {
                string(&thumbnail["local"], "path")
            } else {
                String::new()
            },
            path: if file.complete && valid_copy(&file.path, file.size) {
                file.path
            } else {
                String::new()
            },
            available: true,
            document,
            key: file.key,
            remote: file.remote,
            ..Default::default()
        },
        number(thumbnail, "id") as i32,
    ))
}

pub async fn index(
    td: Arc<Telegram>,
    paths: Arc<Paths>,
    notices: mpsc::Sender<Notice>,
) -> Result<()> {
    let mut store = Store::connect(&paths.root.join("library.db"))?;
    let sources = store.sources()?;
    for source in sources.iter().filter(|s| !s.profile) {
        let _ = notices
            .send(Notice::Status(format!("Indexing {}…", source.title)))
            .await;
        for document in [false, true] {
            let state:Option<(i64,bool,i64)>=store.db.query_row(if document{"SELECT document_cursor,documents_complete,document_newest FROM sources WHERE id=?"}else{"SELECT cursor,complete,newest FROM sources WHERE id=?"},[source.id],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
            let Some((cursor, complete, newest)) = state else {
                break;
            };
            if !complete {
                scan(&td, &mut store, source, document, cursor, 0, true, &notices).await?;
            }
            let checkpoint = scan(
                &td, &mut store, source, document, 0, newest, false, &notices,
            )
            .await?;
            store.db.execute(
                if document {
                    "UPDATE sources SET document_newest=MAX(document_newest,?) WHERE id=?"
                } else {
                    "UPDATE sources SET newest=MAX(newest,?) WHERE id=?"
                },
                params![checkpoint, source.id],
            )?;
        }
    }
    for source in sources.iter().filter(|s| s.profile && s.selected) {
        load_profile(&td, &mut store, source.id, &notices).await?;
    }
    store.rebuild_duplicates()?;
    let _ = notices.send(Notice::Catalog).await;
    let _ = notices.send(Notice::Status(String::new())).await;
    Ok(())
}

async fn scan(
    td: &Telegram,
    store: &mut Store,
    source: &Source,
    document: bool,
    mut from: i64,
    cutoff: i64,
    history: bool,
    notices: &mpsc::Sender<Notice>,
) -> Result<i64> {
    let mut newest = 0;
    let mut published = tokio::time::Instant::now();
    loop {
        if store
            .db
            .query_row("SELECT 1 FROM sources WHERE id=?", [source.id], |r| {
                r.get::<_, i32>(0)
            })
            .optional()?
            .is_none()
        {
            break;
        }
        let response=td.request(json!({"@type":"searchChatMessages","chat_id":source.id,"topic_id":null,"query":"","sender_id":null,"from_message_id":from,"offset":0,"limit":100,"filter":{"@type":if document{"searchMessagesFilterDocument"}else{"searchMessagesFilterAudio"}}})).await?;
        let messages = response["messages"]
            .as_array()
            .context("Invalid Telegram audio history")?;
        let mut tracks = Vec::new();
        let mut thumbs = Vec::new();
        for message in messages {
            newest = newest.max(number(message, "id"));
            if let Some((t, thumb)) = parse_message(message, &source.title) {
                if thumb != 0 {
                    thumbs.push((t.id.clone(), thumb));
                }
                tracks.push(t);
            }
        }
        store.upsert(&tracks)?;
        for (track, file) in thumbs {
            store.db.execute(
                "INSERT OR REPLACE INTO local_thumbnails VALUES(?,?)",
                params![track, file],
            )?;
        }
        let next = number(&response, "next_from_message_id");
        ensure!(
            next == 0 || next != from,
            "Telegram audio history stopped advancing"
        );
        if history {
            store.db.execute(
                if document {
                    "UPDATE sources SET document_cursor=?,documents_complete=? WHERE id=?"
                } else {
                    "UPDATE sources SET cursor=?,complete=? WHERE id=?"
                },
                params![next, next == 0, source.id],
            )?;
            if from == 0 {
                store.db.execute(
                    if document {
                        "UPDATE sources SET document_newest=MAX(document_newest,?) WHERE id=?"
                    } else {
                        "UPDATE sources SET newest=MAX(newest,?) WHERE id=?"
                    },
                    params![newest, source.id],
                )?;
            }
        }
        if published.elapsed() >= Duration::from_millis(500) || next == 0 {
            store.rebuild_duplicates()?;
            let _ = notices.send(Notice::Catalog).await;
            published = tokio::time::Instant::now();
        }
        if next == 0 || cutoff > 0 && next <= cutoff {
            break;
        }
        from = next;
    }
    Ok(newest)
}

pub async fn find_user(td: &Telegram, store: &Store, input: &str) -> Result<i64> {
    let input = input.trim();
    if input == "me" || input.is_empty() {
        return Ok(number(&td.request(json!({"@type":"getMe"})).await?, "id"));
    }
    if let Ok(id) = input.parse::<i64>() {
        if td
            .request(json!({"@type":"getUser","user_id":id}))
            .await
            .is_ok()
        {
            return Ok(id);
        }
    }
    let username = input
        .trim_start_matches("https://t.me/")
        .trim_start_matches("http://t.me/")
        .trim_start_matches("t.me/")
        .trim_start_matches('@')
        .trim_end_matches('/');
    if username
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_')
    {
        if let Ok(chat) = td
            .request(json!({"@type":"searchPublicChat","username":username}))
            .await
        {
            ensure!(
                kind(&chat["type"]) == "chatTypePrivate",
                "This link does not refer to a Telegram profile"
            );
            return Ok(number(&chat["type"], "user_id"));
        }
    }
    let contacts = td
        .request(json!({"@type":"searchContacts","query":input,"limit":30}))
        .await?;
    let ids = contacts["user_ids"]
        .as_array()
        .context("No matching profile")?;
    ensure!(
        ids.len() == 1,
        "Use a Telegram @username to select one profile"
    );
    let _ = store;
    Ok(ids[0]
        .as_i64()
        .context("Invalid Telegram profile identity")?)
}

pub async fn load_profile(
    td: &Telegram,
    store: &mut Store,
    user: i64,
    notices: &mpsc::Sender<Notice>,
) -> Result<()> {
    let user_info = match td.request(json!({"@type":"getUser","user_id":user})).await {
        Ok(value) => value,
        Err(error) => {
            let username: Option<String> = store
                .db
                .query_row("SELECT username FROM profiles WHERE id=?", [user], |r| {
                    r.get(0)
                })
                .optional()?;
            if let Some(username) = username.filter(|s| !s.is_empty()) {
                let chat = td
                    .request(json!({"@type":"searchPublicChat","username":username}))
                    .await?;
                ensure!(
                    number(&chat["type"], "user_id") == user,
                    "The Telegram username now belongs to someone else"
                );
                td.request(json!({"@type":"getUser","user_id":user}))
                    .await?
            } else {
                return Err(error);
            }
        }
    };
    td.request(json!({"@type":"getUserFullInfo","user_id":user}))
        .await?;
    let title = format!(
        "{} {}",
        string(&user_info, "first_name"),
        string(&user_info, "last_name")
    )
    .trim()
    .to_owned();
    let username = user_info["usernames"]["editable_username"]
        .as_str()
        .or_else(|| user_info["usernames"]["active_usernames"][0].as_str())
        .unwrap_or_default();
    let photo = if boolean(
        &user_info["profile_photo"]["small"]["local"],
        "is_downloading_completed",
    ) {
        string(&user_info["profile_photo"]["small"]["local"], "path")
    } else {
        String::new()
    };
    let _ = notices
        .send(Notice::Status(format!("Loading {title}…")))
        .await;
    let mut offset = 0_usize;
    let mut expected = None;
    let mut tracks = Vec::new();
    let mut ids = HashSet::new();
    let mut thumbnails = Vec::new();
    loop {
        let response = td
            .request(
                json!({"@type":"getUserProfileAudios","user_id":user,"offset":offset,"limit":100}),
            )
            .await?;
        let count = response["total_count"]
            .as_u64()
            .context("Incomplete Telegram profile audio response")? as usize;
        ensure!(
            expected.is_none_or(|old| old == count),
            "Profile music changed while loading; refresh it again"
        );
        expected = Some(count);
        let audios = response["audios"]
            .as_array()
            .context("Incomplete Telegram profile audio response")?;
        ensure!(
            !audios.is_empty() || offset >= count,
            "Incomplete Telegram profile audio response"
        );
        for (i, audio) in audios.iter().enumerate() {
            let f = FileState::parse(&audio["audio"]);
            ensure!(!f.key.is_empty(), "Profile audio has no stable identity");
            let id = profile_track_id(user, &f.key);
            ensure!(
                ids.insert(id.clone()),
                "Profile music changed while loading; refresh it again"
            );
            let thumbnail = &audio["album_cover_thumbnail"]["file"];
            if number(thumbnail, "id") != 0 {
                thumbnails.push((id.clone(), number(thumbnail, "id")));
            }
            let mut track_title = string(audio, "title");
            if track_title.is_empty() {
                track_title = string(audio, "file_name");
            }
            let previous = store.track(&id)?;
            tracks.push(Track {
                id,
                profile: user,
                profile_position: (offset + i) as i64,
                file: f.id,
                title: track_title,
                artist: string(audio, "performer"),
                duration: number(audio, "duration"),
                size: f.size,
                source: title.clone(),
                date: previous.map(|t| t.date).unwrap_or(now() as i64),
                key: f.key,
                remote: f.remote,
                path: if f.complete && valid_copy(&f.path, f.size) {
                    f.path
                } else {
                    String::new()
                },
                art: if boolean(&thumbnail["local"], "is_downloading_completed") {
                    string(&thumbnail["local"], "path")
                } else {
                    String::new()
                },
                available: true,
                username: username.into(),
                ..Default::default()
            });
        }
        offset += audios.len();
        if offset >= count {
            break;
        }
    }
    ensure!(
        tracks.len() == expected.unwrap_or(0),
        "Incomplete Telegram profile audio response"
    );
    // A failed or changing paginated response never clears the previously published profile.
    let tx = store.db.unchecked_transaction()?;
    tx.execute("INSERT INTO profiles(id,title,username,photo,audio_count,loaded_at,opened_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,username=excluded.username,photo=excluded.photo,audio_count=excluded.audio_count,loaded_at=excluded.loaded_at,opened_at=excluded.opened_at",params![user,title,username,photo,tracks.len() as i64,(now()*1000) as i64,(now()*1000) as i64])?;
    tx.execute(
        "UPDATE tracks SET profile_position=-1,available=0 WHERE profile_user=?",
        [user],
    )?;
    // upsert owns its transaction, so the complete replacement is applied below in this one.
    for t in &tracks {
        tx.execute("INSERT INTO tracks(id,chat,message,file,title,artist,duration,size,source,date,art,path,available,file_key,remote_id,profile_user,profile_position,search_text,duplicate_group) VALUES(?,0,0,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET file=excluded.file,title=excluded.title,artist=excluded.artist,duration=excluded.duration,size=excluded.size,source=excluded.source,art=CASE WHEN excluded.art!='' THEN excluded.art WHEN tracks.file_key=excluded.file_key THEN tracks.art ELSE '' END,path=CASE WHEN excluded.path!='' THEN excluded.path WHEN tracks.file_key=excluded.file_key THEN tracks.path ELSE '' END,available=1,file_key=excluded.file_key,remote_id=excluded.remote_id,profile_position=excluded.profile_position,search_text=excluded.search_text",params![t.id,t.file,t.title,t.artist,t.duration,t.size,t.source,t.date,t.art,t.path,t.key,t.remote,t.profile,t.profile_position,t.search_text(),t.id])?;
    }
    for (track, file) in thumbnails {
        tx.execute(
            "INSERT OR REPLACE INTO local_thumbnails VALUES(?,?)",
            params![track, file],
        )?;
    }
    tx.commit()?;
    store.revision += 1;
    store.rebuild_duplicates()?;
    let _ = notices.send(Notice::Catalog).await;
    let _ = notices.send(Notice::Status(String::new())).await;
    Ok(())
}

pub async fn resolve(
    td: &Telegram,
    store: &mut Store,
    track: &Track,
    notices: &mpsc::Sender<Notice>,
) -> Result<Track> {
    if track.local() && track.file != 0 {
        return Ok(track.clone());
    }
    ensure!(
        !track.imported(),
        "This imported recording is only available on its original device"
    );
    if track.local() && !track.remote.is_empty() {
        let file=td.request(json!({"@type":"getRemoteFile","remote_file_id":track.remote,"file_type":{"@type":if track.document{"fileTypeDocument"}else{"fileTypeAudio"}}})).await?;
        let file = td.files.remember(&file);
        ensure!(
            track.key.is_empty() || file.key == track.key,
            "The Telegram file identity has changed"
        );
        let mut current = track.clone();
        current.file = file.id;
        store.db.execute(
            "UPDATE tracks SET file=? WHERE id=?",
            params![file.id, track.id],
        )?;
        return Ok(current);
    }
    if track.profile != 0 {
        load_profile(td, store, track.profile, notices).await?;
        let current = store
            .track(&track.id)?
            .context("Profile recording is no longer available")?;
        ensure!(
            current.available && current.file != 0,
            "Profile recording is no longer available"
        );
        return Ok(current);
    }
    let message = td
        .request(json!({"@type":"getMessage","chat_id":track.chat,"message_id":track.message}))
        .await?;
    let (current, thumbnail) = parse_message(&message, &track.source)
        .context("Telegram recording is no longer available")?;
    ensure!(
        track.key.is_empty() || current.key == track.key,
        "The original Telegram recording has changed"
    );
    store.upsert(std::slice::from_ref(&current))?;
    if thumbnail != 0 {
        store.db.execute(
            "INSERT OR REPLACE INTO local_thumbnails VALUES(?,?)",
            params![track.id, thumbnail],
        )?;
    }
    Ok(store.track(&track.id)?.unwrap_or(current))
}

pub async fn discover(
    td: &Telegram,
    query: &str,
    recipients: bool,
    document: bool,
    folder: i32,
    store: &Store,
) -> Result<Vec<Source>> {
    let mut ordered = Vec::new();
    let list = match folder {
        0 => json!({"@type":"chatListMain"}),
        -1 => json!({"@type":"chatListArchive"}),
        id => json!({"@type":"chatListFolder","chat_folder_id":id}),
    };
    loop {
        let loaded = td
            .request(json!({"@type":"loadChats","chat_list":list,"limit":100}))
            .await;
        if let Ok(page) = td
            .request(json!({"@type":"getChats","chat_list":list,"limit":10000}))
            .await
        {
            ordered = page["chat_ids"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_i64)
                .collect();
        }
        if loaded.is_err() || !query.trim().is_empty() {
            break;
        }
    }
    if !query.trim().is_empty() {
        for method in ["searchChats", "searchChatsOnServer"] {
            if let Ok(page) = td
                .request(json!({"@type":method,"query":query.trim(),"limit":100}))
                .await
            {
                ordered.extend(
                    page["chat_ids"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(Value::as_i64),
                );
            }
        }
    }
    let selected: HashSet<_> = store
        .sources()?
        .into_iter()
        .filter(|s| !s.profile && s.selected)
        .map(|s| s.id)
        .collect();
    let mut seen = HashSet::new();
    let mut choices = Vec::new();
    for id in ordered {
        if !seen.insert(id) {
            continue;
        }
        let cached = td.directory.lock().unwrap().get(&id).cloned();
        let chat = if let Some(chat) = cached {
            chat
        } else {
            td.request(json!({"@type":"getChat","chat_id":id})).await?
        };
        if kind(&chat["type"]) == "chatTypeSecret" {
            continue;
        }
        let title = string(&chat, "title");
        if !query.trim().is_empty() && !title.to_lowercase().contains(&query.to_lowercase()) {
            continue;
        }
        if recipients && !recipient_allowed(td, id, document, store.account).await? {
            continue;
        }
        let forum = if recipients
            && kind(&chat["type"]) == "chatTypeSupergroup"
            && !boolean(&chat["type"], "is_channel")
        {
            boolean(&td.request(json!({"@type":"getSupergroup","supergroup_id":number(&chat["type"],"supergroup_id")})).await?,"is_forum")
        } else {
            false
        };
        choices.push(Source {
            id,
            title,
            forum,
            kind: chat_label(&chat, store.account).into(),
            selected: selected.contains(&id),
            photo: if boolean(&chat["photo"]["small"]["local"], "is_downloading_completed") {
                string(&chat["photo"]["small"]["local"], "path")
            } else {
                String::new()
            },
            ..Default::default()
        });
    }
    Ok(choices)
}

pub async fn recipient_allowed(
    td: &Telegram,
    id: i64,
    document: bool,
    account: i64,
) -> Result<bool> {
    let chat = td.request(json!({"@type":"getChat","chat_id":id})).await?;
    let typ = &chat["type"];
    let permission = if document {
        "can_send_documents"
    } else {
        "can_send_audios"
    };
    match kind(typ) {
        "chatTypePrivate" => {
            let user = number(typ, "user_id");
            if user == account {
                return Ok(true);
            }
            if kind(&chat["block_list"]) == "blockListMain" {
                return Ok(false);
            }
            let user = td
                .request(json!({"@type":"getUser","user_id":user}))
                .await?;
            if kind(&user["type"]) == "userTypeDeleted" || user["have_access"] == false {
                return Ok(false);
            }
            let allowed=td.request(json!({"@type":"canSendMessageToUser","user_id":number(&user,"id"),"only_local":!(boolean(&user,"restricts_new_chats") || number(&user,"paid_message_star_count")>0)})).await?;
            Ok(kind(&allowed) == "canSendMessageToUserResultOk")
        }
        "chatTypeBasicGroup" | "chatTypeSupergroup" => {
            let basic = kind(typ) == "chatTypeBasicGroup";
            let group = td
                .request(if basic {
                    json!({"@type":"getBasicGroup","basic_group_id":number(typ,"basic_group_id")})
                } else {
                    json!({"@type":"getSupergroup","supergroup_id":number(typ,"supergroup_id")})
                })
                .await?;
            if basic && group["is_active"] == false || boolean(&group, "is_direct_messages_group") {
                return Ok(false);
            }
            let status = &group["status"];
            let creator = kind(status) == "chatMemberStatusCreator";
            let admin = creator || kind(status) == "chatMemberStatusAdministrator";
            if boolean(typ, "is_channel") {
                return Ok(creator || admin && boolean(&status["rights"], "can_post_messages"));
            }
            if kind(status) == "chatMemberStatusBanned"
                || creator && status["is_member"] == false
                || kind(status) == "chatMemberStatusLeft"
                    && (basic || group["join_to_send_messages"] != false)
                || kind(status) == "chatMemberStatusRestricted" && !boolean(status, "is_member")
            {
                return Ok(false);
            }
            if !admin
                && (!boolean(&chat["permissions"], permission)
                    || number(&group, "paid_message_star_count") > 0
                    || kind(status) == "chatMemberStatusRestricted"
                        && !boolean(&status["permissions"], permission))
            {
                return Ok(false);
            }
            let sender = &chat["message_sender_id"];
            if !sender.is_null()
                && !(kind(sender) == "messageSenderUser" && number(sender, "user_id") == account)
            {
                let senders = td
                    .request(json!({"@type":"getChatAvailableMessageSenders","chat_id":id}))
                    .await?;
                if !senders["senders"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .any(|s| {
                        kind(&s["sender"]) == "messageSenderUser"
                            && number(&s["sender"], "user_id") == account
                    })
                {
                    return Ok(false);
                }
            }
            Ok(true)
        }
        _ => Ok(false),
    }
}

pub async fn topics(td: &Telegram, chat: i64) -> Result<Vec<Source>> {
    let mut choices = Vec::new();
    let (mut date, mut message, mut offset) = (0, 0_i64, 0_i64);
    loop {
        let page=td.request(json!({"@type":"getForumTopics","chat_id":chat,"query":"","offset_date":date,"offset_message_id":message,"offset_forum_topic_id":offset,"limit":100})).await?;
        for entry in page["topics"].as_array().into_iter().flatten() {
            let info = &entry["info"];
            if !boolean(info, "is_closed") && !boolean(info, "is_hidden") {
                choices.push(Source {
                    id: number(info, "forum_topic_id"),
                    title: string(info, "name"),
                    ..Default::default()
                });
            }
        }
        let next = number(&page, "next_offset_forum_topic_id");
        if next == 0 || next == offset {
            break;
        }
        offset = next;
        date = number(&page, "next_offset_date");
        message = number(&page, "next_offset_message_id");
    }
    let mut ids = HashSet::new();
    choices.retain(|s| ids.insert(s.id));
    Ok(choices)
}

fn chat_label(chat: &Value, account: i64) -> &'static str {
    if number(chat, "id") == account {
        "Saved messages"
    } else {
        match kind(&chat["type"]) {
            "chatTypePrivate" | "chatTypeSecret" => "Private chat",
            _ if boolean(&chat["type"], "is_channel") => "Channel",
            _ => "Group",
        }
    }
}

pub async fn send(
    td: &Telegram,
    store: &mut Store,
    track: &Track,
    recipient: i64,
    topic: i64,
    sender: bool,
    caption: bool,
    notices: &mpsc::Sender<Notice>,
) -> Result<()> {
    ensure!(
        recipient_allowed(td, recipient, track.document, store.account).await?,
        "The recipient no longer permits this audio message"
    );
    let target = td
        .request(json!({"@type":"getChat","chat_id":recipient}))
        .await?;
    if kind(&target["type"]) == "chatTypeSupergroup" {
        let group=td.request(json!({"@type":"getSupergroup","supergroup_id":number(&target["type"],"supergroup_id")})).await?;
        if boolean(&group, "is_forum") {
            ensure!(topic != 0, "Choose a forum topic before sending");
            let current = td
                .request(
                    json!({"@type":"getForumTopic","chat_id":recipient,"forum_topic_id":topic}),
                )
                .await?;
            ensure!(
                !boolean(&current["info"], "is_closed") && !boolean(&current["info"], "is_hidden"),
                "This forum topic no longer accepts messages"
            );
        }
    }
    if !boolean(&target["type"], "is_channel")
        && !target["message_sender_id"].is_null()
        && number(&target["message_sender_id"], "user_id") != store.account
    {
        td.request(json!({"@type":"setChatMessageSender","chat_id":recipient,"message_sender_id":{"@type":"messageSenderUser","user_id":store.account}})).await?;
    }
    let input = if track.chat != 0 {
        let properties=td.request(json!({"@type":"getMessageProperties","chat_id":track.chat,"message_id":track.message})).await?;
        ensure!(
            boolean(
                &properties,
                if sender {
                    "can_be_forwarded"
                } else {
                    "can_be_copied"
                }
            ),
            "Telegram protects this recording from sharing"
        );
        let message = td
            .request(json!({"@type":"getMessage","chat_id":track.chat,"message_id":track.message}))
            .await?;
        ensure!(
            !sender
                || caption
                || message["content"]["caption"]["text"]
                    .as_str()
                    .unwrap_or_default()
                    .is_empty(),
            "Keeping the original sender also requires keeping its caption"
        );
        json!({"@type":"inputMessageForwarded","from_chat_id":track.chat,"message_id":track.message,"copy_options":{"@type":"messageCopyOptions","send_copy":!sender,"replace_caption":!caption,"new_caption":{"@type":"formattedText","text":""}}})
    } else {
        let file = if track.profile != 0 {
            let t = resolve(td, store, track, notices).await?;
            json!({"@type":"inputFileId","id":t.file})
        } else if !track.remote.is_empty() {
            match td.request(json!({"@type":"getRemoteFile","remote_file_id":track.remote,"file_type":{"@type":"fileTypeAudio"}})).await {
                Ok(f)=>json!({"@type":"inputFileId","id":number(&f,"id")}),
                Err(error) if error.to_string().starts_with("400:") && track.local()=>json!({"@type":"inputFileLocal","path":track.path}),Err(error)=>return Err(error),
            }
        } else {
            ensure!(track.local(), "No local audio copy is available");
            json!({"@type":"inputFileLocal","path":track.path})
        };
        json!({"@type":"inputMessageAudio","audio":{"@type":"inputAudio","audio":file,"duration":track.duration,"title":track.title,"performer":track.artist},"caption":{"@type":"formattedText","text":""}})
    };
    let mut request = json!({"@type":"sendMessage","chat_id":recipient,"options":{"@type":"messageSendOptions","disable_notification":boolean(&target,"default_disable_notification")},"input_message_content":input});
    if topic != 0 {
        request["topic_id"] = json!({"@type":"messageTopicForum","forum_topic_id":topic});
    }
    let sent = td.send_confirmed(request).await?;
    if track.imported() {
        let f = &sent["content"]["audio"]["audio"]["remote"];
        store.db.execute(
            "UPDATE tracks SET file_key=?,remote_id=? WHERE id=?",
            params![string(f, "unique_id"), string(f, "id"), track.id],
        )?;
    }
    let title = string(&target, "title");
    if let Some((track, _)) = parse_message(&sent, &title) {
        store.upsert(&[track])?;
    }
    let key = format!("sharing_recent_{}", store.account);
    let mut recent: Vec<Source> = store.setting(&key)?.unwrap_or_default();
    recent.retain(|s| s.id != recipient);
    recent.insert(
        0,
        Source {
            id: recipient,
            title,
            forum: topic != 0,
            kind: chat_label(&target, store.account).into(),
            ..Default::default()
        },
    );
    recent.truncate(6);
    store.put_setting(&key, &recent)?;
    let _ = notices.send(Notice::Sent).await;
    Ok(())
}

pub async fn profile_member(
    td: &Telegram,
    store: &mut Store,
    track: &Track,
    set: Option<bool>,
    notices: &mpsc::Sender<Notice>,
) -> Result<bool> {
    ensure!(
        !track.document,
        "Telegram profile music requires an Audio recording"
    );
    if set == Some(true) && track.chat != 0 {
        let properties=td.request(json!({"@type":"getMessageProperties","chat_id":track.chat,"message_id":track.message})).await?;
        ensure!(
            boolean(&properties, "can_be_saved"),
            "Telegram protects this recording from saving"
        );
    }
    let mut preliminary = 0;
    let id = if !track.imported() {
        resolve(td, store, track, notices).await?.file
    } else if !track.remote.is_empty() {
        number(&td.request(json!({"@type":"getRemoteFile","remote_file_id":track.remote,"file_type":{"@type":"fileTypeAudio"}})).await?,"id") as i32
    } else if set == Some(true) {
        ensure!(track.local(), "No local audio copy is available");
        let file=td.request(json!({"@type":"preliminaryUploadFile","file":{"@type":"inputFileLocal","path":track.path},"file_type":{"@type":"fileTypeAudio"},"priority":2})).await?;
        preliminary = number(&file, "id") as i32;
        preliminary
    } else {
        return Ok(false);
    };
    let _upload = PreliminaryUpload {
        td,
        id: preliminary,
    };
    let result=async {if let Some(add)=set {
        let result=if add{td.request_timeout(json!({"@type":"addProfileAudio","audio":{"@type":"inputAudio","audio":{"@type":"inputFileId","id":id},"duration":track.duration,"title":track.title,"performer":track.artist}}),Duration::from_secs(if preliminary!=0{86400}else{30})).await}else{td.request(json!({"@type":"removeProfileAudio","file_id":id})).await};
        result?;
        if add && track.imported(){let f=td.file(id).await?;store.db.execute("UPDATE tracks SET file_key=?,remote_id=? WHERE id=?",params![f.key,f.remote,track.id])?;}
        Ok(add)
    } else {
        match td.request(json!({"@type":"isProfileAudio","file_id":id})).await {Ok(_)=>Ok(true),Err(e) if e.to_string().starts_with("404:")=>Ok(false),Err(e)=>Err(e)}
    }}.await;
    result
}

struct PreliminaryUpload<'a> {
    td: &'a Telegram,
    id: i32,
}
impl Drop for PreliminaryUpload<'_> {
    fn drop(&mut self) {
        if self.id != 0 {
            let _ = self
                .td
                .send(json!({"@type":"cancelPreliminaryUploadFile","file_id":self.id}));
        }
    }
}

pub fn import(paths: &Paths, store: &mut Store, files: &[PathBuf]) -> Result<()> {
    use lofty::{
        file::{AudioFile, TaggedFileExt},
        tag::Accessor,
    };
    for original in files {
        ensure!(original.is_file(), "Select an audio file");
        let id = format!("local:{}", Uuid::new_v4());
        let suffix = original
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("audio");
        let filename = paths.imports.join(format!("{}.{}", &id[6..], suffix));
        let staged = filename.with_extension(format!("{suffix}.part"));
        #[cfg(target_os = "android")]
        let moved = crate::platform::is_owned(original, &paths.cache)
            && std::fs::rename(original, &staged).is_ok();
        #[cfg(not(target_os = "android"))]
        let moved = false;
        if !moved {
            if let Err(error) = std::fs::copy(original, &staged) {
                let _ = std::fs::remove_file(&staged);
                return Err(error.into());
            }
        }
        let metadata = lofty::probe::Probe::open(&staged)
            .and_then(|p| Ok(p.guess_file_type()?))
            .and_then(|p| p.read());
        let mut title = original
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("Audio recording")
            .to_owned();
        let mut artist = String::new();
        let mut duration = 0;
        let mut art = String::new();
        if let Ok(metadata) = metadata {
            duration = metadata.properties().duration().as_secs() as i64;
            if let Some(tag) = metadata.primary_tag().or_else(|| metadata.first_tag()) {
                if let Some(t) = tag.title() {
                    if !t.trim().is_empty() {
                        title = t.into_owned();
                    }
                }
                if let Some(a) = tag.artist() {
                    artist = a.into_owned();
                }
                if let Some(picture) = tag
                    .pictures()
                    .first()
                    .filter(|p| p.data().len() <= 8 * 1024 * 1024)
                {
                    let path = paths.artwork.join(format!("{}.image", &id[6..]));
                    std::fs::write(&path, picture.data())?;
                    art = path.to_string_lossy().into_owned();
                }
            }
        }
        if let Err(error) = std::fs::rename(&staged, &filename) {
            let _ = std::fs::remove_file(&staged);
            return Err(error.into());
        }
        let track = Track {
            id: id.clone(),
            title,
            artist,
            duration,
            size: filename.metadata()?.len() as i64,
            date: now() as i64,
            path: filename.to_string_lossy().into_owned(),
            art,
            available: true,
            saved: true,
            ..Default::default()
        };
        if let Err(error) = store.upsert(std::slice::from_ref(&track)) {
            let _ = std::fs::remove_file(filename);
            return Err(error);
        }
        #[cfg(target_os = "android")]
        if crate::platform::is_owned(original, &paths.cache) {
            let _ = std::fs::remove_file(original);
        }
        #[cfg(target_os = "android")]
        if let Some(directory) = original
            .parent()
            .filter(|p| crate::platform::is_owned(p, &paths.cache))
        {
            let _ = std::fs::remove_dir(directory);
        }
        // Retain the file if a later sync write fails after SQLite has accepted the recording.
        let tx = store.db.unchecked_transaction()?;
        crate::sync::edit(
            &tx,
            store.account,
            [(format!("track/{id}"), track.descriptor())],
        )?;
        tx.commit()?;
    }
    store.rebuild_duplicates()?;
    Ok(())
}

pub async fn artwork(td: &Telegram, store: &mut Store, ids: &[String]) -> Result<bool> {
    let mut changed = false;
    for id in ids {
        let Some(t) = store.track(id)? else {
            continue;
        };
        if !t.art.is_empty() && std::path::Path::new(&t.art).is_file() {
            continue;
        }
        let mut file: Option<i32> = store
            .db
            .query_row(
                "SELECT file FROM local_thumbnails WHERE track=?",
                [id],
                |r| r.get(0),
            )
            .optional()?;
        if file.is_none() && t.chat != 0 {
            let message = td
                .request(json!({"@type":"getMessage","chat_id":t.chat,"message_id":t.message}))
                .await?;
            if let Some((current, thumbnail)) = parse_message(&message, &t.source) {
                if (t.key.is_empty() || t.key == current.key) && thumbnail != 0 {
                    store.db.execute(
                        "INSERT OR REPLACE INTO local_thumbnails VALUES(?,?)",
                        params![id, thumbnail],
                    )?;
                    file = Some(thumbnail);
                }
            }
        }
        if let Some(file) = file {
            let f = td.file(file).await?;
            let f = if f.complete {
                f
            } else {
                td.wait_file(file).await?
            };
            store
                .db
                .execute("UPDATE tracks SET art=? WHERE id=?", params![f.path, id])?;
            changed = true;
        }
    }
    Ok(changed)
}

/// Small avatars are requested only for visible sources/recipients. One worker
/// downloads them serially at low priority; errors keep the initials fallback.
pub async fn source_photos(
    td: &Telegram,
    store: &Store,
    keys: &[(i64, bool)],
) -> Vec<(i64, bool, String)> {
    let mut changed = Vec::new();
    for &(id, profile) in keys {
        let result=tokio::time::timeout(std::time::Duration::from_secs(8),async {
            let entity=if profile {td.request(json!({"@type":"getUser","user_id":id})).await?} else {
                let cached=td.directory.lock().unwrap().get(&id).cloned();
                if let Some(chat)=cached {chat} else {td.request(json!({"@type":"getChat","chat_id":id})).await?}
            };
            let small=if profile {&entity["profile_photo"]["small"]} else {&entity["photo"]["small"]};
            let file=number(small,"id");
            let path=if file==0 {String::new()} else if boolean(&small["local"],"is_downloading_completed") && std::path::Path::new(&string(&small["local"],"path")).is_file() {
                string(&small["local"],"path")
            } else {
                let image=td.request(json!({"@type":"downloadFile","file_id":file,"priority":1,"offset":0,"limit":0,"synchronous":true})).await?;
                ensure!(boolean(&image["local"],"is_downloading_completed"),"Avatar download is incomplete");
                string(&image["local"],"path")
            };
            let rows=if profile {
                if id==store.account {
                    let title=format!("{} {}",string(&entity,"first_name"),string(&entity,"last_name")).trim().to_owned();
                    store.db.execute("INSERT OR IGNORE INTO profiles(id,title) VALUES(?,?)",params![id,title])?;
                }
                store.db.execute("UPDATE profiles SET photo=? WHERE id=? AND photo!=?",params![path,id,path])?
            } else {
                store.db.execute("INSERT INTO source_photos(account,id,path) VALUES(?,?,?) ON CONFLICT(account,id) DO UPDATE SET path=excluded.path WHERE path!=excluded.path",params![store.account,id,path])?
            };
            Ok::<_,anyhow::Error>((rows>0,path))
        }).await;
        if let Ok(Ok((_, path))) = result {
            changed.push((id, profile, path));
        }
    }
    changed
}
