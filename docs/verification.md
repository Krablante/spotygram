# Verification record

[Documentation](README.md) · **English** · [Русский](verification.ru.md)

This page records observed behavior and its limits. A successful build or source
review does not establish account access, audio quality or physical-device behavior.
Checks use real packaged applications, UI interactions and runtime logs. Temporary
inspection code, recordings, screenshots and account state stay outside the checkout.

## 0.15.1 — storage, playback and update audit

Android debug and both release APKs, debug/release Lint and the Linux application
image built successfully. Windows x64 and macOS Intel/Apple Silicon built and ran
on native GitHub runners. The Android application ID, APK signing certificate,
desktop update public key and SQLite schema 5 were preserved. Both APKs passed
signature and 16 KB ZIP alignment checks; both macOS bundles passed strict deep
ad hoc signature verification.

The signed x86-64 APK installed over 0.15.0 on Android 16. All original tracks,
sources, playlists, memberships and download/cache rows matched after the checks;
SQLite integrity was `ok`. Opus playback, seeking, pause/resume, 0.3× and 4× worked.
A recording paused at 112,602 ms resumed at 112,610 ms after switching tracks.
Actual screens were inspected in light/dark appearance, English at 400 dp and
Russian at 320 dp with font scale 1.3. Temporary settings/bookmarks were restored.
App crash logs were empty during these checks; emulator system cold-boot failures
occurred before them.

Linux exercised the packaged application with local recordings and the hardware
Mesa renderer. A retained long-recording position resumed, seeking advanced and
repeated MPRIS Play calls kept playback running. Native Windows/macOS checks imported
three recordings, observed playback and persisted long-recording positions; actual
library/player screenshots were inspected. OS credential stores persisted, reread
and removed a temporary key. Linux and all three native desktop targets verified
signed archives and rejected changed application bytes.

Windows and both macOS architectures passed installation with startup acknowledgement
and three rollback cases: blocked acknowledgement, an unlaunchable signed image and
a corrupt catalog. The Windows checks exposed a child JVM retaining application
JARs after its launcher exited; stopping the owned process family fixed rollback.
Catalog failure did not acknowledge a successful update or erase the original data.

Source review removed playlist-by-playlist queries, repeated alias file checks and
per-file download-list copying. It also covered cancellation during local import,
transactional deletion, catalog readiness and stale Telegram file IDs after logout.
These are source and runtime correctness findings, not large-catalog benchmarks.
The existing 536-record Telegram catalog opened, but authenticated streaming could
not be repeated because its private Linux QA keyring was locked. The session and
original key were retained; the authenticated results below belong to **0.15.0**.

## 0.15.0 — one application on four platforms

Shared screens, resources, Haze Glass, catalog, Telegram, cache policy and queue
compiled into Android and desktop. Android retained Media3; desktop used libmpv
and pinned native prebuilts, with a bundled Java 21 runtime. SQLite stayed at schema 5.

Android debug/minified release assembly and both full Lint checks passed. The
signed x86-64 APK installed over 0.14.0 without resetting data; original track,
playlist, membership and source rows matched, with SQLite integrity `ok`.
MP3/Opus, speed limits, media controls, settings and saved files were exercised.
Real screens were inspected in both appearances, English at 400 dp and Russian
at 320 dp with font scale 1.3. App crash logs were empty after the settings-format
correction. Recurring emulator cold-boot System UI ANRs preceded app checks.

Windows x64 and both macOS architectures ran packaged launchers on native GitHub
runners. Three WAV recordings were imported; playback advanced, the player opened
and a long recording persisted its position. PNGs were inspected. Bundled-runtime
checks covered OS key-store create/read/remove, signed archive verification,
tamper rejection, installation and rollback when startup acknowledgement was
blocked. Both macOS bundles passed strict deep ad hoc signature verification.

Linux completed the normal Install update → Restart and install flow from a
temporary 0.14.0 image to signed 0.15.0. Two local recordings, a favorite, playlist
membership and a paused long-recording position survived with integrity `ok`.
Incomplete published transfers resumed; the installed app cleaned transfer,
staging and helper files. Closing during an uncached read kept playback alive;
Raise restored the window and Quit closed it.

A separate Spotygram production Telegram session on Linux completed phone and
two-step sign-in and indexed **536 recordings**. Phone → QR → phone also worked.
Playback began before a book finished downloading; 40-minute seeking, 0.3×/4×,
positions, completion, repeat-one/all and temporary-copy retention/removal were
observed. A saved book played offline after a fresh launch. Blocking Telegram
traffic during an incomplete 1.6 GB recording preserved a pending seek and bookmark;
read timeout stopped playback without advancing, and restored network allowed resume.

| Configuration | Observed process memory / CPU |
| --- | --- |
| Windows/macOS native runners, local recordings | About 240–320 MiB RSS; paused 0–1.5% of one core |
| Windows runner with visible software-rendered player | About 29% of one core while playing |
| Linux, hardware Mesa through VirtualGL, real Telegram catalog | About 597 MiB RSS; about 19 MiB Java heap |
| Same Linux process, hidden window | About 0.2% of one core paused; about 2% playing |

RSS includes Telegram, graphics and audio allocations outside Java heap. These
observations describe those configurations; they are not cross-hardware benchmarks.

## Limits

No physical ARM64 Android device, OEM background policy, speaker quality, battery
use or physical Windows/macOS hardware was checked. Authenticated Telegram was
checked on Linux; Windows/macOS used local recordings. Actual Wi-Fi interface
changes, every audio container and catalogs with hundreds of thousands of distinct
recordings were not exercised. Older Android's simpler glass fallback was reviewed
in source, not run on an older emulator.

## Earlier evidence

The [complete record through 0.15.0](https://github.com/Krablante/spotygram/blob/v0.15.0/docs/verification.md)
preserves detailed earlier checks, APK hashes, migrations and release-specific
limits. It is an English historical record. The current guides describe current
behavior; [GitHub Releases](https://github.com/Krablante/spotygram/releases) owns
downloads and release notes. Old notes remain available in tagged source history.
