use anyhow::{Context, Result, bail, ensure};
use fs2::FileExt;
use std::{
    fs::{self, File, OpenOptions},
    path::{Path, PathBuf},
    sync::Arc,
};

pub struct Paths {
    pub root: PathBuf,
    pub cache: PathBuf,
    pub imports: PathBuf,
    pub artwork: PathBuf,
    _lock: File,
}

impl Paths {
    pub fn open() -> Result<Arc<Self>> {
        #[cfg(not(target_os = "android"))]
        let root = if let Some(path) = std::env::var_os("SPOTYGRAM_RUST_PROFILE") {
            PathBuf::from(path)
        } else {
            directories::ProjectDirs::from("app", "spotygram", "spotygram-rust")
                .context("Cannot locate the application profile")?
                .data_local_dir()
                .to_owned()
        };
        #[cfg(target_os = "android")]
        let root = PathBuf::from(crate::android::call_string("filesDirectory", "")?);
        fs::create_dir_all(&root)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&root, fs::Permissions::from_mode(0o700))?;
        }
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(root.join("instance.lock"))?;
        lock.try_lock_exclusive()
            .context("spotygram rust is already running for this profile")?;
        let paths = Self {
            cache: root.join("cache"),
            imports: root.join("imports"),
            artwork: root.join("artwork"),
            root,
            _lock: lock,
        };
        for path in [&paths.cache, &paths.imports, &paths.artwork] {
            fs::create_dir_all(path)?;
        }
        Ok(Arc::new(paths))
    }

    pub fn database_key(&self) -> Result<String> {
        #[cfg(target_os = "android")]
        {
            return crate::android::call_string("databaseKey", "");
        }
        #[cfg(not(target_os = "android"))]
        {
            use base64::{Engine, engine::general_purpose::STANDARD};
            let identity = format!(
                "telegram-{:x}",
                sha2::Sha256::digest(self.root.to_string_lossy().as_bytes())
            );
            let entry = keyring::Entry::new("app.spotygram.rust", &identity)?;
            match entry.get_password() {
                Ok(key) => {
                    ensure!(!key.is_empty(), "Telegram database key is empty");
                    Ok(key)
                }
                Err(keyring::Error::NoEntry) => {
                    let database = self.root.join("telegram/db");
                    let existing = database.exists() && fs::read_dir(&database)?.next().is_some();
                    ensure!(
                        !existing,
                        "The key for the existing Telegram database is unavailable; unlock the system key store"
                    );
                    let secret = STANDARD.encode(rand::random::<[u8; 32]>());
                    entry.set_password(&secret).context(
                        "Cannot store the Telegram database key in the system key store",
                    )?;
                    Ok(secret)
                }
                Err(error) => Err(error).context("Unlock the system key store to connect Telegram"),
            }
        }
    }
}

#[cfg(not(target_os = "android"))]
use sha2::Digest;

pub fn native_library(name: &str) -> Result<libloading::Library> {
    let filename = if cfg!(target_os = "windows") {
        if name == "mpv" {
            "libmpv-2.dll".into()
        } else {
            format!("{name}.dll")
        }
    } else if cfg!(target_os = "macos") {
        format!("lib{name}.dylib")
    } else {
        format!("lib{name}.so")
    };
    let mut paths = Vec::new();
    if let Some(dir) = std::env::var_os("SPOTYGRAM_NATIVE_DIR") {
        paths.push(PathBuf::from(dir).join(&filename));
    }
    if let Ok(executable) = std::env::current_exe() {
        if let Some(parent) = executable.parent() {
            paths.extend([
                parent.join("native").join(&filename),
                parent.join("lib").join(&filename),
                parent.join(&filename),
            ]);
            #[cfg(target_os = "macos")]
            paths.push(parent.join("../Frameworks").join(&filename));
        }
    }
    paths.push(PathBuf::from(&filename));
    let mut last = String::new();
    for path in paths {
        // The application loads only its packaged native engines or an explicit operator override.
        #[cfg(target_os = "windows")]
        let loaded = unsafe {
            if path.is_absolute() {
                use windows_sys::Win32::System::LibraryLoader::{
                    LOAD_LIBRARY_SEARCH_DEFAULT_DIRS, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR,
                };
                libloading::os::windows::Library::load_with_flags(
                    &path,
                    LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS,
                )
                .map(Into::into)
            } else {
                libloading::Library::new(&path)
            }
        };
        #[cfg(not(target_os = "windows"))]
        let loaded = unsafe { libloading::Library::new(&path) };
        match loaded {
            Ok(lib) => return Ok(lib),
            Err(error) => last = error.to_string(),
        }
    }
    bail!("Cannot load the bundled {name} engine: {last}")
}

pub fn open_url(url: &str) -> Result<()> {
    ensure!(
        url.starts_with("https://") || url.starts_with("tg://"),
        "Unsupported link"
    );
    #[cfg(target_os = "android")]
    {
        crate::android::call_string("openUrl", url)?;
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("rundll32")
            .args(["url.dll,FileProtocolHandler", url])
            .spawn()?;
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open").arg(url).spawn()?;
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open").arg(url).spawn()?;
    }
    Ok(())
}

pub fn is_owned(path: &Path, root: &Path) -> bool {
    path.canonicalize()
        .ok()
        .zip(root.canonicalize().ok())
        .is_some_and(|(path, root)| path.starts_with(&root) && path != root)
}

/// Keeps Android's single transfer notification alive for overlapping upload/download jobs.
pub struct Transfer;
#[cfg(target_os = "android")]
static TRANSFERS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
impl Transfer {
    pub fn start() -> Result<Self> {
        #[cfg(target_os = "android")]
        if TRANSFERS.fetch_add(1, std::sync::atomic::Ordering::AcqRel) == 0 {
            if let Err(error) = crate::android::call_string("transfer", "true") {
                TRANSFERS.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
                return Err(error);
            }
        }
        Ok(Self)
    }
}
impl Drop for Transfer {
    fn drop(&mut self) {
        #[cfg(target_os = "android")]
        if TRANSFERS.fetch_sub(1, std::sync::atomic::Ordering::AcqRel) == 1 {
            let _ = crate::android::call_string("transfer", "false");
        }
    }
}
