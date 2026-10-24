use crate::{model::*, runtime};
use anyhow::Result;
use souvlaki::{
    MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig,
    SeekDirection,
};
use std::time::{Duration, Instant};

pub struct Controls {
    controls: MediaControls,
    track: String,
    playing: Option<bool>,
    #[cfg(target_os = "linux")]
    volume: Option<f64>,
    progress: Instant,
}

impl Controls {
    pub fn new() -> Result<Self> {
        let mut controls = MediaControls::new(PlatformConfig {
            dbus_name: "spotygram_rust",
            display_name: "spotygram rust",
            hwnd: window_handle(),
        })?;
        controls.attach(|event| {
            let client = runtime::start();
            let state = client.snapshot.load_full();
            let command = match event {
                MediaControlEvent::Play => Some(Command::Resume),
                MediaControlEvent::Pause | MediaControlEvent::Stop => Some(Command::Pause),
                MediaControlEvent::Toggle => Some(Command::Toggle),
                MediaControlEvent::Next => Some(Command::Next),
                MediaControlEvent::Previous => Some(Command::Previous),
                MediaControlEvent::SetPosition(position) => {
                    Some(Command::Seek(position.0.as_secs_f64()))
                }
                MediaControlEvent::Seek(direction) => Some(Command::Seek(
                    (state.playback.position
                        + if direction == SeekDirection::Forward {
                            10.
                        } else {
                            -10.
                        })
                    .max(0.),
                )),
                MediaControlEvent::SeekBy(direction, duration) => Some(Command::Seek(
                    (state.playback.position
                        + duration.as_secs_f64()
                            * if direction == SeekDirection::Forward {
                                1.
                            } else {
                                -1.
                            })
                    .max(0.),
                )),
                MediaControlEvent::SetVolume(volume) => Some(Command::Volume(volume * 100.)),
                MediaControlEvent::Quit => Some(Command::Quit),
                _ => None,
            };
            if let Some(command) = command {
                let _ = client.commands.try_send(command);
            }
        })?;
        Ok(Self {
            controls,
            track: String::new(),
            playing: None,
            #[cfg(target_os = "linux")]
            volume: None,
            progress: Instant::now(),
        })
    }

    pub fn update(&mut self, playback: &Playback) -> Result<()> {
        let id = playback
            .track
            .as_ref()
            .map(|t| {
                format!(
                    "{}\n{}\n{}\n{}\n{}\n{}",
                    t.id, t.title, t.artist, t.source, t.art, playback.duration
                )
            })
            .unwrap_or_default();
        if self.track != id {
            if let Some(track) = &playback.track {
                let cover = if track.art.is_empty() {
                    None
                } else {
                    url::Url::from_file_path(&track.art)
                        .ok()
                        .map(|u| u.to_string())
                };
                self.controls.set_metadata(MediaMetadata {
                    title: Some(&track.title),
                    artist: Some(track.subtitle()),
                    album: Some(&track.source),
                    cover_url: cover.as_deref(),
                    duration: Some(Duration::from_secs_f64(playback.duration.max(0.))),
                })?;
            }
            self.track = id;
            self.playing = None;
        }
        if self.playing != Some(playback.playing)
            || self.progress.elapsed() >= Duration::from_secs(1)
        {
            let progress = Some(MediaPosition(Duration::from_secs_f64(
                playback.position.max(0.),
            )));
            self.controls.set_playback(if playback.track.is_none() {
                MediaPlayback::Stopped
            } else if playback.playing {
                MediaPlayback::Playing { progress }
            } else {
                MediaPlayback::Paused { progress }
            })?;
            self.playing = Some(playback.playing);
            self.progress = Instant::now();
        }
        #[cfg(target_os = "linux")]
        {
            let volume = if playback.muted {
                0.0
            } else {
                playback.volume / 100.0
            };
            if self.volume != Some(volume) {
                self.controls.set_volume(volume)?;
                self.volume = Some(volume);
            }
        }
        Ok(())
    }
}

#[cfg(not(target_os = "windows"))]
fn window_handle() -> Option<*mut std::ffi::c_void> {
    None
}

#[cfg(target_os = "windows")]
fn window_handle() -> Option<*mut std::ffi::c_void> {
    use windows_sys::Win32::{
        Foundation::{HWND, LPARAM},
        System::Threading::GetCurrentProcessId,
        UI::WindowsAndMessaging::{EnumWindows, GetWindowThreadProcessId, IsWindowVisible},
    };
    use windows_sys::core::BOOL;
    unsafe extern "system" fn own(window: HWND, result: LPARAM) -> BOOL {
        let mut process = 0;
        unsafe {
            GetWindowThreadProcessId(window, &mut process);
        }
        if process == unsafe { GetCurrentProcessId() } && unsafe { IsWindowVisible(window) } != 0 {
            unsafe {
                *(result as *mut HWND) = window;
            }
            return 0;
        }
        1
    }
    let mut window: HWND = std::ptr::null_mut();
    unsafe {
        EnumWindows(Some(own), &mut window as *mut _ as isize);
    }
    (!window.is_null()).then_some(window)
}
