# Verification record

## 0.13.0 — playback speed

The round button below Play opens the existing glass sheet with a linear
0.3×–4× slider, 0.05× adjustment and five quick choices. Changed speed has a
subtle button accent and a compact mini-player indicator. The service uses the
standard Media3 speed command and retains the default pitch; it coalesces speed
persistence over 300 ms without rebuilding or serializing the queue. No new
dependency, service, decoder or database migration was added.

Debug assembly, minified release assembly and full debug/release Lint passed.
Lint reported warnings without errors. Both APKs passed signature verification
and 16 KB ZIP alignment with the established certificate
`b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`.
Manifest: `app.spotygram`, versionCode 24 / versionName 0.13.0, minSdk 26,
targetSdk 36. SHA-256: ARM64
`4c4cdd965b83df5821ec793a82bccd0b78a10ceebaf50aa20ef584e438e0c325`,
x86-64 `7874b361e3d1f024c49c8d899072d5f4b33ec8faa8b6ad916476ec1209afa5e2`.

The signed x86-64 update installed over 0.12.1 without clearing data. The
installed `base.apk` hash matched the final artifact. Before/after SQLite checks
preserved all three track IDs, titles, artists, paths and favorite marks, both
playlists and all three memberships; schema remained 4 and integrity was `ok`.

Manual checks used the existing Android 16 emulator and local recordings:

- MediaSession reported PLAYING at 4× and 0.3× on Fur Elise, including positions
  59,319 and 63,949 ms. The plus button changed 1.5× to 1.55×; settled speed was
  present in the service preference. Slider taps and fine adjustment worked.
- Selecting The-Entertainer retained 1.55×; Home left the same playback speed in
  the active session. Force-stop/relaunch restored the indicator and queue
  without automatically starting playback. The debug app also resumed at its
  restored 1.5×. The 1× preset returned to normal and removed the accent/indicator.
- Actual screens were inspected at 400 dp in Russian/English and light/dark,
  at 320 dp with font scale 1.3, and at 1067×667 dp. All five presets fit at the
  narrow width; 3.95× fit in the larger circle and lower actions used icons.
  The mini-player retained its title width, with altered speed on its second row.
  Wide playback kept separate artwork/control columns and a bounded sheet.
- Cross, outside tap and Back dismissed the panel. Minimum/maximum states
  disabled their respective adjustment buttons. A tap above the thin visible
  slider line still changed speed, confirming the expanded touch area.
- The inspected AndroidRuntime, ExoPlayer and playback-error logs were empty.
  The compact sheet handle corrected excess height from Material's default
  handle spacing. The player screenshot and new EN/RU speed-panel images were
  captured from the final signed APK for documentation.

The emulator's recurring System UI and Pixel Launcher ANRs were dismissed before
app checks or after display changes. Its Mesa renderer used Xvfb; builds and
emulator ran sequentially under two-core limits and bounded memory. Display was
restored to 480×1040/192 dpi, font scale 1.0, Russian and dark, speed 1× and
playback stopped; the temporary debug speed preference was removed. The emulator
and Xvfb were stopped. No test files or scaffolds were added. Physical ARM64
behavior, authenticated Telegram playback, acoustic pitch quality, large-catalog
performance and phone power use were not measured.

## 0.12.1 — mobile action layout and solid RGB mark

The music toolbar now has aligned filter and action rows. A flexible source
selector sits beside 48 dp local-file and sort controls. Play has a labelled
primary button; phone layouts use icons for shuffle/random and selection.
Compact playlists accommodate their additional add-tracks action. The selection
bar no longer scrolls horizontally: playlist, download and removal actions stay
visible. On device changes the heading as well as the filter highlight.

One static vector supplies the black equalizer mark with separate saturated red,
green and blue bars to the header, sign-in and adaptive launcher icon. The banner
uses the same geometry/colors. No branding shader, animation, new dependency or
database migration was added. The extra nested glass shell around playback was
removed; track lists remain lazy.

Debug/release assembly and full debug/release Lint passed. Lint reported warnings
without errors. Both signed APKs passed `apksigner verify` and 16 KB ZIP alignment
with the established certificate
`b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`.
Manifest: `app.spotygram`, versionCode 23 / versionName 0.12.1, minSdk 26,
targetSdk 36. SHA-256: ARM64
`c0cc97c8a3560e47bc3751de1131b2c3fbc5774970b85c7e32a0cc8cb074f012`,
x86-64 `e35517353c5c5094b70ba88091bed5aa7aefc6edadbf2e8aa3c86d8ccdafbfc2`.

Manual checks used the existing Android 16 x86-64 installation and local
recordings. The signed update installed over 0.12.0 without clearing data; the
final installed `base.apk` hash matched the final x86-64 artifact. Before/after
SQLite comparisons preserved the IDs, titles, artists, paths and favorites of
all three recordings, both original playlists and all three memberships.
Schema remained 4 and integrity was `ok`. Primary and random playback actions
started local music; MediaSession reported PLAYING and then PAUSED at 18,677 ms.
The inspected AndroidRuntime/ExoPlayer error logs were empty.

Actual screenshots were inspected in Russian/English, light/dark, at 400 dp,
360 dp, 320 dp with font scale 1.3, and 1067×667 dp. Checks covered normal,
disabled and selected buttons, local filtering, selection of all/individual
tracks, the add-to-playlist picker, the playlist add-tracks action and removal
confirmation with cancellation. At 320 dp, search with the keyboard open kept
the controls and a matching track visible; a no-results query disabled playback.
The phone sign-in field and numeric keyboard were inspected at 400 dp without
entering or submitting a phone number. The RGB mark was inspected in the header,
sign-in, the actual circular launcher mask and the Chromium-rendered SVG banner.
Four affected documentation screenshots were refreshed from the signed app.

Iteration fixed clipped phone action labels, the selection download action
falling outside the narrow viewport, and the default source label truncating at
increased text size. The emulator's recurring cold-boot System UI ANR was closed
before app checks. Builds/emulator ran sequentially with two-core limits and
bounded memory. Display was restored to 480×1040/192 dpi, font scale 1.0, Russian
and dark; playback remained paused and the emulator was stopped. No test files
or scaffolds were added. Physical ARM64 behavior, authenticated Telegram flows,
large-catalog performance, phone power use and the full updater flow were not
retested.

## 0.12.0 — rebuilt Liquid Glass system

The control layer uses Haze Glass 2.0.1 with explicit background/content captures,
edge refraction, full diffusion on dense surfaces, spectral dispersion and
lighting. The near-white/near-black appearances share geometry. The old grid,
rainbow bands and glass collection-row shells were removed. Incident light is
bounded to small areas; artwork contributes a reflection near the player.
Press/focus optics run on interactive controls. No per-track effect or animated
background was added; there is no database migration.

Debug and minified release assembly, full debug/release Lint and release
lintVital passed with Gradle 9.3.1, AGP 9.1.1, Kotlin 2.4.20 and SDK 37.
Lint reported warnings, with no errors. Both APKs passed `apksigner verify`
with the established certificate
`b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`.
ZIP alignment and LOAD segments of both packaged native libraries in both ABIs
were checked at 16 KB. Manifest: `app.spotygram`, versionCode 22 / versionName
0.12.0, minSdk 26, targetSdk 36. SHA-256: ARM64
`1b89a8e055340a39e5950e8b4e3ab65775007354784ef09f4da5eccba1ec61ae`,
x86-64 `cf1aed76e0d788abcaa65b5d2441f98da78db089a48d95a37ddfccf57ddc56c0`.

Manual Android 16 x86-64 checks used the existing local recordings. The signed
APK installed over 0.11.1 without clearing data; subsequent iterations retained
that installation. The installed final `base.apk` hash matched the final
x86-64 artifact. Before/after SQLite comparisons preserved all three recording
IDs, titles, artists, paths and favorites, both original playlists and all three
memberships; schema was 4 and integrity was `ok`. A temporary one-track Glass
playlist exercised creation from the keyboard and deletion; it was removed.
Unliking and Undo restored the original favorite. Final local playback reached
MediaSession `PLAYING` at 156,294 ms and later `PAUSED` at 28,558 ms after a
track transition. The final inspected AndroidRuntime/ExoPlayer error logs were
empty.

Full native screenshots were inspected in Russian and English, light and dark,
at 400 dp width, 320 dp with font scale 1.3, a short 320×517 dp viewport and a
1067×667 dp wide layout. Checks covered library, favorites, selection, playlists,
full/mini player, queue, source controls, playlist menu/confirmation, settings, empty
cache and notices. Artwork/row colors visibly changed the dock during scrolling;
final list actions remained reachable. Search and playlist creation were checked
with the keyboard open at narrow width; the phone sign-in field, numeric
keyboard and Continue were inspected without entering/submitting a phone number.
Wide navigation used the side rail; the player used separate artwork/controls.
The README screenshots were refreshed from the installed signed app. The SVG
banner was rendered in Chromium and inspected.

