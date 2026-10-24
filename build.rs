use std::{env, fs, path::PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=SPOTYGRAM_WINDOWS_ICON");
    #[cfg(windows)]
    if let Some(icon) = env::var_os("SPOTYGRAM_WINDOWS_ICON") {
        let mut resource = winresource::WindowsResource::new();
        resource.set_icon(&PathBuf::from(icon).to_string_lossy());
        resource
            .set("FileDescription", "spotygram rust")
            .set("ProductName", "spotygram rust");
        resource
            .compile()
            .expect("Cannot embed the Windows application icon");
    }
    println!("cargo:rerun-if-env-changed=SPOTYGRAM_STATE");
    println!("cargo:rerun-if-env-changed=TELEGRAM_API_ID");
    println!("cargo:rerun-if-env-changed=TELEGRAM_API_HASH");
    let state = env::var_os("SPOTYGRAM_STATE").map(PathBuf::from);
    let private = state.map(|p| p.join("telegram.env"));
    let contents = private
        .as_ref()
        .and_then(|p| {
            println!("cargo:rerun-if-changed={}", p.display());
            fs::read_to_string(p).ok()
        })
        .unwrap_or_default();
    for key in ["TELEGRAM_API_ID", "TELEGRAM_API_HASH"] {
        let value = env::var(key)
            .ok()
            .or_else(|| {
                contents.lines().find_map(|line| {
                    let (name, value) = line.trim().split_once('=')?;
                    (name == key).then(|| value.trim().trim_matches(['\'', '"']).to_owned())
                })
            })
            .unwrap_or_default();
        println!("cargo:rustc-env={key}={value}");
    }
}
