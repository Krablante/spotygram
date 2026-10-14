# Verification record

[Documentation](README.md) · **English** · [Русский](verification.ru.md)

This page records observed behavior and its limits. A successful build or source
review does not establish account access, audio quality or physical-device behavior.
Checks use real packaged applications, UI interactions and runtime logs. Temporary
inspection code, recordings, screenshots and account state stay outside the checkout.

## 0.21.1 — centered compact profile button

The compiled shared interface showed To profile/Remove centered beneath the
artwork/title row, with matching button widths and centered icon/text groups.
Screenshots covered 380 dp, 320 dp with font scale 1.3, English/Russian and both
appearances. Short labels stayed on one line. A controlled request failure kept the
button available with a readable explanation beneath it; retry succeeded.

The production Linux launcher reopened its retained account and catalog. Real
addition, removal and Undo succeeded through the new button; the separate owned
profile returned to its original empty state. The wide menu's two button states
kept the same placement and width. Quit closed the packaged application.

Android debug/release assemblies and both Lint checks passed. The signed APKs use
0.21.1, versionCode 41, the original application ID/certificate and 16 KB alignment.
The retained Android 15 emulator updated from 0.21.0; its installed APK matched the
release file byte-for-byte. All eleven catalog tables, preferences and queue fields
were preserved, including the 8,910 ms position. Restart renewed only the normal
queue-instance UUID, with matching queue/position versions. SQLite integrity was
`ok`. System UI again stalled in the software emulator, so authenticated Android
profile actions were not verified.

Physical devices, physical sound, screen-reader speech and interactive Windows/macOS
operation were not exercised. Narrow-layout and error checks used controlled TDLib
responses in the compiled desktop interface.

## 0.21.0 — direct profile-music actions

Native TDLib through the retained, separate Spotygram production session uploaded
a six-second local WAV into its own profile. The imported entry retained its stable
remote reference and original local file. A fresh application instance recognized
that recording as profile music. Removing and adding the existing Telegram audio
also succeeded; final removal and another restart confirmed that the temporary
recording was absent and the original profile list was restored. These checks used
an isolated local catalog and excluded library-sync transport.

The compiled shared interface used controlled TDLib responses and real local audio.
One click on the header button added or removed the recording. A failed addition
kept Add available with an inline explanation. Screenshots covered the list and
full-player menus, both appearances, the anchored wide menu and 320 dp with font
scale 1.3. The complete action text remained readable. The local-upload explanation
was visible before Add and disappeared after confirmation. Underlying list icons
showing through the nested glass were corrected and rechecked.

An imported song retained its favorite, one-song playlist and local file after
profile removal. The local queue's three IDs, cursor and 60,868 ms paused position
matched the baseline exactly; SQLite integrity was `ok`. The reopened shared player
retained that paused queue with native libmpv and null audio output.

Final Android debug/release assemblies and both Lint checks passed. Both signed
release APKs use 0.21.0, versionCode 40 and the original application ID/certificate;
16 KB alignment passed. The final production Linux application image also built.
A signed x86-64 APK updated the retained Android 15 emulator from 0.20.0.
All eleven catalog tables and all preferences, including the local queue, matched
the pre-update copy; schema 8 and SQLite integrity were retained.
The installed APK matched the final release file byte-for-byte. Its retained Music
screen rendered, but software graphics again produced ANRs in both System UI and
the app. The app's render thread waited on the emulator's OpenGL program linking.
Changing the software backend did not yield a reliable interactive session, so
authenticated Android profile actions and foreground-upload survival were not verified.

The final production Linux launcher opened its retained Spotygram account and
catalog. Its real track-menu button added an existing Telegram song in one click,
then showed Remove on reopening. Removal and a second addition followed by Undo
also succeeded through the UI; My profile returned to its original empty state.
No intermediate menu or confirmation appeared. The application then closed through
its ordinary Quit action.

Physical-device behavior, physical sound and interactive Windows/macOS operation
were not exercised. Production profile mutations were checked on Linux;
the additional appearance, narrow-layout and failure scenarios used controlled responses.

