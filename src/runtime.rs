use crate::{
    cloud::Cloud,
    library,
    model::*,
    platform::{self, Paths},
    player::Engine,
    reader::Media,
    store::Store,
    telegram::Telegram,
};
use anyhow::{Context, Result, ensure};
use arc_swap::ArcSwap;
use makepad_widgets::SignalToUI;
use rand::{Rng, seq::SliceRandom};
use rusqlite::params;
use serde_json::{Value, json};
use std::{
    collections::HashSet,
    sync::{Arc, OnceLock},
    time::Duration,
};
use tokio::{sync::mpsc, task::JoinHandle};

pub struct Client {
    pub commands: mpsc::Sender<Command>,
    pub snapshot: Arc<ArcSwap<Snapshot>>,
}

static CLIENT: OnceLock<Arc<Client>> = OnceLock::new();

pub fn start() -> Arc<Client> {
    CLIENT
        .get_or_init(|| {
            let (commands, receiver) = mpsc::channel(128);
            let snapshot = Arc::new(ArcSwap::from_pointee(Snapshot::default()));
            let client = Arc::new(Client {
                commands,
                snapshot: snapshot.clone(),
            });
            std::thread::Builder::new()
                .name("spotygram".into())
                .spawn(move || {
                    let result =
                        (|| {
                            let runtime = tokio::runtime::Builder::new_current_thread()
                                .max_blocking_threads(2)
                                .enable_all()
                                .build()?;
                            let local = tokio::task::LocalSet::new();
                            runtime.block_on(local.run_until(async {
                                Core::new(snapshot.clone())?.run(receiver).await
                            }))
                        })();
                    if let Err(error) = result {
                        let mut state = Snapshot::default();
                        state.error = error.to_string();
                        state.status =
                            "The application could not start. Your library has been preserved."
                                .into();
                        snapshot.store(Arc::new(state));
                        SignalToUI::set_ui_signal();
                        eprintln!("Startup: {error:#}");
                    }
                })
                .expect("Cannot start the application worker");
            client
        })
        .clone()
}

#[cfg(target_os = "android")]
pub fn existing() -> Option<&'static Arc<Client>> {
    CLIENT.get()
}

struct Core {
    paths: Arc<Paths>,
    store: Store,
    state: Snapshot,
    output: Arc<ArcSwap<Snapshot>>,
    notices: mpsc::Sender<Notice>,
    notice_rx: mpsc::Receiver<Notice>,
    native_tx: mpsc::Sender<Value>,
    native_rx: mpsc::Receiver<Value>,
    td: Option<Arc<Telegram>>,
    auth_job: Option<JoinHandle<()>>,
    restart_auth: bool,
    engine: Option<Engine>,
    #[cfg(not(target_os = "android"))]
    media: Option<crate::media::Controls>,
    queue: Queue,
    query: Query,
    generation: u64,
    loaded_generation: u64,
    loading_position: f64,
    preparing_deadline: Option<(u64, tokio::time::Instant)>,
    directory_generation: u64,
    intent: bool,
    engine_paused: bool,
    visible: bool,
    observe_clock: bool,
    sync_signal: Option<mpsc::Sender<()>>,
    sync_job: Option<JoinHandle<()>>,
    index_job: Option<JoinHandle<()>>,
    index_pending: bool,
    index_generation: u64,
    load_job: Option<JoinHandle<()>>,
    directory_job: Option<JoinHandle<()>>,
    artwork_job: Option<JoinHandle<()>>,
    avatar_job: Option<JoinHandle<()>>,
    avatar_pending: Vec<(i64, bool)>,
    avatar_visible: HashSet<(i64, bool)>,
    avatar_epoch: u64,
    cache_job: Option<JoinHandle<()>>,
    downloads_job: Option<JoinHandle<()>>,
    transfer_job: Option<JoinHandle<()>>,
    cleanup_job: Option<JoinHandle<()>>,
    last_publication: tokio::time::Instant,
    checkpoint: tokio::time::Instant,
    pending_publish: bool,
    controls_deadline: Option<tokio::time::Instant>,
}

impl Core {
    fn new(output: Arc<ArcSwap<Snapshot>>) -> Result<Self> {
        let paths = Paths::open()?;
        let store = Store::open(&paths.root.join("library.db"))?;
        let queue = store.load_queue()?;
        let settings = store.settings()?;
        let query = Query {
            duplicates: settings["hide_duplicates"] != false,
            ..Default::default()
        };
        let (notices, notice_rx) = mpsc::channel(256);
        let (native_tx, native_rx) = mpsc::channel(512);
        let mut core = Self {
            paths,
            store,
            state: Snapshot {
                settings,
                ..Default::default()
            },
            output,
            notices,
            notice_rx,
            native_tx,
            native_rx,
            td: None,
            auth_job: None,
            restart_auth: false,
            engine: None,
            #[cfg(not(target_os = "android"))]
            media: None,
            queue,
            query,
            generation: 0,
            loaded_generation: 0,
            loading_position: 0.,
            preparing_deadline: None,
            directory_generation: 0,
            intent: false,
            engine_paused: true,
            visible: true,
            observe_clock: false,
            sync_signal: None,
            sync_job: None,
            index_job: None,
            index_pending: false,
            index_generation: 0,
            load_job: None,
            directory_job: None,
            artwork_job: None,
            avatar_job: None,
            avatar_pending: Vec::new(),
            avatar_visible: HashSet::new(),
            avatar_epoch: 0,
            cache_job: None,
            downloads_job: None,
            transfer_job: None,
            cleanup_job: None,
            last_publication: tokio::time::Instant::now(),
            checkpoint: tokio::time::Instant::now(),
            pending_publish: false,
            controls_deadline: None,
        };
        core.refresh()?;
        if let Some(id) = core.queue.ids.get(core.queue.index) {
            core.state.playback.track = core.store.track(id)?;
            core.state.playback.position = core.queue.position;
            core.state.playback.duration = core
                .state
                .playback
                .track
                .as_ref()
                .map(|t| t.duration as f64)
                .unwrap_or(0.);
        }
        core.publish();
        Ok(core)
    }

    async fn run(mut self, mut commands: mpsc::Receiver<Command>) -> Result<()> {
        if self
            .store
            .setting::<bool>("telegram_connected")?
            .unwrap_or(false)
        {
            self.start_telegram()?;
        }
        loop {
            #[cfg(target_os = "android")]
            for event in crate::android::take_engine_events() {
                if let Err(error) = self.engine_event(event) {
                    self.state.error = error.to_string();
                    self.publish();
                }
            }
            tokio::select! {
                command=commands.recv()=>{
                    let Some(command)=command else{break;};
                    if matches!(command,Command::Quit){break;}
                    if let Some(authorization)=self.td.as_ref().and_then(|td|td.authorization_update()){
                        if let Err(error)=self.native_event(authorization).await{self.state.error=error.to_string();self.publish();}
                    }
                    let playback_command=matches!(&command,Command::Play(..)|Command::PlayAll|Command::PlayQueue(..)|Command::Resume|Command::Pause|Command::Toggle|Command::Next|Command::Previous|Command::Seek(..));
                    let previous_generation=self.generation;
                    if let Err(error)=self.command(command).await{
                        if playback_command{if self.generation!=previous_generation || self.state.playback.buffering || self.engine.is_none(){let _=self.engine_event(EngineEvent::Failed(self.generation,error.to_string()));}else{self.intent=self.state.playback.playing;}}
                        self.state.error=error.to_string();self.publish();
                    }
                },
                Some(notice)=self.notice_rx.recv()=>{
                    if let Err(error)=self.notice(notice).await{self.state.error=error.to_string();self.publish();}
                },
                Some(event)=self.native_rx.recv()=>{
                    let authorization=self.td.as_ref().and_then(|td|td.authorization_update());
                    if let Some(authorization)=authorization{if let Err(error)=self.native_event(authorization).await{self.state.error=error.to_string();self.publish();}}
                    if let Err(error)=self.native_event(event).await{self.state.error=error.to_string();self.publish();}
                },
                _=async{if self.pending_publish{tokio::time::sleep_until(self.last_publication+Duration::from_millis(250)).await}else{std::future::pending::<()>().await}}=>{self.publish();},
                _=async{if let Some(deadline)=self.controls_deadline{tokio::time::sleep_until(deadline).await}else{std::future::pending::<()>().await}}=>{
                    self.controls_deadline=None;
                    if let Err(error)=self.store.save_queue(&self.queue,false){self.state.error=error.to_string();self.publish();}
                },
                _=async{if let Some((_,deadline))=self.preparing_deadline{tokio::time::sleep_until(deadline).await}else{std::future::pending::<()>().await}}=>{
                    let (generation,_)=self.preparing_deadline.take().unwrap();
                    if generation==self.generation && self.loaded_generation!=generation && self.engine.is_some(){
                        if let Err(error)=self.engine_event(EngineEvent::Failed(generation,"Preparing audio timed out; press Play to try again".into())){self.state.error=error.to_string();self.publish();}
                    }
                },
            }
        }
        self.intent = false;
        if let Some(engine) = &self.engine {
            let _ = engine.stop();
        }
        self.save_checkpoint(true)?;
        for task in [
            &mut self.auth_job,
            &mut self.index_job,
            &mut self.load_job,
            &mut self.directory_job,
            &mut self.artwork_job,
            &mut self.avatar_job,
            &mut self.cache_job,
            &mut self.downloads_job,
            &mut self.transfer_job,
            &mut self.cleanup_job,
            &mut self.sync_job,
        ] {
            if let Some(task) = task.take() {
                task.abort();
                let _ = task.await;
            }
        }
        self.engine.take();
        if let Some(td) = self.td.take() {
            td.close().await;
        }
        #[cfg(target_os = "android")]
        {
            let _ = crate::android::call_string("shutdown", "");
        }
        self.state.closed = true;
        self.publish();
        Ok(())
    }