Iteration corrected white-point washout, sharp text leaking through dense glass,
a clipped artwork reflection, narrow filters, player action wrapping, keyboard
fields consuming the list, sheet glass being outside the native placement
transform, and a standard Snackbar's extra grey layer. No test files, fixture
suite or extra media were added.

The emulator's recurring cold-boot System UI ANRs were dismissed before app
checks. Its SwiftShader backend also exited with a host renderer fault; later
checks used the host Mesa renderer through Xvfb. Builds and emulator ran
sequentially with two-core limits and bounded memory; the emulator was restored
to 480×1040/192 dpi, font scale 1.0, Russian and dark appearance, left paused and
stopped. Physical ARM64 behavior, phone frame time/power use, a large catalog,
authenticated Telegram playback and the complete updater flow were not checked.
Full optics require Android 13+; older-Android tint/lighting/rim fallback was
source-reviewed, not exercised on an older emulator.

## 0.11.1 — restore the 0.10.3 app

The app source and build configuration match the published `v0.10.3` tag,
except versionCode 19 → 21 and versionName 0.10.3 → 0.11.1. Architecture,
dependency documentation and promotional images were restored to that version;
the build guides now name the actual required Build Tools 35.0.0. Release and
verification history remain available. There are no database migrations.

The minified release assembly and full release Lint passed with Gradle 8.14.3,
AGP 8.11.1, Kotlin 2.2.10 and SDK 36. Lint reported warnings, with no errors.
Both signed APKs passed `apksigner verify` and 16 KB ZIP alignment using the
established certificate
`b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`.
Manifest: `app.spotygram`, versionCode 21 / versionName 0.11.1, minSdk 26,
targetSdk 36. The packaged x86-64 TDLib library is byte-identical to 0.10.3.
SHA-256: ARM64
`0c4e367330ae57e3a0b2866f8aed0082903ae4fe86fa7c5e1a6e3460436bd6bb`,
x86-64 `4ab7e75a1e09caf80f42a27c8d94090b8a04b74bc5fa498f01dcd6ea67f1ce81`.

On the existing Android 16 x86-64 emulator, the signed APK installed over
0.11.0 with `adb install -r`, without clearing data. The installed `base.apk`
hash matched the final artifact. Before and after installation, SQLite schema
was 4, integrity was `ok`, and all three local recordings, Night/Road playlists
and three memberships remained. A tap on Fur Elise started local playback:
MediaSession reported `PLAYING` at 11,918 ms and later `PAUSED` at 15,318 ms.
No AndroidRuntime or ExoPlayer error appeared in the inspected logs.

Actual Russian dark library/player/playlists and light settings/player screens
were visually inspected at 480×1040/192 dpi against the restored appearance.
At 320 dp width with font scale 1.3, playback controls remained accessible and
scrolling exposed the lower player actions. The restored interface retains an
existing limitation: the Russian Queue label wraps awkwardly at that size.
The emulator displayed its recurring cold-boot System UI ANR, which was closed
before the app checks. Display size, font scale and dark appearance were
restored, playback left paused and the emulator stopped. Builds and emulator
ran sequentially with two-core CPU limits and bounded memory. No test files
or fixtures were added.

Physical ARM64 installation, authenticated Telegram playback/session retention,
device performance and the complete in-app download/installer flow were not
retested. The data-preserving installation evidence covers the local emulator.

## 0.11.0 — Liquid Glass control layer

Debug and minified release assemblies, debug/release Lint and release lintVital
passed with Gradle 9.3.1, AGP 9.1.1, Kotlin 2.4.20 and SDK 37.0. No native
library rebuild or test files were needed. The signed ARM64 and x86-64 APKs
passed `apksigner verify` and 16 KB ZIP alignment with the established
certificate `b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`.
The manifest reports `app.spotygram`, versionCode 20, versionName 0.11.0,
minSdk 26 and targetSdk 36. SHA-256: ARM64
`072381586e83a45924211c4456a51ff3fe660c2ec2f86a9231e960ebd245285c`,
x86-64 `4a138cd87a819252340b70a0fa67645f1ec5f912ab1305a2c4e7c4200b9af682`.

Android 16 x86-64 manual checks used the existing imported recordings and
playlists. The final signed APK installed over the prior release without
clearing data; the installed `base.apk` SHA-256 matched the x86-64 artifact.
The local library showed three tracks and the Night/Road playlists showed one
and two tracks. The English light player and playlists, English dark library,
favorites, selection and settings, and Russian dark player were inspected as
actual screens; the documentation screenshots were refreshed from this APK.
The SVG banner was rendered in Chrome and inspected.

At 320 dp width and 517 dp height, the signed library and player were
inspected with rows passing behind the floating controls. A visible-text bleed
through the dock in the first Haze Glass pass was corrected with a denser
material tint and protective veil; the final control labels remained readable.
The compact player title ellipsized rather than leaving an orphaned final
letter, Play was available without scrolling, and scrolling revealed the
player's lower actions. The queue sheet, selected-track controls, settings
theme switch, source selector menu and empty music-cache screen were opened.
From the final release, a local track reached MediaSession `PLAYING` at
55,659 ms and `PAUSED` at 55,929 ms. No Spotygram AndroidRuntime crash was
found in the inspected crash logs.

The emulator repeatedly displayed cold-boot Pixel Launcher and System UI ANR
dialogs; changing its display size also produced an app input ANR during
recreation. These were dismissed or followed by a fresh launch at a fixed
size before screen checks. This does not establish physical-device graphics,
frame time, power use, authenticated Telegram playback or a large catalog.
Haze's optical output on a physical ARM64 device was not measured. The
emulator was returned to 480×1040 at 192 dpi and stopped.

## 0.10.3 — restore the 0.10.1 appearance

The source and documentation from the withdrawn 0.10.2 redesign were reverted.
Compared with the 0.10.1 source tree, the app changes only versionCode 17 → 19
and versionName 0.10.1 → 0.10.3. This higher version permits a data-preserving
update over an installed 0.10.2; Android does not accept a normal downgrade to
the original 0.10.1 APK.

The minified release assembly and release lintVital passed. Both signed APKs
passed `apksigner verify` with the established certificate
`b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`
and 16 KB ZIP alignment. Manifest: `app.spotygram`, versionCode 19 /
versionName 0.10.3. SHA-256: ARM64
`8289285cc362fdccaf231953c4634344964d83531d86571ff5bc5f4fbaa3223d`,
x86-64 `97df1737e7c25859f7569d87d228713efe4244dc3dd143124c55b94e291fd6cd`.

The signed x86-64 APK installed over the emulator's 0.10.2 without clearing
data. The installed `base.apk` hash matched the release artifact. The prior
rainbow/glass library, favorites and playlists appeared again; three local
recordings and the Night/Road playlists remained. A local recording reached
MediaSession `PLAYING` with advancing position and then `PAUSED`. No
`app.spotygram` AndroidRuntime crash appeared in the inspected logs. The
emulator showed its recurring System UI and launcher ANRs during cold boot;
these were dismissed before the checks. Playback from an authenticated
Telegram chat and installation on a physical ARM64 phone were not checked.
No test files or fixtures were added.

## 0.10.1 — balanced RGB palette

Debug and minified release assemblies, full debug/release Lint and release
lintVital passed. Both signed APKs passed `apksigner verify` with the existing
certificate and 16 KB ZIP alignment. Manifest: `app.spotygram`, versionCode 17 /
versionName 0.10.1. SHA-256: ARM64
`19e5e9a297d8be37494c6ffe979c506f641b9bf85756d22b784f2dfb3e24a25e`,
x86-64 `49a2f126a085dd02541aa143d541561ddf115adf59a32ed6221f2c24d77804a1`.

The SVG banner was rendered in Chromium and inspected. Manual Android 16 x86-64
checks used the existing local demonstration recordings:

- Debug library, settings and player were visually inspected in both appearances
  at 480×1040/192 dpi. At 320 dp width and font scale 1.3, text, transport
  controls and the two-line mini-player title remained readable. The full
  player still scrolls to its lower actions. The full-spectrum light bands,
  neutral glass and separately colored artwork/favorites were compared with the
  reported violet-heavy phone screenshot.
- Changing emulator density while the debug Activity was running produced an
  input ANR. Its captured main thread waited in Android
  `HardwareRenderer.setStopped` during Activity recreation, while RenderThread
  was parked; a force-stop and launch at the fixed density restored interaction.
  The mini-player and full player then opened normally. This does not establish
  that every device will handle a live density change without delay.
- The signed x86-64 APK installed over 0.10.0 without clearing data. The
  installed `base.apk` SHA-256 matched the x86-64 release artifact. Three local
  recordings and the Night/Road playlists remained visible. Selecting a local
  track reached MediaSession `PLAYING` with advancing position; pause reached
  `PAUSED`. The English light player/playlists and English dark library/settings
  plus Russian dark player were captured from the installed version and visually
  inspected. The four primary images appear in the README; the settings capture
  was updated alongside them.
