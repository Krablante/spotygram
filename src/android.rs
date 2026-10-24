use crate::{
    model::*,
    reader::{Media, Reader},
    runtime,
};
use anyhow::{Context, Result, ensure};
use arc_swap::ArcSwapOption;
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JByteArray, JClass, JString, JValue},
    sys::{jboolean, jint, jlong},
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};
use tokio::sync::mpsc;

struct Java {
    vm: JavaVM,
    class: GlobalRef,
}
static JAVA: OnceLock<Java> = OnceLock::new();
static NOTICES: OnceLock<mpsc::Sender<Notice>> = OnceLock::new();
static MEDIA: ArcSwapOption<Media> = ArcSwapOption::const_empty();
static READERS: OnceLock<Mutex<HashMap<u64, Arc<NativeReader>>>> = OnceLock::new();
static READER_ID: AtomicU64 = AtomicU64::new(1);
struct ReadBuffer {
    reader: Reader,
    buffer: Vec<u8>,
}
struct NativeReader {
    inner: Mutex<ReadBuffer>,
    cancelled: Arc<AtomicBool>,
    size: i64,
}

pub fn set_notices(notices: mpsc::Sender<Notice>) {
    let _ = NOTICES.set(notices);
}

pub fn call_string(method: &str, argument: &str) -> Result<String> {
    let java = JAVA.get().context("Android platform is not initialized")?;
    let mut env = java.vm.attach_current_thread()?;
    let method = env.new_string(method)?;
    let argument = env.new_string(argument)?;
    let class = unsafe { JClass::from_raw(java.class.as_obj().as_raw()) };
    let value = env
        .call_static_method(
            class,
            "call",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[
                JValue::Object(method.as_ref()),
                JValue::Object(argument.as_ref()),
            ],
        )?
        .l()?;
    let value = JString::from(value);
    let result: String = env.get_string(&value)?.into();
    ensure!(
        !result.starts_with("!error:"),
        "{}",
        result.trim_start_matches("!error:")
    );
    Ok(result)
}

pub fn player(value: Value) -> Result<()> {
    if value["action"] == "stop" {
        if let Some(readers) = READERS.get() {
            for reader in readers.lock().unwrap().values() {
                reader.cancelled.store(true, Ordering::Release);
            }
        }
        MEDIA.store(None);
    }
    call_string("player", &value.to_string())?;
    Ok(())
}