    fn refresh(&mut self) -> Result<()> {
        self.state.page = self.store.page(&self.query, self.state.page.offset)?;
        self.state.sources = Arc::new(self.store.sources()?);
        self.state.playlists = Arc::new(self.store.playlists()?);
        if let Some(td) = &self.td {
            self.state.folders = Arc::new(td.folders.lock().unwrap().clone());
        }
        self.state.settings = self.store.settings()?;
        self.state.language =
            crate::i18n::language(self.state.settings["language"].as_str().unwrap_or("system"));
        let (bytes, count) = self.store.storage()?;
        self.state.storage_bytes = bytes;
        self.state.storage_count = count;
        self.state.downloads = self.store.downloads()?.len();
        if let Some(current) = &self.state.playback.track {
            if let Some(track) = self.store.track(&current.id)? {
                if !current.key.is_empty() && current.key != track.key {
                    self.intent = false;
                    self.generation += 1;
                    if let Some(job) = self.load_job.take() {
                        job.abort();
                    }
                    if let Some(engine) = self.engine.take() {
                        let _ = engine.stop();
                    }
                    self.state.playback.playing = false;
                    self.state.playback.buffering = false;
                    self.state.playback.position = 0.;
                }
                self.state.playback.track = Some(track);
            }
        }
        self.queue_state();
        Ok(())
    }

    fn queue_state(&mut self) {
        self.state.playback.order = self.queue.order.clone();
        self.state.playback.repeat = self.queue.repeat.clone();
        self.state.playback.speed = self.queue.speed;
        self.state.playback.volume = self.queue.volume;
        self.state.playback.muted = self.queue.muted;
        self.state.playback.queue_count = self.queue.ids.len();
        self.state.playback.queue_index = self.queue.index;
    }

    fn publish(&mut self) {
        self.state.playback.requested = self.intent;
        #[cfg(not(target_os = "android"))]
        if let Some(media) = &mut self.media {
            if let Err(error) = media.update(&self.state.playback) {
                eprintln!("System media controls: {error}");
            }
        }
        self.state.revision += 1;
        self.output.store(Arc::new(self.state.clone()));
        SignalToUI::set_ui_signal();
        self.pending_publish = false;
        self.last_publication = tokio::time::Instant::now();
    }

    fn signal_sync(&self) {
        if let Some(signal) = &self.sync_signal {
            let _ = signal.try_send(());
        }
    }

    fn start_telegram(&mut self) -> Result<()> {
        if self.td.is_some() {
            return Ok(());
        }
        self.state.auth = Auth {
            kind: "starting".into(),
            busy: true,
            ..Default::default()
        };
        self.publish();
        self.store.forget_native_ids()?;
        self.td = Some(Telegram::start(self.native_tx.clone())?);
        Ok(())
    }

    fn telegram(&self) -> Result<Arc<Telegram>> {
        self.td
            .clone()
            .context("Connect your Telegram account first")
    }

    fn stop_account_work(&mut self) {
        for job in [
            &mut self.auth_job,
            &mut self.sync_job,
            &mut self.index_job,
            &mut self.downloads_job,
            &mut self.transfer_job,
            &mut self.load_job,
            &mut self.artwork_job,
            &mut self.directory_job,
            &mut self.cleanup_job,
        ] {
            if let Some(job) = job.take() {
                job.abort();
            }
        }
        self.index_pending = false;
        self.avatar_pending.clear();
        self.avatar_visible.clear();
        self.state.avatar_paths = Arc::default();
        self.avatar_epoch += 1;
        self.state.cache_size = None;
        self.state.cache_loading = false;
        self.state.cache_error.clear();
        self.index_generation += 1;
        self.directory_generation += 1;
        self.sync_signal = None;
        self.generation += 1;
        self.intent = false;
        if let Some(engine) = self.engine.take() {
            let _ = engine.stop();
        }
        self.state.playback.playing = false;
        self.state.playback.buffering = false;
        self.state.choices = Arc::default();
        self.state.folders = Arc::default();
        self.state.choices_scope.clear();
        self.state.me = 0;
        self.state.profile_member = None;
        self.state.profile_member_id.clear();
    }

    fn auth_request(&mut self, future: impl std::future::Future<Output = Result<()>> + 'static) {
        if let Some(job) = self.auth_job.take() {
            job.abort();
        }
        let notices = self.notices.clone();
        let step = self.state.auth.kind.clone();
        let phone = self.state.auth.phone.clone();
        self.state.auth.busy = true;
        self.auth_job = Some(tokio::task::spawn_local(async move {
            let error = future.await.err().map(|e| e.to_string());
            let _ = notices.send(Notice::AuthResult(step, phone, error)).await;
        }));
    }

    fn configure_telegram(&mut self) -> Result<()> {
        if self.auth_job.as_ref().is_some_and(|job| !job.is_finished()) {
            return Ok(());
        }
        let td = self.telegram()?;
        let paths = self.paths.clone();
        self.auth_request(async move { td.configure(&paths).await });
        Ok(())
    }

    fn spawn(
        &self,
        future: impl std::future::Future<Output = Result<()>> + 'static,
    ) -> JoinHandle<()> {
        let notices = self.notices.clone();
        tokio::task::spawn_local(async move {
            if let Err(error) = future.await {
                let _ = notices.send(Notice::Error(error.to_string())).await;
            }
        })
    }

    fn start_index(&mut self) -> Result<()> {
        if self
            .index_job
            .as_ref()
            .is_some_and(|job| !job.is_finished())
        {
            self.index_pending = true;
            return Ok(());
        }
        let td = self.telegram()?;
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        self.index_generation += 1;
        let generation = self.index_generation;
        self.index_pending = false;
        self.index_job = Some(tokio::task::spawn_local(async move {
            let error = library::index(td, paths, notices.clone())
                .await
                .err()
                .map(|e| e.to_string());
            let _ = notices.send(Notice::Indexed(generation, error)).await;
        }));
        Ok(())
    }

    fn start_sync(&mut self) -> Result<()> {
        if self.sync_job.as_ref().is_some_and(|job| !job.is_finished()) {
            self.signal_sync();
            return Ok(());
        }
        let td = self.telegram()?;
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        let (signal, receiver) = mpsc::channel(1);
        self.sync_signal = Some(signal);
        self.sync_job = Some(self.spawn(Cloud::run(td, paths, receiver, notices)));
        self.signal_sync();
        Ok(())
    }

    fn start_load(&mut self, position: Option<f64>) -> Result<()> {
        if let Some(job) = self.load_job.take() {
            job.abort();
        }
        let id = self
            .queue
            .ids
            .get(self.queue.index)
            .context("The playback queue is empty")?
            .clone();
        let track = self
            .store
            .track(&id)?
            .context("Recording no longer exists")?;
        self.generation += 1;
        let generation = self.generation;
        self.loaded_generation = 0;
        self.engine_paused = true;
        self.preparing_deadline = Some((
            generation,
            tokio::time::Instant::now() + Duration::from_secs(90),
        ));
        self.state.playback.track = Some(track.clone());
        self.state.playback.buffering = true;
        self.state.playback.playing = false;
        let position = match position {
            Some(position) => position.max(0.),
            None => self.store.resume_position(&track)?,
        };
        self.state.playback.position = position;
        self.queue.position = position;
        self.state.playback.duration = track.duration as f64;
        if !self.intent {
            if let Some(engine) = self.engine.take() {
                engine.stop()?;
            }
            self.state.playback.buffering = false;
            self.preparing_deadline = None;
            self.queue_state();
            self.publish();
            return Ok(());
        }
        if self.engine.is_none() {
            self.engine = Some(Engine::new(self.notices.clone())?);
        }
        #[cfg(not(target_os = "android"))]
        if self.media.is_none() {
            match crate::media::Controls::new() {
                Ok(media) => self.media = Some(media),
                Err(error) => eprintln!("System media controls: {error}"),
            }
        }
        self.engine.as_ref().unwrap().stop()?;
        self.engine.as_ref().unwrap().begin(generation, &track)?;
        let td = self.td.clone();
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        self.load_job = Some(tokio::task::spawn_local(async move {
            let result: Result<()> = async {
                let mut store = Store::connect(&paths.root.join("library.db"))?;
                let resolved = if track.local() {
                    track
                } else {
                    library::resolve_playback(
                        td.as_ref()
                            .context("Connect Telegram to play this recording")?,
                        &mut store,
                        &track,
                        &notices,
                    )
                    .await?
                };
                if let Some(td) = &td {
                    if !resolved.local() {
                        let file = td.file(resolved.file).await?;
                        if store.setting::<bool>("discard_listened")?.unwrap_or(false)
                            && !file.complete
                            && !resolved.saved
                            && !resolved.key.is_empty()
                        {
                            store.db.execute(
                                "INSERT OR IGNORE INTO temporary_audio VALUES(?,?,?)",
                                params![resolved.key, resolved.remote, file.path],
                            )?;
                        }
                        td.download(resolved.file, 0, 32)?;
                    }
                }
                notices
                    .send(Notice::Prepared(generation, resolved, position))
                    .await?;
                Ok(())
            }
            .await;
            if let Err(error) = result {
                let _ = notices
                    .send(Notice::Engine(EngineEvent::Failed(
                        generation,
                        error.to_string(),
                    )))
                    .await;
            }
        }));
        self.queue_state();
        self.publish();
        Ok(())
    }

