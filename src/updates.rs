use anyhow::Result;

/// Rust previews have a distinct prerelease tag family; the stable channel is never consulted.
pub async fn check() -> Result<String> {
    #[cfg(not(target_os = "android"))]
    {
        tokio::task::spawn_blocking(|| -> Result<String> {
            let config = ureq::Agent::config_builder()
                .timeout_global(Some(std::time::Duration::from_secs(8)))
                .max_redirects(0)
                .build();
            let agent: ureq::Agent = config.into();
            let mut response = agent
                .get("https://api.github.com/repos/Krablante/spotygram/releases?per_page=30")
                .header("User-Agent", "spotygram-rust")
                .header("Accept", "application/vnd.github+json")
                .call()?;
            let releases: serde_json::Value = response
                .body_mut()
                .with_config()
                .limit(1024 * 1024)
                .read_json()?;
            let current = version(env!("CARGO_PKG_VERSION")).unwrap_or_default();
            let mut newest = current.clone();
            let mut url = String::new();
            for release in releases.as_array().into_iter().flatten() {
                let tag = release["tag_name"].as_str().unwrap_or_default();
                if release["prerelease"] == true && release["draft"] == false {
                    if let Some(number) = tag.strip_prefix("rust-v").and_then(version) {
                        if number > newest {
                            newest = number;
                            url = release["html_url"].as_str().unwrap_or_default().to_owned();
                        }
                    }
                }
            }
            Ok(url)
        })
        .await?
    }
    #[cfg(target_os = "android")]
    {
        tokio::task::spawn_blocking(|| {
            crate::android::call_string("checkUpdate", env!("CARGO_PKG_VERSION"))
        })
        .await?
    }
}

#[cfg(not(target_os = "android"))]
fn version(text: &str) -> Option<Vec<u64>> {
    let parts = text
        .split('.')
        .map(str::parse)
        .collect::<std::result::Result<Vec<u64>, _>>()
        .ok()?;
    (parts.len() == 3).then_some(parts)
}
