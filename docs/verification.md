# Verification record

[Documentation](README.md) · **English** · [Русский](verification.ru.md)

This page records observed behavior and its limits. A successful build or source
review does not establish account access, audio quality or physical-device behavior.
Checks use real packaged applications, UI interactions and runtime logs. Temporary
inspection code, recordings, screenshots and account state stay outside the checkout.

## 0.15.6 — phone codes and desktop playback

A fresh production Windows client requested a code for a controlled account.
TDLib reported Telegram-message delivery and the correct destination; a new code
message appeared in that account's existing Spotygram session three seconds after
the Windows request. The new Windows session did not complete code/password sign-in.

Native Windows and Linux sandbox checks covered international-number normalization,
rejection of a stale phone-screen submission, server countdowns, early resend
rejection, incorrect codes, changing the destination, and code → QR → phone.
The Android debug app exercised code entry, a wrong-code error, number editing and
QR → phone on Android 16. Actual Windows light/dark and Android Russian screenshots
were inspected, including 320 dp with font scale 1.3. The resend countdown fit one
line. An emulator System UI ANR preceded app checks; the application crash log was empty.

The native Windows software adapter selected ANGLE automatically. Hyper-V exposed
Microsoft Basic Render Driver twice, including one entry without its software flag;
checking WARP's vendor/device identity handled that case. Continuous redraws of the
phone screen measured about 51 FPS automatically and 54 FPS with explicit ANGLE;
the published 0.15.5 ANGLE baseline measured 54 FPS. Earlier software Direct3D runs
measured about 16–17 FPS. These are software-runner measurements; hardware Direct3D
and physical-machine scrolling were not measured.

The published 0.15.5 player lost playback intent after a controlled native pause.
The updated player retained it. Native Windows then completed 38 automatic random
transitions, six shuffle/repeat-all transitions and four repeat-one transitions.
The signed Linux package completed 12 random transitions through Telegram recordings
after seeking near their ends. Desktop checks used libmpv's null audio output;
physical audio drivers and the owner's exact end-of-song stall were not reproduced.

Android debug/release assembly and both Lint checks passed. Both release APKs kept
the existing signer and application ID, used versionCode 33 and passed 16 KB alignment.
All four desktop targets built on native runners. Four installer signatures, four
archive manifests and every archived payload hash verified with the existing key.
Packaged desktop versions were 0.15.6 with the release flag; inspection classes were
absent from their launcher classpaths. Physical-device and account limits below remain.

## 0.15.5 — Windows installation and removal

Windows now uses a per-user Inno Setup EXE with its own uninstaller. The prepared
application image, profile location and trusted desktop signing key stay the same.

An ordinary account on Windows Server 2025 installed the published 0.15.4 MSI on
an owned virtual X drive. Restricting its `Config.Msi` folder caused error 1314 and
MSI exit code 1603. The new EXE found that registered MSI, encountered the failure,
retried removal with `DISABLEROLLBACK=1` and completed with exit code 0. It retained
the program directory. EXE reinstallation and removal passed; installed JARs were
gone and a profile sentinel remained. The isolated server temporarily allowed
unmanaged MSI packages; that policy was restored after inspection. The product
installer changes neither system policies nor drive permissions.

The published 0.15.2 MSI also migrated to EXE and uninstalled under the ordinary
account. A separate failed MSI removal left program files after losing its
registration. Installing the EXE in that old directory removed obsolete libraries
from the old launcher classpath. A file outside that classpath survived both
installation and removal, while the new application libraries were removed.

Interactive installation, installed launch and interactive removal passed on the
native Windows desktop. The same credential-store key and TDLib database reopened
after removal. English/Russian wizard screens, the installed app and uninstall
confirmation/completion were inspected as actual screenshots. Windows's installed
update marker selected EXE; its portable image selected the archive channel.

Android debug/release builds and both Lint checks passed. Both APKs kept the
original application ID and signer, used versionCode 32 and passed 16 KB alignment.
All four desktop targets built on native runners. Four installer signatures,
four signed archive manifests and every archive entry passed verification with
the existing public key. Linux and both macOS images launched and initialized
Telegram with retained keys; macOS ad hoc signatures verified.

The owner's exact file-security error 1926 (`Error: 5`) was not reproduced on the
physical X drive. The observed failure was the related rollback-folder error 1314;
the guarded retry also recognizes 1926. Production-account playback and physical
device behavior were not checked. Old Windows MSI clients need one manual EXE
download because their updater expects an MSI asset.

## 0.15.4 — return to phone entry during sign-in

The packaged Linux debug launcher and an isolated Android 16 debug APK used
Telegram's test DCs with reserved phone numbers. Both reached code entry, returned
to phone entry and requested a code for another number. Linux exercised the arrow,
Esc and Change phone number, including an invalid-number error that left the field
editable. Android exercised system Back, Change phone number and the arrow. Actual
screens were inspected in light appearance on Linux and dark appearance on Android,
including English at 400 dp and Russian at 320 dp. The Russian action fit the narrow
screen and its arrow returned to phone entry.

Android debug/release assembly and both Lint checks passed. Both release APKs kept
the original application ID and signing certificate, increased versionCode to 31
and passed 16 KB ZIP alignment. All four desktop targets built on native runners.
Four installer signatures, four signed archive manifests and every archive payload
entry were verified with the existing public key.