    fn advance(&mut self, forward: bool, automatic: bool) -> Result<()> {
        if self.queue.ids.is_empty() {
            return Ok(());
        }
        self.save_checkpoint(true)?;
        if automatic && self.queue.repeat == Repeat::One {
            return self.start_load(Some(0.));
        }
        let length = self.queue.ids.len();
        let previous = self.queue.index;
        let next = match self.queue.order {
            Order::Normal | Order::Reverse => {
                let up = forward != (self.queue.order == Order::Reverse);
                if up && previous + 1 < length {
                    Some(previous + 1)
                } else if !up && previous > 0 {
                    Some(previous - 1)
                } else if self.queue.repeat == Repeat::All || !automatic {
                    Some(if up { 0 } else { length - 1 })
                } else {
                    None
                }
            }
            Order::Random => {
                if forward {
                    self.queue.history.push(previous);
                    if self.queue.history.len() > 32 {
                        self.queue.history.remove(0);
                    }
                    let next = rand::rng().random_range(0..length.saturating_sub(1).max(1));
                    Some(if length > 1 && next >= previous {
                        next + 1
                    } else {
                        next
                    })
                } else {
                    self.queue.history.pop()
                }
            }
            Order::Shuffle => {
                if self.queue.shuffled.len() != length || !self.queue.shuffled.contains(&previous) {
                    self.shuffle();
                }
                let index = self
                    .queue
                    .shuffled
                    .iter()
                    .position(|id| *id == previous)
                    .unwrap_or(0);
                if forward && index + 1 < length {
                    Some(self.queue.shuffled[index + 1])
                } else if !forward && index > 0 {
                    Some(self.queue.shuffled[index - 1])
                } else if self.queue.repeat == Repeat::All || !automatic {
                    self.shuffle();
                    if length > 1 && self.queue.shuffled[0] == previous {
                        self.queue.shuffled.swap(0, 1);
                    }
                    Some(if forward {
                        self.queue.shuffled[0]
                    } else {
                        *self.queue.shuffled.last().unwrap()
                    })
                } else {
                    None
                }
            }
        };
        if let Some(next) = next {
            self.queue.index = next;
            self.queue.position = 0.;
            self.intent = true;
            self.start_load(None)?;
        } else {
            if !automatic {
                return Ok(());
            }
            self.intent = false;
            self.generation += 1;
            self.state.playback.playing = false;
            self.state.playback.buffering = false;
            self.state.playback.position = 0.;
            self.queue.position = 0.;
            if let Some(engine) = self.engine.take() {
                engine.stop()?;
            }
            self.publish();
        }
        self.store.save_queue(&self.queue, false)?;
        self.controls_deadline = None;
        self.cleanup();
        Ok(())
    }

    fn shuffle(&mut self) {
        self.queue.shuffled = (0..self.queue.ids.len())
            .filter(|i| *i != self.queue.index)
            .collect();
        self.queue.shuffled.shuffle(&mut rand::rng());
        self.queue.shuffled.insert(0, self.queue.index);
    }

    fn save_checkpoint(&mut self, force: bool) -> Result<()> {
        if !force && self.checkpoint.elapsed() < Duration::from_secs(5) {
            return Ok(());
        }
        self.queue.position = self.state.playback.position;
        self.store.save_queue(&self.queue, false)?;
        if let Some(track) = &self.state.playback.track {
            self.store.bookmark(
                track,
                self.state.playback.position,
                self.state.playback.duration,
            )?;
        }
        self.checkpoint = tokio::time::Instant::now();
        self.controls_deadline = None;
        Ok(())
    }

    fn set_playing(&mut self, playing: bool) -> Result<()> {
        self.intent = playing;
        if playing {
            if self.engine.is_none() {
                self.start_load(Some(self.state.playback.position))?;
            } else if self.loaded_generation == self.generation {
                self.engine.as_ref().unwrap().pause(false)?;
            }
        } else if self
            .engine
            .as_ref()
            .is_some_and(|engine| self.loaded_generation != self.generation || engine.waiting())
        {
            // Cancel preparation and stalled desktop range reads, including
            // prefetch after Ready. Pausing must not leave a timed-out read alive.
            self.generation += 1;
            self.loaded_generation = 0;
            self.preparing_deadline = None;
            if let Some(job) = self.load_job.take() {
                job.abort();
            }
            if let Some(engine) = self.engine.take() {
                engine.stop()?;
            }
            self.state.playback.buffering = false;
        } else if let Some(engine) = &self.engine {
            engine.pause(true)?;
        }
        self.state.playback.playing =
            playing && !self.engine_paused && !self.state.playback.buffering;
        if !playing {
            self.save_checkpoint(true)?;
        }
        Ok(())
    }

    fn set_speed(&mut self, value: f64) -> Result<()> {
        ensure!(value.is_finite(), "Invalid playback speed");
        let value = playback_speed_step(value);
        if let Some(engine) = &self.engine {
            engine.speed(value)?;
        }
        self.queue.speed = value;
        self.controls_deadline = Some(tokio::time::Instant::now() + Duration::from_millis(250));
        Ok(())
    }

    fn set_setting(&mut self, key: &str, value: Value) -> Result<()> {
        self.store.set_preference(key, value.clone())?;
        self.state.settings = self.store.settings()?;
        self.state.language =
            crate::i18n::language(self.state.settings["language"].as_str().unwrap_or("system"));
        if key == "hide_duplicates" {
            self.query.duplicates = value != false;
        }
        if key == "discard_listened" && value == false {
            self.store.db.execute("DELETE FROM temporary_audio", [])?;
        }
        if key == "discard_listened" {
            self.cleanup();
        }
        Ok(())
    }

