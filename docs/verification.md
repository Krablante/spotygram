# Verification record

[Documentation](README.md) · **English** · [Русский](verification.ru.md)

This page records observed behavior and its limits. A build does not establish
account access, physical sound or responsiveness on another computer. Temporary
inspection code, timings and account data stay outside the checkout.

## 0.28.0 — wheel cadence and scrolling costs

### Linux comparison

The 0.27.1 comparison and candidate used the same JDK 21, 256 MiB heap,
two logical CPU cores, AMD Radeon 680M/OpenGL and private Weston/Xwayland display.
The synthetic catalog contained 10,002 recordings, 60 chats, 40 selected profile
descriptors, 200 playlists and 20,000 memberships. No Telegram authorization was
supplied to that catalog.

Native wheel input made five forward/back passes through each surface. The table
uses the last four passes, excludes deliberate pauses between gestures and
measures intervals between lazy-list scroll callbacks, including boundary handling:

| P95 scroll-callback interval | 0.27.1 | Candidate |
| --- | ---: | ---: |
| Music | 28.88 ms | 19.12 ms |
| Settings | 22.33 ms | 18.83 ms |
| Source chooser | 34.63 ms | 18.58 ms |

In the chooser, intervals above 25 ms fell from 17.2% to zero in those passes;
Music fell from 6.7% to 0.2%, Settings from 4.5% to 0.9%. Settings' scroll callback
P95 fell from 3.16 to 0.82 ms. The wheel adapter keeps movement continuous between
notches instead of finishing each short linear segment before the next input.

These are temporary in-process callback observations in a virtual display. They
are not GPU timer queries, end-to-end input latency or a physical-device FPS
guarantee. Native draw/flush P95 remained around 15–16 ms; smoother cadence does
not mean every rendering stage became faster. Some delayed frames remain.

An eight-second idle sample after scrolling recorded no render callbacks and
about 0.25% of one CPU, with approximately 399 MiB RSS. Native allocations remain
outside the 256 MiB Java heap. Eight injected fractional wheel events produced
eight direct scroll updates; a reduced-motion desktop property disabled
interpolation. These observations do not establish physical touchpad behavior.

The duration-label implementation matched the prior formatter at 17 boundary
values in English and Russian, including negative values and `Long.MAX_VALUE`.
Seven warm runs of 100,000 actual compiled calls had median times of 132.13 ms
before and 2.79 ms after the change. That is a small-function comparison, not an
application FPS measurement.

Screenshots checked light/dark Settings, its fixed header at the bottom of the
form, dismissal during wheel motion and the boundary between long profile/chat
groups in the source chooser. Panel geometry stayed aligned with the lazy rows.

The published 0.27.1 package reproduced `RootNodeOwner is already disposed` when
an open wide Settings popup crossed the narrow-window threshold. The candidate
retained the open panel through shrinking, scrolling, closing and growing;
reopening selected the presentation for the new width. The source chooser retained
its open popup too. A separate reproduction found the same disposal failure in
the player's open order menu. Moving the existing controls between layouts kept
that menu and the speed panel alive; changing the order, resizing again and
reopening showed the updated selection. No disposal error appeared in those runs.

The packaged launcher also checked local playback with audio output disabled,
search, browsing 200 playlists and filtering the editor's 10,002-recording catalog.
These checks do not establish authenticated Telegram transport or physical sound.

### Android update and interface

Debug/release builds and both Lint variants passed with zero errors and 42 warnings
per variant. Both release APKs passed v2 signature and 16 KiB ZIP alignment checks
and retained the original signing certificate.

A retained Android 16 installation first updated from 0.17.0 to the published
0.27.1, then to 0.28.0 (50). The last update preserved all 18 saved application
files byte-for-byte before launch. The catalog, favorites and paused queue position
remained visible. Russian Settings at 320 dp/font scale 1.3 scrolled to the bottom
with its fixed Close button; Close and drag dismissal worked. Local playback
continued after Home, the media session reported PLAYING, and a media Pause command
returned to the paused player. The application crash buffer remained empty.

That virtual device also encountered a System UI ANR during cold boot and had
substantial system delays. It establishes the listed update and interface behavior,
not a useful Android performance comparison, physical audio or battery behavior.

