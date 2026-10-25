use crate::{model::*, reader::Media};
use anyhow::Result;
use std::sync::Arc;
use tokio::sync::mpsc;

#[cfg(not(target_os = "android"))]
mod mpv;

pub struct Engine {
    #[cfg(not(target_os = "android"))]
    inner: mpv::Mpv,
}

impl Engine {
    pub fn new(notices: mpsc::Sender<Notice>) -> Result<Self> {
        #[cfg(not(target_os = "android"))]
        {
            Ok(Self {
                inner: mpv::Mpv::new(notices)?,
            })
        }
        #[cfg(target_os = "android")]
        {
            crate::android::set_notices(notices);
            Ok(Self {})
        }
    }
    pub fn begin(&self, generation: u64, track: &Track) -> Result<()> {
        #[cfg(target_os = "android")]
        crate::android::player(
            serde_json::json!({"action":"begin","generation":generation,"title":track.title}),
        )?;
        #[cfg(not(target_os = "android"))]
        let _ = (generation, track);
        Ok(())
    }
    pub fn load(
        &self,
        media: Arc<Media>,
        position: f64,
        queue: &Queue,
        playing: bool,
    ) -> Result<()> {
        #[cfg(not(target_os = "android"))]
        {
            let _ = (queue, playing);
            self.inner.load(media, position)
        }
        #[cfg(target_os = "android")]
        {
            crate::android::load(media, position, queue, playing)
        }
    }
    pub fn pause(&self, pause: bool) -> Result<()> {
        self.property("pause", if pause { "yes" } else { "no" })
    }
    pub fn speed(&self, value: f64) -> Result<()> {
        self.property("speed", &value.clamp(0.3, 4.).to_string())
    }
    pub fn volume(&self, value: f64) -> Result<()> {
        self.property("volume", &value.clamp(0., 100.).to_string())
    }
    pub fn mute(&self, value: bool) -> Result<()> {
        self.property("mute", if value { "yes" } else { "no" })
    }
    pub fn property(&self, key: &str, value: &str) -> Result<()> {
        #[cfg(not(target_os = "android"))]
        {
            self.inner.property(key, value)
        }
        #[cfg(target_os = "android")]
        {
            crate::android::player(serde_json::json!({"action":"property","key":key,"value":value}))
        }
    }
    pub fn seek(&self, position: f64) -> Result<()> {
        #[cfg(not(target_os = "android"))]
        {
            self.inner.seek(position)
        }
        #[cfg(target_os = "android")]
        {
            crate::android::player(serde_json::json!({"action":"seek","position":position.max(0.)}))
        }
    }
    pub fn waiting(&self) -> bool {
        #[cfg(not(target_os = "android"))]
        {
            self.inner.waiting()
        }
        #[cfg(target_os = "android")]
        {
            false
        }
    }
    pub fn stop(&self) -> Result<()> {
        #[cfg(not(target_os = "android"))]
        {
            self.inner.stop()
        }
        #[cfg(target_os = "android")]
        {
            crate::android::player(serde_json::json!({"action":"stop"}))
        }
    }
}