- The emulator showed its recurring cold-boot System UI and Pixel Launcher ANRs
  before the installed release became responsive. Density, font scale, language
  and appearance were restored after verification, and the emulator was stopped.

No authenticated Telegram session, physical ARM64 device, large distinct-track
catalog or device power/performance measurement was checked. Track lists remain
lazy; the background is one static canvas rather than a shader per row. No test
files or fixtures were added.

## 0.10.0 — Liquid Glass redesign

Debug assembly, minified release assembly, full debug/release Lint and release
lintVital passed. The signed ARM64 and x86-64 APKs passed `apksigner verify` and
16 KB ZIP alignment. Both use the established certificate
`b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`.
Manifest: `app.spotygram`, versionCode 16 / versionName 0.10.0. SHA-256:
ARM64 `053ee14a5b2171896f7e791090a9d7c61f6c5f5851175a81a3dbe2d45d2a375a`,
x86-64 `f5558f52690b7a3d039e35f8316d2af45fec7f960e6168adbb55e91e1187fca9`.

Manual Android 16 x86-64 checks used the existing local demonstration recordings
and playlists, without a Telegram login or new test fixtures:

- The debug build was inspected in both appearances. A first pass revealed black
  inherited text in dark mode and an oversized empty playlist surface; both
  were corrected and inspected again. Switching appearance inside the open
  settings sheet left its text readable with no underlying content bleeding
  through. Search with the keyboard open and track selection were exercised.
- At 320 dp width and font scale 1.3, the library, selection controls, mini-player
  and full player were inspected. The mini-player was revised to give the title
  two lines at that width. The full player remains scrollable for lower actions.
- The signed x86-64 APK installed over the existing 0.9.2 without clearing data.
  Three local recordings and the Night/Road playlists remained visible. Local
  playback reached MediaSession `PLAYING` with an advancing position; pause
  reached `PAUSED`. The installed package reported 0.10.0. The final library,
  player, playlists and settings captures were visually inspected and the four
  README images were refreshed from that signed APK.
- After replacing the last green launcher/window resources, the final x86-64
  artifact installed over that 0.10.0 without clearing data. The installed
  `base.apk` SHA-256 matched the x86-64 digest above. The library still showed
  three recordings; choosing a different local track again reached `PLAYING`
  with advancing position and then `PAUSED`. The new SVG banner was rendered
  and visually checked in Chromium. Full debug/release Lint and both assemblies
  were rerun after the resource change.
- A 1200×800 display showed the 720 dp centered content and bottom controls.
  The emulator process exited during the following wide-screen navigation, so
  only the initial wide layout was observed. Its cold starts also produced
  recurring System UI ANR dialogs, including with a bounded 4 GB guest; these
  were distinct from the responsive Spotygram screens after dismissal.

No physical ARM64 device, authenticated Telegram account, large distinct-track
catalog, backdrop distortion benchmark or battery measurement was checked.
Music-row drawing and the source-count projection were reviewed in source; no
claim about phone performance follows from these emulator captures. No test
files or scaffolds were added.

## 0.9.2 — local-first chat discovery

The picker previously used only `searchChatsOnServer` for a nonempty query and
replaced its whole list with that response. Typing also cancelled directory
loading. The native TDLib checkout matched the pinned build revision; its
offline `searchChats` API and chat-list update contracts were reviewed before
the change. Private channels are not explicitly filtered out by Spotygram.

Manual Android 16 debug checks used temporary known-chat entries in memory and
debugger-completed request futures. They exercised the actual picker, Kotlin
request/merge code and SQLite source selection, not real Telegram discovery:

- The main/archive loading path completed separately from local list reads.
  After the final event deduplication change, an unchanged revision did not
  cause a second initial local read.
- A local match stayed visible after the online search returned no IDs. With
  the keyboard open, the row, clear-search control and Done remained usable.
- Overlapping local/server IDs produced one row per ID. Advancing the native
  revision with an additional diagnostic entry updated the open query without
  another keystroke; all three distinct results appeared.
- Two rows could be selected and deselected, with the updated selected-count
  label. The temporary selections were removed again.
- A supplied online error kept the local row visible alongside Retry. An empty
  local and online result displayed the new explanatory empty state.
- RU/light and EN/dark layouts were inspected. At 320 dp width and font scale
  1.3, the keyboard left too little space while the permissions explanation was
  fixed above Done; that explanation was moved into the scrolling list. The
  final build was inspected at that size with the keyboard open: the entire
  diagnostic chat row and checkbox were visible, with Done above the keyboard.

Final debug/release assembly and both Lint tasks passed. EN/RU keys and format
arguments matched for 284 strings; documentation links resolved. Both release
APKs passed signature and 16 KB ZIP alignment checks with the existing signing
certificate. Manifest: `app.spotygram`, versionCode 15 / versionName 0.9.2.

The signed x86-64 APK was installed over 0.9.1 without clearing data. After the
layout iteration, the emulator was returned to the same signed 0.9.1 with a
data-preserving downgrade, and the final 0.9.2 was installed over it again.
The installed final APK hash matched the release artifact; all three recordings,
two playlists and three memberships remained, with SQLite integrity `ok`.
Final local playback reached PLAYING at 28695 ms while the Activity was in the
background. Returning kept the same installation; playback was left paused at
28978 ms. No AndroidRuntime or ExoPlayer error was found in the final inspected
logs.

The debug catalog ended with its original four recordings, one playlist and
three memberships, no diagnostic sources, and integrity `ok`. Temporary
auth/chat state was removed by process exit; the diagnostic Saved Messages
preference was removed, theme and last destination restored, and density, font
scale and locale returned to their prior values. Debug-app selection and the
JDWP port forward were cleared. The emulator was stopped.

Final SHA-256: ARM64
`51bf87f6b52e561a4e862d41e006d0c73093d5f6ad112e4d1db8e28707e01e85`,
x86-64 `dad1626c1ca9d465e2e81acfbfbbdba143ad4b11a54a8157cb17584d74bdc864`.

Typing cancellation, lifecycle-scoped stop/restart, late-result isolation,
secret-chat exclusion, real offline TDLib lookup and native new-channel delivery
were source-reviewed; delayed authenticated responses were not forced. The
debugger's early main-thread pauses caused diagnostic input ANRs, and the
emulator also showed its recurring boot System UI ANR. These are separate from
the ordinary signed-app playback checks.

An isolated in-memory Telegram sandbox client confirmed test mode on DC 2,
but the documented five-digit code returned `PHONE_CODE_INVALID`. No production
account or another application's session was used. Authenticated private-channel
search, physical-device behavior and battery measurements remain unverified.
No test files, test dependencies or persistent test harness were added to the
project; debugger data and screenshots stayed outside source.

## 0.9.1 — foreground player connection and stale row callbacks

Before changing code, the published 0.9.0 was reproduced with a working
mini-player and a **Connecting to player…** notice when another row was tapped.
Switching tabs restored row actions. Debug bytecode showed the local `::play`
reference storing its captured controller, while lazy-item caches used equality
that did not include those captured fields. This was a UI callback defect,
independent of remote audio delivery.

Final debug/release assembly and both Lint tasks passed. EN/RU keys and format
arguments matched for 276 resources; documentation links resolved. Signed
x86-64 0.9.1 installed over 0.9.0 without clearing data. Its installed hash
matched, all three recordings and Night/Road playlists remained, and SQLite
integrity was `ok`.

Manual Android 16 emulator observations:

- Cold launch restored one recording paused. One tap on a different Music row
  started it (PLAYING at 11671 ms), without switching tabs or using transport
  buttons to refresh the list first.
- Home left playback running in the same service process (position 37250 ms).
  Returning and tapping another row switched recordings (PLAYING at 11752 ms).
- Changing display density from 192 to 240 forced Activity configuration
  recreation while playback continued. Selecting a different row still worked
  (PLAYING at 11657 ms), with the service process unchanged. Density was restored.
- Only the debug PlaybackService component was temporarily disabled. Initial
  connection made two attempts and stopped. Each of two explicit song taps
  initiated another pair, with no intervening retry loop. The final Russian
  message said to tap a song to retry; it did not remain in the connecting state.
  Restoring the component to its default state caused Android to terminate that
  debug process; reopening and choosing another recording reached PLAYING at
  6207 ms. This was not a same-process hot-enable recovery test.
- With the debug app backgrounded and paused, `am stopservice` removed its
  PlaybackService (the service dump showed none). Returning/reopening and
  choosing a song recreated the service and reached PLAYING at 8826 ms.
- After changing duplicate mode in the UI, grouped unlike cleared two marked
  aliases and Undo restored exactly both. Switching back to separate entries
  and removing the extra mark restored the original favorite. Debug integrity
  remained `ok`, and Hide duplicates was left enabled.

