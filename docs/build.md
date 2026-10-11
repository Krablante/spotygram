# Building Spotygram

[Documentation](README.md) · **English** · [Русский](build.ru.md)

For listening, use the APK or desktop archive from [Releases](https://github.com/Krablante/spotygram/releases/latest).
This guide is for building your own copy or maintaining the published app.
The first Android build compiles TDLib and OpenSSL; later builds reuse them.
Desktop builds download pinned TDLib/libmpv binaries with verified checksums.

## Prepare an Android build machine

Use Linux, JDK 21 (a full JDK, not only a JRE), Android SDK platform 37, build tools 36.0.0, NDK `28.2.13676358`, CMake, Ninja, gperf, Perl, a C++ compiler, Git, and Make. The Gradle wrapper pins Gradle 9.3.1 with Android Gradle plugin 9.1.1 and Kotlin 2.4.20. The app still targets Android 36 and supports Android 8+.

Keep generated data outside the checkout. `SPOTYGRAM_STATE` holds native sources,
build outputs, caches and private configuration; it is not a second source repository.

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

For a new fork's desktop update channel, create an Ed25519 signing key once:

```sh
python3 tools/package-desktop.py --init-key
```

This writes the private key to `$SPOTYGRAM_STATE/desktop/signing/release-private.pem`
and prints its public key. Put only that public key in
`desktop/src/main/resources/update-key.pub`. **Maintainers of official Spotygram
must keep the existing key and public key.** Back up both the desktop private key
and the Android keystore privately. Then build and sign the release archive:

```sh
python3 tools/build-desktop.py
```

Archives are written to `$SPOTYGRAM_STATE/releases/VERSION/`. Each contains the
complete application, a file manifest and an Ed25519 signature. The packager rejects
a private key that does not match the application's trusted public key. Extract the
whole archive when installing it; the app retains music and settings in separate
per-user storage.

The **Desktop release assets** GitHub Actions workflow builds all four desktop
targets on their native runners. Set repository secrets `TELEGRAM_API_ID`,
`TELEGRAM_API_HASH` and `DESKTOP_RELEASE_KEY` (the private PEM), run the workflow,
and download its four artifacts. It produces assets without publishing a release.

## Publishing updates

The repository and release assets are public. Keep API configuration, sessions and
signing keys outside Git; do not embed a GitHub access token. Increase the common
numeric version in root `build.gradle.kts` and Android's monotonic `versionCode`.
Create a draft tagged `vMAJOR.MINOR.PATCH`, then upload:

- `spotygram-MAJOR.MINOR.PATCH-arm64.apk` and `spotygram-MAJOR.MINOR.PATCH-x86_64.apk`;
- `spotygram-MAJOR.MINOR.PATCH-windows-x64.zip`;
- `spotygram-MAJOR.MINOR.PATCH-linux-x64.tar.gz`;
- `spotygram-MAJOR.MINOR.PATCH-macos-x64.zip` and `spotygram-MAJOR.MINOR.PATCH-macos-arm64.zip`;
- `SHA256SUMS` covering all six application assets.

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

Desktop verifies the digest, then the manifest's release signature, target and
every extracted file. Installation requires a writable parent application folder.
After confirmation, a helper runs from cache, waits for the old process to close,
replaces the application and waits for the new one to acknowledge catalog startup.
It restores the previous application if startup fails. User data stays outside
the application tree. Incomplete transfers resume after launch; cancellation
removes the transfer and staging files.

## Manual development

Debug builds use the application ID `app.spotygram.dev`; release builds use `app.spotygram`, so they cannot accidentally share account state. Debug builds can opt into Telegram's official sandbox with `-PtelegramTestDc=true`; release builds always use production. Do not switch an existing debug installation between environments without clearing that debug installation's data.

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
