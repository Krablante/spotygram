#![cfg_attr(
    all(target_os = "windows", not(debug_assertions)),
    windows_subsystem = "windows"
)]

fn main() {
    if std::env::args().any(|arg| arg == "--version") {
        println!("spotygram rust {}", env!("CARGO_PKG_VERSION"));
        return;
    }
    spotygram_rust::app::app_main();
}