The service component override, diagnostic accessibility timeout and the
Activity-finish developer setting left from diagnosis were cleared. Playback
was paused and the emulator stopped. Inspected logs contained only the six
intentional debug connection failures; no app/player crash was observed. The
emulator's recurring boot System UI ANR was handled separately. The ten-second
timeout, stale-future rejection, latest-pending-selection replacement and its
cancellation on exit were source-reviewed, not forced with delayed Binder
responses. Physical-device overnight/OEM battery behavior and authenticated
Telegram playback were not measured. No test files or fixture suite were added.

Both APKs passed signing and 16 KB alignment with the existing certificate.
VersionCode 14 / versionName 0.9.1. SHA-256: ARM64
`dec65717b4e28cc04835453ebcd6949fa9d7f0af19ad1343a56dfdee6951e587`, x86-64
`259deb176f0a1ae9ed6e503dfadfe8a956fc21b519b3159248704e6e6fff7ede`.

## 0.9.0 — reversible duplicate hiding

Debug/release assembly and both Lint tasks passed. EN/RU keys and format
arguments matched for 276 resources; documentation links resolved. Manual checks
used the existing Android 16 emulator and its four-row debug library, including
three separate imports of the same public-domain demonstration recording.

- On first launch, the default-on setting produced two songs from four records.
  The retained representative displayed the favorite mark from another alias.
- Unliking removed the group mark. After re-adding a favorite, removing it and
  pressing Undo restored exactly that previous track ID, not every duplicate.
  The emulator's interactive accessibility timeout was temporarily extended to
  make the snackbar action inspectable, then returned to its default.
- Turning the switch off showed all four records. Force-stop/cold launch retained
  the false preference and four rows. Re-enabling grouped them again. On device
  and Settings both showed two songs; total retained storage remained 20 MB.
- Play all from the grouped local list produced two logical queue IDs and
  PLAYING at 5610 ms with two physical media items. The existing playlist kept
  its three memberships.
- In a temporary edit of an existing demonstration row, changing duration from
  213 to 214 seconds kept that row separate (three visible songs). Giving two
  existing rows the same diagnostic stable file key joined them despite that
  metadata difference (two songs). Searching for the demonstration title then
  showed one song. These are local grouping checks, not real TDLib identity
  delivery or an authenticated source-chat test.
- The original database was restored: four imports, no diagnostic file keys,
  the original favorite, three playlist memberships, integrity `ok`. Audio files
  were unchanged. The Russian light Settings screen was visually inspected.

The signed x86-64 APK installed over 0.8.1 without clearing data. Its hash matched;
all three distinct recordings and Night/Road playlists remained with integrity
`ok`. English Settings showed Hide duplicates enabled by default. No inspected
Spotygram/player error was observed; the emulator did exhibit its boot-time
System UI ANR and a separate Nexus Launcher crash. The emulator was stopped.
Both APKs passed signature and 16 KB alignment checks with the existing key.
SHA-256: ARM64
`f5cc2e286cc6c2d4002f88c680e3a01aac616f6263b4720f634e134ab7c6f3bf`, x86-64
`9a4960f9ac598918a082c05d779b781c427c8df39585d2452c6ccdbdb38a3714`.
No test files or fixture suite were added. Metadata equality remains a heuristic;
physical-device and authenticated Telegram source-filter behavior were not
verified in this cycle.

## 0.8.1 — mini-player previous button

Release assembly and Lint passed. The signed x86-64 APK installed over 0.8.0
without clearing data; all three recordings and Night/Road playlists remained,
SQLite integrity was `ok`, and the installed APK hash matched the release copy.
In the bottom mini-player, Next moved from the demonstration recording to
another song (PLAYING at 8766 ms). A single tap on the new Previous button
returned to the demonstration recording through absolute-random history
(PLAYING at 5784 ms), rather than merely restarting the current song. The media
session kept three physical items. No inspected app/player errors were logged.

The mini-player was visually inspected at 320 dp width with font scale 1.3:
all three controls remained separate, and the long title ellipsized. Display
settings were restored, playback paused and the emulator stopped. Both APKs
passed signature and 16 KB alignment checks with the existing key. SHA-256:
ARM64 `2706c6b3c1f57c04a599fe930fec4f05ddbfaddc42f81d79bba256cda2e0b5f6`,
x86-64 `7361d908fd176bed2068e5055c9ec92a4bc77ab94f49cc50a126caca24e232e5`.
No test files were added. Physical-device and authenticated Telegram behavior
were not re-tested for this UI-only change.

## 0.8.0 — temporary playback copies

Initial debug assembly and Lint passed. Manual checks used the existing Android
16 x86-64 emulator and its debug catalog, without clearing app data. Migration
from SQLite 3 to 4 retained all four imported recordings and marked each saved;
the new journal was empty and the setting was off by default.

For UI/state checks only, two existing public-domain demonstration rows were
temporarily given one shared local path, remote-shaped identity and journal
entry. No audio was copied into source, and no test files or fixture suite were
created. Cold launch kept the enabled preference and journal. Both rows showed
the temporary clock marker; **On device** and Settings excluded them (two retained
files, rather than four catalog rows). **Save on device** marked both aliases
saved, removed the journal entry and left the download queue empty.

Recreating that temporary state exercised local-file playback through the new
lease path. Media session reached PLAYING at 32573 ms with two physical items.
Turning the mode off during playback marked both aliases saved and emptied the
journal; playback continued at 92450 ms. Inspected AndroidRuntime/player logs
contained no errors. The original debug database was restored afterwards and
the mode left off. This is local UI/state evidence, not a native remote-transfer
or deletion test.

Source review added a post-journal-write pin/mode check, disabled-mode recovery
for a process death during that transition, a shared registration barrier before
manual cleanup waits for readers, and continued batching after deferred files.
The pinned TDLib source drops its local location before unlink and ignores the
unlink error in `FileLoadManager`; cleanup therefore persists the resolved path
before deletion and checks actual file absence before forgetting ownership.
Active leases retain stable identity, local path and runtime file ID so legacy
local readers remain protected even when a later message resolution learns a key.
Deletion/read exclusion,
queue-window protection, repeated-file aliases, deferred retry and global
cleanup coordination were reviewed in source. Authenticated cancel/delete,
redownload after eviction, network loss during streaming, process death during
native deletion and physical-device resource use remain unverified. An isolated
in-memory Telethon session reached official test DC 2 but the documented test
phone/code combination returned `PHONE_CODE_INVALID`; no real account or existing
session was opened and no media was uploaded.

Final debug/release assembly and both Lint tasks passed after the native-source
audit. EN/RU keys and format arguments matched for 274 resources; documentation
links resolved. Both signed APKs passed signature and 16 KB alignment checks with
the existing certificate. Manifest: `app.spotygram`, versionCode 11, versionName
0.8.0, minSdk 26. SHA-256: ARM64
`cb842ef4b7a26168e58767dc789233a473840a7bf28de5b8a81bdfc405d29b7c`, x86-64
`db032ed6a5af19f60e4554fbe17357bfc97b087411304bbed7d9c22b1115292f`.
The final signed x86-64 APK installed over 0.7.0; its installed hash matched.
All three recordings and Night/Road playlists remained, schema was 4, integrity
was `ok`, and the setting was initially off. The final English dark Settings
screen was visually inspected; local playback with the mode enabled reached
PLAYING at 10508 ms with three physical media items.
Seeking reached 170546 ms, Next changed recordings, and Previous retraced
absolute-random history to the demonstration recording. The window remained
three items, with no inspected app/player errors. Playback was paused and the
setting turned off afterwards. The emulator's recurring boot-time System UI ANR
was handled separately before installation; no Spotygram ANR was observed.
The final debug APK also recovered a manually journaled existing demonstration
row on cold launch with the mode off: saved became true, the local path remained
and the journal emptied. Restoring the original debug database left four imports,
four saved flags, an empty journal and integrity `ok`. The emulator was stopped.

## 0.7.0 — in-app download and installation

Debug/release builds and both Lint tasks passed after correcting an initial
Intent builder compilation error. EN/RU resource keys and format arguments
matched for 265 resources. Both release APKs passed signing and 16 KB alignment
with the existing certificate. Manifest: `app.spotygram`, versionCode 10,
versionName 0.7.0, minSdk 26, with `REQUEST_INSTALL_PACKAGES` and a non-exported
update FileProvider. Final APK hashes: ARM64
`d2e3160adfdced8314664a76d5e40feb29b9c668b32927b8b81c50e07015ec61`, x86-64
`495bb02f1c5a304acd720087a11e431b0909f8b008b9d2daaea7cee92b2e6e2a`.