    async fn command(&mut self, command: Command) -> Result<()> {
        if !matches!(
            &command,
            Command::Artwork(..)
                | Command::SourcePhotos(..)
                | Command::Visible(..)
                | Command::ObserveClock(..)
                | Command::ClearSelection
                | Command::Page(..)
        ) {
            self.state.error.clear();
        }
        let library_changed = matches!(
            &command,
            Command::ConnectSource(..)
                | Command::Favorite(..)
                | Command::PlaylistSave { .. }
                | Command::PlaylistRename(..)
                | Command::PlaylistDelete(..)
                | Command::PlaylistAdd(..)
                | Command::PlaylistRemove(..)
                | Command::PlaylistMove(..)
                | Command::Delete(..)
        );
        let sync_changed = library_changed
            || matches!(&command,Command::Setting(key,_) | Command::ToggleSetting(key) if crate::sync::SETTINGS.contains(&key.as_str()));
        let catalog_changed = library_changed
            || matches!(&command,Command::Setting(key,_) | Command::ToggleSetting(key) if key=="hide_duplicates")
            || matches!(
                &command,
                Command::Logout
                    | Command::Download(..)
                    | Command::CancelDownloads
                    | Command::ClearCache
                    | Command::Refresh
            )
            || self.query.screen == Screen::Queue
                && matches!(
                    &command,
                    Command::QueueAdd(..) | Command::QueueRemove(..) | Command::QueueMove(..)
                );
        match command {
            Command::Query(mut query, offset) => {
                query.duplicates = self.state.settings["hide_duplicates"] != false;
                self.query = query;
                self.state.page = self.store.page(&self.query, offset)?;
                self.start_artwork();
                self.publish();
                return Ok(());
            }
            Command::Page(offset) => {
                self.state.page = self.store.page(&self.query, offset)?;
                self.start_artwork();
                self.publish();
                return Ok(());
            }
            Command::Artwork(ids) => {
                self.artwork(ids);
                return Ok(());
            }
            Command::SelectAll(query) => {
                self.state.selection_error.clear();
                self.state.selection_ids = Arc::new(match self.store.query_ids(&query) {
                    Ok(ids) => ids,
                    Err(error) => {
                        self.state.selection_error = error.to_string();
                        self.state.error = self.state.selection_error.clone();
                        Vec::new()
                    }
                });
                self.state.selection_query = query;
                self.state.selection_revision += 1;
                self.publish();
                return Ok(());
            }
            Command::ClearSelection => {
                self.state.selection_ids = Arc::default();
                self.state.selection_error.clear();
                self.publish();
                return Ok(());
            }
            Command::SourcePhotos(keys) => {
                if keys.is_empty() {
                    self.avatar_pending.clear();
                    self.avatar_visible.clear();
                    if let Some(job) = self.avatar_job.take() {
                        job.abort();
                    }
                    self.avatar_epoch += 1;
                    return Ok(());
                }
                if self.avatar_visible.len() > 64 {
                    self.avatar_visible.clear();
                }
                for key in keys.into_iter().filter(|(id, _)| *id != 0).take(32) {
                    self.avatar_visible.insert(key);
                    if !self.avatar_pending.contains(&key) {
                        self.avatar_pending.push(key);
                    }
                }
                if self.avatar_pending.len() > 32 {
                    self.avatar_pending.drain(..self.avatar_pending.len() - 32);
                }
                self.start_source_photos();
                return Ok(());
            }
            Command::CacheInfo => {
                self.start_cache_info();
                return Ok(());
            }
            Command::StartTelegram => {
                self.start_telegram()?;
                if self.state.auth.kind == "authorizationStateWaitTdlibParameters" {
                    self.configure_telegram()?;
                }
            }
            Command::AuthSubmit { kind, phone, value } => {
                ensure!(
                    kind == self.state.auth.kind
                        && phone == self.state.auth.phone
                        && !self.state.auth.busy,
                    "Authorization step changed; submit the current form again"
                );
                let td = self.telegram()?;
                self.auth_request(async move { td.authenticate(&kind, &value).await });
            }
            Command::Qr => {
                let td = self.telegram()?;
                self.auth_request(async move {
                    td.request(json!({"@type":"requestQrCodeAuthentication","other_user_ids":[]}))
                        .await?;
                    Ok(())
                });
            }
            Command::Resend => {
                ensure!(
                    !self.state.auth.next_code.is_empty() && now() >= self.state.auth.resend_at,
                    "Wait before requesting another code"
                );
                let td = self.telegram()?;
                self.auth_request(async move{td.request(json!({"@type":"resendAuthenticationCode","reason":{"@type":"resendCodeReasonUserRequest"}})).await?;Ok(())});
            }
            Command::ChangePhone => {
                if self.state.auth.kind == "authorizationStateWaitOtherDeviceConfirmation" {
                    self.restart_auth = true;
                    let td = self.telegram()?;
                    self.auth_request(async move {
                        td.request(json!({"@type":"logOut"})).await?;
                        Ok(())
                    });
                } else {
                    self.state.auth.kind = "authorizationStateWaitPhoneNumber".into();
                    self.state.auth.phone.clear();
                }
            }
            Command::Logout => {
                self.stop_account_work();
                self.state.playback = Playback::default();
                self.telegram()?.request(json!({"@type":"logOut"})).await?;
                self.store.detach_account()?;
                self.store.put_setting("telegram_connected", &false)?;
                self.queue
                    .ids
                    .retain(|id| self.store.track(id).ok().flatten().is_some());
                self.queue.index = self.queue.index.min(self.queue.ids.len().saturating_sub(1));
                self.queue.shuffled.clear();
                self.queue.history.clear();
                self.queue.position = 0.;
                self.store.save_queue(&self.queue, true)?;
                self.state.auth = Auth::default();
                self.state.auth.kind = "welcome".into();
            }
            Command::Discover(..) | Command::ShareRecipients(..) => {
                let (query, folder, recipients, document) = match command {
                    Command::Discover(query, folder) => (query, folder, false, false),
                    Command::ShareRecipients(query, folder, document) => {
                        (query, folder, true, document)
                    }
                    _ => unreachable!(),
                };
                if let Some(job) = self.directory_job.take() {
                    job.abort();
                }
                self.directory_generation += 1;
                let generation = self.directory_generation;
                self.state.choices = Arc::default();
                self.state.choices_scope = if recipients { "recipients" } else { "chats" }.into();
                self.publish();
                let td = self.telegram()?;
                let paths = self.paths.clone();
                let notices = self.notices.clone();
                self.directory_job = Some(self.spawn(async move {
                    let store = Store::connect(&paths.root.join("library.db"))?;
                    let choices =
                        library::discover(&td, &query, recipients, document, folder, &store)
                            .await?;
                    notices.send(Notice::Choices(generation, choices)).await?;
                    Ok(())
                }));
            }
            Command::ShareTopics(chat) => {
                if let Some(job) = self.directory_job.take() {
                    job.abort();
                }
                self.directory_generation += 1;
                let generation = self.directory_generation;
                self.state.choices = Arc::default();
                self.state.choices_scope = format!("topics:{chat}");
                self.publish();
                let td = self.telegram()?;
                let notices = self.notices.clone();
                self.directory_job = Some(self.spawn(async move {
                    let choices = library::topics(&td, chat).await?;
                    notices.send(Notice::Choices(generation, choices)).await?;
                    Ok(())
                }));
            }
            Command::ConnectSource(source, selected) => {
                self.store.select_source(&source, selected)?;
                for choice in Arc::make_mut(&mut self.state.choices) {
                    if choice.id == source.id && choice.profile == source.profile {
                        choice.selected = selected;
                    }
                }
                if selected {
                    self.start_index()?;
                }
            }
            Command::Refresh => {
                if let Screen::Profile(user) = self.query.screen {
                    let td = self.telegram()?;
                    let paths = self.paths.clone();
                    let notices = self.notices.clone();
                    if let Some(job) = self.directory_job.take() {
                        job.abort();
                    }
                    self.directory_job = Some(self.spawn(async move {
                        let mut store = Store::connect(&paths.root.join("library.db"))?;
                        library::load_profile(&td, &mut store, user, &notices).await?;
                        notices.send(Notice::Catalog).await?;
                        Ok(())
                    }));
                } else {
                    self.start_index()?;
                }
                self.signal_sync();
            }
            Command::OpenProfile(input) => {
                if self.state.auth.kind != "authorizationStateReady" {
                    if let Ok(user) = input.trim().parse::<i64>() {
                        if user > 0
                            && self.store.db.query_row(
                                "SELECT EXISTS(SELECT 1 FROM profiles WHERE id=?)",
                                [user],
                                |r| r.get::<_, bool>(0),
                            )?
                        {
                            self.notice(Notice::Profile(user)).await?;
                            return Ok(());
                        }
                    }
                }
                let td = self.telegram()?;
                let paths = self.paths.clone();
                let notices = self.notices.clone();
                if let Some(job) = self.directory_job.take() {
                    job.abort();
                }
                self.directory_job = Some(self.spawn(async move {
                    let mut store = Store::connect(&paths.root.join("library.db"))?;
                    let user = library::find_user(&td, &store, &input).await?;
                    library::load_profile(&td, &mut store, user, &notices).await?;
                    notices.send(Notice::Profile(user)).await?;
                    Ok(())
                }));
            }
            Command::Play(id) => {
                self.save_checkpoint(true)?;
                if self.query.screen != Screen::Queue {
                    self.queue.ids = self.store.query_ids(&self.query)?;
                }
                if !self.queue.ids.contains(&id) {
                    self.queue.ids.push(id.clone());
                }
                self.queue.index = self.queue.ids.iter().position(|t| t == &id).unwrap();
                self.queue.position = 0.;
                self.queue.history.clear();
                self.queue.shuffled.clear();
                self.store.save_queue(&self.queue, true)?;
                self.intent = true;
                self.start_load(None)?;
            }
            Command::PlayAll => {
                let ids = self.store.query_ids(&self.query)?;
                ensure!(!ids.is_empty(), "There are no recordings to play");
                self.save_checkpoint(true)?;
                let index = match self.queue.order {
                    Order::Reverse => ids.len() - 1,
                    Order::Shuffle | Order::Random => rand::rng().random_range(0..ids.len()),
                    Order::Normal => 0,
                };
                self.queue.ids = ids;
                self.queue.index = index;
                self.queue.position = 0.;
                self.queue.shuffled.clear();
                self.queue.history.clear();
                self.store.save_queue(&self.queue, true)?;
                self.intent = true;
                self.start_load(None)?;
            }
            Command::PlayQueue(index) => {
                ensure!(index < self.queue.ids.len(), "The queue changed; try again");
                self.save_checkpoint(true)?;
                self.queue.index = index;
                self.queue.position = 0.;
                self.queue.history.clear();
                self.queue.shuffled.clear();
                self.store.save_queue(&self.queue, false)?;
                self.intent = true;
                self.start_load(None)?;
            }
            Command::Toggle => {
                self.set_playing(!self.intent)?;
            }
            Command::Pause => {
                self.set_playing(false)?;
            }
            Command::Resume => {
                self.set_playing(true)?;
            }
            Command::Next => self.advance(true, false)?,
            Command::Previous => self.advance(false, false)?,
            Command::Seek(position) => {
                ensure!(position.is_finite(), "Invalid playback position");
                let position = position.clamp(0., self.state.playback.duration.max(0.));
                if self.engine.as_ref().is_some_and(Engine::waiting) {
                    // libmpv cannot execute a new seek while its demuxer is
                    // blocked inside the old range read. Reopen only that case;
                    // ordinary seeks keep their existing decoder and buffers.
                    if let Some(engine) = self.engine.take() {
                        engine.stop()?;
                    }
                    self.start_load(Some(position))?;
                } else if self.loaded_generation == self.generation {
                    if let Some(engine) = &self.engine {
                        engine.seek(position)?;
                    }
                }
                self.state.playback.position = position;
                self.save_checkpoint(true)?;
            }
            Command::Speed(value) => {
                ensure!(value.is_finite(), "Invalid playback speed");
                if playback_speed_step(value) == self.queue.speed {
                    return Ok(());
                }
                self.set_speed(value)?;
            }
            Command::SpeedStep(steps) => {
                self.set_speed(self.queue.speed + f64::from(steps) * PLAYBACK_SPEED_STEP)?;
            }
            Command::Volume(value) => {
                ensure!(value.is_finite(), "Invalid volume");
                let value = value.clamp(0., 100.);
                if value == self.queue.volume {
                    return Ok(());
                }
                if let Some(engine) = &self.engine {
                    engine.volume(value)?;
                }
                self.queue.volume = value;
                self.controls_deadline =
                    Some(tokio::time::Instant::now() + Duration::from_millis(250));
            }
            Command::Mute => {
                let muted = !self.queue.muted;
                if let Some(engine) = &self.engine {
                    engine.mute(muted)?;
                }
                self.queue.muted = muted;
                self.store.save_queue(&self.queue, false)?;
            }
            Command::Order(order) => {
                self.queue.order = order;
                self.queue.shuffled.clear();
                self.queue.history.clear();
                self.store.save_queue(&self.queue, false)?;
            }
            Command::Repeat(repeat) => {
                self.queue.repeat = repeat;
                self.store.save_queue(&self.queue, false)?;
            }
            Command::QueueAdd(ids) => {
                self.queue.ids.extend(ids);
                self.store.save_queue(&self.queue, true)?;
            }
            Command::QueueRemove(index) => {
                ensure!(index < self.queue.ids.len(), "The queue changed; try again");
                let current = index == self.queue.index;
                if current {
                    self.save_checkpoint(true)?;
                }
                self.queue.ids.remove(index);
                if index < self.queue.index {
                    self.queue.index -= 1;
                }
                self.queue.index = self.queue.index.min(self.queue.ids.len().saturating_sub(1));
                self.queue.shuffled.clear();
                self.queue.history.clear();
                if current {
                    if self.queue.ids.is_empty() {
                        self.intent = false;
                        if let Some(job) = self.load_job.take() {
                            job.abort();
                        }
                        self.generation += 1;
                        if let Some(engine) = self.engine.take() {
                            engine.stop()?;
                        }
                        self.state.playback = Playback::default();
                    } else {
                        self.start_load(None)?;
                    }
                }
                self.store.save_queue(&self.queue, true)?;
            }
            Command::QueueMove(from, to) => {
                ensure!(
                    from < self.queue.ids.len() && to < self.queue.ids.len(),
                    "The queue changed; try again"
                );
                let selected = self.queue.index;
                let t = self.queue.ids.remove(from);
                self.queue.ids.insert(to, t);
                self.queue.index = if selected == from {
                    to
                } else if from < selected && to >= selected {
                    selected - 1
                } else if from > selected && to <= selected {
                    selected + 1
                } else {
                    selected
                };
                self.queue.shuffled.clear();
                self.queue.history.clear();
                self.store.save_queue(&self.queue, true)?;
            }
            Command::Favorite(ids, liked) => self.store.favorite(&ids, liked)?,
            Command::PlaylistSave {
                playlist,
                name,
                ids,
            } => {
                let id = if let Some(id) = playlist {
                    self.store.playlist_edit(id, &ids, true)?;
                    id
                } else {
                    self.store.playlist_create(&name, &ids)?
                };
                self.state.playlist_saved_id = id;
                self.state.playlist_save_revision += 1;
            }
            Command::PlaylistRename(id, name) => self.store.playlist_rename(id, &name)?,
            Command::PlaylistDelete(id) => self.store.playlist_delete(id)?,
            Command::PlaylistAdd(id, ids) => self.store.playlist_edit(id, &ids, true)?,
            Command::PlaylistRemove(id, ids) => self.store.playlist_edit(id, &ids, false)?,
            Command::PlaylistMove(id, track, up) => {
                let members = self.store.playlist_members(id)?;
                let from = members
                    .iter()
                    .position(|t| t == &track)
                    .context("The playlist changed; reopen the recording menu")?;
                let to = if up {
                    from.saturating_sub(1)
                } else {
                    (from + 1).min(members.len() - 1)
                };
                self.store.playlist_move(id, from, to)?;
            }
            Command::PickFiles => {
                #[cfg(not(target_os = "android"))]
                {
                    let paths = self.paths.clone();
                    let notices = self.notices.clone();
                    self.spawn(async move {
                        if let Some(files) = rfd::AsyncFileDialog::new()
                            .set_title("Import audio files")
                            .add_filter(
                                "Audio",
                                &[
                                    "mp3", "flac", "m4a", "ogg", "opus", "aac", "wav", "aiff",
                                    "alac",
                                ],
                            )
                            .pick_files()
                            .await
                        {
                            let files = files
                                .into_iter()
                                .map(|f| f.path().to_owned())
                                .collect::<Vec<_>>();
                            tokio::task::spawn_blocking(move || {
                                let mut store = Store::connect(&paths.root.join("library.db"))?;
                                library::import(&paths, &mut store, &files)
                            })
                            .await??;
                            notices.send(Notice::Imported).await?;
                        }
                        Ok(())
                    });
                }
                #[cfg(target_os = "android")]
                {
                    crate::android::call_string("pickFiles", "")?;
                }
            }
            Command::Import(files) => {
                let paths = self.paths.clone();
                let notices = self.notices.clone();
                self.spawn(async move {
                    tokio::task::spawn_blocking(move || {
                        let mut store = Store::connect(&paths.root.join("library.db"))?;
                        library::import(&paths, &mut store, &files)
                    })
                    .await??;
                    notices.send(Notice::Imported).await?;
                    Ok(())
                });
            }
            Command::Download(ids) => {
                self.store.enqueue_downloads(&ids)?;
                self.start_downloads()?;
            }
            Command::CancelDownloads => {
                self.stop_downloads(true).await?;
            }
            Command::Delete(ids) => {
                for id in ids {
                    let t = self
                        .store
                        .track(&id)?
                        .context("Recording no longer exists")?;
                    if self.state.playback.track.as_ref().is_some_and(|current| {
                        current.id == id
                            || (!t.path.is_empty() && current.path == t.path)
                            || (!t.key.is_empty() && current.key == t.key)
                    }) {
                        self.intent = false;
                        if let Some(job) = self.load_job.take() {
                            job.abort();
                        }
                        self.generation += 1;
                        if let Some(engine) = self.engine.take() {
                            engine.stop()?;
                        }
                        self.state.playback = Playback::default();
                    }
                    if t.imported() {
                        self.store.delete_import(&t)?;
                        let removed_before = self
                            .queue
                            .ids
                            .iter()
                            .take(self.queue.index)
                            .filter(|entry| *entry == &id)
                            .count();
                        self.queue.ids.retain(|entry| entry != &id);
                        self.queue.index = self
                            .queue
                            .index
                            .saturating_sub(removed_before)
                            .min(self.queue.ids.len().saturating_sub(1));
                        self.queue.shuffled.clear();
                        self.queue.history.clear();
                        self.store.save_queue(&self.queue, true)?;
                    } else {
                        let td = self.telegram()?;
                        let resolved =
                            library::resolve(&td, &mut self.store, &t, &self.notices).await?;
                        td.request(json!({"@type":"deleteFile","file_id":resolved.file}))
                            .await?;
                        self.store.remove_copy(&resolved)?;
                    }
                }
            }
            Command::ClearCache => {
                self.state.cache_loading = true;
                self.state.status = "Clearing music cache…".into();
                self.publish();
                if let Err(error) = self.clear_cache().await {
                    self.state.cache_loading = false;
                    self.state.status.clear();
                    return Err(error);
                }
            }
            Command::Setting(key, value) => {
                self.set_setting(&key, value)?;
            }
            Command::ToggleSetting(key) => {
                let value = json!(self.state.settings[&key] != true);
                self.set_setting(&key, value)?;
            }
            Command::Send {
                track,
                recipient,
                topic,
                sender,
                caption,
            } => {
                ensure!(
                    self.transfer_job
                        .as_ref()
                        .is_none_or(JoinHandle::is_finished),
                    "An audio transfer is already running"
                );
                let td = self.telegram()?;
                let paths = self.paths.clone();
                let notices = self.notices.clone();
                let track = self
                    .store
                    .track(&track)?
                    .context("Recording no longer exists")?;
                self.transfer_job = Some(self.spawn(async move {
                    let _foreground = platform::Transfer::start()?;
                    let mut store = Store::connect(&paths.root.join("library.db"))?;
                    library::send(
                        &td, &mut store, &track, recipient, topic, sender, caption, &notices,
                    )
                    .await
                }));
            }
            Command::ProfileMember(id) => {
                self.state.profile_member = None;
                self.state.profile_member_id = id.clone();
                let td = self.telegram()?;
                let paths = self.paths.clone();
                let notices = self.notices.clone();
                let track = self
                    .store
                    .track(&id)?
                    .context("Recording no longer exists")?;
                self.spawn(async move {
                    let mut store = Store::connect(&paths.root.join("library.db"))?;
                    let member =
                        library::profile_member(&td, &mut store, &track, None, &notices).await?;
                    notices.send(Notice::ProfileMember(id, member)).await?;
                    Ok(())
                });
            }
            Command::SetProfileMember(id, true) => {
                self.set_profile_member(id, true)?;
            }
            Command::SetProfileMember(id, false) => {
                self.set_profile_member(id, false)?;
            }
            Command::Visible(visible) => {
                self.visible = visible;
                if !visible {
                    if let Some(job) = self.artwork_job.take() {
                        job.abort();
                    }
                    if let Some(job) = self.avatar_job.take() {
                        job.abort();
                    }
                    self.avatar_epoch += 1;
                    self.avatar_pending.clear();
                    self.avatar_visible.clear();
                    self.save_checkpoint(true)?;
                } else {
                    self.publish();
                }
                return Ok(());
            }
            Command::ObserveClock(observe) => {
                self.observe_clock = observe;
                if observe {
                    self.publish();
                }
                return Ok(());
            }
            Command::CheckUpdate => {
                let notices = self.notices.clone();
                self.spawn(async move {
                    let result = crate::updates::check().await?;
                    notices.send(Notice::Update(result)).await?;
                    Ok(())
                });
            }
            Command::NetworkChanged => {
                #[cfg(target_os = "android")]
                {
                    let wifi = crate::android::call_string("wifi", "")? == "true";
                    if self.state.settings["wifi_only"] == true && !wifi {
                        self.stop_downloads(false).await?;
                    } else {
                        self.start_downloads()?;
                    }
                }
            }
            Command::Quit => {}
        }
        if catalog_changed {
            self.refresh()?;
        } else {
            self.queue_state();
        }
        if sync_changed {
            self.signal_sync();
        }
        self.publish();
        Ok(())
    }