The fresh emulator had System UI, phone-service and Google-service ANRs during cold
boot, before app checks; installation required a retry. The successful application
flow had no AndroidRuntime crash. Linux sandbox code submission returned
PHONE_CODE_INVALID, so this cycle establishes navigation and new code requests,
not completed account authorization. Password/email transitions were reviewed
against the pinned TDLib contract, but not exercised with an account. Windows/macOS
UI, installer lifecycle, production-account playback and physical-device behavior
were not rechecked in this cycle.

## 0.15.3 — Telegram initialization on Windows

Packaged Windows, Linux and both macOS targets initialized a fresh TDLib database
and reached phone entry, then reopened it with the same credential-store key.
Windows and macOS completed 200 key reads with forced garbage collection per run,
including fresh and restarted processes. The older Windows heap failure did not
occur with java-keyring 1.0.4. Windows also initialized a profile containing spaces
and a separate Unicode path. Removing only a temporary desktop key made startup
reject that database; its files remained byte-for-byte unchanged. Actual Windows
and macOS phone-entry screens were inspected.

Windows installed the **published 0.15.2 MSI**, upgraded to 0.15.3 and launched the
installed app through phone entry. The same key and database remained after MSI
removal. A separate standard account completed installation and removal of 0.15.2,
reinstallation, upgrade and removal of 0.15.3 without elevation; a profile file
survived. This isolated Server 2022 runner temporarily allowed unmanaged MSI
packages, which its default policy blocks for standard accounts.
`AlwaysInstallElevated` remained off. The original 0.15.2 product identifier and
its elevated removal command were checked against the public package.

The owner's `X:\Config.Msi` file-security failure was not reproduced. The MSI
lifecycle passed with both administrator and standard accounts; that release's user guide
provided elevated removal and log collection for diagnosing that machine's
rollback-folder permissions. Per-user installation and the upgrade identity remain
the same.

Android debug/release builds and both Lint checks passed. Both APKs retained the
original application ID and signer and passed 16 KB ZIP alignment. An isolated
Android 16 debug installation reached phone entry on a fresh profile and after a
process restart; its wrapped key was unchanged. The real screens were inspected.
Cold-boot Pixel Launcher/System UI ANRs and Bluetooth crashes occurred before these
checks; the app log contained no application crash during the successful scenario.

Four installer signatures, four archive manifests and every archive payload file
were verified with the existing desktop key. Both macOS bundles passed strict deep
ad hoc signature checks. This cycle checks initialization, key retention and MSI
lifecycle; it does not establish authenticated account playback or reproduce the
owner's Windows drive permissions. Physical-device, audio and battery limits below
still apply.

## 0.15.2 — native desktop installation

The same prepared application image now produces a Windows MSI, Ubuntu DEB or
macOS DMG as well as its signed portable archive. Android debug/release builds
and both full Lint checks passed. Both release APKs retained the original
application ID and signing certificate and passed 16 KB ZIP alignment checks.

Windows x64 completed silent MSI installation, upgrade from a private MSI wrapping
the actual 0.15.1 payload, installed launch and native MSI removal. Three recordings
imported with the older installed launcher survived upgrade and removal with
integrity `ok`. The Start menu shortcut appeared and was removed on uninstall;
Windows Installer registered one 0.15.2 product at the separate per-user program
location. The real welcome wizard and installed application were inspected as PNGs;
the complete interactive wizard was not driven on the hosted runner.

Both macOS architectures mounted their actual DMGs, showed the Applications
shortcut and copied the app into a user Applications folder. Installed launchers
opened the retained catalog and position. Strict deep ad hoc signature checks
passed before and after installation; removing the app preserved its profile.
Actual Finder/install and application PNGs were inspected.

Ubuntu 24.04 completed a fresh DEB installation, reinstallation and removal with
a valid application-menu entry and the RGB Spotygram icon. Installation also
worked when the standard XDG menu directory was initially absent. The installed
app restored two recordings, a favorite, playlist membership and the paused
position. A stale portable transfer in that profile was discarded after switching
to the installed copy. A separately signed local fixture exercised the real
Close and open installer flow: Spotygram verified it, closed and opened GDebi.
All catalog/progress rows matched afterwards, integrity was `ok`, and temporary
preferences/transfers were restored. The fixture's version label was private
verification input, not a published future release.

Installed runtime checks verified the selected package asset name, detached
signature and tamper rejection with the original desktop update key. Portable
installation/rollback checks also ran against the fresh native builds. This
release focuses on packaging; authenticated Telegram, physical-device audio,
battery and the other limits below were not rechecked. Windows Authenticode and
Apple notarization remain unconfigured; Linux package-handler behavior depends
on the user's desktop, with `apt install` as the documented alternative.
One additional credential-store probe on a reused Windows image exited with native
heap status `0xC0000374`. The accepted MSI lifecycle check used installed applications;
this run does not establish reliable authenticated Telegram on Windows.

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

After publication, Linux 0.15.0 fetched the actual GitHub Latest release through
Check for updates → Download and install → Restart and install. The running app
became 0.15.1; both recordings, the favorite and playlist membership matched the
original database. The paused position remained 1,172,086 ms, integrity was `ok`
and the app removed download, staging and helper files. All six uploaded binaries
and `SHA256SUMS` matched GitHub's server digests.

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