For the self-update check, the same installer source was also built privately
with versionCode 9/versionName 0.6.1 and the existing release key. This temporary
version change was restored before committing. The private x86-64 bridge APK
(`2a7dfe5990f7604cd76dc8cc61f0a30a503874e9caacc57d87732f546595864a`)
installed over the emulator's existing 0.6.1 without clearing data. It is not a
release asset. This arrangement lets a real older installed version download
and install the final newer APK instead of pretending a same-version install
is a self-update. No test files or fixture suite were added to the project.

The complete APKs were first published as a prerelease, which stayed out of the
ordinary latest endpoint. Its real asset metadata was temporarily placed in the
bridge's existing update preferences to exercise download/installation without
announcing the release to other clients. The actual checks on Android 16 x86-64:

- The library banner offered **Download and install**. With network disabled,
  one DownloadManager request was created. Force-stop/relaunch retained the same
  ID (33); cancellation removed that system row and left no APK behind.
- For the next request, only the expected hash in private pending-download
  preferences was changed to zeros. The real APK downloaded, failed verification,
  and was deleted along with the DownloadManager record (34). No installer opened.
- Retrying with the real metadata verified the APK and opened Android's
  unknown-source permission screen. Back without enabling permission returned
  to **Install update** with guidance, not another permission prompt.
- After explicitly retrying and enabling permission, returning opened Android's
  **Do you want to update this app?** dialog. Cancel, Home and return did not
  reopen it. The private ready APK still matched the final release SHA-256 and
  could be reused without downloading.
- The release was then made ordinary/latest. Clearing only the bridge's cached
  release-check result and attempt timestamp caused a real automatic GitHub
  check on launch. It saved the new ETag and exact 0.7.0 asset metadata and
  offered **Install update** for the ready APK.
- Accepting the system **Update** action produced **App installed**. This final
  transition used the in-app FileProvider/Android installer, not `adb install`.
  On opening 0.7.0, the installed SHA-256 matched the published x86-64 APK, pending
  installation preferences were empty, and the private update cache was empty.
- All three local tracks and Night/Road playlists remained; SQLite integrity was
  `ok`. Local playback reached PLAYING at 194419 ms with three physical media
  items and no inspected app/player errors. Manual checking in the installed
  0.7.0 showed the current-version result. The final Settings view was visually
  inspected, playback paused, network restored and emulator stopped.

Wrong-package/certificate rejection and download-notification click routing were
source-reviewed, not separately simulated. Physical ARM64 installation, Android
8–15 permission screens, system reboot during transfer and real-account Telegram
flows were not exercised. No private bridge APK, user media or account state was
published; the 0.7.0 release contains only the final builds.

## 0.6.1 — Telegram file identity and local-copy validation

The supplied MP3 was inspected privately, outside the repository: 8,058,659 bytes,
MPEG Layer III, 320 kbit/s, 44.1 kHz stereo, duration 196.049 seconds. Full FFmpeg
audio decoding completed without errors. Importing the unmodified file through
Android's file picker in both the previous debug and signed 0.6.0 builds produced
PLAYING with correct metadata (observed positions 6020/6127 ms). The recording
and its artwork are not source or release assets.

Source inspection found that `Library.file` matched completion updates against
persisted numeric TDLib IDs, although the native FileManager assigns them from
its current in-memory ID table. After restart an unrelated completion could
replace a catalog path. The old copy validation checked existence only.

To reproduce the resulting condition without an account or extra catalog rows,
the existing debug import's path was temporarily pointed at its own extracted
cover. Its chat/file fields were temporarily set to a remote-shaped row with
a stale numeric ID. The original audio remained untouched. Old 0.6.0 kept this
entry local; playing it through the mini-player emitted
`UnrecognizedInputFormatException: None of the available extractors ... could
read the stream`, matching the reported failure class. This demonstrates the
wrong-path failure, not a captured TDLib collision on the user's phone.

After installing debug 0.6.1 over that state, the stale file ID became 0 and the
mismatched path became empty. All four catalog rows remained and SQLite integrity
was `ok`. Restoring the correct audio path retained the local copy; the temporary
chat/file changes were then restored to their original import values. The temporary
cover copies in the emulator were removed; the original recording was unchanged.

Debug/release builds and both Lint tasks passed, as did EN/RU key/argument checks
for 252 resources. ARM64 and x86-64 signatures and 16 KB alignment passed with the
existing certificate. Final APK SHA-256: ARM64
`de417541d1f8c532ff891e6e94c17e4ba0c85264bbae5a794db91a42b9a3eee9`, x86-64
`c82eacc179af48e12ea2ba0c24f3a675684161396c6f3d513a91b3c8249b9bba`.
The installed signed x86-64 hash matched. The update over 0.6.0 retained Night/Road
playlists and all three local recordings, including the supplied import. That
import's SHA-256 matched the original. Playback after seeking reached 162972 ms
of 196048 ms with three physical media items, without app/player errors. Playback
was paused and the emulator stopped.

The personal-chat URI parameters were checked against Telegram Android's
LaunchActivity; server-message conversion was checked against TDLib's
`MessageId::SERVER_ID_SHIFT`. The personal-chat menu action was exercised with
the temporary debug row, but actual navigation inside an authenticated Telegram
client was not verified. Actual TDLib collision delivery, remote redownload,
the reporting phone's local database and physical ARM64 execution remain outside
this check. No production Telegram session was accessed and no test suite was added.

## 0.6.0 — quiet release checks and public distribution

Debug compilation/packaging and Lint passed. On the existing Android 16 x86-64 emulator, Settings showed the Russian automatic-check switch and manual action. The switch persisted across a force-stop/relaunch, and the attempt timestamp stayed unchanged while automatic checks were disabled. A repeated manual press showed the recent-check message without changing the timestamp. A later manual check while automatic checks were disabled made a new attempt and displayed a recoverable GitHub error (the repository was still private); no automatic error banner/snackbar appeared.

For the newer-release UI only, the existing debug update preferences temporarily held version `0.6.1`; no remote release, fixture file or music data was created. The banner was visually inspected. Its close action stored dismissal for that version; after force-stop/relaunch the banner remained absent and the attempt timestamp was unchanged. The cached offer remained accessible in settings. Temporary version/dismissal values were removed afterwards. This checks cached-offer UI, not discovery of a real newer release. Runtime app/player error logs were empty.

Before visibility change, all 168 reachable Git blob objects were checked for the current Telegram API credentials and common private-key/token patterns, with no matches. Sensitive session/signing filenames were absent. GitHub had no issues/PRs, Actions runs or Actions artifacts. Existing APKs contain the app's API credentials by design; account sessions and signing private keys are not release assets. This is a scoped publication check, not a claim of exhaustive secret detection.

Minified release compilation/packaging and release Lint passed. Both ABI signature checks and 16 KB zip alignment passed with the existing certificate. EN/RU keys and format placeholders matched for all 249 resources. ARM64 SHA-256: `fa54b37f24bb03f5d053718ee7a3b35e1f94eaa6e28e80c5e3661fd2adbb87a4`; x86-64: `e7cacb880816cc5f21d8636fce31f0b7ebad51fe5b0da63fe0e86bcc08d7fabe`. GitHub repository visibility was changed to public by owner authorization, verified through an unauthenticated HTTP 200 response, and updated in BURO through its draft workflow.

The final signed x86-64 APK installed over 0.5.1; its installed SHA-256 matched the release asset. Night/Road playlists and two local tracks (7 MB) remained. Local playback reached PLAYING at 25161 ms with two physical media items and no logged app/player errors. Playback was paused afterwards.

Release assets were uploaded to a GitHub draft, checked against local SHA-256 digests, then published as the ordinary latest release `v0.6.0`. The unauthenticated latest endpoint returned HTTP 200 with both uploaded APKs. The installed release's manual check showed “You have the latest version.” and persisted version `0.6.0` and the matching ETag. An independent conditional HTTP request using that ETag returned 304 with no body. A later in-app manual check with the cached ETag also returned the current-version result; an immediate force-stop/relaunch retained the same attempt timestamp. The final dark settings view was visually inspected and captured in `docs/screenshots/updates-dark.png`. Both emulator and build units were stopped.

GitHub 403/429 cooldown, malformed/oversized responses, clock rollback and numeric version boundaries were source-reviewed, not simulated as passed runtime cases. Discovery of a genuinely newer public version and ARM64 physical-device execution were not exercised; the cached newer-version banner check is distinguished above. No account/session, fixture suite or extra audio files were used for update verification.

## 0.5.1 — unique local-file view

The reported mismatch was confirmed in source: Settings used `distinctBy(path)` while the On device list counted every message reference. Verification used the existing debug imports in the Android 16 x86-64 emulator. No test files or extra audio files were created; builds and emulator remained sequential and resource-limited.