## 0.27.1 — desktop Settings dismissal

Settings still used a mobile bottom sheet in wide windows after 0.27.0. It had
no Close button, and opening moved a large glass surface across the window.
From 600 dp, Settings and its cache/sharing pages now use a stationary centered
popup. Headers stay visible while the content scrolls; narrow screens retain
the draggable sheet and gain the same Close control.

Manual Linux/OpenGL interaction checked Close after scrolling to the bottom,
Escape, outside clicks, cache/sharing navigation, an inner duration dialog and
both themes at 1090 px. Twenty openings alternating Close/Escape/outside dismissal
were confirmed by screen pixels. A 314 px window retained the bottom sheet,
scrolling and fixed Close button. Screenshots checked the actual rendered panels.
These checks establish those interactions; they do not establish a new physical
Windows GPU measurement or a cold-start frame-time guarantee.

The final Windows ZIP ran through its actual `Spotygram.exe` and bundled Java
on a Windows VM using ANGLE/Microsoft Basic Render Driver. Real mouse and keyboard
input checked opening, scrolling to the bottom, fixed Close, Escape, outside
dismissal and sharing-page Back navigation. Pixel inspection confirmed every
result and the packaged app closed normally. The temporary inspection branch was
deleted; no diagnostic workflow was added to the default branch.

Android debug/release and both Lint variants passed (zero errors, 53 warnings
per variant). Both APKs passed v2 signature and 16 KiB ZIP alignment checks,
retained the original certificate and carry `f74ed7e` in their VCS metadata.
An Android 15 installation updated from 0.27.0 (48) to 0.27.1 (49), retaining all
16 saved application files byte-for-byte before launch. Russian Settings at
320 dp/font scale 1.3 retained its fixed Close button after scrolling; Close and
drag dismissal worked, with an empty crash buffer. This remains emulator evidence.