[All four desktop targets](https://github.com/Krablante/spotygram/actions/runs/38509295498)
built from `0cc2927` on native runners. The four installer signatures and four
archive manifests verified with the existing key, as did every archive payload
hash. Each compiled app reported 0.21.0 with DEBUG false, included `TrackActions`
and embedded the original trusted update key. The signed Linux release archive
reopened the retained catalog, displayed the working profile action and exited
through the ordinary Quit action. All fifteen draft asset names, sizes and GitHub
SHA-256 digests matched the local files before publication; the published ordinary
[v0.21.0 release](https://github.com/Krablante/spotygram/releases/tag/v0.21.0) was
confirmed as Latest with the same assets. The signed ARM64 APK was delivered to the
owner through Telegram artifacts.

## 0.20.0 — grouped glass sources and chat photos

The production Linux image's compiled shared screens used an isolated catalog,
controlled TDLib responses and real JPEG files. Screenshots covered the continuous
profile/chat panels, scrolling beyond the viewport, photo placeholders, selected
source, search by name and @username, and the anchored desktop picker. Narrow
screens used the bottom sheet. English/Russian, light/dark and a 320-pixel viewport
with font scale 1.3 remained readable. The full-name dialog showed an otherwise
truncated source title; Esc dismissed it while retaining the source. Switching
language immediately updated counts in lazy rows. README images use this working
shared interface with demonstration data.

Photo replacement and removal events changed the visible avatar. Downloads used
priority 1 and at most two shared chat/profile slots; restarting reused cached
files. A native TDLib request through an owned Spotygram production session fetched
and decoded a public chat's 160×160 JPEG. Its source remained unselected, and the
536-track catalog was unchanged.

The final packaged Linux launcher also reopened that owned catalog and displayed
its connected Sources with real chat/profile photos and the Saved Messages bookmark.
Android debug/release assemblies and both Lint checks passed. The two release APKs
retained the original signer and application ID, used versionCode 39 and passed
16 KB alignment. The production Linux image, signed archive and DEB built successfully.

A signed x86-64 APK updated 0.19.0 on the retained Android 15 emulator. Its imported
song, favorite, playlist, memberships, sources and profile rows matched the baseline
exactly; schema 8 and SQLite integrity were retained.
The installed APK matched the final release file byte-for-byte; Media3 retained
the local queue's song and its 8,910 ms position after restart. The bottom sheet and
its current-selection mark were inspected. With the keyboard open, search, the
empty-result message and management footer remained within the available area.
The emulator's software graphics repeatedly stalled in native shader compilation,
also affecting System UI and keyboard startup. Mesa allowed the initial app and
picker screens to render, but keyboard interaction still produced ANRs. This does
not establish responsiveness or performance on Android; physical-device validation
remains outstanding.

Native Linux libmpv played a local WAV recording with null audio output. Removing
and reconnecting its chat source retained the favorite, local file and queue;
SQLite integrity remained `ok`. Shared panel clipping during scrolling and text
bleed through picker cards were corrected and rechecked in the actual interface.

Physical-device behavior, physical sound and interactive Windows/macOS operation
were not exercised. Long source lists and photo-change/removal scenarios used
controlled responses; production checks covered native photo requests, their cache
and the retained catalog's Sources screen.

[All four desktop targets](https://github.com/Krablante/spotygram/actions/runs/38486421547)
built from `e37bece` on native runners. Four installer signatures, four archive
manifests and every payload hash verified with the existing desktop key. Each
compiled application reported 0.20.0 with DEBUG false and contained the chat-photo
and grouped-source components. The verified Linux release archive reopened the
retained Sources screen with real photos and closed through the ordinary Quit
action. All fifteen GitHub draft asset names, sizes and SHA-256 digests matched
the local files; SHA256SUMS covers both APKs and every desktop package, archive
and detached signature. The complete ordinary release is published as Latest.

## 0.19.0 — profile music and unified Sources

An owned Spotygram production Telegram session exercised native TDLib `getMe`,
`getUser`, `getUserFullInfo` and `getUserProfileAudios`. Its own profile and an
accessible public user returned empty music lists. The actual UI handled these
states; public channels were excluded from profile results, and an unused username
became an ordinary empty search result. The retained schema 6 catalog upgraded to
8 with all 536 tracks and unchanged playlist counts; SQLite integrity was `ok`.

The compiled shared interface and native Linux libmpv player also used an isolated
catalog with controlled TDLib responses and local recordings. My profile stayed
at the top of Sources. Its five recordings played and advanced automatically;
restart retained the paused local queue. Liking one song left the profile
unselected and added only that song to Music. Retained listening copies remained
in On device. A second profile loaded 230 songs in three pages, preserving profile
order. Opening it did not add its songs to Music. Explicit connection added the
collection, and removal returned Music to the individual favorite without deleting
it or the retained local queue. A shared recording displayed its existing heart
in both profiles. Real screenshots covered the profile list, source picker,
connected Sources and removal confirmation in the shared glass interface.
At 320 pixels with font scale 1.3, profile controls and the song menu remained
usable. Adding one preview song to a new playlist kept both profiles unselected;
Music showed exactly the favorite and that playlist song. SQLite integrity was `ok`.

The compiled format 3 reader restored profile identity, username, a favorite,
a one-song playlist and a selected profile on a fresh peer. Saving individual
songs left their profile unselected. Native file IDs were reset to zero for
resolution through TDLib on that device; legacy cloud playback fields were ignored.
These were controlled metadata checks, without a new two-device Telegram exchange.

A signed x86-64 APK updated 0.18.0 on a fresh Android 15 emulator, preserving an
imported MP3, its favorite and paused queue. Media3 reported the retained track and
position. Creating a playlist and restarting preserved its name. The new Sources
connection screen was inspected at 320 dp with font scale 1.3; its text, Connect
button and four navigation tabs remained usable. The normal-width library and
playlist screens were also inspected after upgrade.

Android debug/release assemblies and both Lint checks passed. A final release
rebuild after the welcome-text update also passed Lint. Both release APKs retained
the original signer and application ID, used versionCode 38 and passed 16 KB
alignment. The production Linux image, signed archive and DEB built successfully;
the packaged launcher reopened the 536-track catalog and My profile screen.

[All four desktop targets](https://github.com/Krablante/spotygram/actions/runs/38430850916)
built on their native runners from `da94a32`. Four installer signatures, four
archive manifests and every payload hash verified with the existing desktop key.
All compiled applications reported 0.19.0 with DEBUG false and contained the new
profile and Sources implementation. The release Linux archive reopened the
retained catalog and own-profile screen, then closed normally. The final installed
Android APK matched the release file byte-for-byte; Media3 played the retained
MP3 and the crash log was empty. SHA256SUMS covers both APKs and every desktop
package, archive and detached signature. All fifteen GitHub draft asset names,
sizes and SHA-256 digests matched the local files before publication.

Positive profile playback used controlled responses and local recordings;
production profiles with pinned songs were not available for that check. Physical
audio output, physical-device behavior and interactive Windows/macOS operation
were not exercised in this cycle.

## 0.18.0 — reverse order and local sound controls

The actual Linux application used native libmpv with null audio output and four
local WAV recordings. Reverse Play all started at the last available entry;
manual and automatic transitions followed the reversed queue. Changing mode kept
the paused song and position. Play next, upward movement and removal followed the
displayed order, including intentional repeated entries. Repeat-all crossed the
reverse boundary and repeat-one restarted the same recording.

The full-player and mini-player volume controls changed the same engine state.
Pointer adjustment, the mouse wheel and focused keyboard arrows worked. Mute
survived restart and restored the previous nonzero level. Real screenshots covered
English/Russian, light/dark, wide windows and a 360-pixel desktop window. Percentage
wrapping and cramped mini-player controls were corrected; Esc dismissed sheets and
playback menus before their underlying player.

A signed Android APK updated 0.17.0 on an Android 15 emulator. Its four tracks,
two favorites, one playlist and four memberships survived, with SQLite integrity
`ok`. The local random mode, repeat-one, 1.5× speed and 9,074 ms paused position
were retained. Three legacy sound-setting sync rows were removed while the library
remained intact. Reverse playback from the playlist started at its last entry;
system Next and an actual end transition advanced in the intended order. The new
mode choices and track-action row were inspected in Russian at 320 dp with font
scale 1.3, and the player was inspected in both appearances. Android kept its
existing touch controls without a desktop volume control.

The compiled sync reader ignored legacy speed/order/repeat fields and retained a
favorite in the same document. Independent local profiles kept independent modes;
mode and speed changes created no sound-setting sync rows. Format 2 is unchanged.

Android debug/release assemblies and both Lint checks passed. The final release
APKs kept the original signer and application ID, used versionCode 37 and passed
16 KB alignment. The installed x86-64 APK matched the release file byte-for-byte;
a fresh launch retained reverse order, the paused song, position and 1.5× speed.
The final production Linux image rebuilt and reopened the retained local catalog.
Its archive manifest, all 278 payload entries and installer signature verified
with the existing desktop key; the application reported 0.18.0 with DEBUG false.
The final packaged volume control adjusted to 55%, muted to zero and restored 55%.

[All four desktop targets](https://github.com/Krablante/spotygram/actions/runs/38398113189)
built on their native runners. Four installer signatures, four archive manifests
and every archived payload verified with the existing key. The compiled applications
reported 0.18.0 with DEBUG false and included reverse order and volume controls.
The release Linux archive reopened the retained queue and 55% volume; manual Next
and a native end-of-file transition followed the reversed queue. SHA256SUMS covers
both APKs and every desktop package, archive and detached signature.

These checks used isolated catalogs and local recordings. Production Telegram
exchange, physical audio output, physical devices and interactive Windows/macOS
behavior were not exercised in this cycle.

## 0.17.0 — local playback and bounded sync history

Temporary runtime checks used the compiled application with isolated catalogs and
controlled TDLib responses. A schema 6 catalog upgraded to 7, retaining its queue
and 125,000 ms bookmark while ignoring a foreign legacy playback position. Its
playlists and favorites survived; playback-only descriptors left the portable
snapshot. A fresh client recovered the library without inheriting a queue or
bookmarks. The actual 0.16.0 reader paused on format 2, sent nothing and retained
a pending local setting.

Controlled history checks covered the shared tail threshold, old history without
local edits, deletion failure, cleanup retry without another upload, and restart
with unfinished cleanup. Concurrent edits arriving after a snapshot's boundary
survived compaction; a stale offline rename could not restore a deleted playlist.
The actual one-minute maintenance timer consolidated a short tail and did not
start an idle publication loop. Android's and desktop's JSON implementations
both recognized unchanged object values without publishing them again.
Short history pages were retried; a persistently incomplete history kept cleanup
pending until a later successful pass. A 10,000-song metadata snapshot was about
231 kB compressed. Two offline additions converged to 10,002 songs and identical
portable records, with one retained snapshot. This used the controlled transport
and does not establish Telegram media availability.

The packaged Linux player used native libmpv with null audio output and a synthetic
31-minute WAV. Periodic checkpoints, seeking and pausing saved local progress
without pending playback edits. Received order/repeat preferences updated the
active queue; later local saves did not overwrite them. Explicit mode changes
published only settings. Closing and reopening retained the local track and
paused position. Actual Linux screenshots covered the library, player and settings
in light English and dark Russian, including a narrow window.

A signed APK upgraded the retained Android 16 catalog from schema 6 to 7, keeping
three tracks, two favorites, two playlists and three memberships with SQLite
integrity `ok`. That emulator again reported launcher/system ANRs. A fresh
Android 15 emulator completed local import, playback, seeking and process restart.
Its 652,707 ms bookmark remained local with zero cloud queue/progress records and
integrity `ok`. The application had no AndroidRuntime crash during these checks.
Actual Android library, player and settings screens were inspected in both themes,
including Russian at 320 dp with font scale 1.3.

The final Android debug/release assemblies and both Lint checks passed. Both
release APKs retained the original signer and application ID, used versionCode 36
and passed 16 KB alignment. The production Linux image built and reopened its
retained local queue paused. Its archive manifest, every payload hash and installer
signature verified with the existing desktop key; the packaged version was 0.17.0
with DEBUG false.

[All four desktop targets](https://github.com/Krablante/spotygram/actions/runs/38293684212)
also built on their native runners. Four installer signatures, four archive
manifests and every archived payload hash verified with the existing key. All
packages reported 0.17.0, DEBUG false and sync format 2. The release Linux archive
passed the native local-playback and restart checks. The final installed Android
APK matched the release file byte-for-byte and retained the same catalog counts
with integrity `ok`.

Native TDLib sandbox sessions reached code entry, but Telegram rejected the reserved
account codes with `PHONE_CODE_INVALID`. Authenticated format 2 exchange and server
deletion were not exercised in this cycle. The controlled transport scenarios
above verify application behavior; the production transport evidence for 0.16.0
below does not establish a new server check. Current Windows/macOS account flows,
physical audio output and physical-device behavior were not rechecked.

## 0.16.0 — automatic library sync

Two independent Spotygram TDLib sessions on Linux used a controlled production
Telegram account. Both found the same private storage channel, with archive
placement and notifications disabled. Playlists and favorites changed in both
directions. A fresh second catalog recovered the library, settings and selected
source even after the channel was renamed. That source indexed an actual uploaded
MP3. The channel name was restored after the check.

A playlist with 10,000 synthetic song descriptors transferred through Telegram
in a full snapshot of about 763 kB compressed. After separate offline additions
and a restart of the second client, both playlists contained all 10,002 songs.
The clients' portable records matched exactly; a stale offline rename did not
restore a deleted playlist. A 125,000 ms bookmark and paused queue metadata also
reached the second catalog. SQLite integrity was `ok`. These are metadata and
transport checks; the synthetic entries do not establish media availability.

A document advertising an unknown protocol version paused both clients without
changing their playlists. A new local edit remained pending. Removing that
temporary document allowed the edit to send. Reading the channel after the large
snapshot showed that incorporated older protocol documents had been compacted.

A signed APK upgraded the retained Android 16 catalog from schema 5 to 6, keeping
all three tracks, two favorites, two playlists and three memberships. That emulator
later terminated with host exit code 139 during sign-in; its System UI had also
reported an ANR. A fresh Android 15 emulator with the SWANGLE renderer completed
sign-in and recovered the same library, including the 10,002-song playlist,
bookmark, source, Russian language and light appearance. Removing a favorite,
creating a one-song playlist and selecting a Telegram recording through Android's
UI reached the desktop client. The recording downloaded; application crash logs
were empty during these checks. Physical audio output was not checked.

Actual desktop screenshots covered the sync error, Retry and ready states, English
in dark appearance and Russian in light appearance at wide and narrow sizes.
Android's Russian library, playlists and ready status were inspected, including
controls at 320 dp with font scale 1.3. A bad document digest retained the library;
removing the temporary document and using Retry returned the desktop UI to ready.

The final Android debug/release assemblies, both Lint checks and the Linux image
build passed. The final signed APK reopened the retained Android 15 session and
10,002-song playlist with no pending edits or application crash; SQLite integrity
was `ok`. Separate local runtime checks retained a speed change made while detached
after rebinding the same account, and decoded a descriptor whose long title had
been bounded to the protocol's 8,192-character limit. Imported placeholders now
report unavailable audio rather than a deleted Telegram message.

All four desktop targets built on their native runners. The four installer
signatures, archive manifests and every archived payload hash verified with the
existing desktop key. Both final APKs kept the original signer and application ID,
used versionCode 35 and passed 16 KB alignment. The test collection was removed
from shared storage, leaving a clean muted, archived library channel. The temporary
audio-source channel was deleted and the controlled Android/desktop sessions signed
out. Signing out did not publish deletion of the shared library. Account sync was
exercised on Linux and Android emulators; current Windows/macOS account flows and
physical devices were not exercised in this cycle.

## 0.15.7 — Windows streaming and seeking

The published 0.15.6 reproduced stalled seeks and an absolute-random end transition
on native Windows with actual Telegram recordings. Its reader waited for the last
partial download block while TDLib reported an incomplete, inactive transfer.
The file already had its expected physical size, but native range queries confirmed
that the final bytes were not available to the reader. Clearing a UI loading flag
did not resolve this case.

Windows rejected renaming a file held by `RandomAccessFile`. A NIO channel allowed
the same rename and kept positional reads working, including a nonzero destination
buffer offset. With that channel, the same Telegram files completed downloading.
The release Windows image exercised four local recordings (CBR/VBR MP3, FLAC and
Opus) and four actual Telegram recordings at 0.3×, 1×, 2× and 4×. Each collection passed 80 individual seeks,
16 paused seeks, 16 bursts of four rapid seeks, 40 absolute-random end transitions,
six shuffle/repeat-all transitions and four repeat-one transitions. End transitions
followed seeks near the ends; no stalls or player errors occurred. Tests used an
isolated copy of a controlled Spotygram session with fresh media downloads, without
forced download retries or manual file-state refreshes.

The older player also retained loading after a completed paused seek at 4×:
the audio clock differed from the target by more than its 500 ms tolerance. The
updated player acknowledges libmpv's restart event and rejects queued work from
an earlier seek. Actual Windows and Linux player screenshots were inspected in
light/dark themes after pointer seeking, paused seeking and resumed playback. The
timeline advanced and the transport returned to Play/Pause without a loading loop.
Native audio checks used null output; physical audio drivers and the owner's exact
recording were not checked.

Android debug/release assembly and both Lint checks passed. Both release APKs kept
the existing signer and application ID, used versionCode 34 and passed 16 KB
alignment. All four desktop targets built on native runners. The installer
signatures, signed archive manifests and all archived payload hashes verified
with the existing key. All four desktop packages reported 0.15.7 with DEBUG false.
The signed Linux archive also passed the local seek matrix and 50 end transitions;
the retained local desktop image was rebuilt as release 0.15.7.
The signed Android app reached its welcome and local-library screens, but the
host emulator crashed during UI checks on Android 15/16. Android playback through
the changed reader was not confirmed in this cycle.

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
