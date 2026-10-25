use crate::{
    model::*,
    platform,
    reader::{Media, Reader},
};
use anyhow::{Result, ensure};
use arc_swap::ArcSwapOption;
use libloading::Library;
use std::{
    ffi::{CStr, CString, c_char, c_int, c_void},
    ptr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    thread,
};
use tokio::sync::mpsc;

#[repr(C)]
struct Event {
    id: c_int,
    error: c_int,
    userdata: u64,
    data: *mut c_void,
}
#[repr(C)]
struct Property {
    name: *const c_char,
    format: c_int,
    data: *mut c_void,
}
#[repr(C)]
struct StreamInfo {
    cookie: *mut c_void,
    read: Option<unsafe extern "C" fn(*mut c_void, *mut c_char, u64) -> i64>,
    seek: Option<unsafe extern "C" fn(*mut c_void, i64) -> i64>,
    size: Option<unsafe extern "C" fn(*mut c_void) -> i64>,
    close: Option<unsafe extern "C" fn(*mut c_void)>,
    cancel: Option<unsafe extern "C" fn(*mut c_void)>,
}

struct Api {
    _library: Library,
    create: unsafe extern "C" fn() -> *mut c_void,
    initialize: unsafe extern "C" fn(*mut c_void) -> c_int,
    option: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
    set: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
    get_string: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_char,
    free: unsafe extern "C" fn(*mut c_void),
    command: unsafe extern "C" fn(*mut c_void, u64, *const *const c_char) -> c_int,
    observe: unsafe extern "C" fn(*mut c_void, u64, *const c_char, c_int) -> c_int,
    wait: unsafe extern "C" fn(*mut c_void, f64) -> *const Event,
    wakeup: unsafe extern "C" fn(*mut c_void),
    destroy: unsafe extern "C" fn(*mut c_void),
    stream: unsafe extern "C" fn(
        *mut c_void,
        *const c_char,
        *mut c_void,
        unsafe extern "C" fn(*mut c_void, *const c_char, *mut StreamInfo) -> c_int,
    ) -> c_int,
}

impl Api {
    fn load() -> Result<Arc<Self>> {
        let l = platform::native_library("mpv")?;
        unsafe {
            Ok(Arc::new(Self {
                create: *l.get(b"mpv_create\0")?,
                initialize: *l.get(b"mpv_initialize\0")?,
                option: *l.get(b"mpv_set_option_string\0")?,
                set: *l.get(b"mpv_set_property_string\0")?,
                get_string: *l.get(b"mpv_get_property_string\0")?,
                free: *l.get(b"mpv_free\0")?,
                command: *l.get(b"mpv_command_async\0")?,
                observe: *l.get(b"mpv_observe_property\0")?,
                wait: *l.get(b"mpv_wait_event\0")?,
                wakeup: *l.get(b"mpv_wakeup\0")?,
                destroy: *l.get(b"mpv_terminate_destroy\0")?,
                stream: *l.get(b"mpv_stream_cb_add_ro\0")?,
                _library: l,
            }))
        }
    }
}

struct StreamContext {
    media: ArcSwapOption<Media>,
    notices: mpsc::Sender<Notice>,
    waiting: Arc<AtomicUsize>,
}
struct Stream {
    reader: Mutex<Reader>,
    cancelled: Arc<AtomicBool>,
    generation: u64,
    notices: mpsc::Sender<Notice>,
    waiting: Arc<AtomicUsize>,
}

pub struct Mpv {
    api: Arc<Api>,
    handle: usize,
    context: Box<StreamContext>,
    generation: Arc<AtomicU64>,
    stop: Arc<AtomicBool>,
    thread: Option<thread::JoinHandle<()>>,
}

