# Rust preview verification

[Documentation](README.md) · **English** · [Русский](verification.ru.md)

Verification uses manual interaction, runtime logs and screenshots. No automated
test scaffolding, account fixtures or media are shipped with the application.
Preview releases are independent of the stable release channel.

## 0.2.5 playback lifecycle

On 0.2.4, a normal pause lasting over 20 minutes resumed on the API-36 emulator.
Stopping only its playback service reproduced the reported symptom: Play changed
the icon but did not recreate the engine. This establishes a lifecycle failure;
it does not establish exactly what retired playback on the owner's phone.

A 0.2.5 release candidate paused a one-hour recording at 35:07.114 and 1.05×,
went to Home with the screen off for over 20 minutes, then resumed with one Play
tap. Android had removed foreground status after ten minutes and the media
session was absent before the return; the activity process remained the same.
The restored session reported PLAYING at 1.05× and actual player frames advanced
from 35:07 to 35:09.

The revised Android player registers its Media3 session, reports service loss to
Rust and reloads the current recording on demand. Final APK checks cover service
retirement with the activity process retained, position recovery and pausing on
the audio-becoming-noisy event. A controlled TDLib transport delayed file
resolution by 20 seconds while the activity was in the background: the service
remained foreground and actual playback began when the file arrived. This also
exposed and fixed a Media3 notification race: its initially empty controller
snapshot could remove foreground status before preparation finished.

Real short MP3, FLAC, AAC/M4A, Vorbis/Ogg and Opus files decoded and advanced
through the queue. Progressive loading checks every 32 KiB rather than every
1 MiB: the inspected hour-long low-bitrate Opus buffered about 69 seconds at
startup instead of tens of minutes. This is an interval/duration observation,
not a battery-life measurement.

Linux release interaction with genuine audio and a temporary controlled transport
covered a one-hour recording, exact long seeks, 1.05× playback, pause checkpoints
and graceful exit. A stalled preparation cancels immediately; bytes or resolution
responses arriving after cancellation do not restart it or replace a newer
selection. Inactive ranges are requested again after two seconds. A published
prefix whose bytes were not yet visible did not consume CPU in a tight loop and
resumed when the bytes became readable. A missing range produces a visible read
timeout after 60 seconds. Actual full-player and 320-px frames were inspected;
Pause remains available while loading and errors appear beside the controls.

The final native package also received a reported full range without a local
path. Its reader thread's accumulated CPU time remained below one second during the wait; the error
appeared after 60 seconds and one Play tap resumed when a path became available.
OS controls offered Pause during preparation and accepted Pause/Quit. The stock
caption Close button exposed another failure: it bypassed the application's
window-close handler. Its replacement preserved requested playback during both
preparation and actual decoding, and exited cleanly when paused. Inspected frames
retain the original caption geometry and player surfaces.

A partial-file seek exposed a separate libmpv stall: moving back to available
bytes left the old range read alive and the clock frozen until its timeout.
The final revision reopened only while a range read was waiting. Returning to
available bytes resumed decoding (inspected frames advance from 1:09 to 1:12),
and Pause removed the stalled audio threads. Two seeks with the full file
available retained the same audio-events, demux and output threads. Loading now
uses the artist line, keeping the seek bar and controls stationary.

Removing the Android task during buffering exposed Makepad's terminal activity
shutdown: the process exited despite a foreground playback service. The staged
activity hook now separates UI destruction from worker lifetime. On the final
APK, the task was swiped away while Media3 reported BUFFERING, then bytes were
delivered: the same process reported PLAYING at 1.05× with a foreground service.
Launching a new activity restored the live player. Stopping the service while
paused retained the activity process; one Play restored its position and speed.
The noisy-audio event paused it. Seeking to an unavailable range and then back
changed BUFFERING to PLAYING within the two-second observation interval; controls
kept their geometry.

The ordinary signed APK was restored after controlled transport checks. Version
0.2.5-rust / 1007 retained the original 10,007-row offline catalog, favorite,
Russian/dark preferences, 1.05× and paused queue. Genuine local Vorbis advanced
from the restored position; Pause, explicit Quit and a fresh-process restart
retained it without autoplay. Both APKs pass application lint, signature checks
with the previous certificate and 16-KiB ZIP alignment. Formatting and Cargo
checks pass; the pinned Makepad workspace still prints its duplicate-package notices.