| Surface | Observation |
| --- | --- |
| Debug build | Debug compilation/packaging and Lint passed. |
| Manual reproduction | With the debug app stopped, one existing catalog row's path was temporarily pointed at another existing import's path. The catalog had three records and two referenced paths; the original files were not changed or deleted. One hidden alias, not the displayed representative, held the favorite flag. |
| Counts | On device displayed `2 трека`; Settings displayed `2 трека · 8 МБ`. The catalog retained all three records. The size is the referenced-file projection; the temporarily unreferenced original file was deliberately retained for restoration. |
| Aggregate favorite and Undo | The shared row showed a heart despite its displayed representative being unliked in SQLite. Removing the heart cleared the hidden alias's flag. Undo restored only the original `681640e6…` reference to liked=1; `76a007a7…` and `8e28a8a1…` remained 0. The row did not duplicate or change identity on the toggle. |
| Playing alias and deletion guard | The active reference was `681640e6…`, while the shared row represented `76a007a7…`. Visual inspection showed the shared row highlighted. Its Remove from device action returned “Сначала переключите текущий трек”; the file/catalog records remained intact. |
| Selection and queue | Select all showed `Выбрано: 2`. Starting the collection produced two queue IDs referencing two distinct paths, while the catalog still had three rows. |
| Restoration and boundary | The original path was restored. SQLite reported three records, three distinct paths, one favorite and `integrity_check: ok`; On device again showed three entries. All three imports share a title, demonstrating that title equality alone does not merge different files. |
| Logs | Inspected AndroidRuntime/ExoPlayerImplInternal error logs were empty. The emulator again showed its unrelated boot-time System UI ANR. |
| Final build/artifacts | Minified release and release Lint passed. Both ABI signatures and 16 KB zip alignment passed with the existing certificate. Manifest: `app.spotygram`, versionCode 7, versionName 0.5.1, minSdk 26. Installed final x86-64 SHA-256 matched `392d3f398a11090141a1df9580a77aaee068686669310877785bd67a3e7a8859`. |
| Signed update | Installed over 0.5.0 without clearing data. Night/Road playlists remained visible. Settings showed `2 tracks · 7 MB`; tapping its On device entry opened a list with `2 tracks`, empty search and All sources. Playback from that list reached PLAYING at 11456 ms without logged app/player errors. Playback was paused and the emulator stopped. |

The phone's exact 63/49 dataset was not accessed. This manually reproduced the same shared-path condition without opening a Telegram account. Native remote-file deletion and source-specific alias searches remain source-reviewed rather than authenticated end-to-end checks. Existing explicitly constructed playback queues retain their occurrences; a new queue launched from On device uses unique files.

## 0.5.0 — Telegram music cache cleanup

Manual inspection used the existing Android 16 x86-64 emulator and local demonstration imports. No unit/integration/smoke files, synthetic cache media or account fixtures were created. Build and emulator runs were sequential under the two-core resource limit.

| Surface | Observation |
| --- | --- |
| Debug build | Debug APK compilation/packaging and Lint passed. |
| Entry point and sizing | Settings showed three imported tracks occupying 12 MB. Its new Clear music cache entry opened the dedicated screen, which showed `0 Б` and “Кэш музыки пуст”: imports were not counted as Telegram cache. Refresh returned the same empty result. |
| Disabled empty action | Accessibility inspection identified the clickable clear-action parent as `enabled=false`; it was visually disabled. The empty screen did not offer an unnecessary destructive confirmation. |
| Layout | The Russian cache screen was visually inspected at normal scale and font scale 1.3. At 1.3 the title wrapped to two lines, explanatory text remained visible and the button fit above system navigation. Font scale was restored to 1.0. |
| Catalog integrity | After visiting/refreshing the screen, an in-memory inspection of the existing debug database returned `integrity_check: ok`, three tracks/all three local imports, one favorite, one playlist and three memberships. No application-data reset. |
| Playback regression | The existing random queue resumed the imported recording and the media session reached PLAYING at 82796 ms with three physical entries. AndroidRuntime and ExoPlayerImplInternal error logs were empty. Playback was paused afterwards. |
| Resource validation | All 237 EN/RU resources had matching keys and string format arguments. |
| API/source review | Storage statistics field names and `optimizeStorage` parameters were checked against the pinned TDLib schema. Deletion is restricted to audio/document file types; account storage and imported directories are not deletion targets. Cancellation, physical-player release, media-command guards, path reconciliation and finally-block recovery were reviewed in source. |
| Audit corrections | Source review caught a size-request cancellation path that could leave `loading` set; completion now clears it in `finally`. File reconciliation was changed from separate row commits to one transaction/prepared statement and one final library reload. These error/mass-deletion branches were reviewed, not simulated as an authenticated cleanup. |
| Final build/artifacts | Minified release and release Lint passed after the audit changes. Both ABI signature checks and 16 KB zip alignment passed with the existing signing certificate. Manifest: `app.spotygram`, versionCode 6, versionName 0.5.0, minSdk 26. Installed final x86-64 SHA-256 matched `17a3a8e4a1006079473d624999dd60a8f91ae93fa2f73b0ca872713f81a2dce8`. |
| Signed update/UI | The 0.5.0 update over 0.4.0 retained Night/Road playlists and the two imported recordings (7 MB). Final English cache/settings captures were visually inspected: cache size was 0 B, explanatory text named explicit downloads, and the clear action was disabled. |
| Final playback/logs | After visiting the final cache screen, the imported recording reached PLAYING at 17398 ms with three physical entries. AndroidRuntime/ExoPlayerImplInternal error logs were empty. Playback was paused and the emulator stopped. |

**Nonempty authenticated Telegram cache deletion, its positive confirmation/result flow, cancellation of an actual remote download, partial-delete errors and subsequent remote redownload were not exercised end to end.** The emulator has no authorized Telegram account and no cached Telegram audio. Local empty-cache UI checks and source/API review are not represented as proof of those scenarios. Physical ARM64 execution and device-specific storage behavior remain unverified locally.

## 0.4.0 — absolute random and full-height player

Verification used manual Android UI operations, media-session inspection, app-owned playback preferences and runtime logs. No test files or scaffolds were created. The existing debug catalog of three independent local imports was used; no synthetic large catalog was added. Builds and emulator remained sequential under the two-core resource limit.