impl Mpv {
    pub fn new(notices: mpsc::Sender<Notice>) -> Result<Self> {
        let api = Api::load()?;
        let handle = unsafe { (api.create)() };
        ensure!(!handle.is_null(), "Cannot create the audio engine");
        let mut this = Self {
            api,
            handle: handle as usize,
            context: Box::new(StreamContext {
                media: ArcSwapOption::empty(),
                notices: notices.clone(),
                waiting: Arc::new(AtomicUsize::new(0)),
            }),
            generation: Arc::new(AtomicU64::new(0)),
            stop: Arc::new(AtomicBool::new(false)),
            thread: None,
        };
        for (key, value) in [
            ("vo", "null"),
            ("vid", "no"),
            ("terminal", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("idle", "yes"),
            ("keep-open", "no"),
            ("cache", "yes"),
            ("demuxer-max-bytes", "8MiB"),
            ("demuxer-readahead-secs", "30"),
            ("audio-pitch-correction", "yes"),
        ] {
            let key = CString::new(key)?;
            let value = CString::new(value)?;
            ensure!(
                unsafe { (this.api.option)(handle, key.as_ptr(), value.as_ptr()) } >= 0,
                "Unsupported audio engine option"
            );
        }
        if let Ok(audio) = std::env::var("SPOTYGRAM_AUDIO_OUTPUT") {
            let audio = CString::new(audio)?;
            unsafe {
                (this.api.option)(handle, c"ao".as_ptr(), audio.as_ptr());
            }
        }
        ensure!(
            unsafe { (this.api.initialize)(handle) } >= 0,
            "Cannot initialize audio output"
        );
        ensure!(
            unsafe {
                (this.api.stream)(
                    handle,
                    c"spotygram".as_ptr(),
                    (&mut *this.context) as *mut _ as *mut c_void,
                    open_stream,
                )
            } >= 0,
            "Cannot register the Telegram audio reader"
        );
        for (id, name, format) in [
            (1, c"time-pos", 5),
            (2, c"duration", 5),
            (3, c"pause", 3),
            (4, c"paused-for-cache", 3),
        ] {
            ensure!(
                unsafe { (this.api.observe)(handle, id, name.as_ptr(), format) } >= 0,
                "Cannot observe the audio engine"
            );
        }
        let api = this.api.clone();
        let stop = this.stop.clone();
        let generation = this.generation.clone();
        let pointer = this.handle;
        this.thread = Some(thread::Builder::new().name("audio-events".into()).spawn(
            move || {
                let handle = pointer as *mut c_void;
                let mut loaded = false;
                let mut active = 0;
                while !stop.load(Ordering::Acquire) {
                    // mpv_wakeup interrupts the wait during Drop; idle/paused engines
                    // need no polling timer.
                    let event = unsafe { &*(api.wait)(handle, -1.) };
                    let current = generation.load(Ordering::Acquire);
                    let shutdown = event.id == 1;
                    let result = match event.id {
                        1 if !stop.load(Ordering::Acquire) => Some(EngineEvent::Failed(
                            current,
                            "The audio engine shut down; press Play to reopen it".into(),
                        )),
                        6 => {
                            loaded = false;
                            active = stream_generation(&api, handle).unwrap_or(0);
                            None
                        }
                        8 => {
                            active = stream_generation(&api, handle).unwrap_or(active);
                            loaded = active == current;
                            if loaded {
                                Some(EngineEvent::Loaded(active))
                            } else {
                                None
                            }
                        }
                        5 if event.userdata != 0
                            && event.userdata == current
                            && event.error < 0 =>
                        {
                            Some(EngineEvent::Failed(
                                current,
                                format!("Audio command error {}", event.error),
                            ))
                        }
                        7 if active == current && !event.data.is_null() => {
                            let reason = unsafe { *(event.data as *const c_int) };
                            let error = unsafe { *((event.data as *const c_int).add(1)) };
                            loaded = false;
                            if reason == 0 {
                                Some(EngineEvent::Finished(active))
                            } else if reason == 4 {
                                Some(EngineEvent::Failed(
                                    active,
                                    format!("Audio engine error {error}"),
                                ))
                            } else {
                                None
                            }
                        }
                        22 if loaded && active == current && !event.data.is_null() => {
                            let property = unsafe { &*(event.data as *const Property) };
                            if property.data.is_null() {
                                None
                            } else {
                                match event.userdata {
                                    1 => Some(EngineEvent::Position(active, unsafe {
                                        *(property.data as *const f64)
                                    })),
                                    2 => Some(EngineEvent::Duration(active, unsafe {
                                        *(property.data as *const f64)
                                    })),
                                    3 => Some(EngineEvent::Paused(
                                        active,
                                        unsafe { *(property.data as *const c_int) } != 0,
                                        numeric_property(&api, handle, c"time-pos"),
                                    )),
                                    4 => Some(EngineEvent::Buffering(
                                        active,
                                        unsafe { *(property.data as *const c_int) } != 0,
                                    )),
                                    _ => None,
                                }
                            }
                        }
                        _ => None,
                    };
                    if let Some(event) = result {
                        let droppable =
                            matches!(event, EngineEvent::Position(..) | EngineEvent::Duration(..));
                        let mut notice = Notice::Engine(event);
                        loop {
                            match notices.try_send(notice) {
                                Ok(()) => break,
                                Err(mpsc::error::TrySendError::Closed(_)) => return,
                                Err(mpsc::error::TrySendError::Full(value)) => {
                                    if droppable || stop.load(Ordering::Acquire) {
                                        break;
                                    }
                                    notice = value;
                                    thread::park_timeout(std::time::Duration::from_millis(5));
                                }
                            }
                        }
                    }
                    if shutdown {
                        break;
                    }
                }
            },
        )?);
        Ok(this)
    }

    pub fn load(&self, media: Arc<Media>, position: f64) -> Result<()> {
        self.generation.store(media.generation, Ordering::Release);
        let uri = format!("spotygram://{}", media.generation);
        self.context.media.store(Some(media));
        self.property("pause", "yes")?;
        self.command_id(
            self.generation.load(Ordering::Acquire),
            &[
                "loadfile",
                &uri,
                "replace",
                "0",
                &format!("start={}", position.max(0.)),
            ],
        )
    }
    pub fn stop(&self) -> Result<()> {
        self.generation.fetch_add(1, Ordering::AcqRel);
        self.context.media.store(None);
        self.command(&["stop"])
    }
    pub fn property(&self, name: &str, value: &str) -> Result<()> {
        let name = CString::new(name)?;
        let value = CString::new(value)?;
        ensure!(
            unsafe { (self.api.set)(self.handle as *mut c_void, name.as_ptr(), value.as_ptr()) }
                >= 0,
            "Audio control failed"
        );
        Ok(())
    }
    pub fn command(&self, arguments: &[&str]) -> Result<()> {
        self.command_id(0, arguments)
    }
    pub fn seek(&self, position: f64) -> Result<()> {
        self.command_id(
            self.generation.load(Ordering::Acquire),
            &["seek", &position.max(0.).to_string(), "absolute+exact"],
        )
    }
    pub fn waiting(&self) -> bool {
        self.context.waiting.load(Ordering::Acquire) != 0
    }
    fn command_id(&self, id: u64, arguments: &[&str]) -> Result<()> {
        let strings = arguments
            .iter()
            .map(|s| CString::new(*s))
            .collect::<std::result::Result<Vec<_>, _>>()?;
        let mut pointers: Vec<_> = strings.iter().map(|s| s.as_ptr()).collect();
        pointers.push(ptr::null());
        ensure!(
            unsafe { (self.api.command)(self.handle as *mut c_void, id, pointers.as_ptr()) } >= 0,
            "Audio command failed"
        );
        Ok(())
    }
}

fn numeric_property(api: &Api, handle: *mut c_void, name: &CStr) -> Option<f64> {
    let pointer = unsafe { (api.get_string)(handle, name.as_ptr()) };
    if pointer.is_null() {
        return None;
    }
    let value = unsafe { CStr::from_ptr(pointer) }
        .to_str()
        .ok()
        .and_then(|s| s.parse().ok());
    unsafe { (api.free)(pointer as *mut c_void) };
    value
}

fn stream_generation(api: &Api, handle: *mut c_void) -> Option<u64> {
    let pointer = unsafe { (api.get_string)(handle, c"path".as_ptr()) };
    if pointer.is_null() {
        return None;
    }
    let result = unsafe { CStr::from_ptr(pointer) }
        .to_str()
        .ok()
        .and_then(|s| s.strip_prefix("spotygram://"))
        .and_then(|s| s.parse().ok());
    unsafe { (api.free)(pointer as *mut c_void) };
    result
}

impl Drop for Mpv {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        unsafe {
            (self.api.wakeup)(self.handle as *mut c_void);
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
        unsafe {
            (self.api.destroy)(self.handle as *mut c_void);
        }
    }
}

unsafe extern "C" fn open_stream(
    context: *mut c_void,
    url: *const c_char,
    info: *mut StreamInfo,
) -> c_int {
    let context = unsafe { &*(context as *const StreamContext) };
    let Some(media) = context.media.load_full() else {
        return -1;
    };
    let uri = unsafe { CStr::from_ptr(url) }.to_string_lossy();
    if uri != format!("spotygram://{}", media.generation) {
        return -1;
    }
    let generation = media.generation;
    let reader = Reader::open(media);
    let cancelled = reader.cancelled.clone();
    let stream = Box::new(Stream {
        reader: Mutex::new(reader),
        cancelled,
        generation,
        notices: context.notices.clone(),
        waiting: context.waiting.clone(),
    });
    unsafe {
        *info = StreamInfo {
            cookie: Box::into_raw(stream) as *mut c_void,
            read: Some(read_stream),
            seek: Some(seek_stream),
            size: Some(size_stream),
            close: Some(close_stream),
            cancel: Some(cancel_stream),
        };
    }
    0
}
unsafe extern "C" fn read_stream(cookie: *mut c_void, buffer: *mut c_char, size: u64) -> i64 {
    let stream = unsafe { &*(cookie as *const Stream) };
    let bytes = unsafe {
        std::slice::from_raw_parts_mut(buffer as *mut u8, size.min(usize::MAX as u64) as usize)
    };
    let mut waiting = false;
    let result = stream.reader.lock().unwrap().read_interruptible(bytes, || {
        if !waiting {
            stream.waiting.fetch_add(1, Ordering::AcqRel);
            waiting = true;
        }
        false
    });
    if waiting {
        stream.waiting.fetch_sub(1, Ordering::AcqRel);
    }
    match result {
        Ok(count) => count as i64,
        Err(error) => {
            eprintln!("Audio reader: {error}");
            let mut notice =
                Notice::Engine(EngineEvent::Failed(stream.generation, error.to_string()));
            while !stream.cancelled.load(Ordering::Acquire) {
                match stream.notices.try_send(notice) {
                    Ok(()) | Err(mpsc::error::TrySendError::Closed(_)) => break,
                    Err(mpsc::error::TrySendError::Full(value)) => {
                        notice = value;
                        thread::park_timeout(std::time::Duration::from_millis(5));
                    }
                }
            }
            -1
        }
    }
}
unsafe extern "C" fn seek_stream(cookie: *mut c_void, position: i64) -> i64 {
    unsafe { &*(cookie as *const Stream) }
        .reader
        .lock()
        .unwrap()
        .seek(position)
        .unwrap_or(-1)
}
unsafe extern "C" fn size_stream(cookie: *mut c_void) -> i64 {
    unsafe { &*(cookie as *const Stream) }
        .reader
        .lock()
        .unwrap()
        .media
        .track
        .size
}
unsafe extern "C" fn close_stream(cookie: *mut c_void) {
    drop(unsafe { Box::from_raw(cookie as *mut Stream) });
}
unsafe extern "C" fn cancel_stream(cookie: *mut c_void) {
    unsafe { &*(cookie as *const Stream) }
        .cancelled
        .store(true, Ordering::Release);
}