    fn set_profile_member(&mut self, id: String, add: bool) -> Result<()> {
        ensure!(
            self.transfer_job
                .as_ref()
                .is_none_or(JoinHandle::is_finished),
            "An audio transfer is already running"
        );
        let td = self.telegram()?;
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        let track = self
            .store
            .track(&id)?
            .context("Recording no longer exists")?;
        let user = self.state.me;
        let refresh_profile: bool = self.store.db.query_row(
            "SELECT EXISTS(SELECT 1 FROM profiles WHERE id=? AND (loaded_at>0 OR selected=1))",
            [user],
            |r| r.get(0),
        )?;
        self.transfer_job = Some(self.spawn(async move {
            let _foreground = platform::Transfer::start()?;
            let mut store = Store::connect(&paths.root.join("library.db"))?;
            let member =
                library::profile_member(&td, &mut store, &track, Some(add), &notices).await?;
            notices.send(Notice::ProfileMember(id, member)).await?;
            if refresh_profile {
                if let Err(error) = library::load_profile(&td, &mut store, user, &notices).await {
                    eprintln!("Profile list refresh: {error}");
                }
                notices.send(Notice::Catalog).await?;
            }
            Ok(())
        }));
        Ok(())
    }

    async fn notice(&mut self, notice: Notice) -> Result<()> {
        match notice {
            Notice::CacheInfo(account, result) => {
                if account != self.state.me {
                    return Ok(());
                }
                self.cache_job = None;
                self.state.cache_loading = false;
                match result {
                    Ok(size) => self.state.cache_size = Some(size),
                    Err(error) => self.state.cache_error = error,
                }
                self.publish();
            }
            Notice::SourcePhotos(epoch, photos) => {
                if epoch != self.avatar_epoch {
                    return Ok(());
                }
                self.avatar_job = None;
                if !photos.is_empty() {
                    self.state.sources = Arc::new(self.store.sources()?);
                    let cache = Arc::make_mut(&mut self.state.avatar_paths);
                    if cache.len() + photos.len() > 256 {
                        cache.clear();
                    }
                    for (id, profile, path) in photos {
                        cache.insert((id, profile), path);
                    }
                    self.publish();
                }
                self.start_source_photos();
            }
            Notice::Catalog => {
                self.store.revision += 1;
                self.refresh()?;
                self.publish();
            }
            Notice::CloudLibrary { sources_changed } => {
                self.store.revision += 1;
                self.refresh()?;
                if sources_changed {
                    self.start_index()?;
                }
                self.publish();
            }
            Notice::Indexed(generation, error) => {
                if generation == self.index_generation {
                    self.index_job = None;
                    self.state.busy = false;
                    self.state.status.clear();
                    if let Some(error) = error {
                        self.state.error = error;
                    }
                    if self.index_pending {
                        self.start_index()?;
                    }
                    self.refresh()?;
                    self.publish();
                }
            }
            Notice::Imported => {
                self.store.revision += 1;
                self.refresh()?;
                self.signal_sync();
                self.publish();
            }
            Notice::Status(status) => {
                self.state.busy = !status.is_empty();
                self.state.status = status;
                self.publish();
            }
            Notice::Error(error) => {
                self.state.error = error;
                self.state.busy = false;
                self.publish();
            }
            Notice::Sync(status) => {
                self.state.sync = status;
                self.publish();
            }
            Notice::Choices(generation, choices) => {
                if generation == self.directory_generation {
                    self.state.choices = Arc::new(choices);
                    self.publish();
                }
            }
            Notice::Profile(user) => {
                self.store.prune_profiles(user)?;
                self.query.screen = Screen::Profile(user);
                self.query.search.clear();
                self.query.source.clear();
                self.state.page.offset = 0;
                self.refresh()?;
                self.publish();
            }
            Notice::ProfileMember(id, member) => {
                if self.state.profile_member_id == id {
                    self.state.profile_member = Some(member);
                }
                self.refresh()?;
                self.publish();
            }
            Notice::Transfer(count, progress) => {
                self.state.downloads = count;
                self.state.transfer_progress = progress;
                self.publish();
            }
            Notice::Sent => {
                self.state.status = "Recording sent".into();
                self.refresh()?;
                self.publish();
            }
            Notice::Prepared(generation, track, _) => {
                if generation != self.generation {
                    return Ok(());
                }
                self.load_job = None;
                let position = self.state.playback.position;
                let td = if track.local() { None } else { self.td.clone() };
                let media = Arc::new(Media {
                    generation,
                    track: track.clone(),
                    telegram: td,
                });
                let loaded = self
                    .engine
                    .as_ref()
                    .context("Audio engine is unavailable")
                    .and_then(|engine| engine.load(media, position, &self.queue, self.intent));
                if let Err(error) = loaded {
                    return self.engine_event(EngineEvent::Failed(generation, error.to_string()));
                }
                self.loading_position = position;
                self.state.playback.track = Some(track);
                self.state.playback.position = position;
                self.publish();
            }
            Notice::Engine(event) => self.engine_event(event)?,
            #[cfg(target_os = "android")]
            Notice::EngineWake => {}
            Notice::Update(url) => {
                self.state.status = if url.is_empty() {
                    "You have the latest Rust test build".into()
                } else {
                    "A newer Rust test build is available".into()
                };
                if !url.is_empty() {
                    platform::open_url(&url)?;
                }
                self.publish();
            }
            Notice::AuthResult(step, phone, error) => {
                if self.state.auth.kind == step && self.state.auth.phone == phone {
                    self.state.auth.busy = false;
                    if let Some(error) = error {
                        self.state.error = error;
                    }
                    self.publish();
                }
            }
        }
        Ok(())
    }

