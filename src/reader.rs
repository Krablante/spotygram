use crate::{model::*, telegram::Telegram};
use anyhow::{Context, Result, bail, ensure};
use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

pub struct Media {
    pub generation: u64,
    pub track: Track,
    pub telegram: Option<Arc<Telegram>>,
}

pub struct Reader {
    pub media: Arc<Media>,
    pub position: i64,
    file: Option<File>,
    file_position: i64,
    path: String,
    requested_at: i64,
    requested_time: Instant,
    pub cancelled: Arc<AtomicBool>,
}

impl Reader {
    pub fn open(media: Arc<Media>) -> Self {
        Self {
            media,
            position: 0,
            file: None,
            file_position: 0,
            path: String::new(),
            requested_at: -1,
            requested_time: Instant::now(),
            cancelled: Arc::new(AtomicBool::new(false)),
        }
    }
    pub fn seek(&mut self, offset: i64) -> Result<i64> {
        ensure!(
            offset >= 0 && (self.media.track.size <= 0 || offset <= self.media.track.size),
            "Invalid audio seek"
        );
        self.position = offset;
        self.requested_at = -1;
        Ok(offset)
    }
    pub fn read_interruptible(
        &mut self,
        buffer: &mut [u8],
        mut interrupted: impl FnMut() -> bool,
    ) -> Result<usize> {
        if self.media.track.size > 0 && self.position >= self.media.track.size {
            return Ok(0);
        }
        if buffer.is_empty() {
            return Ok(0);
        }
        let started = Instant::now();
        loop {
            ensure!(
                !self.cancelled.load(Ordering::Acquire),
                "Audio read was cancelled"
            );
            let (path, available, complete) =
                if self.media.track.imported() || self.media.telegram.is_none() {
                    ensure!(
                        !self.media.track.path.is_empty(),
                        "This recording has no local audio file"
                    );
                    let path = &self.media.track.path;
                    let size = if self.media.track.size > 0 {
                        self.media.track.size
                    } else {
                        std::fs::metadata(path)
                            .context("Cannot inspect the audio file")?
                            .len() as i64
                    };
                    (path.clone(), size - self.position, true)
                } else {
                    let td = self.media.telegram.as_ref().unwrap();
                    ensure!(
                        !td.files.closed.load(Ordering::Acquire),
                        "Telegram connection was closed"
                    );
                    let file = self.media.track.file;
                    if let Some(f) = td.files.get(file) {
                        if f.complete && f.size > 0 && self.position >= f.size {
                            return Ok(0);
                        }
                        let available = if f.complete {
                            f.size - self.position
                        } else if self.position >= f.offset {
                            f.offset + f.prefix - self.position
                        } else {
                            0
                        };
                        if available > 0 && !f.path.is_empty() {
                            (f.path, available, f.complete)
                        } else {
                            if self.requested_at != self.position
                                || (!f.active
                                    && self.requested_time.elapsed() >= Duration::from_secs(2))
                            {
                                td.download(file, self.position, 32)?;
                                self.requested_at = self.position;
                                self.requested_time = Instant::now();
                            }
                            ensure!(
                                started.elapsed() < Duration::from_secs(60),
                                "Telegram audio read timed out"
                            );
                            ensure!(!interrupted(), "Audio read was interrupted");
                            if f.complete || available > 0 {
                                // A reported range without a usable path would make
                                // wait(offset) return immediately on every iteration.
                                td.files.wait_change(Duration::from_millis(250));
                            } else {
                                td.files
                                    .wait(file, self.position, Duration::from_millis(250));
                            }
                            continue;
                        }
                    } else {
                        bail!("The Telegram file reference expired; select the recording again");
                    }
                };
            if self.file.is_none() || self.path != path {
                match File::open(&path) {
                    Ok(file) => {
                        self.file = Some(file);
                        self.path = path;
                        self.file_position = 0;
                    }
                    Err(error) if self.file.is_none() => {
                        return Err(error).context("Cannot open the audio file");
                    }
                    // TDLib can rename a partial file before the new path becomes visible.
                    Err(_) => {}
                }
            }
            let file = self.file.as_mut().unwrap();
            if self.file_position != self.position {
                file.seek(SeekFrom::Start(self.position as u64))?;
                self.file_position = self.position;
            }
            let limit = buffer.len().min(available.max(0) as usize);
            if limit == 0 && complete {
                return Ok(0);
            }
            let count = file.read(&mut buffer[..limit])?;
            if count > 0 {
                self.position += count as i64;
                self.file_position += count as i64;
                self.requested_at = -1;
                return Ok(count);
            }
            ensure!(!complete, "The audio file ended before its expected size");
            ensure!(
                started.elapsed() < Duration::from_secs(60),
                "Telegram audio read timed out"
            );
            if let Some(td) = &self.media.telegram {
                // TDLib can publish a prefix slightly before the bytes become visible.
                // wait(offset) would return immediately for that same prefix and spin.
                ensure!(!interrupted(), "Audio read was interrupted");
                td.files.wait_change(Duration::from_millis(20));
            }
        }
    }
}
