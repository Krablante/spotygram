# Verification record

[Documentation](README.md) · **English** · [Русский](verification.ru.md)

This page records observed behavior and its limits. A build does not establish
account access, physical sound or responsiveness on another computer. Temporary
inspection code, timings and account data stay outside the checkout.

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