pub fn load(media: Arc<Media>, position: f64) -> Result<()> {
    if let Some(readers) = READERS.get() {
        for reader in readers.lock().unwrap().values() {
            reader.cancelled.store(true, Ordering::Release);
        }
    }
    let value = json!({"action":"load","generation":media.generation,"uri":format!("spotygram://{}",media.generation),"position":position,"title":media.track.title,"artist":media.track.subtitle(),"art":media.track.art,"duration":media.track.duration});
    MEDIA.store(Some(media));
    player(value)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_initialize(
    env: JNIEnv,
    class: JClass,
) {
    let result = (|| -> Result<()> {
        let java = Java {
            vm: env.get_java_vm()?,
            class: env.new_global_ref(class)?,
        };
        let _ = JAVA.set(java);
        Ok(())
    })();
    if let Err(error) = result {
        eprintln!("Android initialization: {error}");
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_command(
    mut env: JNIEnv,
    _class: JClass,
    action: JString,
    value: f64,
) -> jboolean {
    let Ok(action) = env.get_string(&action) else {
        return 0;
    };
    let command = match action.to_str().unwrap_or_default() {
        "play" => Command::Resume,
        "pause" => Command::Pause,
        "toggle" => Command::Toggle,
        "next" => Command::Next,
        "previous" => Command::Previous,
        "seek" => Command::Seek(value),
        "speed" => Command::Speed(value),
        "visible" => Command::Visible(value != 0.),
        "network" => Command::NetworkChanged,
        _ => return 0,
    };
    runtime::existing().is_some_and(|client| client.commands.try_send(command).is_ok()) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_playerEvent(
    mut env: JNIEnv,
    _class: JClass,
    generation: jlong,
    event: jint,
    value: f64,
    error: JString,
) {
    let Some(notices) = NOTICES.get() else {
        return;
    };
    let generation = generation as u64;
    let event = match event {
        0 => EngineEvent::Loaded(generation),
        1 => EngineEvent::Position(generation, value),
        2 => EngineEvent::Duration(generation, value),
        3 => EngineEvent::Buffering(generation, value != 0.),
        4 => EngineEvent::Paused(generation, value != 0.),
        5 => EngineEvent::Finished(generation),
        _ => EngineEvent::Failed(
            generation,
            env.get_string(&error)
                .map(|s| s.to_string_lossy().into_owned())
                .unwrap_or_else(|_| "Android audio error".into()),
        ),
    };
    let _ = notices.try_send(Notice::Engine(event));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_openAudio(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
    position: jlong,
) -> jlong {
    let result = (|| -> Result<u64> {
        let uri: String = env.get_string(&uri)?.into();
        let media = MEDIA
            .load_full()
            .context("Audio stream has been replaced")?;
        ensure!(
            uri == format!("spotygram://{}", media.generation),
            "Audio stream has been replaced"
        );
        let mut reader = Reader::open(media);
        reader.seek(position)?;
        let cancelled = reader.cancelled.clone();
        let size = reader.media.track.size;
        let id = READER_ID.fetch_add(1, Ordering::Relaxed);
        READERS
            .get_or_init(|| Mutex::new(HashMap::new()))
            .lock()
            .unwrap()
            .insert(
                id,
                Arc::new(NativeReader {
                    inner: Mutex::new(ReadBuffer {
                        reader,
                        buffer: vec![0; 32768],
                    }),
                    cancelled,
                    size,
                }),
            );
        Ok(id)
    })();
    match result {
        Ok(id) => id as i64,
        Err(error) => {
            eprintln!("Audio open: {error}");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_readAudio(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    array: JByteArray,
    offset: jint,
    length: jint,
) -> jint {
    let result = (|| -> Result<i32> {
        ensure!(
            offset >= 0
                && length >= 0
                && offset as i64 + length as i64 <= env.get_array_length(&array)? as i64,
            "Invalid audio buffer"
        );
        let reader = READERS
            .get()
            .context("Audio stream is closed")?
            .lock()
            .unwrap()
            .get(&(handle as u64))
            .cloned()
            .context("Audio stream is closed")?;
        let mut inner = reader.inner.lock().unwrap();
        let ReadBuffer { reader, buffer } = &mut *inner;
        let limit = (length as usize).min(buffer.len());
        let count = reader.read(&mut buffer[..limit])?;
        if count == 0 {
            return Ok(-1);
        }
        let signed = unsafe { std::slice::from_raw_parts(buffer.as_ptr() as *const i8, count) };
        env.set_byte_array_region(&array, offset, signed)?;
        Ok(count as i32)
    })();
    match result {
        Ok(count) => count,
        Err(error) => {
            eprintln!("Audio read: {error}");
            -2
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_audioSize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    READERS
        .get()
        .and_then(|readers| {
            readers
                .lock()
                .unwrap()
                .get(&(handle as u64))
                .map(|r| r.size)
        })
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_closeAudio(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(readers) = READERS.get() {
        if let Some(reader) = readers.lock().unwrap().remove(&(handle as u64)) {
            reader.cancelled.store(true, Ordering::Release);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_spotygram_rust_PlatformBridge_imported(
    mut env: JNIEnv,
    _class: JClass,
    paths: JString,
) -> jboolean {
    let result = (|| -> Result<()> {
        let paths: String = env.get_string(&paths)?.into();
        let files: Vec<std::path::PathBuf> = serde_json::from_str(&paths)?;
        runtime::start().commands.try_send(Command::Import(files))?;
        Ok(())
    })();
    match result {
        Ok(()) => 1,
        Err(error) => {
            eprintln!("Android import: {error}");
            0
        }
    }
}
