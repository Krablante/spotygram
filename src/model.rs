use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    path::Path,
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Track {
    pub id: String,
    pub chat: i64,
    pub message: i64,
    pub file: i32,
    pub title: String,
    pub artist: String,
    pub duration: i64,
    pub size: i64,
    pub source: String,
    pub date: i64,
    pub art: String,
    pub path: String,
    pub liked: bool,
    pub available: bool,
    pub document: bool,
    pub key: String,
    pub remote: String,
    pub saved: bool,
    pub temporary: bool,
    pub profile: i64,
    pub profile_position: i64,
    pub username: String,
    #[serde(skip)]
    pub queue_position: Option<usize>,
}

impl Track {
    pub fn imported(&self) -> bool {
        self.chat == 0 && self.profile == 0
    }
    pub fn local(&self) -> bool {
        valid_copy(&self.path, self.size)
    }
    pub fn subtitle(&self) -> &str {
        if !self.artist.is_empty() {
            &self.artist
        } else if self.imported() {
            "From device"
        } else {
            &self.source
        }
    }
    pub fn descriptor(&self) -> Value {
        json!({"id": self.id, "chat": self.chat, "message": self.message,
            "title": self.title, "artist": self.artist, "duration": self.duration,
            "size": self.size, "source": self.source, "date": self.date,
            "document": self.document, "key": self.key, "remote": self.remote,
            "profile": self.profile, "username": self.username})
    }
    pub fn source_key(&self) -> String {
        if self.profile != 0 {
            format!("profile:{}", self.profile)
        } else {
            format!("chat:{}", self.chat)
        }
    }
    pub fn search_text(&self) -> String {
        format!("{} {} {}", self.title, self.artist, self.source).to_lowercase()
    }
    pub fn metadata_key(&self) -> Option<String> {
        (self.duration > 0 && self.size > 0).then(|| {
            format!(
                "{}|{}|{}|{}",
                self.title
                    .split_whitespace()
                    .collect::<Vec<_>>()
                    .join(" ")
                    .to_lowercase(),
                self.artist
                    .split_whitespace()
                    .collect::<Vec<_>>()
                    .join(" ")
                    .to_lowercase(),
                self.duration,
                self.size
            )
        })
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Source {
    pub id: i64,
    pub title: String,
    pub profile: bool,
    pub username: String,
    pub photo: String,
    pub selected: bool,
    pub count: usize,
    pub complete: bool,
    pub forum: bool,
    #[serde(default)]
    pub kind: String,
}

#[derive(Clone, Debug, Default)]
pub struct Folder {
    pub id: i32,
    pub title: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Playlist {
    pub id: i64,
    pub uid: String,
    pub name: String,
    pub count: usize,
    #[serde(default)]
    pub covers: Vec<Artwork>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Artwork {
    pub title: String,
    pub path: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Screen {
    #[default]
    Music,
    Favorites,
    Playlists,
    Playlist(i64),
    OnDevice,
    Sources,
    Profile(i64),
    Queue,
    PlaylistEditor(Option<i64>),
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Sort {
    #[default]
    Newest,
    Oldest,
    Title,
    Artist,
    Duration,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Query {
    pub screen: Screen,
    pub search: String,
    pub source: String,
    pub sort: Sort,
    pub duplicates: bool,
}

#[derive(Clone, Debug, Default)]
pub struct TrackPage {
    pub query: Query,
    pub revision: u64,
    pub total: usize,
    pub offset: usize,
    pub tracks: Arc<Vec<Track>>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Auth {
    pub kind: String,
    pub detail: String,
    pub phone: String,
    pub next_code: String,
    pub resend_at: u64,
    pub busy: bool,
    pub error: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub enum Order {
    #[default]
    Normal,
    Reverse,
    Shuffle,
    Random,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub enum Repeat {
    #[default]
    Off,
    All,
    One,
}

pub const MIN_PLAYBACK_SPEED: f64 = 0.3;
pub const MAX_PLAYBACK_SPEED: f64 = 4.0;
pub const PLAYBACK_SPEED_STEP: f64 = 0.05;

pub fn playback_speed_step(value: f64) -> f64 {
    let ticks = 1.0 / PLAYBACK_SPEED_STEP;
    ((value * ticks).round() / ticks).clamp(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Queue {
    #[serde(skip)]
    pub ids: Vec<String>,
    pub index: usize,
    pub position: f64,
    pub order: Order,
    pub repeat: Repeat,
    pub speed: f64,
    pub volume: f64,
    pub muted: bool,
    pub shuffled: Vec<usize>,
    pub history: Vec<usize>,
}

impl Default for Queue {
    fn default() -> Self {
        Self {
            ids: vec![],
            index: 0,
            position: 0.,
            order: Order::Normal,
            repeat: Repeat::Off,
            speed: 1.,
            volume: 100.,
            muted: false,
            shuffled: vec![],
            history: vec![],
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct Playback {
    pub track: Option<Track>,
    pub requested: bool,
    pub playing: bool,
    pub buffering: bool,
    pub position: f64,
    pub duration: f64,
    pub speed: f64,
    pub volume: f64,
    pub muted: bool,
    pub order: Order,
    pub repeat: Repeat,
    pub queue_count: usize,
    pub queue_index: usize,
}

#[derive(Clone, Debug, Default)]
pub struct Snapshot {
    pub revision: u64,
    pub query_request: u64,
    pub auth: Auth,
    pub me: i64,
    pub page: TrackPage,
    pub sources: Arc<Vec<Source>>,
    pub playlists: Arc<Vec<Playlist>>,
    pub choices: Arc<Vec<Source>>,
    pub avatar_paths: Arc<HashMap<(i64, bool), String>>,
    pub selection_ids: Arc<Vec<String>>,
    pub selection_query: Query,
    pub selection_revision: u64,
    pub selection_error: String,
    pub playlist_saved_id: i64,
    pub playlist_save_revision: u64,
    pub choices_scope: String,
    pub folders: Arc<Vec<Folder>>,
    pub playback: Playback,
    pub settings: Value,
    pub language: String,
    pub status: String,
    pub error: String,
    pub busy: bool,
    pub downloads: usize,
    pub transfer_progress: f64,
    pub sync: String,
    pub storage_bytes: i64,
    pub cache_size: Option<(u64, u64)>,
    pub cache_loading: bool,
    pub cache_error: String,
    pub storage_count: usize,
    pub profile_member: Option<bool>,
    pub profile_member_id: String,
    pub profile_open: Option<(u64, Result<i64, String>)>,
    pub closed: bool,
}

#[derive(Debug)]
pub enum Command {
    Query(Query, usize, u64),
    Page(usize),
    Artwork(Vec<String>),
    SourcePhotos(Vec<(i64, bool)>),
    CacheInfo,
    SelectAll(Query),
    ClearSelection,
    StartTelegram,
    AuthSubmit {
        kind: String,
        phone: String,
        value: String,
    },
    Qr,
    ChangePhone,
    Resend,
    Logout,
    Discover(String, i32),
    ConnectSource(Source, bool),
    Refresh,
    OpenProfile(String, u64),
    CancelProfile,
    Play(String),
    PlayQueue(usize),
    PlayAll,
    Toggle,
    Resume,
    Pause,
    Next,
    Previous,
    Seek(f64),
    Speed(f64),
    SpeedStep(i8),
    Volume(f64),
    Mute,
    Order(Order),
    Repeat(Repeat),
    QueueAdd(Vec<String>),
    QueueRemove(usize),
    QueueMove(usize, usize),
    Favorite(Vec<String>, bool),
    PlaylistSave {
        playlist: Option<i64>,
        name: String,
        ids: Vec<String>,
    },
    PlaylistRename(i64, String),
    PlaylistDelete(i64),
    PlaylistAdd(i64, Vec<String>),
    PlaylistRemove(i64, Vec<String>),
    PlaylistMove(i64, String, bool),
    Import(Vec<std::path::PathBuf>),
    PickFiles,
    Download(Vec<String>),
    CancelDownloads,
    Delete(Vec<String>),
    ClearCache,
    Setting(String, Value),
    ToggleSetting(String),
    ShareRecipients(String, i32, bool),
    ShareTopics(i64),
    Send {
        track: String,
        recipient: i64,
        topic: i64,
        sender: bool,
        caption: bool,
    },
    ProfileMember(String),
    SetProfileMember(String, bool),
    Visible(bool),
    ObserveClock(bool),
    CheckUpdate,
    NetworkChanged,
    Quit,
}

#[derive(Debug)]
pub enum Notice {
    SourcePhotos(u64, Vec<(i64, bool, String)>),
    CacheInfo(i64, Result<(u64, u64), String>),
    Catalog,
    Indexed(u64, Option<String>),
    Status(String),
    Error(String),
    Sync(String),
    CloudLibrary {
        sources_changed: bool,
    },
    Choices(u64, Vec<Source>),
    Profile(u64, Result<i64, String>),
    ProfileMember(String, bool),
    Transfer(usize, f64),
    Sent,
    Engine(EngineEvent),
    #[cfg(target_os = "android")]
    EngineWake,
    Prepared(u64, Track, f64),
    Imported,
    Update(String),
    AuthResult(String, String, Option<String>),
}

#[derive(Clone, Debug)]
pub enum EngineEvent {
    Loaded(u64),
    Position(u64, f64),
    Duration(u64, f64),
    Buffering(u64, bool),
    Paused(u64, bool, Option<f64>),
    Finished(u64),
    Failed(u64, String),
    Unavailable(u64, Option<f64>),
    Interrupted(u64, f64),
}

#[derive(Clone, Debug, Default)]
pub struct FileState {
    pub id: i32,
    pub path: String,
    pub size: i64,
    pub downloaded: i64,
    pub offset: i64,
    pub prefix: i64,
    pub complete: bool,
    pub active: bool,
    pub key: String,
    pub remote: String,
}

impl FileState {
    pub fn parse(v: &Value) -> Self {
        let l = &v["local"];
        Self {
            id: v["id"].as_i64().unwrap_or(0) as i32,
            path: string(l, "path"),
            size: if number(v, "size") > 0 {
                number(v, "size")
            } else {
                number(v, "expected_size")
            },
            downloaded: number(l, "downloaded_size"),
            offset: number(l, "download_offset"),
            prefix: number(l, "downloaded_prefix_size"),
            complete: boolean(l, "is_downloading_completed"),
            active: boolean(l, "is_downloading_active"),
            key: string(&v["remote"], "unique_id"),
            remote: string(&v["remote"], "id"),
        }
    }
}

pub fn string(v: &Value, key: &str) -> String {
    v[key].as_str().unwrap_or_default().to_owned()
}
pub fn number(v: &Value, key: &str) -> i64 {
    v[key].as_i64().unwrap_or_default()
}
pub fn boolean(v: &Value, key: &str) -> bool {
    v[key].as_bool().unwrap_or_default()
}
pub fn kind(v: &Value) -> &str {
    v["@type"].as_str().unwrap_or_default()
}
pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
pub fn profile_track_id(user: i64, key: &str) -> String {
    format!("profile:{user}:{:x}", Sha256::digest(key.as_bytes()))
}
pub fn valid_copy(path: &str, size: i64) -> bool {
    !path.is_empty()
        && Path::new(path)
            .metadata()
            .is_ok_and(|m| m.is_file() && m.len() > 0 && (size <= 0 || m.len() == size as u64))
}
pub fn seconds(value: f64) -> String {
    let s = value.max(0.) as u64;
    format!("{}:{:02}", s / 60, s % 60)
}
