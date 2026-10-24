# Rust preview verification

[Documentation](README.md) · **English** · [Русский](verification.ru.md)

Verification uses manual interaction, runtime logs and screenshots. No automated
test scaffolding, account fixtures or media are shipped with the application.
Preview releases are independent of the stable release channel.

## Native Linux checks

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

## Device and account limits

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

Physical Android battery use, hardware Windows/macOS rendering, lock-screen
behavior on a phone, Bluetooth changes and interrupted network recovery need
device checks. A cross-platform build alone cannot establish these properties.
The Makepad visual is an adaptation: it does not reproduce every Compose/Haze
material, emoji decoration, tray behavior or gesture of stable Spotygram.

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
