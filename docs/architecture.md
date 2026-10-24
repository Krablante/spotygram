# Architecture

[English](architecture.md) · [Русский](architecture.ru.md) · [Documentation](README.md)

The Rust preview is a separate native application. Makepad owns its shared
screens and GPU rendering. TDLib owns Telegram's protocol and downloads.
libmpv on desktop and Media3 on Android own demuxing, decoding and audio output.
There is no webview, audio proxy or application backend.

## Interface and application state

`app.rs` binds navigation and actions to the widgets in `ui.rs`. The photographed
Takopi artwork is reused from the stable application. Makepad's glass material
replaces Compose/Haze; it is a visual adaptation, not a pixel-identical port.
Standalone glass buttons and buttons drawn inside a glass surface have separate
implementations so hidden panels do not leave independent overlay draw lists.
The music sliders handle absolute taps and drags without creating text inputs.

The UI sends bounded, non-blocking commands to `runtime.rs`. One Tokio worker
with a local task set owns the main SQLite connection, playback intent and job
handles. File import and blocking file waits use a pool limited to two threads.
The UI reads immutable `ArcSwap` snapshots and receives wake signals. It never
waits for Telegram, the database, file copying or the decoder.

Snapshots contain one catalog page, small collections and current playback
state. `PortalList` draws visible rows. The database supplies pages of at most
256 tracks, aligned to 128-row boundaries. Trigram FTS handles search strings
of three or more characters; shorter searches use escaped `LIKE`. Sorting uses
a Unicode-aware collation. Duplicate groups are computed during indexing and
used to choose one representative without deleting other recordings.

Covers decode for their displayed size. Catalog page changes evict old image
cache entries while retaining current artwork. Playback clock updates reach the
full player at most four times per second; hidden UI does not receive those
redraws. Position checkpoints are written at five-second intervals and on
meaningful playback transitions. Sound controls do not reload the catalog.

## Catalog and synchronization

`store.rs` owns schema 9. It extends stable schema 8 with local settings, a
separate queue, search data and duplicate indexes. Older schemas are refused
without resetting their data. Database access uses WAL and bounded SQLite
caches. The preview does not automatically open the stable application's files.

Chat recordings use the `(chat, message)` identity. Profile recordings use the
profile user and a hash of the stable Telegram file key. Imports have UUIDs.
Native file IDs are session-local and are rebound after restart; they are never
cloud identities. A changed file identity invalidates an old cached path and
bookmark. Failed or incomplete profile pagination does not replace the previous
published collection. Older unconnected previews are pruned while keeping
recordings referenced by favorites, playlists, queue or downloads.

`sync.rs` implements the existing format 3 records and legacy format 1/2 reads.
Cloud data includes playlists, favorites, sources, profile subscriptions,
portable track descriptors and the library preferences defined in that module.
Queue, position, order, repeat, speed, volume and native paths remain local.
Logical clocks and writer IDs resolve ordinary conflicts. Track and playlist
deletions retain their tombstones and dominate stale offline copies.

`cloud.rs` stores compressed, hashed documents in owner-controlled private
Telegram channels bearing the existing `spotygram-storage/1` marker. It checks
ownership and account identity, confirms sends before acknowledging records,
and keeps compaction debt durable. It preserves concurrent changes beyond a
confirmed snapshot's boundary. Discovery failures and inaccessible remembered
storage produce retries instead of silently creating replacement storage.
FLOOD_WAIT delays survive restart.

The stable app and Rust preview can read the same cloud library. Their local
databases and Telegram sessions are independent; cloud edits intentionally
propagate between them.

## Playback and storage

`player.rs` selects the platform engine. Desktop calls libmpv's C API directly.
Android calls a small Java bridge around ExoPlayer and a `MediaSessionService`.
Playback intent and queue rules stay in Rust. Engine events and prepared
recordings carry generations so an obsolete load cannot replace a newer choice.

`reader.rs` supplies the engines with local or progressively downloaded bytes.
It retains a file handle, reads available ranges and asks TDLib for missing
ranges at the requested offset. Cancellation interrupts waits. Seeking and
decoding remain the engine's responsibility. The app does not implement codecs.

Imports are app-owned copies. Android first copies a content URI into private
staging storage and then moves it into import storage. No external original is
deleted. Explicit downloads are persisted in SQLite and run sequentially.
Android uses a foreground service for transfers and checks the Wi-Fi policy.
Temporary playback cleanup protects current audio and saved recordings, bounds
each cleanup pass and verifies native file removal before discarding its journal.

## Platform and release boundaries

`platform.rs` owns profile location, instance locking, OS key storage, native
library loading and links. Desktop Telegram database keys use the OS key store;
Android wraps its key with Android Keystore. Missing keys do not cause database
replacement. The Android Java layer also owns the activity, content picker,
network notifications and system media session.

Makepad's Android Java/resources compile as the `makepad-platform` dependency
from its pinned checkout, outside the source tree. Staging adds one version guard
around `InputDevice.isEnabled`, which Android 26 lacks; the upstream checkout
stays intact. Application lint checks the app-owned adapter, which disables unused
MIDI discovery. Ordinary text/key input remains available. Predictive Back uses Makepad's
existing callback and the application's shared panel navigation.

The application ID and desktop profile identity are `app.spotygram.rust` and
`spotygram-rust`. `SPOTYGRAM_RUST_PROFILE` overrides the desktop profile for
operator verification. Generated build state belongs under `SPOTYGRAM_STATE/rust`.
It is independent of the installed profile.

Rust tags are `rust-v…` prereleases with their own asset names. Manual preview
update checks select only newer Rust prereleases. Stable installers, the stable
update channel and its signing keys are not replaced. See [verification](verification.md)
for practical evidence and remaining coverage limits.
