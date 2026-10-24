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

## Desktop packages

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

Physical Android battery use, owner Windows/macOS devices, lock-screen
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

On 2026-10-24, the packaged Rust 0.1.0 and packaged stable 0.28.0 were launched
one at a time on toma with the same offline 10,001-track catalog, Russian/light
appearance and 1040×760 window. No compiler or emulator was running. Both were
limited to two CPU cores, with no account, playback or inspection bridge. After
initial rendering settled, `/proc` samples over 20 seconds showed:

| Stack | RSS | PSS | Swap | Idle CPU, one core = 100% |
| --- | --- | --- | --- | --- |
| Rust / Makepad | 210.4 MiB | 179.2 MiB | 0 | 0.94% |
| Stable JVM / Compose | 367.3–367.6 MiB | 315.2–315.5 MiB | 0 | 0.30% |

RSS was about 43% lower in this catalog-only case. Idle CPU was not lower.
Rust used native Wayland/Vulkan; stable used Xwayland/OpenGL on the same Radeon.
The figures exclude the separate compositor and do not account for all GPU/kernel
allocations. This is one short controlled observation, not a startup, battery or
frame-pacing benchmark. No multiplicative playback or Android speedup is claimed.
