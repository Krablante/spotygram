# Building Spotygram

[Documentation](README.md) · **English** · [Русский](build.ru.md)

For listening, use the APK or desktop installer from [Releases](https://github.com/Krablante/spotygram/releases/latest).
This guide is for building your own copy or maintaining the published app.
The first Android build compiles TDLib and OpenSSL; later builds reuse them.
Desktop builds download pinned TDLib/libmpv binaries with verified checksums.

## Prepare an Android build machine

Use Linux, JDK 21 (a full JDK, not only a JRE), Android SDK platform 37, build tools 36.0.0, NDK `28.2.13676358`, CMake, Ninja, gperf, Perl, a C++ compiler, Git, and Make. The Gradle wrapper pins Gradle 9.3.1 with Android Gradle plugin 9.1.1 and Kotlin 2.4.20. The app still targets Android 36 and supports Android 8+.

Keep generated data outside the checkout. `SPOTYGRAM_STATE` holds native sources,
build outputs, caches and private configuration; it is not a second source repository.
Both Gradle modules use this same directory. Without an explicit value, Gradle
uses `~/.local/state/spotygram`; the Android wrapper also honors `XDG_STATE_HOME`.
Set `SPOTYGRAM_STATE` explicitly when switching tools. Build scripts reject state
inside the checkout before generating or unpacking anything there.

Run heavy operations sequentially: finish native compilation before Gradle, and stop the emulator during builds. Native builds default to two workers (`SPOTYGRAM_JOBS`); Gradle defaults to two workers and a 2 GB heap. On shared hosts use an additional CPU/memory limit for the build process.

Use debug builds for routine UI iteration; run the slower minified release build after changes settle. Keep compiler project caches under state as the build script does.

```sh
export ANDROID_HOME=/path/to/android-sdk
export SPOTYGRAM_STATE=/path/to/private/spotygram-state
```

Create `$SPOTYGRAM_STATE/telegram.env` with your own app's `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`, obtained through my.telegram.org. Restrict it to your user. These values identify the app, not an already authorized user account. They are embedded in the APK and must not be considered unrecoverable secrets once an APK is distributed. Never put a user session or bot token here.

Use this format, replacing both placeholders with your own values:

```dotenv
TELEGRAM_API_ID=YOUR_NUMERIC_API_ID
TELEGRAM_API_HASH=YOUR_API_HASH
```

## Build a development APK

Run the script from the repository root:

```sh
bash tools/build.sh debug
```

The native build downloads official TDLib at `d1085f9cebc5a62379991ae1652673954f229c1f` and OpenSSL `openssl-3.5.6`. It generates JNI libraries for ARM64 and x86-64 with NDK r28c (16 KB page support), keeping all artifacts in state. Subsequent builds reuse them. No native binaries are committed.

Debug APKs are written to `$SPOTYGRAM_STATE/build/app/outputs/apk/debug/`.
Choose `app-arm64-v8a-debug.apk` for an ARM64 phone, or
`app-x86_64-debug.apk` for an x86-64 emulator.

## Signing

Create a release keystore once, and back it up securely. Future updates **must use the same key**.

```sh
keytool -genkeypair -keystore "$SPOTYGRAM_STATE/release.jks" \
  -alias spotygram -keyalg RSA -keysize 3072 -validity 10000
```

Create private `$SPOTYGRAM_STATE/signing.properties` containing `storePassword=YOUR_PASSWORD` (the same password for the keystore and key). Both files belong outside source control. Then:

```sh
bash tools/build.sh release
```

The phone APK is at `$SPOTYGRAM_STATE/build/app/outputs/apk/release/app-arm64-v8a-release.apk`. A separate `app-x86_64-release.apk` is built for x86-64 Android environments. This avoids making every phone carry an unused second native architecture. Verify with Android SDK `apksigner verify --verbose`, compute SHA-256, and upload the signed APKs to a GitHub release. Never publish the keystore or signing configuration.

## Build desktop packages

Build on the target operating system with **JDK 21**, **Python 3.12+** and the
OpenSSL 3 CLI (on macOS, `brew install openssl@3`). Windows and Linux use x86-64;
macOS has separate x86-64 and ARM64 builds.
JDK's `jpackage` prepares the application image and Linux/macOS installers.
Windows wraps that image with **Inno Setup 6.4.3**; Ubuntu needs **fakeroot** and
**binutils** (`readelf`) for DEB
packaging. The workflow prepares these tools. For a local Windows build, install
Inno Setup 6.4.3 or set `INNO_SETUP_COMPILER` to its `ISCC.exe`.
There is no native source compilation or Node.js runtime. Keep `SPOTYGRAM_STATE`
outside the checkout and use the same `telegram.env`, or supply `TELEGRAM_API_ID`
and `TELEGRAM_API_HASH` as environment variables.

```sh
python3 tools/build-desktop.py --image-only
```

On Windows use `python` instead of `python3`. The script uses two Gradle workers,
in-process Kotlin compilation and a single-use Gradle daemon. The image is in
`$SPOTYGRAM_STATE/build/desktop/distributions/main/app/`. Its bundled Java runtime
has a 256 MiB maximum heap; that limit excludes native Telegram, audio and graphics memory.
`shared/` is compiled directly by both platform modules, so there is no second
desktop UI or queue implementation to synchronize.

TDLib comes from `prebuilt-tdlib` 0.1008067.0. The LGPL libmpv 0.41.0 runtime comes
from the pinned `jason-yau/mpv-prebuilt` release. `tools/desktop-dependencies.py`
owns the URLs and integrity values. Downloads, unpacking and native binaries stay
under state. The platform image includes only its matching native dependencies.
macOS bundles receive an ad hoc code signature and the shared public version;
Apple notarization is not configured.
Packaging mounts the finished DMG read-only and checks the contained app with
`codesign --verify --deep --strict` before signing the installer for updates.

For a new fork's desktop update channel, create an Ed25519 signing key once:

```sh
python3 tools/package-desktop.py --init-key
```

This writes the private key to `$SPOTYGRAM_STATE/desktop/signing/release-private.pem`
and prints its public key. Put only that public key in
`desktop/src/main/resources/update-key.pub`. **Maintainers of official Spotygram
must keep the existing key and public key.** Back up both the desktop private key
and the Android keystore privately. Then build the native installer and signed portable archive:

```sh
python3 tools/build-desktop.py
```

EXE, DEB or DMG, its detached `.sig`, and the portable archive are written to
`$SPOTYGRAM_STATE/releases/VERSION/`. Each archive contains the
complete application, a file manifest and an Ed25519 signature. The packager rejects
a missing private key or one that does not match the application's trusted public key. Extract the
whole archive for portable use; the app retains music and settings in separate
per-user storage. Installers wrap the same prepared image. Windows defaults to
`%LOCALAPPDATA%\SpotygramApp`; keep AppId `58e4eaa7-5519-5da3-8f61-6e1dc41508c8`
unchanged. The same UUID identifies the old MSI upgrade family for migration.
The EXE removes matching current-user MSI products before writing its files,
retains their installation directory and leaves the separate profile and saved
credential key. A rollback-folder security failure allows one logged legacy
removal retry with `DISABLEROLLBACK=1`. Future removal uses Inno Setup directly.
Ubuntu's package owns `/opt/spotygram` and a desktop menu entry. DMGs
contain the signed app and an Applications shortcut. Only installer images receive
the `installation` resource marker; it selects package updates instead of archive replacement.
Installer signatures bind `Spotygram installer\nFILENAME\nSHA256\n` to the existing
Ed25519 key. This publisher signature is separate from Windows Authenticode and
Apple notarization, which are not configured.

The **Desktop release assets** GitHub Actions workflow builds all four desktop
targets on their native runners. Set repository secrets `TELEGRAM_API_ID`,
`TELEGRAM_API_HASH` and `DESKTOP_RELEASE_KEY` (the private PEM), run the workflow,
and download its four artifacts. It produces assets without publishing a release.

Linux native-library aliases remain symbolic links in the application image and
TAR.GZ archive, avoiding duplicate copies after Gradle resource copying.
JDK 21's DEB bundler dereferences them again when copying its installer image;
the installed DEB therefore retains those duplicate files.
macOS keeps regular copies in ZIP: older portable clients cannot extract ZIP
symlinks, so flattening preserves their update path.
DEB compression defaults to two threads via `DPKG_DEB_THREADS_MAX`; an explicit
environment value overrides that default.

The Linux DEB declares the highest glibc symbol version required by its bundled
ELF binaries, with a minimum of 2.35. The JDK distribution can raise that floor:
the published Temurin-based image uses 2.35; an Ubuntu-built JDK can require 2.38.
Portable archives inherit the same actual requirements. Use the release workflow
for the published platform set rather than assuming all JDK 21 images are equivalent.

## Publishing updates

The repository and release assets are public. Keep API configuration, sessions and
signing keys outside Git; do not embed a GitHub access token. Increase the common
numeric version in root `build.gradle.kts` and Android's monotonic `versionCode`.
Create a draft tagged `vMAJOR.MINOR.PATCH`, then upload:

- `spotygram-MAJOR.MINOR.PATCH-arm64.apk` and `spotygram-MAJOR.MINOR.PATCH-x86_64.apk`;
- `spotygram-MAJOR.MINOR.PATCH-windows-x64.zip`;
- `spotygram-MAJOR.MINOR.PATCH-linux-x64.tar.gz`;
- `spotygram-MAJOR.MINOR.PATCH-macos-x64.zip` and `spotygram-MAJOR.MINOR.PATCH-macos-arm64.zip`;
- `spotygram-MAJOR.MINOR.PATCH-windows-x64.exe` and its `.exe.sig`;
- `spotygram-MAJOR.MINOR.PATCH-linux-x64.deb` and its `.deb.sig`;
- `spotygram-MAJOR.MINOR.PATCH-macos-x64.dmg` and `spotygram-MAJOR.MINOR.PATCH-macos-arm64.dmg`, each with `.dmg.sig`;
- `SHA256SUMS` covering all packages, portable archives and detached signatures.

Verify every uploaded asset's size and GitHub SHA-256 digest against the local
file before publishing the complete ordinary release as Latest. Keep both signing
keys and the Android application ID. The shared release checker uses the public
`/releases/latest`, numeric versions and the exact asset name for the host.
Prereleases stay outside this update channel.

The in-app installer requires GitHub's asset `digest` (SHA-256) and exact size,
and checks the APK's package, version and current signing certificate set before
handoff to Android. Check uploaded asset digests against the local APKs before
publishing. Builds with another signing key, including debug, intentionally
cannot install the official release as an update. The app grants the installer
read access only to its private cached APK; no storage-wide permission is needed.

Installed desktop copies verify the package's digest and detached release signature
before opening the EXE, Finder or the Linux software installer. The app closes;
the user completes OS installation and launches it normally. Program files and
uninstall registration remain under the native installer's ownership. Linux requires
a DEB handler or manual `sudo apt install ./FILE.deb`; package removal keeps the profile.

Windows MSI clients through 0.15.4 expect an MSI asset, so the transition requires
one manual EXE download. The new installed client accepts the legacy `msi` marker
as well as `exe`, but requests the signed EXE for future updates. Portable clients
continue to use ZIP. Do not publish a new MSI alongside the EXE: that would keep
users on the failing installer path.

Portable desktop verifies the digest, then the manifest's release signature, target and
every extracted file. Installation requires a writable parent application folder.
After confirmation, a helper runs from cache, waits for the old process to close,
replaces the application and waits for the new one to acknowledge catalog startup.
It restores the previous application if startup fails. User data stays outside
the application tree. Incomplete transfers resume after launch; cancellation
removes the transfer and staging files.

## Manual development

Debug builds use the application ID `app.spotygram.dev`; release builds use `app.spotygram`, so they cannot accidentally share account state. Debug builds can opt into Telegram's official sandbox with `-PtelegramTestDc=true`; release builds always use production. Do not switch an existing debug installation between environments without clearing that debug installation's data.

Reserved sandbox numbers still reach code entry, but Telegram has
[disabled their simplified login codes](https://github.com/tdlib/td/issues/3083).
They can verify requests and screen transitions, not completed sign-in. Full
authorization needs an account you control; do not record its codes or session in
public inspection artifacts.

Use Android's file picker, ordinary UI interaction, `adb logcat`, `adb shell dumpsys media_session`, `adb shell dumpsys meminfo`, and `adb shell dumpsys gfxinfo` for verification. There is no test suite or generated test scaffold.

On desktop, run the actual packaged launcher, check its native libraries, import,
playback, system media controls and update behavior, and inspect real screenshots
in both themes and at narrow/wide sizes. Record CPU and process memory separately
for idle, visible playback and hidden playback. Desktop `--image-only --debug`
permits isolated Telegram test-DC use through `-Dspotygram.telegram.test=true`;
debug images cannot be signed for the ordinary update channel. Keep checks and
private account data outside source, and report unverified cases in the
[verification record](verification.md).

For repeatable downloads, the wrapper's distribution checksum is pinned. Dependencies use explicit versions. No machine-specific paths, credentials, or media library are needed in Git.
