# Build the Rust preview

[English](build.md) · [Русский](build.ru.md) · [Documentation](README.md)

Use the `rust-rewrite` branch. Source is public; credentials, signing keys,
Telegram sessions and generated packages must stay outside the checkout.

## Desktop

Install Rust **1.97.0**, Python **3.12+**, the platform's C/C++ toolchain and Git.
Windows needs the Visual Studio C++ build tools and Windows SDK; macOS needs
Xcode's command-line tools. Makepad is pinned by revision in Cargo.toml and
resolved through Cargo.lock.

On Ubuntu 24.04, install the native build dependencies:

```sh
sudo apt-get install build-essential pkg-config libx11-dev libxcursor-dev \
  libxrandr-dev libxi-dev libxinerama-dev libxkbcommon-dev libwayland-dev \
  libasound2-dev libpulse-dev libegl1-mesa-dev libgl1-mesa-dev \
  libgles2-mesa-dev libdbus-1-dev
```

Choose a private build-state directory and run the packager:

```sh
export SPOTYGRAM_STATE="$HOME/.local/state/spotygram"
python3 -B tools/build-rust.py
```

PowerShell:

```powershell
$env:SPOTYGRAM_STATE = Join-Path $env:LOCALAPPDATA 'spotygram-build'
python -B tools/build-rust.py
```

Windows and macOS packaging also need Pillow (`python3 -m pip install Pillow==12.0.0`). The
packager creates the app icon, builds the native executable, bundles Makepad
resources and pinned TDLib/libmpv libraries, and ad hoc signs the macOS app.
Linux's private audio dependencies exclude libstdc++ and libgcc so the graphics
driver uses the host's C++ runtime.

The resulting image is under `SPOTYGRAM_STATE/rust/images/<target>`. Archives
and SHA-256 files are under `SPOTYGRAM_STATE/rust/releases/<version>`. Extracted
desktop packages are portable application folders; they do not include the
stable installers or a Java runtime. `--image-only` skips archive creation.
`--native-only` prepares just the desktop native dependencies.

For Telegram sign-in, set `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` in the build
environment or in `SPOTYGRAM_STATE/telegram.env`:

```dotenv
TELEGRAM_API_ID=your_application_id
TELEGRAM_API_HASH=your_application_hash
```

Obtain your own application credentials at https://my.telegram.org for a new
distribution. Do not commit this file. A build without them can import and play
local files; Telegram connection reports the missing configuration. Published
owner builds use the existing private application configuration.

## Android

Build on Linux with JDK **21**, Android SDK platform **37**, NDK
**28.2.13676358**, Ninja, CMake and the Android SDK command-line tools. The
Gradle wrapper supplies Gradle 9.3.1 and the Android project uses AGP 9.1.1.
The app targets Android 36 and supports API 26+.

```sh
export SPOTYGRAM_STATE="$HOME/.local/state/spotygram"
export ANDROID_HOME=/absolute/path/to/android-sdk
rustup target add aarch64-linux-android x86_64-linux-android
python3 -B tools/build-rust.py --android
```

The packager reuses the pinned native dependency cache or builds TDLib's C API
with static OpenSSL. Rust uses the NDK linkers and 16 KiB page alignment. The
shared UI, core and JNI bridge become `libmakepad.so`; TDLib is `libtdjson.so`.
Makepad's Java platform files, resources and native outputs are staged outside
source, then Gradle builds and lints the signed APKs. To build one ABI, add
`--abi arm64-v8a` or `--abi x86_64`.

Owner release signing uses these existing private files:

```text
SPOTYGRAM_STATE/release.jks
SPOTYGRAM_STATE/signing.properties
```

The properties file supplies `storePassword`; the existing `spotygram` key uses
that password. A new distributor must provide their own keystore. Preserve the
owner's key when updating the same Rust application. The experimental
application ID is `app.spotygram.rust`, so installing it does not replace
`app.spotygram`.

## Verification and publishing

The packager defaults to two workers. Linux builds also inherit a two-CPU
affinity limit. Keep native builds, Gradle and emulator execution sequential.
The release profile enables optimization, thin LTO and stripped debug symbols.

```sh
cargo fmt --all -- --check
cargo check --locked -j2
```

Checks do not validate Makepad scripts, rendering or device behavior by
themselves. Run the packaged release app with a separate profile, exercise its
controls and inspect actual GPU frames. Use physical devices to assess sound,
background reliability, battery use and frame pacing. Do not open the owner's
unrelated Telegram sessions for verification.

The manual **Rust preview desktop builds** GitHub workflow builds all four
desktop targets from the selected ref. It uses existing Telegram application
secrets and uploads archives with hashes. It does not publish them automatically.

Create a draft release tagged `rust-v<version>` targeting `rust-rewrite`. Upload
the signed APKs, desktop archives and matching hashes. Check the complete set,
then publish with **prerelease** enabled and **latest** disabled. Ordinary
numeric tags and the stable latest-release channel belong to `main`.