    fn engine_event(&mut self, event: EngineEvent) -> Result<()> {
        let generation = match &event {
            EngineEvent::Loaded(g)
            | EngineEvent::Position(g, _)
            | EngineEvent::Duration(g, _)
            | EngineEvent::Buffering(g, _)
            | EngineEvent::Paused(g, _, _)
            | EngineEvent::Finished(g)
            | EngineEvent::Failed(g, _)
            | EngineEvent::Unavailable(g, _)
            | EngineEvent::Interrupted(g, _) => *g,
        };
        if generation != self.generation {
            return Ok(());
        }
        match event {
            EngineEvent::Loaded(_) => {
                self.preparing_deadline = None;
                self.loaded_generation = self.generation;
                let engine = self
                    .engine
                    .as_ref()
                    .context("Audio engine is unavailable")?;
                let result = (|| -> Result<()> {
                    if (self.state.playback.position - self.loading_position).abs() > 0.001 {
                        engine.seek(self.state.playback.position)?;
                    }
                    engine.speed(self.queue.speed)?;
                    engine.volume(self.queue.volume)?;
                    engine.mute(self.queue.muted)?;
                    engine.pause(!self.intent)
                })();
                if let Err(error) = result {
                    return self.engine_event(EngineEvent::Failed(generation, error.to_string()));
                }
                self.state.playback.buffering = false;
                self.state.playback.playing = self.intent && !self.engine_paused;
                self.save_checkpoint(true)?;
                self.publish();
            }
            EngineEvent::Position(_, position) => {
                if position.is_finite() && self.loaded_generation == generation {
                    self.state.playback.position = position.max(0.);
                    self.save_checkpoint(false)?;
                    if self.visible && self.observe_clock {
                        self.pending_publish = true;
                    }
                }
            }
            EngineEvent::Duration(_, duration) => {
                if duration.is_finite() {
                    self.state.playback.duration = duration.max(0.);
                    if self.visible {
                        self.pending_publish = true;
                    }
                }
            }
            EngineEvent::Buffering(_, buffering) => {
                self.state.playback.buffering = buffering;
                self.state.playback.playing = self.intent && !self.engine_paused && !buffering;
                self.publish();
            }
            EngineEvent::Paused(_, paused, position) => {
                self.engine_paused = paused;
                if self.loaded_generation == generation {
                    if let Some(position) = position.filter(|p| p.is_finite()) {
                        self.state.playback.position = position.max(0.);
                    }
                }
                self.state.playback.playing =
                    self.intent && !paused && !self.state.playback.buffering;
                self.save_checkpoint(true)?;
                self.publish();
            }
            EngineEvent::Finished(_) => {
                if let Some(t) = &self.state.playback.track {
                    self.store
                        .db
                        .execute("DELETE FROM playback_progress WHERE track=?", [&t.id])?;
                }
                self.state.playback.position = 0.;
                if self.intent {
                    self.advance(true, true)?;
                } else {
                    // A queued EOF must not undo a Pause that has already been accepted.
                    self.generation += 1;
                    if let Some(engine) = self.engine.take() {
                        engine.stop()?;
                    }
                    self.state.playback.playing = false;
                    self.state.playback.buffering = false;
                    self.save_checkpoint(true)?;
                    self.publish();
                }
            }
            EngineEvent::Failed(_, error) => {
                self.preparing_deadline = None;
                self.intent = false;
                self.generation += 1;
                self.loaded_generation = 0;
                if let Some(job) = self.load_job.take() {
                    job.abort();
                }
                if let Some(engine) = self.engine.take() {
                    let _ = engine.stop();
                }
                self.state.playback.playing = false;
                self.state.playback.buffering = false;
                self.state.error = error;
                self.save_checkpoint(true)?;
                self.publish();
            }
            EngineEvent::Unavailable(_, position) => {
                self.preparing_deadline = None;
                self.generation += 1;
                self.loaded_generation = 0;
                if let Some(job) = self.load_job.take() {
                    job.abort();
                }
                self.engine = None;
                if let Some(position) = position.filter(|p| p.is_finite()) {
                    self.state.playback.position = position.max(0.);
                }
                self.state.playback.playing = false;
                self.state.playback.buffering = false;
                self.save_checkpoint(true)?;
                if self.intent {
                    self.start_load(Some(self.state.playback.position))?;
                } else {
                    self.publish();
                }
            }
            EngineEvent::Interrupted(_, position) => {
                self.intent = false;
                self.engine_paused = true;
                self.state.playback.playing = false;
                if position.is_finite() && self.loaded_generation == generation {
                    self.state.playback.position = position.max(0.);
                }
                self.save_checkpoint(true)?;
                self.publish();
            }
        }
        #[cfg(not(target_os = "android"))]
        if let Some(media) = &mut self.media {
            if let Err(error) = media.update(&self.state.playback) {
                eprintln!("System media controls: {error}");
            }
        }
        Ok(())
    }

