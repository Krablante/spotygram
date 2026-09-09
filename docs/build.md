# Building Spotygram

Use Linux, JDK 21 (a full JDK, not only a JRE), Android SDK platform 36, build tools 36, NDK `28.2.13676358`, CMake, Ninja, gperf, Perl, a C++ compiler, Git, and Make. The Gradle wrapper pins Gradle 8.14.3.

Keep generated data outside the checkout:

Run heavy operations sequentially: finish native compilation before Gradle, and stop the emulator during builds. Native builds default to two workers (`SPOTYGRAM_JOBS`); Gradle defaults to two workers and a 2 GB heap. On shared hosts use an additional CPU/memory limit for the build process.

Use debug builds for routine UI iteration; run the slower minified release build after changes settle. Keep compiler project caches under state as the build script does.

```sh
export ANDROID_HOME=/path/to/android-sdk
export SPOTYGRAM_STATE=/path/to/private/spotygram-state
```

Create `$SPOTYGRAM_STATE/telegram.env` with your own app's `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`, obtained through my.telegram.org. Restrict it to your user. These values identify the app, not an already authorized user account. They are embedded in the APK and must not be considered unrecoverable secrets once an APK is distributed. Never put a user session or bot token here.

```sh
bash tools/build.sh debug
```

The native build downloads official TDLib at `d1085f9cebc5a62379991ae1652673954f229c1f` and OpenSSL `openssl-3.5.6`. It generates JNI libraries for ARM64 and x86-64 with NDK r28c (16 KB page support), keeping all artifacts in state. Subsequent builds reuse them. No native binaries are committed.

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

## Publishing updates

The repository and release APKs are public. Keep API configuration, sessions and the signing key outside Git; do not embed a GitHub access token. For each update, increase Android `versionCode` and numeric `versionName` together. Publish an ordinary (not prerelease) GitHub release tagged `vMAJOR.MINOR.PATCH`, mark it latest, and attach `spotygram-MAJOR.MINOR.PATCH-arm64.apk`, `spotygram-MAJOR.MINOR.PATCH-x86_64.apk` and `SHA256SUMS`. Prepare/upload assets in a draft before publication, so the update endpoint never advertises a half-uploaded release. Preserve the existing Android signing key and application ID. The app checks `/releases/latest` without authentication and offers only a numerically newer version with a compatible APK. GitHub prereleases are deliberately excluded.

## Manual development

Debug builds use the application ID `app.spotygram.dev`; release builds use `app.spotygram`, so they cannot accidentally share account state. Debug builds can opt into Telegram's official sandbox with `-PtelegramTestDc=true`; release builds always use production. Do not switch an existing debug installation between environments without clearing that debug installation's data.

Use Android's file picker, ordinary UI interaction, `adb logcat`, `adb shell dumpsys media_session`, `adb shell dumpsys meminfo`, and `adb shell dumpsys gfxinfo` for verification. There is no test suite or generated test scaffold.

For repeatable downloads, the wrapper's distribution checksum is pinned. Dependencies use explicit versions. No machine-specific paths, credentials, or media library are needed in Git.