All four desktop archives and native-engine checks passed in
[CI](https://github.com/Krablante/spotygram/actions/runs/40207994473).
Apple Silicon Metal frames were inspected. Hosted Intel frames were blank; its
exact archive rendered in the [Rosetta recheck](https://github.com/Krablante/spotygram/actions/runs/40209807728).
Both macOS bundles passed strict ad hoc signature checks. The Windows runner's
first-adapter graphics probe remained blocked with `0x887a0004`. Downloaded Linux
and Windows archives passed genuine one-hour Opus playback, long seeking, 1.05×,
pause checkpoints and exit on Radeon through OpenGL and Wine/DXVK respectively;
Windows restart restored pause and speed. These are the CI archives, with hashes
verified before extraction. Subsequent source changes affect only the Android
activity adapter/staging and documentation; desktop source is unchanged.

Normal/reverse order, shuffle without repeats, repeat-one/all, random history,
removing/reordering queue entries and deleting the last item were exercised with
real short recordings. Pause cannot be undone by a stale EOF. Exact paused
position and bookmarks were inspected in SQLite. The paused libmpv event thread
showed unchanged context-switch counters between observations; its old half-second
polling wait has been removed.

Wine and Rosetta checks do not establish physical Windows/Intel GPU behavior.
Verification profiles, controlled responses and media stay outside the source and
release packages. Production Telegram exchange, the owner's physical phone and
speaker/Bluetooth output remain outside these checks.

## 0.2.4 settings

The old settings panel reproduced two concrete problems: returning from a child
page reset its scroll, and clamped touch drawing hid the gesture helper's extra
overscroll range. Reversing direction at the edge could therefore do nothing
until that invisible distance was crossed.

Settings now has fixed navigation and separate scroll positions for General,
Player, Storage and Telegram. The opaque workspace hides the library/player and
does not observe the playback UI clock. Storage requests cache usage on entry or
explicit refresh; it has no polling timer. Action labels wrap independently of
their leading icons.

Linux release checks cover both languages, light/dark rendering, wide and narrow
windows down to 280 px, section changes, child dialogs and disabled controls.
The 10,007-row offline catalog restores exactly the same visible recordings and
pixel offsets after Settings → Storage → On device → Back → closing Settings,
including beyond the first 128 rows. Section switches and the duration editor
retain their scroll position. A swipe beginning over a toggle scrolls without
changing its value. At the lower edge, reversing a held touch by 20 px moves the
content by 20 px immediately.

Five rapid preference taps retain their odd/even result. Sender preservation
enables the caption in the same transaction; disabling the caption disables the
sender. The duration editor rejects zero/empty/invalid values, including Enter,
and saves a positive value. Tab/Shift+Tab, Enter and editing/saving through the
keyboard were exercised. The update check displays its result in General.

The signed x86-64 APK updated 0.2.3 in place on the Android 16 / API-36 emulator;
rebuilt candidates were reinstalled without clearing data. Version 0.2.4-rust /
1006 retains the 10,007-row catalog, favorite, Russian/dark preferences and paused
1.05× playback. Actual frames cover 100%, 130% and 200% system text sizes, live
font-size changes, portrait/landscape layout, wrapped Russian action labels,
numeric input with the field and Save above the keyboard, and returning to Player
with the old threshold after invalid input. System Back and cancelling the native
file picker return to their source context. Quit exposed an older Android bug:
the worker stopped but the activity remained. The corrected Quit removes the
task and exits after worker cleanup; relaunch restores the same library and
preferences in a fresh process. These are offline verification
profiles; physical phones, production-account operations and battery life remain
outside these checks.

Formatting and `cargo check --locked -j2` pass. Both APKs pass application lint,
signature verification and 16-KiB ZIP alignment with the existing certificate.
All four desktop packages and native-engine checks passed in
[CI](https://github.com/Krablante/spotygram/actions/runs/40191338978); both macOS
bundles passed strict ad hoc signature verification. Apple Silicon Metal library
and Settings frames were inspected. Hosted Intel frames remained blank; the exact
x64 archive rendered both scenes in the [Rosetta recheck](https://github.com/Krablante/spotygram/actions/runs/40192245340).

The downloaded Linux archive was launched on Radeon 680M through Xwayland/OpenGL:
section keyboard navigation, duration editing/validation, On device → Back,
both themes/languages, a 320-px window and explicit Quit passed. The Windows
runner's graphics probe remained blocked with `0x887a0004`. Its exact archive
passed Wine/DXVK checks on Radeon: all four sections, keyboard navigation,
duration saving, account-dialog return, light/dark appearance, a 390-px Russian
layout and Quit. These compatibility checks do not establish physical
Windows/Intel GPU behavior. The published desktop and Android
[screenshots](usage.md#settings) come from these final packages.

## 0.2.3 player controls

The lower player row follows the stable Compose reference: a 48-dp circular
speed button, equal-width side buttons, 8-dp gaps and the rounded QueueMusic
glyph. Non-1× speed uses the reference accent and secondary text colors.
Commands and restored queue values use the same 0.05 grid over 0.3–4×.

The packaged Linux interface was inspected through Radeon-backed Vulkan and
OpenGL in both appearances, with responsive checks at 390×812 and 320×720,
including Download, On device, Queue, hover/press and the 1× reset.
1× → 1.05× → 1.1× → 1.05× works, as do slider input, presets, the 0.3×/4× limits
and their disabled controls. Russian comma and English decimal labels fit inside
the circle. A genuine Ogg played through libmpv with null output at 1.05×;
restart retained the value and paused position. The previous contour-shadow fix
remains intact in the inspected frames.

Rapid Android taps exposed a second issue: five + presses could advance only
three steps because the UI reused an older snapshot. Relative −/+ commands now
apply sequentially to the worker's current speed. Five rapid + presses on desktop
and Android advance exactly from 1× to 1.25×; five Android − presses return to 1×.

The signed APK updated 0.2.2 in place on the Android 16 / API-36 emulator and the
final rebuilt package was reinstalled without clearing data. Version 0.2.3-rust /
1005 retained the 10,007-row catalog, favorite, language, theme and playback state.
Actual frames cover both themes, 1× reset, 1.05× and 3.95× labels, speed limits and
Queue. Media3 advanced the genuine Ogg at 1.05×; restart retained 1.05× and pause
at 2:28. No Makepad script/shader failure or app crash appeared. Both APKs pass
application lint, signature verification and 16-KiB ZIP alignment with the existing
signing certificate. These checks use offline verification profiles and do not
establish production-account or physical-phone behavior.

Formatting and `cargo check --locked -j2` pass. All four desktop package jobs and
native-engine checks passed in [CI](https://github.com/Krablante/spotygram/actions/runs/40179232813);
both macOS bundles passed strict ad hoc signature verification. Apple Silicon
Metal startup/settings frames were inspected. Hosted Intel frames remained blank;
the exact x64 archive rendered those scenes in the [Rosetta recheck](https://github.com/Krablante/spotygram/actions/runs/40179962902).
The Windows runner's graphics probe remained blocked with `0x887a0004`. Its exact
archive passed Wine/DXVK checks on Radeon: both player appearances, 1×/1.05× tint,
five rapid + steps to 1.25×, Queue, genuine Ogg playback through libmpv with null
output and pause. These compatibility checks do not prove physical Windows/Intel
GPU behavior or speaker output.

## 0.2.2 glass edges

The owner's square gray marks reproduce around the circular player Close button
and the speed capsule in the packaged 0.2.1 interface. The shared outer shadow
retained visible alpha at its rectangular draw boundary, and stationary surfaces
and buttons also clipped their own drawing to their layout bounds.

Both glass shaders now end the contour shadow smoothly within their geometry.
Stationary surfaces and glass buttons allow their existing outer padding to draw;
ancestor scrolling clips remain active. Rounded faces, optical rims, dimensions
and the shared capture/blur path remain in place. The transparent sign-in image
also masks its empty edge, as the header stickers already do, to avoid sampler
streaks.

The Linux release was inspected on Radeon 680M through native Wayland/Vulkan
and Xwayland/OpenGL. Before/after enlarged crops cover the reported circular
button and speed capsule. Checks also cover light/dark appearance, hover and
press, the 10,007-row library, grouped source panels, playlist editor, settings,
language, speed and queue panels, and the 390×812 responsive player and sheets.
The shadow follows the rounded contour without the former rectangular corner
marks. `cargo fmt --all -- --check` and `cargo check --locked -j2` pass; Cargo
still reports the pinned Makepad workspace's duplicate-package notices.

The signed x86-64 APK updated 0.2.1 in place on the Android 16 / API-36 emulator
with Radeon-backed OpenGL. Version 0.2.2-rust / 1004 retained the 10,007-row
library, favorite, Russian/dark appearance, 2× speed and paused position 2:19.
Actual frames cover both themes, library, grouped sources, playlist editor,
player, settings, speed and queue sheets. A settings swipe starting over the
enabled resume toggle scrolled without changing it; returning to the top kept
its state. No Makepad script/shader error or application crash appeared.
Both ABIs pass application lint, signature verification and 16-KiB ZIP alignment;
the signing certificate matches 0.2.1.

Desktop packages and native-engine loading passed in [CI](https://github.com/Krablante/spotygram/actions/runs/40174812426)
and the [Intel retry](https://github.com/Krablante/spotygram/actions/runs/40175008276).
The first Intel build hit a rustc SIGSEGV while optimizing unchanged Makepad code;
a clean retry succeeded. Both macOS bundles passed strict ad hoc signature checks.
Apple Silicon Metal frames of the library and settings were visually inspected.
Hosted Intel captures remained blank; the exact x64 archive also produced inspected
frames in the [Rosetta recheck](https://github.com/Krablante/spotygram/actions/runs/40175667150).
The Windows first-adapter probe remained blocked with `0x887a0004`. The exact
Windows archive rendered through Wine/DXVK on Radeon: light/dark player and
settings, the speed panel, genuine Ogg import, libmpv null-output playback and
pause passed. These compatibility checks do not establish physical Windows/Intel
GPU behavior or speaker output.

These are rendering and interaction checks. The account and physical-device
coverage limits below still apply.

## 0.2.1 synchronization recovery

The owner reported `Sync will retry: 400: Supergroup not found`. The 0.2.0
discovery loop handled an inaccessible chat but aborted on inaccessible
supergroup metadata. A controlled TDLib response reproduced that exact status
in the packaged 0.2.0 interface, leaving zero imported sync records and sources.

The 0.2.1 checks use the real release application's worker, SQLite store and GPU
interface with a temporary native transport override. No production account or
remote channel is modified. The responses include an existing format-3 snapshot,
an inaccessible supergroup and an inaccessible full-info request. Checks cover:

- Skipping both unavailable candidates and reading the existing storage without
  creating another channel. Sources, a favorite, playlist membership/order and
  Russian/light appearance restore in the previous profile without a data reset.
- Automatic indexing after source restoration. The snapshot contains one track
  descriptor; the source query adds a second recording without a manual refresh.
  A separate profile-only snapshot also restores its subscription and loads its
  profile audio without a connected chat.
- Applying the read snapshot even when an outgoing send fails. The pending local
  Wi-Fi preference stays pending and retains its value; imported records remain
  visible and source indexing still runs.
- A missing remembered storage reports an error and does not create or send to
  a replacement. A metadata `500` remains a retryable failure, rather than being
  silently treated as an unavailable candidate.

This reproduces the reported failure and exercises recovery through the actual
application. It does not establish live authenticated synchronization on the
owner's phone or replace the remaining account checks below. The temporary
transport, profiles and configuration are not shipped.

The signed x86-64 APK updated 0.2.0 in place on the Android 16 / API-36 emulator.
The 10,007-row library, favorite, Russian/dark appearance, current recording,
paused position 2:19 and speed 2× remained intact. Installed version 0.2.1-rust /
1003 and actual frames were checked; no Makepad/AndroidRuntime errors appeared.
Both APK ABIs passed application lint, signature verification and 16-KiB ZIP
alignment. Their certificate matches 0.2.0.

All four desktop packages and native-engine loading passed in [CI](https://github.com/Krablante/spotygram/actions/runs/40153036648).
Apple Silicon Metal and the exact Intel archive's [Rosetta recheck](https://github.com/Krablante/spotygram/actions/runs/40153909786)
produced visually inspected frames. Native hosted Intel frames remained blank;
the Windows adapter probe remained blocked with `0x887a0004`. The exact Windows
archive rendered through Wine/DXVK on Radeon and passed real Ogg import, libmpv
null-output playback, seeking to 1:27 and pause. These remain compatibility checks,
not physical Windows/Intel GPU or phone account evidence.

## 0.2.0 visual migration

The visual reference is stable Spotygram 0.28.0: its Compose layouts, design
constants, vector icons, font, bundled stickers and the owner's Android
screenshots. The Rust interface now follows those layouts across the library,
sources, playlists and their editor, settings, sign-in, sharing and full player.
Stationary controls use the same environment colors; floating navigation,
player and panels capture the scene behind them. Makepad and Compose/Haze still
use different renderers, so this is not a pixel-identical rendering claim.

The packaged Linux release was inspected on Radeon 680M / Mesa 25.2.8 through
Xwayland/OpenGL and native Wayland/Vulkan, using an isolated offline catalog.
Checks covered:

- Light/dark themes, Russian/English labels, wide and narrow layouts, settings
  navigation, switches, source groups and the searchable source chooser.
- Playlist mosaics and anchored menus, selection across searches, a Russian
  playlist name and ordered atomic saving. Adding all 10,003 missing recordings
  to a four-recording playlist produced 10,007 memberships without duplicates.
- Rejection of invalid recording identities without partially changing playlist
  membership, error recovery and confirmation before discarding editor changes.
- Full player, speed controls and the compact queue overlay with distinct current
  occurrence, move-up and remove actions. Removing entries updates the count and
  panel height; removing the last one returns to the previous library search.
- Immediate search after closing the queue remains in the library. A Russian
  query matched 3,333 rows instead of incorrectly searching the closed queue.
- Language selection and playback-order icons across theme changes, checkbox
  selection at 390×812, and the editor when all available songs are already added.
- Returning from the queue preserves the library scroll position beyond the first
  128-row page. The 10,007-row catalog restored the same visible rows and offsets.
- Genuine local Ogg decoding through libmpv with null output, absolute seeking,
  2× speed and pause on Vulkan. Transparent header stickers retain clean edges.

Screenshots were inspected as pixels and corrected through repeated release
builds. This caught material/radius errors, an incorrect image mask, empty
preference hit regions and displaced anchored menus. Synthetic profiles and
avatars exercise layout only; they do not establish authenticated Telegram
behavior. Their verification profiles must never be connected to an account.

Unchanged SVGs no longer parse again or request another frame from row drawing.
In an unfocused, paused catalog, a ten-second observation produced no intervening
frames; the next injected pointer input requested one. This establishes removal
of that redraw loop, not phone battery use or a comparative performance figure.

On the owned Android 16 / API-36 emulator, the signed x86-64 update retained the
offline library. Radeon-backed OpenGL rendered the 10,007-row catalog, both
themes, Material icons, the shared navigation capsule and the full player.
Manual checks covered:

- Typing through Gboard, visible input text and Android Back hiding the keyboard
  without reopening it; long-press selection and checkbox layout.
- The playlist editor saving two selected recordings in order, and returning to
  the created playlist with the right count.
- The System/English/Russian chooser, returning to Settings after selection and
  switching appearance. System followed the emulator's English locale.
- The queue retaining distinct repeated occurrences and resizing after removal.
- Real Ogg playback through Media3, seeking, 2× speed, background playback on Home
  and pause through the system media-session command.

Both ABI packages passed application lint, signature verification and 16-KiB ZIP
alignment. The signing certificate matches 0.1.0. Font, sticker and icon assets
and their notices were checked in the APK; old versioned notices are not retained.

The final APK also updated the owned Android 8 / API-26 emulator without resetting
its library. The system document picker imported Ogg, Media3 played it and drag
seeking reached 1:50. Home-screen playback and a system media pause passed.
The phone and server-generated QR sign-in screens were inspected without linking
an account. The QR layout uses the reference's compact branding, 240-point code
and instructions instead of displaying the authentication URL.

A touch swipe starting over a settings row now scrolls the panel and cancels its
tap after touch slop. The final APK was checked with both an opening row and a
toggle: taps still activate them, swipes do not navigate or change the toggle,
and the lower settings remain reachable. Native Linux touch injection and wheel
scrolling passed the same container checks, including clearing the row's focus.

All four 0.2.0 desktop packages were built in [one CI run](https://github.com/Krablante/spotygram/actions/runs/40149534585).
Packaged TDLib/libmpv loading and strict ad hoc signature verification of both
macOS bundles passed. The release uses the locally exercised Linux archive above.

| Package | 0.2.0 interface evidence |
| --- | --- |
| Linux x64 | Native OpenGL and Vulkan, the 10,007-row library and the scenarios above. |
| Windows x64 | The exact CI archive ran through Wine 9 / DXVK 2.4.1, native D3DCompiler 47 and Radeon. Both themes, settings, track menu, Ogg import, libmpv null-output playback, seeking to 1:27 and pause passed. |
| macOS ARM64 | The packaged app rendered through Metal on the Apple Silicon runner; actual frames were visually inspected. |
| macOS x64 | The same Intel archive, checked against its SHA-256, rendered through Rosetta on Apple Silicon; the [recheck](https://github.com/Krablante/spotygram/actions/runs/40150751632) and visual inspection of its actual frame passed. |

The Windows runner's independent probe of Makepad's required Direct3D adapter
returned `0x887a0004`, leaving native graphics coverage blocked. The Intel macOS
runner returned blank frames without an application error. Wine and Rosetta
establish rendering of the packaged archives, not physical Windows or Intel GPU
behavior. WinRT media controls were unavailable in Wine, and null output does not
verify speakers or Bluetooth.

Authenticated Telegram history, incomplete remote-file seeking, confirmed sends,
profile uploads and two-device synchronization remain unverified for 0.2.0.
Synthetic catalogs and recipient layouts do not prove those account operations.
Physical phones, Windows/macOS owner devices, Bluetooth, battery use and frame
pacing also remain outside the current evidence.

## 0.1.0 baseline

The following observations describe the previous release. They are retained as
historical coverage and do not substitute for checking a changed 0.2.0 screen.

### Native Linux checks

The portable release image was launched from its packaged directory on toma,
Ubuntu 24.04, AMD Radeon 680M, Mesa 25.2.8 and a private Weston display. Makepad
reported the real RADV Vulkan backend. No CPU renderer or framework fork was used.
The UI inspection bridge was enabled only in owned verification instances.

The owned synthetic catalog contains 10,000 Telegram-shaped metadata rows plus
one genuine local Ogg import. The synthetic rows are not playable Telegram media
and the verification profile must never be connected to an account. Checks covered:

- Page-based list display and scrolling, Russian search, navigation and source filters.
- A real local import through native drag/drop and tag extraction.
- Playback through the progressive file reader and libmpv with null audio output;
  elapsed time, absolute seeking to 1:27 in a 2:56 recording, pause and 2× speed.
- Playlist creation with a Russian name, membership and favorites.
- Multiple occurrences of one recording in the queue; playing the second and
  moving it down retained the correct queue index.
- Restoring local state after a graceful restart without automatic playback.
- Wide 1040×760 and narrow 390×812 layouts, full player, modal panels, light/dark
  appearance and Russian labels. Screenshots were inspected as actual pixels.
- Packaged brand and SVG resources. Missing resource-directory normalization,
  glass controls left behind by hidden parents, text clipping and seek behavior
  found during iteration were corrected in application code.

Null audio output verifies decoding and playback control, not physical speakers,
Bluetooth routing or audio focus. Synthetic metadata verifies catalog behavior,
not Telegram authorization or remote availability.

### Desktop packages

All four desktop targets were built with their packaged native engines. Both
macOS app bundles passed ad hoc signing and strict signature verification.

| Package | Runtime evidence |
| --- | --- |
| Linux x64 | Native Radeon/Vulkan UI, catalog and local playback checks above. |
| Windows x64 | MSVC package and native DLL loading passed on Windows CI. The exact archive rendered correctly under Wine 9, native D3DCompiler 47 and DXVK 2.4.1 on Radeon; Ogg drag/drop, libmpv null-output playback, seeking to 1:27 and pause passed. |
| macOS ARM64 | Packaged app opened on the Apple Silicon runner; native Metal frames were visually inspected. |
| macOS x64 | Intel compilation, signing and native library loading passed. The exact Intel archive rendered correctly through Rosetta on Apple Silicon. |

The Windows CI runner's first DXGI adapter could not create Makepad's required
Direct3D 11 device (`0x887a0004`); an independent probe with the same adapter,
feature level and flags records that coverage as blocked. It is not a native
Windows visual pass. Wine's built-in shader compiler/renderer were insufficient;
the additional components above belonged only to the owned verification prefix,
not the released package. WinRT media controls were unavailable in Wine.

The Intel macOS CI runner returned blank frames without a rendering error. The
Rosetta check establishes that the packaged x64 application can render, but does
not establish physical Intel GPU behavior. Captures from the runtime checks were
inspected as pixels rather than treating a successful process launch as visual proof.

### Device and account limits

The signed x86-64 APK was installed on an owned Android 16 / API 36 emulator,
using Radeon-backed OpenGL through the emulator. Manual checks reached TDLib's
phone screen and a server-generated QR challenge with a fresh Android Keystore
key; no account was linked. A real Ogg was imported through the system document
picker, decoded by Media3, sought to about 1:50 and kept playing on the home
screen. System media commands paused and resumed it. Android Back returned from
the player to the library. Confirmed import deletion removed the owned file while
preserving the original in Downloads.
External Share intents imported audio both into a cold process and an already
open application. After seeking to the end of a one-track queue, Play started
the recording again. Compact menus, confirmation panels and the centered login
artwork were inspected on the updated build.

Application `:lintRelease` passes. Makepad's staged Java compiles as a library
dependency; its framework diagnostics were reviewed separately. Remaining lint
warnings include pinned dependency/target versions, the public MediaSession
service and Makepad's optional insecure-socket mode. Spotygram's update requests
use HTTPS certificate validation; Telegram uses TDLib. No MIDI/location permission
was added. APK signatures and 16-KiB ZIP alignment were checked.

The Android 8 / API-26 emulator rendered the library with Radeon-backed OpenGL
after the staged InputDevice API guard and initial theme setup were corrected.
The final signed APK also imported Ogg through the system document picker,
played it through Media3 and sought to about 1:50 on that API-26 image. The picker
accepts both audio MIME types and application/ogg used by older providers.
The image's SwiftShader path rejected a GLSL shader and is not a supported
rendering result. Physical older devices still require verification. Emulator
results are not phone battery or frame-time measurements.

Authenticated Telegram history, incomplete remote-file seeking, confirmed sends,
profile uploads, live two-device sync and account switching require an owned
signed-in session. They have not been presented as successful account tests.
No live Odnal session was reused. The implementation retains format 3, its marker,
account scoping, immutable file references, confirmed-send handling and tombstones;
compatibility does not make this preview a backup.

Physical Android battery use, owner Windows/macOS devices, lock-screen
behavior on a phone, Bluetooth changes and interrupted network recovery need
device checks. A cross-platform build alone cannot establish these properties.
The 0.1.0 Makepad visual was an adaptation and did not reproduce the stable
Compose/Haze design. It is not the visual reference for 0.2.0.

## Performance interpretation

The catalog is paged and searched in SQLite rather than retained as UI objects.
Queue identities remain in SQLite with bounded published snapshots, and playback
transport does not refresh the entire catalog. Idle UI has no application polling
timer; visible playback updates are throttled and hidden playback publishes less.
Artwork is requested for visible rows and obsolete page images are evicted.

These implementation choices are not a claim of universal speedup. Earlier stable
0.28.0 observations of roughly 383–396 MiB RSS came from a different verification
setup and cannot serve as a controlled comparison. Reported memory must distinguish
catalog-only startup, initialized audio/Telegram engines and the graphics driver.

On 2026-10-24–25, the packaged Rust 0.2.0 and packaged stable 0.28.0 were launched
one at a time on toma with the same offline 10,001-track catalog, Russian/light
appearance and 1040×760 window. No compiler or emulator was running. Both were
limited to two CPU cores, with no account, playback or inspection bridge. After
initial rendering settled, `pidstat` samples over 20 seconds and a final
`smaps_rollup` reading showed:

| Stack | RSS | PSS | Swap | Idle CPU, one core = 100% |
| --- | --- | --- | --- | --- |
| Rust 0.2.0 / Makepad | 192 MiB | 159.8 MiB | 0 | 0.95% |
| Stable 0.28.0 JVM / Compose | 376–377 MiB | 325.0 MiB | 0 | 0.30% |

RSS was about 49% lower in this catalog-only case. Idle CPU was not lower.
Rust used native Wayland/Vulkan; stable used Xwayland/OpenGL on the same Radeon.
The figures exclude the separate compositor and do not account for all GPU/kernel
allocations. This is one short controlled observation, not a startup, battery or
frame-pacing benchmark. No multiplicative playback or Android speedup is claimed.