| Surface | Observation |
| --- | --- |
| Debug build | Debug APK compilation/packaging and Lint passed. |
| Mode migration | The existing 0.3.0 debug queue opened as shuffle without repeats at its saved 9960 ms position. One mode-button press selected RANDOM without restarting the current recording. |
| Independent draw | The first random path was `[1,1]`. System Next reached cursor 1 and extended it to `[1,1,2]`: the same source occurrence genuinely played twice consecutively. |
| Bounded history and writes | After 40 additional system Next key presses, the saved history contained 34 indices and cursor 32. It included runs such as `0,0,0,0` and `1,1,1,1`. The full queue file's modification time remained unchanged (`1788902321`); the small position/history snapshot advanced instead. No distribution benchmark is inferred from this sample. |
| Back and restore | Previous returned cursor 32 → 31 without changing the retained path. Force-stop/relaunch restored that exact 34-index path, cursor 31 and RANDOM without autoplay. |
| Explicit next and automatic transition | Play Next inserted source occurrence 3 immediately after current occurrence 1 (`[1,3]`). Seeking the playing item to its end automatically reached cursor 1/occurrence 3 and prepared another independent draw (`[1,3,0]`). The media session remained PLAYING with three physical entries. |
| Repeat-one | In random mode, enabling repeat-one and seeking to the end retained cursor 1 and path `[1,3,0]`, restarting the same recording. Turning it off restored endless random traversal. |
| One-entry pool | Launching Random from the one-track Favorites collection and pressing system Next produced `[0,0,0]`, cursor 1 and repeat off; the app did not stop or crash because the pool had one entry. |
| Pool UI | The queue sheet displayed “Подборка для рандома”, its track count, explanation of independent selection, the prepared next song and the three selectable source rows. |
| Height and text | At 480×1040/192 dpi, bottom-action bounds ended at y=973 above the system navigation area. At 480×1200, the player spread its blocks over the taller viewport instead of leaving a large bottom void; the capture was visually inspected. At 480×1072 with font scale 1.3 (close to the supplied phone screenshot's aspect ratio), both bottom actions remained visible through y=1010. |
| Landscape | At 1072×480, scrolling exposed metadata, seek, all transport buttons, mode caption and both bottom actions together; their observed bounds remained inside the viewport. Size and font scale were restored afterwards. |
| Debug runtime logs | AndroidRuntime/ExoPlayerImplInternal error logs were empty during the checked scenarios. The emulator again displayed a boot-time System UI ANR outside Spotygram. |
| Final build/artifacts | Minified release build and release Lint passed. Both ABI signatures and 16 KB zip alignment passed with the existing certificate. Manifest: `app.spotygram`, versionCode 5, versionName 0.4.0, minSdk 26. Installed x86-64 SHA-256 matched `faeb039fd5229d60e540cb328d26d7f70a6291672631ab2be0340f83125eac91`. EN/RU key and format-argument validation passed for 221 resources. |
| Signed update and mode cycle | Release installed over 0.3.0 and restored Fur Elise at 73543 ms, non-playing, in shuffle mode. UI presses visibly cycled RANDOM → ORDERED → SHUFFLE → RANDOM. After another force-stop/relaunch it retained RANDOM, The Entertainer at 61253 ms and a three-item physical timeline without autoplay. |
| Signed background playback | From Fur Elise in RANDOM, system Next reached The Entertainer. With Wi-Fi/mobile data disabled and `mWakefulness=Asleep`, it remained PLAYING at 35028 ms with three physical items. Network settings were restored and playback paused. Release AndroidRuntime/ExoPlayerImplInternal logs were empty. |
| Release visual inspection | Updated the light-player and settings screenshots from 0.4.0, and captured the Russian dark player at 480×1072. All were visually inspected. The emulator was returned to 480×1040, English override and font scale 1.0, then stopped. |

The physical phone in the user's screenshot was not connected. These are layout and local playback observations in an x86-64 emulator, not claims about physical touch feel, ARM64 execution, battery life, remote Telegram playback or large-catalog performance. The existing 0.3.0 long-queue evidence remains below; this change's new random history was checked directly for its bounded size and lack of full-list writes.

## 0.3.0 — languages, navigation and bounded playback queue

Manual UI and runtime inspection used the Android 16 x86-64 emulator at 480×1040/192 dpi. Builds and emulator runs were sequential, with two-core CPU and bounded memory limits. Commands had short timeouts; long builds ran as asynchronously polled units. No test files, fixture suites or automation scaffolds were added to the project.

| Surface | Observation |
| --- | --- |
| Builds | Debug and minified release APKs, debug Lint and release Lint passed. EN/RU resource validation found matching keys and format arguments across 213 resources, including the track-count plural. |
| Artifacts | ARM64/x86-64 signature checks and 16 KB zip alignment passed. The signing certificate remains `b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`. Manifest: `app.spotygram`, versionCode 4, versionName 0.3.0, minSdk 26. Installed release SHA-256 matched `ebe51fe7fa78eef3061b38c81caca57e0a014b2f156f43998f1ffe69c1cab5d5`. |
| Update and migration | Signed release installed over 0.2.0 without clearing data. Both local recordings, both favorites and Night/Road playlists remained visible. The legacy queue restored The Entertainer at 93680 ms, non-playing. Debug legacy migration separately retained 29629 ms. |
| Navigation | The visible order is Chats, Favorites, Playlists, Music. After selecting Favorites, inspecting that screen, force-stopping and reopening the debug app, Favorites remained selected. |
| Languages | System-default English, explicit Russian and explicit English rendered translated screens, local-source labels and accessibility text. Observed plurals included `1 трек`, `3 трека` and `2 tracks`. Release Settings → Language opened Android's native selector with English/Russian entries. Requesting German through the locale-manager command produced English app text. The release override was returned to English. |
| Transition correction | An intermediate debug build crashed on system Next because timeline edits ran inside a Media3 transition callback. Window sliding was deferred until that notification completes. Repeating system Next twice then advanced logical cursor 0 → 1 → 2 without the error. |
| Repeat | With three independently imported local entries, repeat-all prepared a second pass and system Next crossed cursor 2 → 3. Repeat-one followed by a seek to the end retained cursor 3 and restarted the current recording. This checks behavior, not the statistical quality of shuffle. |
| Long queue | A temporary debug-only queue contained 10000 references to the three existing local recordings; no catalog rows or audio files were added. Shuffle produced 10000 distinct source indices. Three system Next actions reached cursor 3 while the media-session timeline stayed at 2–3 items. The full queue sheet opened and scrolled; removing a visible non-current occurrence left 9999 entries and playback continued. After pause/force-stop/relaunch, all 9999 entries and shuffle restored at 51324 ms without autoplay. The original debug queue/position were then restored and the two temporary backup files removed. |
| Final release playback | The minified release played The Entertainer, then system Next with shuffle enabled reached Fur Elise/sebion and PLAYING. With Wi-Fi/data disabled and `mWakefulness=Asleep`, playback advanced to 59755 ms. Network settings were restored and playback paused afterwards. |
| UI evidence | All six README screenshots were recaptured from the signed release and visually inspected, showing English and Russian, light/dark themes, the new navigation order and language setting. |
| Runtime logs | AndroidRuntime and ExoPlayerImplInternal error logs were empty after the corrected build's debug and release scenarios. Initial emulator startup still occasionally stalled installation/UI hierarchy inspection; timed-out operations were retried only after checking actual state. |

The long-queue check is deliberately synthetic queue state, not 10000 unique tracks or an authenticated large Telegram chat. It demonstrates bounded Media3/IPC representation and local queue operations, not large-catalog indexing speed, network playback, phone latency or battery behavior. The original user-reported phone crash was not accompanied by a device stack trace, so its exact cause is not claimed as reproduced. Physical ARM64 execution, Android versions below 13 and authorized Telegram flows remain unverified locally.

## 0.2.0 — four destinations and touch interaction

Verification remains manual UI operation and runtime inspection, without test files or automated test scaffolds. Android 16 x86-64 emulator, 480×1040 at 192 dpi. Compilation and emulator runs were sequential under a two-core CPU limit. The debug package is separate from the signed release package.

| Surface | Observation |
| --- | --- |
| Debug build | Debug APK compilation/packaging and Android Lint passed before manual checks and after the keyboard/landscape fixes. |
| Final build/artifacts | Final minified release build and release Lint passed. ARM64/x86-64 signatures and 16 KB zip alignment passed. Manifest reports `app.spotygram`, versionCode 3, versionName 0.2.0, minSdk 26. The installed final x86-64 APK matched SHA-256 `9428e0dda5404da946b10e1119d6de6ae9a43157a3a22e25872af1e57fa1139a`. |
| Release update | Signed 0.2.0 installed over 0.1.1 without clearing data. The three local tracks, one favorite and `Night` playlist remained; schema stayed at 3. Queue `[1,2,0]`, selected index 1 and 71262 ms restored in the non-playing state. |
| Favorites | Heart tap populated the separate Favorites destination. Removing the heart removed the row; the snackbar Undo restored it. The action did not start playback. |
| Long-press | Holding a song entered selection with `Выбрано: 1`, without starting playback. A second tap selected another song. Searching `nomatch` left `Выбрано: 2`; clearing search and adding to an existing playlist added both. |
| Playlist creation | Created `Evening` from one selected song; it contained that song immediately. Created empty `Road` through the full editor, then used its Add Tracks action. Batch-adding two more imported entries produced three members. |
| Membership uniqueness | Adding an already included song to `Road` again left three members, rather than four. |
| Drag reorder | Held the first drag handle, moved it to the third row and released. SQLite positions changed from `[681640e6…,76a007a7…,8e28a8a1…]` to `[76a007a7…,8e28a8a1…,681640e6…]`. The up-arrow then moved the second row to the first position. |
| Rename/delete | Renamed `Road` to `Travel`; all three members remained. Deleted the newly created debug-only `Evening` through its confirmation dialog: the three local tracks remained and no orphan playlist memberships remained. |
| Editor cancellation | Selected a song in the editor, rotated back to portrait and observed `Выбрано: 1`. Back showed a discard confirmation. Confirming exit created no additional playlist. |
| Keyboard | Initial inspection caught search focus reopening the keyboard after closing the add sheet. After the fix, repeating the operation ended with `mInputShown=false`, usable bottom navigation and unchanged playlist membership. While typing in search, bottom navigation/player were absent rather than covered by the keyboard. |
| Layout | Font scale 1.3 kept all four portrait navigation labels readable. Initial landscape inspection exposed a near-zero-height list; the revised compact layout displayed scrollable track rows, and the playlist editor placed name/search side by side. Font scale and orientation were restored afterwards. |
| Logs | Inspected AndroidRuntime and ExoPlayerImplInternal error logs contained no Spotygram error during the debug scenarios. The emulator again showed a boot-time System UI ANR outside Spotygram. |
| Final queue deletion check | Imported a temporary duplicate recording and placed its ID twice in a four-entry queue. Deleting that non-current import removed both entries: the queue became two valid IDs, the current ID was unchanged, and next/play reached PLAYING on the remaining recording without a source error. Final-process AndroidRuntime/ExoPlayerImplInternal error logs were empty. |
| Final offline/background check | With Wi-Fi/mobile data disabled and `mWakefulness=Asleep`, the final release media session remained PLAYING and advanced to 62503 ms. Network settings were restored and the emulator stopped afterwards. This checks local decoding/session progression, not audible output or remote streaming. |

These observations used local recordings and independently imported entries, not a seeded database or a generated fixture suite. Artwork/network behavior and large-library throughput are not inferred from these small local samples. A user-provided 0.1.1 phone screenshot showed 805 indexed tracks, which demonstrates exceeding the former 100-per-chat symptom, but is not proof that every accessible Telegram message was indexed.

Physical-device haptics, touch latency, battery drain, full authorized Telegram operation and ARM64 execution remain outside the local verification. New gestures use Android's platform feedback API, whose actual sensation was not observed on this audio-less emulator.

The release UI also imported and played Ogg Vorbis [Für Elise by Sebion7125](https://commons.wikimedia.org/wiki/File:Fur_Elise.ogg), licensed [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/). The app read its embedded title `Fur Elise` and artist `sebion`; the media session reported PLAYING with advancing position. This recording and the earlier public-domain *The Entertainer* are local demonstration media only, not APK/repository assets. Screenshots show their real imported metadata and ordinary artwork fallbacks.

Cleaning up two duplicate demonstration imports exposed a pre-existing queue problem: deleted imported IDs remained queued and advancing reached `Source error`. The deletion path was changed to remove all matching queued entries before deleting an imported record. It still refuses to delete the currently selected track. Telegram tracks retain their remote references when only their local copy is removed.

## 0.1.1 — history, network and shuffle

Manual checks use the same Android 16 x86-64 emulator, at 480×1040/192 dpi. Builds and emulator runs remain sequential, with a two-core CPU limit. No automated test files, fixtures or scaffolding were added.

| Surface | Observation |
| --- | --- |
| Build | Debug compilation/packaging and Android Lint passed during iteration. Final minified release compilation/packaging and release Lint passed with no errors. |
| Final artifacts | ARM64 and x86-64 APK signature checks and `zipalign -c -P 16 4` passed. The signer matches 0.1.0. ARM64 manifest reports `app.spotygram`, versionCode 2, versionName 0.1.1, minSdk 26. The installed final x86-64 `base.apk` SHA-256 matched the release output exactly (`3c06ad7f91b1e4790fb2612ab71179b340b36da8e283ffb8c4d1db9662144542`). |
| In-place migration | Android accepted the signed update over installed 0.1.0. SQLite reported schema 3 with both newest-message checkpoint columns; the original imported track, local path, favorite flag and `Night` playlist remained. No data reset. |
| Shuffle start and traversal | With three independently imported copies of the existing public-domain Opus recording, shuffled playback started at index 1 and traversed `1 → 2 → 0`. A new shuffle launch produced `[0,2,1]`. This checks observable startup/traversal, not a statistical distribution benchmark. Fisher–Yates and bounded SecureRandom draws were also reviewed in source. |
| Explicit next | Selecting the original recording's “Слушать следующим” action while shuffled inserted it at playback-order position 1 (`[0,1,3,2]`); the next button played that exact media ID at queue index 1. Duplicate entries here result from an explicit insertion, not shuffle repetition. |
| Restore | Force-stop/relaunch preserved the four-entry queue, exact order `[0,1,3,2]`, selected media ID/index 1 and position 11875 ms, with the media session non-playing. |
| Final repeat/restore pass | With repeat-all, manual transitions traversed `[1,0,2]` and refreshed the order to `[2,0,1]` on reaching its last entry. Seeking the next entry to the end caused an automatic transition and a fresh `[1,2,0]` order. Repeat-one then kept the same media ID/index after another end seek. A final paused seek and force-stop/relaunch retained 71262 ms, `[1,2,0]`, index 1, shuffle and repeat-one, without autoplay. |
| Seek and local background playback | A paused seek saved 71262 ms. After resuming and disabling Wi-Fi/mobile data, the media session reported `PLAYING` at 78777 ms with `mWakefulness=Asleep`. The emulator has no audible output; this verifies local decoding/session progression only. |
| UI and startup | Inspected library controls and dark-theme layout visually; theme switching remained functional. Production TDLib/JNI reached the phone-number entry UI. No account credentials were entered. |
| Runtime logs | Inspected Spotygram-process AndroidRuntime/ExoPlayerImplInternal error logs were empty. Emulator boot produced a System UI ANR and a separate Nexus Launcher crash, outside Spotygram; launch timing is not a phone-performance measurement. |

The history loop was reviewed against the pinned TDLib schema: `searchChatMessages` allows at most 100 messages per request and can return fewer; `next_from_message_id`, not page length or the number of audio documents, controls continuation. Persisted cursors and independent catch-up checkpoints were reviewed for interruption/restart. **Full authenticated history over 100 tracks, interrupted remote catch-up, lazy Telegram thumbnails, remote range playback and reconnection latency have not been exercised end to end on a production account.** Device-speed, battery and large-library memory improvements are not measured here. The changes remove identified extra work; they are not a claimed network benchmark.

## 0.1.0 — original release evidence

Verification is manual UI interaction and runtime inspection. There are no unit, integration, or smoke-test files. This document records observations, not assumed success.

Environment: Android 16 (API 36), x86-64 Pixel 6 emulator, initially 720×1560 at 288 dpi and then 480×1040 at 192 dpi. Both represent approximately the same logical phone layout. Builds and emulator runs were performed sequentially under a two-core CPU limit after the owner's load constraint. No physical phone was connected.

## Observed results

| Surface | Observation |
| --- | --- |
| Build | Debug and minified release Kotlin/Java compilation and packaging succeeded. Android Lint reported no errors; advisory dependency-version, KTX-style and exported-media-service warnings remain. |
| Native packaging | Both ARM64 and x86-64 TDLib/JNI builds completed. ARM64 ELF LOAD segments in TDLib and AndroidX graphics-path use 16 KB alignment; APK zip alignment was checked with `zipalign -c -P 16 4`. |
| Signing | Release APK signature verification passed. RSA-3072 release key is retained outside Git. Android accepted the signed x86-64 APK and subsequent in-place updates. |
| Telegram startup | The minified signed app loaded TDLib/JNI, initialized its Keystore-backed database key, and reached the production phone-number entry screen without a native or application crash. No real account was logged in. |
| Import | Android's file picker imported the public-domain Wikimedia recording *The Entertainer* (Ogg Opus). Duration, filename fallback, and offline indicator appeared. |
| Playback | The system media session reached `PLAYING` with advancing position, populated metadata and a queue. Media pause/resume keys worked. |
| Seeking | Seeking in the imported Opus file changed playback position successfully. The player uses the final thin seek track, not the original thick Material track. |
| Offline / screen off | With Wi-Fi and mobile data disabled and `mWakefulness=Asleep`, the media session remained `PLAYING` and advanced to 162144 ms. This verifies decoding/session progression, not audible physical speaker output: the emulator ran with `-no-audio`. |
| Favorites | The favorite flag survived app updates/restarts; the favorite filter showed the imported track and its menu offered removal from favorites. |
| Playlists | Creating `Night` from the track menu produced a playlist containing one track. It still contained that track after force-stop/relaunch. The earlier empty `Evening` development playlist was removed through the UI. |
| Search | `enter` matched the track; `nomatch` produced an empty result and the no-results state. |
| Themes | Light and dark player/library/settings layouts were visually inspected. Switching light → dark inside the settings sheet updated system-bar contrast correctly after the fix. |
| Restore | After pausing, seeking to 71786 ms, force-stopping and relaunching, the session restored exactly 71786 ms in the non-playing state. A previously completed item restored to the beginning and played on the first press. |
| Data preservation | The development database schema upgrade and release APK updates retained the imported file and queue. No app data reset was used to make these cases pass. |
| Logs | Inspected `AndroidRuntime` and `ExoPlayerImplInternal` logs contained no application crash or playback error in the checked cases. |

Manual checks caught and led to fixes for three behaviors: a new playlist losing the selected track when its sheet closed before an async write; theme changes not updating a modal window's system bars; and paused seeks not being persisted. Completed-track restoration was also normalized so the first play action can restart it.

## Performance evidence and limits

One `dumpsys meminfo` sample in the minified app, with one local track, restored paused queue and no authorized Telegram session, reported **25326 KiB PSS** and **146080 KiB RSS** (RSS includes shared mappings). This is an observation for that small local state, not a bound on a large Telegram library.

Emulator cold launches varied under the host limits; boot-time Android System UI/Messages ANR dialogs also occurred, outside Spotygram. These conditions are not a valid device-speed or battery benchmark. Physical-device battery drain, audible output, Bluetooth hardware, large-library scale, and actual ARM64 execution remain unmeasured.

## External authentication limit

Independent attempts to prepare a Telegram sandbox account reached Telegram's test DCs (`test_mode=True`), but documented fixed codes were rejected with `PHONE_CODE_INVALID`. No production account session was copied or opened. **Authenticated production-account discovery, document indexing, remote streaming/range seeking, and Telegram offline-download recovery remain unverified end to end.** They are implemented against the pinned TDLib schema, not represented here as passed scenarios.

The owner must enter their own phone/code/password on their device. API application credentials are not a logged-in user session. This is why the first release is marked alpha.