[0.27.1](https://github.com/Krablante/spotygram/releases/tag/v0.27.1) targets
`f74ed7e`. All four jobs in the [native workflow](https://github.com/Krablante/spotygram/actions/runs/39968659718)
passed. The published 0.27.0 reader verified/extracted all four final archives,
rejected modified launchers and verified all four installer signatures. All 15
uploaded sizes and SHA-256 digests matched local files before publication as
Latest. Afterwards, 0.27.0 `AppUpdates` selected 0.27.1 from public Latest with
the exact verified Linux archive size and SHA-256.

## 0.27.0 — playback controls and library responsiveness

### Desktop interface and resources

Linux checks used OpenGL on the AMD Radeon 680M in a private Weston/Xwayland
display, JDK 21 and a 256 MiB heap. Temporary instrumentation timed Skiko's
recording and native draw/flush callbacks; screenshots checked the actual pixels.
The speed-panel comparison used the same two logical CPU threads, catalog and
native libraries in 0.26.0 and the changed app. Twenty repeated openings gave:

| Native draw/flush callback | 0.26.0 | 0.27.0 |
| --- | ---: | ---: |
| Median | 18.78 ms | 3.77 ms |
| P95 | 24.04 ms | 6.40 ms |

The wide speed panel is now anchored to its button, so opening it also needs
fewer animation frames. These are callback timings in a virtual display, not GPU
timer queries, end-to-end input latency or a sustained-FPS guarantee. Cold JVM,
Compose and shader initialization still produced multi-frame stalls; the warm
figures do not describe a cold launch or prove performance on another GPU.

A separate catalog held 10,001 recordings, 60 sources, 200 playlists and 20,000
memberships. Manual interaction checked search, scrolling, a menu opened after
scrolling, a favorite edit, Favorites and a 100-song playlist. The catalog passed
SQLite integrity checking. The new G1 pause target retained the 256 MiB heap;
65 recorded collections in that run had pauses from 0.64 to 30.58 ms. The 10 ms
target is not a hard bound.

Screenshots checked dark/light controls, the anchored speed panel, narrow player,
bottom sheet and a frame during animated dismissal. Linux's original package
crashed in `glGetString` after loading mpv's private GL dispatch. System GL dispatch
avoided that crash; the changed package ran without the environment workaround
after removing the bundled EGL/GL-dispatch/GBM/DRM copies.

The final CI Linux archive also ran through its actual launcher and bundled
Temurin 21 runtime, with no GL preload or renderer override. Local playback,
speed changes, minimize, pause and return worked with the 10,001-recording catalog.
Eight-second samples measured 6.12% of one CPU while visible, 2.38% minimized and
0.25% after restoring the paused player; RSS ranged from 397 to 402 MiB. Native
allocations are outside the 256 MiB Java heap. Audio output was disabled.

On a native Windows VM, both versions used ANGLE with Microsoft's Basic Render
Driver (software D3D11), JDK 21, two active CPU cores and a 256 MiB heap. Pixel
checks confirmed playback and the first/last speed panels. Twenty warm openings
gave these native draw/flush callback timings:

| Windows software adapter | 0.26.0 | 0.27.0 |
| --- | ---: | ---: |
| Median | 23.34 ms | 10.01 ms |
| P95 | 37.25 ms | 12.58 ms |

CPU recording P95 did not improve (15.53 to 28.35 ms); the native draw reduction
does not establish that every rendering stage became faster. These observations
do not establish hardware Windows FPS or physical sound.

The final Windows archive was also opened through `Spotygram.exe` with its
bundled runtime, without an instrumentation agent or external Java. Real mouse
input opened the player and speed panel; local playback, pause and normal close
worked. Screenshots confirmed the panel and controls in that packaged run.

The Windows renderer choice follows the unresolved upstream
[SKIKO-1179](https://youtrack.jetbrains.com/issue/SKIKO-1179), which reports a
Direct3D first-filter stall in the same Skiko 0.150.1. That report establishes the
backend issue on its reporter's hardware, not a measurement of Spotygram on a
physical Windows GPU. macOS/Metal responsiveness was not measured here.

### Android retention and controls

Debug/release assembly and both Lint variants passed: zero errors and 53 warnings
per variant. Both release ABIs passed 16 KiB ZIP alignment and v2 signature
checks and matched 0.26.0's signing certificate.

An existing Android 15 emulator updated from 0.26.0 (47) to 0.27.0 (48).
All 16 saved application files matched byte-for-byte before first launch. After
launch, all 12 SQLite tables matched the prior contents and integrity checking
returned `ok`. The queue restored paused at 8,910 ms, also exposed by MediaSession.
Reinstalling the final APK built from `535b65c` again retained all 16 files before
launch and all 12 tables afterwards; the later 2,621 ms position also survived.

Manual interaction checked presets, 0.05 steps, speed-sheet drag and button
dismissal, Settings scrolling, both themes and Russian controls at 320 dp with
font scale 1.3. Local playback continued after Home; the system Pause command
stopped it at 2,621 ms, and returning to the player showed that position. The
crash buffer contained no AndroidRuntime exception from these flows. The emulator
used host GPU rendering with audio output disabled. These checks do not establish
physical-device FPS, battery use or physical sound.

### Scope and remaining limits

The audit covered shared screens and state publication, catalog costs, playback
observation, platform adapters, native dependencies, packaging, update trust and
the English/Russian guides. The existing shared/platform boundaries, release path
and documentation language-suffix structure remain. No runtime dependency,
service, idle poll or permanent diagnostic/test scaffold was added.

No fresh authenticated Telegram exchange was established in this audit. Live
transport, privacy and account evidence from earlier releases remains bounded
by the observations below; the existing production verification session was
preserved. Hardware Windows, macOS and physical Android responsiveness remain
unverified.

### Published artifacts

[0.27.0](https://github.com/Krablante/spotygram/releases/tag/v0.27.0) targets
`535b65c`. The [native workflow](https://github.com/Krablante/spotygram/actions/runs/39957173501)
completed Windows, Linux and both macOS architectures. The published 0.26.0
archive reader verified and extracted all four final archives and rejected a
modified launcher in each; all four installer signatures matched its trusted key.
All 15 remote assets matched local sizes and SHA-256 digests before publication
as Latest. Both APKs carry `535b65c` in their VCS metadata. Signing identities and
application IDs are unchanged. The Linux DEB declares glibc 2.35 and the system
graphics dependencies; its Ubuntu 24.04 package dependencies remain separate from
the portable archive's glibc floor.

After publication, the unmodified 0.26.0 `AppUpdates` fetched public Latest
without credentials and selected 0.27.0 with the exact verified Linux archive
size and SHA-256. Temporary Windows inspection used a separate branch; it was
deleted afterwards and added no diagnostic workflow to the default branch.

## 0.26.0 — desktop motion and library reliability

### Desktop

The Linux interface ran with OpenGL on an AMD Radeon 680M through a private
Weston/Xwayland display. Earlier Xvfb runs fell back to software rendering and
are excluded from GPU comparisons. Work was limited to two CPU cores.

The comparison uses the release classes and native libraries from 0.25.0 and
the changed application, the same retained local catalog, JDK 21 and a 256 MiB
heap. A temporary in-process timer records render callbacks; screen captures
separately inspect the actual pixels. These are virtual-display measurements,
not a guarantee of sustained 60 FPS, hardware Direct3D or Metal behavior.

Thirty Settings openings per version used real mouse input, with pixel checks
that each sheet opened and closed. The last twenty openings gave these results:

| Measurement | 0.25.0 | 0.26.0 |
| --- | ---: | ---: |
| Render callback median / P95 | 1.57 / 4.42 ms | 1.52 / 3.46 ms |
| Frame interval median / P95 | 25.38 / 27.99 ms | 25.30 / 27.42 ms |

The virtual display limits steady animation to about 40 FPS in both versions.
These results do not establish a sustained FPS improvement. Early openings had
larger compilation/initialization stalls. An experimental geometry compensation
did not help and was removed. Settings now compose visible groups lazily; dense
glass uses the library's Performance sampling profile without changing the design.
The actual packaged launcher also played the retained local recording, continued
after minimizing and paused normally, using bundled Java and libmpv with null audio.
Ten-second samples measured 3.6% of one CPU for visible playback, 2.5% minimized
and 0.3% after settling on pause; process RSS ranged from 355 to 381 MiB.

Screenshots checked light/dark Settings, the bottom of the lazy form, 1090- and
390-pixel windows, track popup, imported-track deletion confirmation, player,
speed and queue. Native dragging dismissed the speed sheet. Cancelling deletion
kept the imported file and favorite. Windows/Direct3D and macOS/Metal were not
measured during this audit.

### Android

Debug/release assembly and Lint passed for both variants. Lint retained 54 warnings
per variant and no errors; neither animated-offset warning remains. Both release
ABIs passed APK v2 signature verification and 16 KiB ZIP alignment, with the same
certificate as 0.25.0.

A retained Android 15 installation updated from 0.25.0 (46) to 0.26.0 (47).
All 16 saved database/preference/application files matched byte-for-byte before
first launch. After launch all 11 SQLite tables matched; queue IDs, order, cursor
and paused position at 8,910 ms survived. MediaSession exposed that same position
without starting playback.

The emulator used host GPU rendering. Russian Settings, its lazy-list bottom,
switch motion, both themes, track actions and deletion cancellation were checked
at 320 dp and font scale 1.3. No AndroidRuntime exception appeared in those flows.
These checks do not establish physical-device frame rate, battery use or sound.

### Sync and data

Temporary runtime inspection used the compiled application and independent
SQLite catalogs, without replacing the project's manual verification workflow.

- Two catalogs restored 10,000 referenced songs. Independent offline additions
  converged to identical portable records and all 10,002 playlist entries.
- Acknowledging an older send left a newer rename pending. Deleting a playlist
  survived replay of an older offline snapshot.
- The published 0.25.0 falsely confirmed a send when two chats used the same
  temporary message ID. The changed code confirmed each chat independently.
- An imported-track tombstone combined with a later offline membership restored
  a dangling playlist row in 0.25.0. The changed code kept zero memberships.
- Concurrent deletion versions converged. A failed account-binding transaction
  restored both SQLite and the in-memory account; retry retained its local playlist.
- The old reader accepted a snapshot whose `through` included its own message.
  The changed reader rejected it. Bad SHA-256 and an unknown format were also
  rejected with the library and local pending edits unchanged.

These checks establish application merge, confirmation and validation behavior.
They do **not** establish a new authenticated exchange through Telegram. The
retained production verification profile could not unlock its separate OS key
store; the existing encryption key and Telegram database were preserved. Its
catalog passed SQLite integrity checking and all ten tables matched the pre-check backup. Earlier
live transport evidence remains available below.

### Source, dependencies and documentation

The shared application and platform-adapter boundaries remain. No service, idle
poll, alternative player, new runtime dependency or permanent verification scaffold was
added. Empty sync batches avoid a merge transaction; preference reads select only
settings. The existing package/release path and signing identities are retained.
SQLite's query plan confirms a bounded account/key index range for preference reads.
ELF inspection found glibc 2.35 in the published Linux image and 2.38 in the local
Ubuntu-JDK image. DEB packaging now derives its dependency from the bundled binaries.
The portable Linux image retains two libmpv aliases as symlinks, removing
92,100,902 duplicate bytes (87.8 MiB). The application's own signed-archive reader
extracted both links successfully and rejected a modified executable. JDK 21's
DEB copy dereferences them again; installer inspection confirmed this remaining
duplication. The DEB compressor was observed creating 16 threads and using about
1.6 GiB on a two-core affinity; packaging now defaults to two compression threads.
Restoring aliases in macOS ZIP exposed a reader incompatibility: the old streaming
ZIP reader rejected them. macOS packages retain regular copies to keep updates
from existing portable clients working; no format transition is required.

OSV returned no matching advisories on 22 October 2026 for the nine queried Maven
versions: Commons Compress 1.28.0, Commons Lang 3.18.0, Commons IO 2.20.0, SQLite
JDBC 3.50.3.0, JNA 5.17.0, JSON 20250517, coroutines 1.10.2, ZXing 3.5.4 and
SLF4J 2.0.17. This is a bounded advisory lookup, not a security certification of
all native and transitive code. Package inspection found no duplicated application
or dependency classes; repeated versioned `module-info` entries belong to their
individual JARs.

English and Russian documentation now explain same-field conflict resolution,
local-file backups and the correct format-3 minimum version. README introduces
the product and routes detailed controls to the user guide. Earlier verification
history is linked instead of copied into the current record.

### Published artifacts

[0.26.0](https://github.com/Krablante/spotygram/releases/tag/v0.26.0) targets
`83650bb`. The [native workflow](https://github.com/Krablante/spotygram/actions/runs/39835819489)
completed all four platforms, including strict verification of the apps mounted
from both DMGs. On Linux, the published 0.25.0 archive reader verified and extracted
all four final archives and rejected a modified executable in each. All four
installer signatures matched the existing trusted key. All 15 uploaded assets
matched local sizes and SHA-256 digests before publication as the ordinary Latest release.
The final downloaded Linux package required glibc 2.35 and ran local playback with
null audio through its actual bundled launcher. Both APKs carry the release commit
in their VCS metadata. Release signing identities and application IDs are unchanged.
After publication, the 0.25.0 client fetched public Latest without credentials and
selected 0.26.0 with the exact verified Linux archive size and SHA-256.

## Earlier evidence

The [complete record through 0.25.0](https://github.com/Krablante/spotygram/blob/8a9900e/docs/verification.md)
preserves the observations and limitations for each earlier release. The
[release archive](https://github.com/Krablante/spotygram/releases) contains the
published binaries and tagged source.

| Area | Earlier observation | Important limit |
| --- | --- | --- |
| Telegram transport | 0.16.0 exchanged a 10,000-song library through two owned production sessions and Android; offline additions converged to 10,002 songs | Does not establish a fresh format-3 server exchange |
| Desktop playback | 0.15.7 exercised local/Telegram seeking and transitions on native Windows and Linux | Null audio output; physical drivers were not checked |
| Profile music | 0.21.0–0.21.1 added, removed and undid profile audio through an owned Linux session | Other desktop OS account flows were not repeated |
| Sending audio | 0.24.0 confirmed native upload/copy/forwarding, caption handling and permissions | Authenticated Android sends were not checked |
| Install and update | 0.15.3–0.15.5 exercised retained keys, MSI-to-EXE migration and uninstall on native Windows; later releases verified all signed assets | macOS notarization and Windows Authenticode remain unconfigured |
| Android retention | 0.25.0 updated a retained Android 15 emulator, preserving its catalog and 8,910 ms paused position | Emulator graphics/System UI faults are not physical-device performance evidence |
