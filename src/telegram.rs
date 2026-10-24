use crate::{
    model::*,
    platform::{self, Paths},
};
use anyhow::{Context, Result, bail, ensure};
use libloading::Library;
use serde_json::{Value, json};
use std::{
    collections::{HashMap, VecDeque},
    ffi::{CStr, CString, c_char, c_int},
    sync::{
        Arc, Condvar, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread,
    time::Duration,
};
use tokio::sync::{Notify, mpsc, oneshot};

struct Native {
    _library: Library,
    send: unsafe extern "C" fn(c_int, *const c_char),
    receive: unsafe extern "C" fn(f64) -> *const c_char,
    execute: unsafe extern "C" fn(*const c_char) -> *const c_char,
    create: unsafe extern "C" fn() -> c_int,
}

impl Native {
    fn load() -> Result<Arc<Self>> {
        let library = platform::native_library("tdjson")?;
        // Symbols are copied while the owning library remains alive in this object.
        unsafe {
            Ok(Arc::new(Self {
                send: *library.get(b"td_send\0")?,
                receive: *library.get(b"td_receive\0")?,
                execute: *library.get(b"td_execute\0")?,
                create: *library.get(b"td_create_client_id\0")?,
                _library: library,
            }))
        }
    }
}

#[derive(Default)]
pub struct Files {
    states: Mutex<HashMap<i32, FileState>>,
    pub changed: Condvar,
    pub closed: AtomicBool,
}

impl Files {
    pub fn remember(&self, file: &Value) -> FileState {
        let state = FileState::parse(file);
        self.states.lock().unwrap().insert(state.id, state.clone());
        self.changed.notify_all();
        state
    }
    pub fn get(&self, id: i32) -> Option<FileState> {
        self.states.lock().unwrap().get(&id).cloned()
    }
    pub fn wait(&self, id: i32, offset: i64, timeout: Duration) -> Option<FileState> {
        let states = self.states.lock().unwrap();
        let (states, _) = self
            .changed
            .wait_timeout_while(states, timeout, |states| {
                !self.closed.load(Ordering::Acquire)
                    && states.get(&id).is_none_or(|f| {
                        !f.complete && !(offset >= f.offset && offset < f.offset + f.prefix)
                    })
            })
            .unwrap();
        states.get(&id).cloned()
    }
    pub fn wait_complete(&self, id: i32, timeout: Duration) -> Option<FileState> {
        let states = self.states.lock().unwrap();
        let (states, _) = self
            .changed
            .wait_timeout_while(states, timeout, |states| {
                !self.closed.load(Ordering::Acquire) && states.get(&id).is_none_or(|f| !f.complete)
            })
            .unwrap();
        states.get(&id).cloned()
    }
    fn update(&self, file: &Value) -> Option<FileState> {
        let f = FileState::parse(file);
        let mut states = self.states.lock().unwrap();
        let previous = states.get(&f.id)?;
        let completed = f.complete && !previous.complete;
        states.insert(f.id, f.clone());
        drop(states);
        self.changed.notify_all();
        completed.then_some(f)
    }
}

type RequestResult = std::result::Result<Value, String>;
type Pending = Mutex<HashMap<u64, oneshot::Sender<RequestResult>>>;

struct RequestGuard {
    sequence: u64,
    pending: Arc<Pending>,
}
impl Drop for RequestGuard {
    fn drop(&mut self) {
        self.pending.lock().unwrap().remove(&self.sequence);
    }
}

pub struct Telegram {
    native: Arc<Native>,
    client: i32,
    sequence: AtomicU64,
    pending: Arc<Pending>,
    pub files: Arc<Files>,
    pub directory: Arc<Mutex<HashMap<i64, Value>>>,
    pub folders: Mutex<Vec<Folder>>,
    sent: Arc<Mutex<VecDeque<((i64, i64), Value)>>>,
    sent_changed: Arc<Notify>,
    stopped: Arc<AtomicBool>,
    pub overflow: Arc<AtomicBool>,
    authorization: Mutex<Option<Value>>,
}

impl Telegram {
    pub fn start(events: mpsc::Sender<Value>) -> Result<Arc<Self>> {
        let native = Native::load()?;
        let zero = CString::new(
            json!({"@type":"setLogVerbosityLevel","new_verbosity_level":0}).to_string(),
        )?;
        unsafe {
            (native.execute)(zero.as_ptr());
        }
        let client = unsafe { (native.create)() };
        let this = Arc::new(Self {
            native,
            client,
            sequence: AtomicU64::new(1),
            pending: Arc::new(Mutex::new(HashMap::new())),
            files: Arc::new(Files::default()),
            directory: Arc::new(Mutex::new(HashMap::new())),
            folders: Mutex::new(Vec::new()),
            sent: Arc::new(Mutex::new(VecDeque::new())),
            sent_changed: Arc::new(Notify::new()),
            stopped: Arc::new(AtomicBool::new(false)),
            overflow: Arc::new(AtomicBool::new(false)),
            authorization: Mutex::new(None),
        });
        let receiver = this.clone();
        thread::Builder::new()
            .name("telegram-events".into())
            .spawn(move || {
                while !receiver.stopped.load(Ordering::Acquire) {
                    let pointer = unsafe { (receiver.native.receive)(0.5) };
                    if pointer.is_null() {
                        continue;
                    }
                    let parsed = unsafe { CStr::from_ptr(pointer) }.to_bytes();
                    let Ok(value) = serde_json::from_slice::<Value>(parsed) else {
                        continue;
                    };
                    if value["@client_id"].as_i64().unwrap_or(client as i64) != client as i64 {
                        continue;
                    }
                    if let Some(extra) = value["@extra"].as_u64() {
                        if let Some(reply) = receiver.pending.lock().unwrap().remove(&extra) {
                            let result = if kind(&value) == "error" {
                                Err(format!(
                                    "{}: {}",
                                    number(&value, "code"),
                                    string(&value, "message")
                                ))
                            } else {
                                Ok(value.clone())
                            };
                            let _ = reply.send(result);
                        }
                    }
                    match kind(&value) {
                        "updateAuthorizationState" => {
                            let closed =
                                kind(&value["authorization_state"]) == "authorizationStateClosed";
                            *receiver.authorization.lock().unwrap() = Some(value);
                            let _ = events.try_send(json!({"@type":"spotygramAuthorization"}));
                            if closed {
                                receiver.stopped.store(true, Ordering::Release);
                            }
                            continue;
                        }
                        "updateFile" => {
                            if let Some(f) = receiver.files.update(&value["file"]) {
                                let event = json!({"@type":"spotygramFileComplete","id":f.id});
                                if events.try_send(event).is_err() {
                                    receiver.overflow.store(true, Ordering::Release);
                                }
                            }
                            continue;
                        }
                        "updateNewChat" => {
                            receiver
                                .directory
                                .lock()
                                .unwrap()
                                .insert(number(&value["chat"], "id"), value["chat"].clone());
                            if !boolean(&value["chat"]["type"], "is_channel") {
                                continue;
                            }
                        }
                        "updateChatTitle"
                        | "updateChatPosition"
                        | "updateChatPhoto"
                        | "updateChatPermissions" => {
                            if let Some(chat) = receiver
                                .directory
                                .lock()
                                .unwrap()
                                .get_mut(&number(&value, "chat_id"))
                            {
                                match kind(&value) {
                                    "updateChatTitle" => chat["title"] = value["title"].clone(),
                                    "updateChatPhoto" => chat["photo"] = value["photo"].clone(),
                                    "updateChatPermissions" => {
                                        chat["permissions"] = value["permissions"].clone()
                                    }
                                    _ => {}
                                }
                            }
                            if kind(&value) != "updateChatPhoto" {
                                continue;
                            }
                        }
                        "updateMessageSendSucceeded" | "updateMessageSendFailed" => {
                            let chat = number(&value["message"], "chat_id");
                            let id = if kind(&value) == "updateMessageSendSucceeded" {
                                number(&value, "old_message_id")
                            } else {
                                number(&value["message"], "id")
                            };
                            let mut sent = receiver.sent.lock().unwrap();
                            if sent.len() >= 128 {
                                sent.pop_front();
                            }
                            sent.push_back(((chat, id), value.clone()));
                            drop(sent);
                            receiver.sent_changed.notify_waiters();
                        }
                        "updateNewMessage" => {
                            let content = kind(&value["message"]["content"]);
                            if !["messageAudio", "messageDocument"].contains(&content) {
                                continue;
                            }
                        }
                        "updateChatFolders" => {
                            *receiver.folders.lock().unwrap() = value["chat_folders"]
                                .as_array()
                                .into_iter()
                                .flatten()
                                .map(|folder| Folder {
                                    id: number(folder, "id") as i32,
                                    title: folder["name"]["text"]["text"]
                                        .as_str()
                                        .or_else(|| folder["name"]["text"].as_str())
                                        .or_else(|| folder["title"].as_str())
                                        .unwrap_or("Folder")
                                        .to_owned(),
                                })
                                .collect();
                        }
                        "updateConnectionState"
                        | "updateDeleteMessages"
                        | "updateMessageContent"
                        | "updateUser" => {}
                        _ => continue,
                    }
                    if let Err(error) = events.try_send(value) {
                        if matches!(error, mpsc::error::TrySendError::Closed(_)) {
                            break;
                        }
                        receiver.overflow.store(true, Ordering::Release);
                    }
                }
                receiver.files.closed.store(true, Ordering::Release);
                receiver.files.changed.notify_all();
                receiver.pending.lock().unwrap().clear();
            })?;
        // Creating a client does not emit the initial authorization update until its first request.
        this.send(json!({"@type":"getAuthorizationState"}))?;
        Ok(this)
    }

    pub fn send(&self, value: Value) -> Result<()> {
        ensure!(
            !self.stopped.load(Ordering::Acquire),
            "Telegram client is closed"
        );
        let encoded = CString::new(value.to_string())?;
        unsafe {
            (self.native.send)(self.client, encoded.as_ptr());
        }
        Ok(())
    }

    pub async fn request(&self, value: Value) -> Result<Value> {
        self.request_timeout(value, Duration::from_secs(30)).await
    }

    pub async fn request_timeout(&self, mut value: Value, timeout: Duration) -> Result<Value> {
        let sequence = self.sequence.fetch_add(1, Ordering::Relaxed);
        value["@extra"] = json!(sequence);
        let (sender, receiver) = oneshot::channel();
        self.pending.lock().unwrap().insert(sequence, sender);
        let _guard = RequestGuard {
            sequence,
            pending: self.pending.clone(),
        };
        if let Err(error) = self.send(value) {
            self.pending.lock().unwrap().remove(&sequence);
            return Err(error);
        }
        let result = tokio::time::timeout(timeout, receiver).await;
        self.pending.lock().unwrap().remove(&sequence);
        match result {
            Ok(Ok(Ok(value))) => Ok(value),
            Ok(Ok(Err(error))) => bail!("{error}"),
            Ok(Err(_)) => bail!("Telegram connection was closed"),
            Err(_) => bail!("Telegram request timed out"),
        }
    }

    pub async fn configure(&self, paths: &Arc<Paths>) -> Result<()> {
        let id = env!("TELEGRAM_API_ID")
            .parse::<i64>()
            .context("This build has no Telegram API configuration")?;
        let hash = env!("TELEGRAM_API_HASH");
        ensure!(
            !hash.is_empty(),
            "This build has no Telegram API configuration"
        );
        let key_paths = paths.clone();
        let key = tokio::task::spawn_blocking(move || key_paths.database_key()).await??;
        let database = paths.root.join("telegram/db");
        let files = paths.root.join("telegram/files");
        std::fs::create_dir_all(&database)?;
        std::fs::create_dir_all(&files)?;
        self.request(json!({"@type":"setTdlibParameters","database_directory":database,"files_directory":files,"database_encryption_key":key,"use_file_database":true,"use_chat_info_database":true,"use_message_database":true,"use_secret_chats":false,"use_test_dc":false,"api_id":id,"api_hash":hash,"system_language_code":"en","device_model":format!("spotygram rust ({})",std::env::consts::OS),"system_version":std::env::consts::OS,"application_version":env!("CARGO_PKG_VERSION")})).await?;
        Ok(())
    }

    pub async fn authenticate(&self, kind: &str, value: &str) -> Result<()> {
        let query = match kind {
            "authorizationStateWaitPhoneNumber" => {
                let compact: String = value
                    .trim()
                    .chars()
                    .filter(|c| !c.is_whitespace() && !"()-\u{2011}.".contains(*c))
                    .collect();
                let phone = if let Some(rest) = compact.strip_prefix("00") {
                    format!("+{rest}")
                } else {
                    compact
                };
                ensure!(
                    phone.starts_with('+')
                        && (6..=16).contains(&phone.len())
                        && phone[1..].bytes().all(|c| c.is_ascii_digit())
                        && !phone.starts_with("+0"),
                    "Enter a phone number with its country code"
                );
                json!({"@type":"setAuthenticationPhoneNumber","phone_number":phone,"settings":{"@type":"phoneNumberAuthenticationSettings","allow_missed_call":true}})
            }
            "authorizationStateWaitCode" => {
                json!({"@type":"checkAuthenticationCode","code":value.trim()})
            }
            "authorizationStateWaitPassword" => {
                json!({"@type":"checkAuthenticationPassword","password":value})
            }
            "authorizationStateWaitEmailAddress" => {
                json!({"@type":"setAuthenticationEmailAddress","email_address":value.trim()})
            }
            "authorizationStateWaitEmailCode" => {
                json!({"@type":"checkAuthenticationEmailCode","code":{"@type":"emailAddressAuthenticationCode","code":value.trim()}})
            }
            _ => bail!("Authorization step changed; submit the current form again"),
        };
        self.request(query).await?;
        Ok(())
    }

    pub fn auth(state: &Value) -> Auth {
        let kind = kind(state).to_owned();
        let code = &state["code_info"];
        let detail = match kind.as_str() {
            "authorizationStateWaitPassword" => string(state, "password_hint"),
            "authorizationStateWaitCode" => string(&code["type"], "@type"),
            "authorizationStateWaitEmailCode" => string(code, "email_address_pattern"),
            "authorizationStateWaitOtherDeviceConfirmation" => string(state, "link"),
            _ => String::new(),
        };
        Auth {
            kind,
            detail,
            phone: string(code, "phone_number"),
            next_code: string(&code["next_type"], "@type"),
            resend_at: now() + number(code, "timeout").max(0) as u64,
            ..Default::default()
        }
    }

    pub async fn file(&self, id: i32) -> Result<FileState> {
        let value = self
            .request(json!({"@type":"getFile","file_id":id}))
            .await?;
        Ok(self.files.remember(&value))
    }

    pub fn download(&self, id: i32, offset: i64, priority: i32) -> Result<()> {
        self.send(json!({"@type":"downloadFile","file_id":id,"priority":priority,"offset":offset,"limit":0,"synchronous":false}))
    }

    pub async fn wait_file(&self, id: i32) -> Result<FileState> {
        self.wait_file_progress(id, None, 0).await
    }

    pub async fn wait_file_progress(
        &self,
        id: i32,
        notices: Option<&mpsc::Sender<Notice>>,
        count: usize,
    ) -> Result<FileState> {
        self.file(id).await?;
        self.download(id, 0, 16)?;
        let mut progress_at = tokio::time::Instant::now();
        let mut downloaded = 0;
        loop {
            let files = self.files.clone();
            let f = tokio::task::spawn_blocking(move || {
                files.wait_complete(id, Duration::from_secs(1))
            })
            .await?;
            if let Some(f) = f {
                if f.complete && valid_copy(&f.path, f.size) {
                    return Ok(f);
                }
                if f.downloaded > downloaded {
                    downloaded = f.downloaded;
                    progress_at = tokio::time::Instant::now();
                    if let Some(notices) = notices {
                        let _ = notices.try_send(Notice::Transfer(
                            count,
                            if f.size > 0 {
                                f.downloaded as f64 / f.size as f64
                            } else {
                                0.
                            },
                        ));
                    }
                }
            }
            ensure!(
                progress_at.elapsed() < Duration::from_secs(120),
                "Telegram file download stalled"
            );
            ensure!(
                !self.files.closed.load(Ordering::Acquire),
                "Telegram connection was closed"
            );
        }
    }

    pub fn authorization_update(&self) -> Option<Value> {
        self.authorization.lock().unwrap().take()
    }

    pub async fn send_confirmed(&self, request: Value) -> Result<Value> {
        let pending = self.request(request).await?;
        if pending["sending_state"].is_null() {
            return Ok(pending);
        }
        let identity = (number(&pending, "chat_id"), number(&pending, "id"));
        let start = tokio::time::Instant::now();
        loop {
            let notified = self.sent_changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            let result = {
                let mut sent = self.sent.lock().unwrap();
                sent.iter()
                    .position(|(id, _)| *id == identity)
                    .and_then(|i| sent.remove(i))
                    .map(|(_, v)| v)
            };
            if let Some(value) = result {
                if kind(&value) == "updateMessageSendFailed" {
                    bail!("{}", string(&value["error"], "message"));
                }
                return Ok(value["message"].clone());
            }
            let remaining = Duration::from_secs(86400).saturating_sub(start.elapsed());
            ensure!(
                !remaining.is_zero(),
                "Telegram has not confirmed delivery; check the recipient chat before retrying"
            );
            let _ = tokio::time::timeout(remaining, notified).await;
        }
    }

    pub async fn close(&self) {
        let _ = tokio::time::timeout(
            Duration::from_secs(5),
            self.request(json!({"@type":"close"})),
        )
        .await;
        self.stopped.store(true, Ordering::Release);
        self.files.closed.store(true, Ordering::Release);
        self.files.changed.notify_all();
    }
}