    async fn native_event(&mut self, event: Value) -> Result<()> {
        match kind(&event) {
            "updateAuthorizationState" => {
                self.state.auth = Telegram::auth(&event["authorization_state"]);
                match self.state.auth.kind.as_str() {
                    "authorizationStateWaitTdlibParameters" => {
                        self.configure_telegram()?;
                    }
                    "authorizationStateReady" => {
                        let td = self.telegram()?;
                        td.request(json!({"@type":"setOption","name":"use_storage_optimizer","value":{"@type":"optionValueBoolean","value":false}})).await?;
                        let me = td.request(json!({"@type":"getMe"})).await?;
                        self.state.me = number(&me, "id");
                        let changed_account =
                            self.store.account != 0 && self.store.account != self.state.me;
                        self.store.bind_account(self.state.me)?;
                        if changed_account {
                            let old_index = self.queue.index;
                            let preceding = self
                                .queue
                                .ids
                                .iter()
                                .take(old_index)
                                .filter(|id| id.starts_with("local:"))
                                .count();
                            let keep_current = self
                                .queue
                                .ids
                                .get(old_index)
                                .is_some_and(|id| id.starts_with("local:"));
                            self.queue.ids.retain(|id| id.starts_with("local:"));
                            self.queue.index =
                                preceding.min(self.queue.ids.len().saturating_sub(1));
                            self.queue.shuffled.clear();
                            self.queue.history.clear();
                            if !keep_current {
                                self.queue.position = 0.;
                                self.state.playback = Playback::default();
                            }
                            self.store.save_queue(&self.queue, true)?;
                        }
                        self.store.put_setting("telegram_connected", &true)?;
                        self.start_sync()?;
                        self.start_index()?;
                        self.start_downloads()?;
                    }
                    "authorizationStateClosed" => {
                        self.stop_account_work();
                        self.td = None;
                        self.state.auth.busy = false;
                        self.store.put_setting("telegram_connected", &false)?;
                        if self.restart_auth {
                            self.restart_auth = false;
                            if let Some(job) = self.auth_job.take() {
                                job.abort();
                            }
                            self.start_telegram()?;
                        }
                    }
                    _ => {}
                }
                self.refresh()?;
                self.publish();
            }
            "updateConnectionState" => {
                if kind(&event["state"]) == "connectionStateReady"
                    && self.state.auth.kind == "authorizationStateReady"
                {
                    self.signal_sync();
                    self.start_index()?;
                    self.start_downloads()?;
                }
            }
            "updateNewChat" => {
                if boolean(&event["chat"]["type"], "is_channel") {
                    self.signal_sync();
                }
            }
            "updateChatFolders" => {
                self.refresh()?;
                self.publish();
            }
            "updateChatPhoto" | "updateUser" => {
                let profile = kind(&event) == "updateUser";
                let id = if profile {
                    number(&event["user"], "id")
                } else {
                    number(&event, "chat_id")
                };
                if self.avatar_visible.contains(&(id, profile)) {
                    if !self.avatar_pending.contains(&(id, profile)) {
                        self.avatar_pending.push((id, profile));
                    }
                    self.start_source_photos();
                }
            }
            "spotygramFileComplete" => {
                if let Some(f) = self.telegram()?.files.get(number(&event, "id") as i32) {
                    if self.store.file_complete(&f)? {
                        self.refresh()?;
                        self.cleanup();
                        self.publish();
                    }
                }
            }
            "updateNewMessage" => {
                let message = &event["message"];
                let chat = number(message, "chat_id");
                if message["content"]["caption"]["text"]
                    .as_str()
                    .is_some_and(|s| s.starts_with("spotygram-sync/"))
                {
                    self.signal_sync();
                }
                if let Some(source) = self
                    .state
                    .sources
                    .iter()
                    .find(|s| !s.profile && s.id == chat)
                {
                    if let Some((track, _)) = library::parse_message(message, &source.title) {
                        self.store.upsert(&[track])?;
                        self.store.rebuild_duplicates()?;
                        self.refresh()?;
                        self.publish();
                    }
                }
            }
            "updateDeleteMessages" if boolean(&event, "is_permanent") => {
                if let Some(ids) = event["message_ids"].as_array() {
                    for id in ids.iter().filter_map(Value::as_i64) {
                        self.store.db.execute(
                            "UPDATE tracks SET available=0 WHERE chat=? AND message=?",
                            params![number(&event, "chat_id"), id],
                        )?;
                    }
                }
                self.store.revision += 1;
                self.refresh()?;
                self.publish();
            }
            "updateMessageContent" => {
                let chat = number(&event, "chat_id");
                let message = number(&event, "message_id");
                if let Some(old) = self.store.track(&format!("{chat}:{message}"))? {
                    let value = json!({"chat_id":chat,"id":message,"date":old.date,"content":event["new_content"]});
                    if let Some((track, _)) = library::parse_message(&value, &old.source) {
                        self.store.upsert(&[track])?;
                        self.store.rebuild_duplicates()?;
                    } else {
                        self.store
                            .db
                            .execute("UPDATE tracks SET available=0 WHERE id=?", [old.id])?;
                        self.store.revision += 1;
                    }
                    self.refresh()?;
                    self.publish();
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn start_cache_info(&mut self) {
        if let Some(job) = self.cache_job.take() {
            job.abort();
        }
        self.state.cache_loading = false;
        self.state.cache_error.clear();
        if self.state.auth.kind != "authorizationStateReady" {
            self.state.cache_size = None;
            self.publish();
            return;
        }
        let Ok(td) = self.telegram() else {
            return;
        };
        self.state.cache_loading = true;
        self.publish();
        let account = self.state.me;
        let notices = self.notices.clone();
        self.cache_job = Some(self.spawn(async move {
            let result = td
                .request_timeout(
                    json!({"@type":"getStorageStatistics","chat_limit":0}),
                    Duration::from_secs(120),
                )
                .await
                .map(|data| {
                    let mut bytes = 0u64;
                    let mut count = 0u64;
                    for chat in data["by_chat"].as_array().into_iter().flatten() {
                        for entry in chat["by_file_type"].as_array().into_iter().flatten() {
                            if matches!(
                                kind(&entry["file_type"]),
                                "fileTypeAudio" | "fileTypeDocument"
                            ) {
                                bytes += entry["size"].as_u64().unwrap_or(0);
                                count += entry["count"].as_u64().unwrap_or(0);
                            }
                        }
                    }
                    (bytes, count)
                })
                .map_err(|error| error.to_string());
            let _ = notices.send(Notice::CacheInfo(account, result)).await;
            Ok(())
        }));
    }

    fn start_source_photos(&mut self) {
        if !self.visible
            || self.state.auth.kind != "authorizationStateReady"
            || self.avatar_pending.is_empty()
            || self.avatar_job.is_some()
        {
            return;
        }
        let Ok(td) = self.telegram() else {
            return;
        };
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        let keys = std::mem::take(&mut self.avatar_pending);
        self.avatar_epoch += 1;
        let epoch = self.avatar_epoch;
        self.avatar_job = Some(self.spawn(async move {
            let photos = match Store::connect(&paths.root.join("library.db")) {
                Ok(store) => library::source_photos(&td, &store, &keys).await,
                Err(error) => {
                    eprintln!("Cannot load source avatars: {error:#}");
                    Vec::new()
                }
            };
            let _ = notices.send(Notice::SourcePhotos(epoch, photos)).await;
            Ok(())
        }));
    }

    fn start_artwork(&mut self) {
        let mut ids = self
            .state
            .page
            .tracks
            .iter()
            .take(24)
            .map(|t| t.id.clone())
            .collect::<Vec<_>>();
        if let Some(track) = &self.state.playback.track {
            ids.insert(0, track.id.clone());
        }
        self.artwork(ids);
    }

    fn artwork(&mut self, mut ids: Vec<String>) {
        if !self.visible {
            return;
        }
        if let Some(job) = self.artwork_job.take() {
            job.abort();
        }
        let Some(td) = self.td.clone() else {
            return;
        };
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        ids.truncate(48);
        self.artwork_job = Some(tokio::task::spawn_local(async move {
            let result: Result<()> = async {
                let mut store = Store::connect(&paths.root.join("library.db"))?;
                if library::artwork(&td, &mut store, &ids).await? {
                    notices.send(Notice::Catalog).await?;
                }
                Ok(())
            }
            .await;
            if let Err(error) = result {
                eprintln!("Cover: {error}");
            }
        }));
    }

    fn start_downloads(&mut self) -> Result<()> {
        if self
            .downloads_job
            .as_ref()
            .is_some_and(|j| !j.is_finished())
        {
            return Ok(());
        }
        if self.store.downloads()?.is_empty() {
            return Ok(());
        }
        #[cfg(target_os = "android")]
        if self.state.settings["wifi_only"] == true
            && crate::android::call_string("wifi", "")? != "true"
        {
            self.state.status = "Downloads are waiting for Wi-Fi".into();
            return Ok(());
        }
        let td = self.telegram()?;
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        self.downloads_job = Some(self.spawn(async move {
            let _foreground = platform::Transfer::start()?;
            let mut store = Store::connect(&paths.root.join("library.db"))?;
            loop {
                let pending = store.downloads()?;
                let Some(id) = pending.first() else {
                    break;
                };
                let track = store
                    .track(id)?
                    .context("A queued download no longer exists")?;
                let track = library::resolve(&td, &mut store, &track, &notices).await?;
                if !track.local() {
                    let f = td
                        .wait_file_progress(track.file, Some(&notices), pending.len())
                        .await?;
                    store.file_complete(&f)?;
                }
                let tx = store.db.unchecked_transaction()?;
                tx.execute(
                    "UPDATE tracks SET saved=1 WHERE id=? OR (file_key!='' AND file_key=?)",
                    params![id, track.key],
                )?;
                tx.execute("DELETE FROM temporary_audio WHERE file_key=?", [&track.key])?;
                tx.execute("DELETE FROM downloads WHERE track=?", [id])?;
                tx.commit()?;
                notices
                    .send(Notice::Transfer(pending.len() - 1, 1.))
                    .await?;
                notices.send(Notice::Catalog).await?;
            }
            Ok(())
        }));
        Ok(())
    }

    async fn stop_downloads(&mut self, clear: bool) -> Result<()> {
        if let Some(job) = self.downloads_job.take() {
            job.abort();
            let _ = job.await;
        }
        if let Some(td) = &self.td {
            for id in self.store.downloads()? {
                if let Some(track) = self.store.track(&id)? {
                    let current = self.engine.is_some()
                        && self.state.playback.track.as_ref().is_some_and(|t| {
                            t.id == track.id || !track.key.is_empty() && t.key == track.key
                        });
                    if track.file != 0 && !current {
                        let _=td.send(json!({"@type":"cancelDownloadFile","file_id":track.file,"only_if_pending":false}));
                    }
                }
            }
        }
        if clear {
            self.store.db.execute("DELETE FROM downloads", [])?;
            self.state.downloads = 0;
            self.state.transfer_progress = 0.;
        }
        Ok(())
    }

    fn cleanup(&mut self) {
        if self.state.settings["discard_listened"] != true
            || self.cleanup_job.as_ref().is_some_and(|j| !j.is_finished())
        {
            return;
        }
        if self
            .store
            .setting::<u64>("cleanup_retry_at")
            .ok()
            .flatten()
            .unwrap_or(0)
            > now()
        {
            return;
        }
        let Some(td) = self.td.clone() else {
            return;
        };
        let paths = self.paths.clone();
        let notices = self.notices.clone();
        let mut protected = HashSet::new();
        for index in [
            self.queue.index.saturating_sub(1),
            self.queue.index,
            self.queue.index + 1,
        ] {
            if let Some(id) = self.queue.ids.get(index) {
                if let Ok(Some(t)) = self.store.track(id) {
                    protected.insert(t.key);
                }
            }
        }
        self.cleanup_job = Some(tokio::task::spawn_local(async move {
            let result:Result<()>=async {
            let mut store=Store::connect(&paths.root.join("library.db"))?;
            let entries={let mut s=store.db.prepare("SELECT a.file_key,a.remote_id,a.path FROM temporary_audio a WHERE NOT EXISTS(SELECT 1 FROM tracks t WHERE t.file_key=a.file_key AND (t.saved=1 OR t.id IN(SELECT track FROM downloads))) LIMIT 16")?;s.query_map([],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?)))?.collect::<rusqlite::Result<Vec<_>>>()?};
            let mut removed=0;
            for (key,remote,_) in entries {
                if protected.contains(&key){continue;}
                if removed>=4{break;}
                let file=td.request(json!({"@type":"getRemoteFile","remote_file_id":remote,"file_type":{"@type":"fileTypeAudio"}})).await?;
                let f=td.files.remember(&file);ensure!(f.key==key,"Temporary file identity changed");
                if !f.path.is_empty(){ensure!(platform::is_owned(std::path::Path::new(&f.path),&paths.root.join("telegram/files")),"Temporary file is outside Telegram storage");}
                td.request(json!({"@type":"cancelDownloadFile","file_id":f.id,"only_if_pending":false})).await?;
                td.request(json!({"@type":"deleteFile","file_id":f.id})).await?;
                let after=td.file(f.id).await?;ensure!(!after.complete && (f.path.is_empty() || !std::path::Path::new(&f.path).exists()),"Temporary audio is still in use");
                let tx=store.db.unchecked_transaction()?;tx.execute("UPDATE tracks SET path='' WHERE file_key=?",[&key])?;tx.execute("DELETE FROM temporary_audio WHERE file_key=?",[&key])?;tx.commit()?;removed+=1;
            }
            store.reconcile_files()?;if removed>0{notices.send(Notice::Catalog).await?;}Ok(())
            }.await;
            if let Err(error) = result {
                if let Ok(store) = Store::connect(&paths.root.join("library.db")) {
                    let _ = store.put_setting("cleanup_retry_at", &(now() + 30));
                }
                eprintln!("Temporary audio cleanup: {error}");
            }
        }));
    }

    async fn clear_cache(&mut self) -> Result<()> {
        ensure!(
            self.state.auth.kind == "authorizationStateReady",
            "Connect Telegram before clearing its music cache"
        );
        ensure!(
            self.transfer_job
                .as_ref()
                .is_none_or(JoinHandle::is_finished),
            "Wait for the audio transfer to finish before clearing its files"
        );
        let td = self.telegram()?;
        self.save_checkpoint(true)?;
        if let Some(job) = self.load_job.take() {
            job.abort();
            let _ = job.await;
        }
        self.generation += 1;
        self.intent = false;
        if let Some(engine) = self.engine.take() {
            engine.stop()?;
        }
        self.state.playback.playing = false;
        self.state.playback.buffering = false;
        self.stop_downloads(true).await?;
        if let Some(job) = self.cleanup_job.take() {
            job.abort();
            let _ = job.await;
        }
        if let Some(job) = self.sync_job.take() {
            job.abort();
            let _ = job.await;
        }
        self.sync_signal = None;
        td.request(json!({"@type":"optimizeStorage","size":0,"ttl":0,"count":0,"immunity_delay":0,"file_types":[{"@type":"fileTypeAudio"},{"@type":"fileTypeDocument"}],"chat_ids":[],"exclude_chat_ids":[],"return_deleted_file_statistics":true,"chat_limit":0})).await?;
        self.store.reconcile_files()?;
        self.store.db.execute("DELETE FROM downloads", [])?;
        self.store.db.execute("DELETE FROM temporary_audio WHERE NOT EXISTS(SELECT 1 FROM tracks t WHERE t.file_key=temporary_audio.file_key AND t.path!='')",[])?;
        self.start_sync()?;
        self.state.status = "Music cache cleared".into();
        self.start_cache_info();
        Ok(())
    }
}
