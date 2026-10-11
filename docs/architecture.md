# Architecture

[Documentation](README.md) · **English** · [Русский](architecture.ru.md)

Spotygram uses the same Kotlin/Compose screens and application rules on Android,
Windows, Linux and macOS. `shared/` holds the UI, resources, catalog, Telegram,
queue and cache code. `app/` and `desktop/` compile that source with small OS
adapters. Telegram connections and audio stay in the application process;
there is no backend or local HTTP proxy.

```text
Compose UI ── SpotygramApp ── Library / SQLite
                   │
                   ├── Telegram / TDLib ── Telegram servers
                   │
                    ├── PlaybackQueue ── Media3 (Android) / libmpv (desktop)
                    │                        └── TrackReader ── downloaded file ranges
                   │
                   └── AppUpdates ── GitHub releases
```

## Where work belongs

`SpotygramApp` coordinates user actions, discovery and imports. `Telegram`
owns the TDLib instance, authorization, JSON requests and update stream.
`Library` owns the music index and publishes immutable snapshots to the UI.
Screens do not read TDLib's database.

`PlaybackQueue` owns all queue, speed and progress rules. A small `Player`
contract carries transport commands and native events; `AndroidPlayer` adapts
Media3 and `MpvPlayer` adapts libmpv. Android's `PlaybackService` owns ExoPlayer
and the shared queue. Its UI uses MediaController for transport and the
in-process queue for edits. Desktop uses the same queue in its application process.
`PlayerConnection` owns the Activity's controller from `onStart` to `onStop`;
releasing this UI connection does not pause background playback. It handles
disconnects, bounds each connection attempt to ten seconds and permits one
automatic retry per foreground entry or explicit playback request. No background
reconnect loop runs. Generation checks discard obsolete callbacks and release
old futures. A later foreground entry or song tap can retry a failed connection.

Android playback speed uses MediaController's standard Media3 command, preserving pitch.
The full player's existing sheet offers 0.3×–4× in 0.05× steps; player events
update both full and mini-player indicators. The shared queue owns persistence: one
300 ms deferred callback coalesces dragging into a small speed preference in
`playback_position`, flushed on queue closure and restored at startup. Desktop
uses libmpv's speed with pitch correction; neither platform rebuilds or serializes
the queue for a speed change.

Song and random-play requests go through this owner rather than a captured
nullable controller. During connection, only the latest selected queue/track is
retained; it runs once after readiness using current catalog records. Leaving
the Activity clears an unfulfilled request. The connecting snackbar follows
that real pending state and disappears when it settles. Player observations
reset on controller replacement instead of retaining a stale playing snapshot.

Local UI function references capturing changing values are passed as ordinary
lambdas. In the previous build, `::play` captured the initial null controller;
generated lazy-item caches compared function references by equality, which did
not include their captured values. This could keep a dead row handler even
while the mini-player was connected. The same correction covers group-aware
favorite actions and the add-to-playlist callback.

`Downloads` owns one sequential explicit-download queue. Android exposes it
through the foreground `DownloadService`; desktop keeps it in the app while its
window can be hidden. No extra service is needed for updates or cache cleanup.

`Platform` supplies preferences, SQLite, key storage, metadata, files, links,
network information and installation. SQLiteOpenHelper and SQLite JDBC use the
same schema and library operations. Desktop preferences write atomic JSON files
through one writer. Platform adapters contain no screen layouts or ordering rules.

## From messages to tracks

The chat picker loads the main and archive lists independently of its query.
TDLib remains the directory owner; Spotygram does not persist another chat index.
Local `searchChats` results are shown without waiting for the debounced server
search. Server matches supplement, rather than replace, the local results;
the merged list is unique by chat ID. A blank query reads the loaded main/archive
lists, with Saved Messages included. Secret chats remain excluded.

Native new-chat, title and position/list updates advance a small revision flow.
The visible picker coalesces those events over 200 ms and rereads the local
index; this is not network polling. Each query owns its results and cancels its
obsolete requests. Directory loading is independent of typing. All picker jobs
stop below Activity STARTED or when the sheet closes and restart on foreground
entry. Reconnection or the picker's refresh action retries loading/search once;
errors do not start retry loops. Loaded results remain selectable on network
failure. The refresh control outside the picker still updates music in selected
sources, not the account's chat directory.

A Telegram track is identified by its chat and message IDs. TDLib file IDs
are process-local references, so saved messages are resolved again when
needed after a restart. Several messages may reference the same audio file;
those message identities are retained in the catalog.

Persisted numeric file bindings are cleared when the catalog opens, before
TDLib updates can match them. Only messages resolved in the current client
lifetime establish new bindings. A completed path must match the track's known
byte size; startup reconciliation and the data source reject mismatched copies
without deleting the underlying file. This also repairs old catalog paths
that may have been replaced by a thumbnail after an ID collision. Explicit
file removal resolves the current ID rather than trusting a persisted one.

Personal-chat navigation uses Telegram Android's `tg://openmessage` URI with
the user, server message and account IDs. Desktop opens Telegram Web K at the
user and server message, because Telegram Desktop has no openmessage handler.
Other chats retain TDLib's link lookup. Transient notices replace older
pending notices, and playback errors use localized text rather than raw
extractor exceptions. The app's own diagnostic event records only the playback
error code.

Discovery pages through the audio and document filters until Telegram returns
no next cursor. Short pages are not treated as the end. Each filter stores
both its history cursor and newest-message checkpoint, so interrupted work
can resume without skipping a gap. Server search also follows all pages.
Indexing collects metadata; it does not prefetch every audio file.

The catalog includes Telegram audio and documents with a supported audio MIME
type or extension. Voice notes and videos are excluded. Embedded metadata
can fill in document titles and duration after download. Artwork comes from
Telegram thumbnails or embedded artwork; absent artwork gets a static tile.

`LibraryState` caches its ID lookup and local-file groups per snapshot.
**On device** groups retained, nonempty local paths, after search and source filtering;
Settings derives its count and size from that collection, with the optional
recording projection described below. No file hashing or title-only matching
is involved. Favorites, the playing highlight and removal
guards account for multiple references to one file. Undo restores the exact
previous favorite IDs. A newly launched local collection is deduplicated;
an explicitly built queue is not silently rewritten.

The default-on `hide_duplicates` preference adds a reversible recording view
over Music, Favorites and On device. `TrackDuplicates` is built lazily once per
library snapshot, joining stable TDLib file keys, shared paths and exact
normalized title/artist/duration/byte-size signatures. Duration and size must
be known for metadata matching. It uses no hashing, file reads, network calls
or quadratic pairwise comparisons. This is a metadata heuristic, not proof of
identical audio content.

Filtering happens before choosing representatives; retained local, temporary
local and available remote copies are preferred in that order. Group favorites
and playing/download highlights account for hidden aliases. Unliking clears
existing marks in the group with exact Undo; destructive file actions still
target the selected physical copy. Lists and their new queues use the same
projection. Existing playlists and queues retain their explicit entries.
Settings uses the projected local count but keeps total physical storage size,
because hidden copies are not deleted. Disabling the preference restores raw
message rows; On device still groups references to one physical path.

## A large queue, a small media session

The full logical queue stays inside `PlaybackQueue`. Android's media session sees
at most three physical items: previous, current and next. Sliding this window
is posted after Media3's transition callback; mutating it synchronously there
can expose an inconsistent timeline to controllers.

Queue entries use source indices, not just track IDs, so Play next can add an
intentional repeat. Shuffle uses a Fisher–Yates permutation. Repeat-all
prepares another pass without immediately repeating the boundary song.
At most two passes are retained for backward navigation.

Absolute random draws each next source index independently, including the
current song. It stores at most 32 past choices, the current choice and one
prepared next choice. Previous follows that history. Play next overrides the
prepared draw; repeat-one remains available. Native ExoPlayer shuffle stays
off because the service owns these ordering rules.

Full queue snapshots are written only on structural changes. Small position,
mode and cursor updates use separate preferences, linked to the queue by
a snapshot version. One conflated IO writer persists them. Reopening restores
the position paused; neither a progress tick nor a random transition rewrites
the full song list.

Long-recording progress belongs to `PlaybackQueue`, alongside queue persistence.
SQLite schema 5 adds `playback_progress`, keyed by catalog track ID with cascading
deletion. It stores position, observed duration and file identity/size, without
adding fields to UI library snapshots. Only entries with an unfinished position
are loaded into the queue's lookup. Fresh selections and native transitions
resume eligible entries; the default inclusive threshold is 30 minutes, with
an enable switch and positive integer minutes in existing settings.
Selection waits for the initial progress load, retaining only the latest start
request; leaving the Activity cancels a start that has not yet run.

The existing five-second playback checkpoint and pause/seek/lifecycle events
capture progress. Before replacing a queue, the outgoing item is saved; native
transitions use the old `PositionInfo`, before advancing the logical cursor.
Completion, including repeat-one, retires that bookmark. Unknown lengths use
the player's duration when available. Changed file identity/size and positions
outside the current duration cannot resume. Removed restored queue entries do
not transfer their old position to a replacement song.

Dirty entries accompany immutable queue saves through the existing conflated IO
writer. Each batch writes only changed progress rows in one transaction, and
acknowledges only values still unchanged in the pending map. Coalescing therefore
retains outgoing-track updates while avoiding full-catalog or queue rewrites.
No additional timer, service, dependency or library reload runs on progress ticks.
Disabling or raising the threshold retains bookmarks; completion and catalog
deletion still remove them. Logout clears the service's Telegram lookup too.
The seek slider owns its mutable drag value locally so a tap can read the new
value on release without waiting for parent recomposition.

## Files and account state

`TrackReader` reads available ranges directly from TDLib's downloaded file.
Android's `TelegramDataSource` and libmpv's read/seek/cancel stream callbacks use
that same reader. Missing ranges wait for file updates with a timeout; closing
or replacing a stream cancels its wait. There is no second
media cache. By default, fully downloaded files remain available offline;
TDLib's automatic storage optimizer is disabled.

The optional **Do not keep played tracks** setting makes new playback copies
temporary. `ListeningCache` owns their lifecycle. SQLite schema 4 stores retained
intent on tracks and a `temporary_audio` journal keyed by TDLib's stable remote
unique ID, with the remote ID needed to resolve a current native file ID after
restart. Migration retains existing local paths, imports and queued downloads.
Previously unowned bytes are conservatively retained, not adopted for deletion.

The previous/current/prepared-next window and reference-counted data-source
leases protect shared files, including repeated queue entries. Per-file mutexes
serialize opening with deletion. Explicit Download pins all known aliases and
removes temporary ownership; temporary copies do not count as **On device**.
Turning the setting off retains remaining journal-owned copies. It does not
restore files already removed.

Cleanup is event-driven after queue restoration, file completion, reader close
or account/connectivity changes. It resolves and verifies stable identity, checks
protection again, records the actual path before deletion, cancels active downloads
and deletes through TDLib, then verifies native state and absence of the file
before retiring journal ownership. The path remains journaled if TDLib loses its
location but unlink fails. No directory scanner, timer
or extra service is used. Work is bounded to four eligible files per pass. Failed
files remain journaled, with a one-minute retry floor on subsequent events and a
manual Retry action in Settings. TDLib still uses disk: this is a bounded working
set policy, not RAM-only streaming or a fixed byte quota.

`MusicCache` queries storage only when its screen opens or the user refreshes it.
Confirmed global cleanup still includes explicitly saved Telegram audio/document
copies, excluding imports. It waits for automatic cleanup, stops playback and
downloads, and waits for open readers, then
reconciles missing paths in one SQLite transaction. Favorites, playlists and
sign-in remain. The logical queue is restored paused, without inaccessible
entries. Late download updates validate file presence before marking a file
offline again.

Removing an imported file removes its queued occurrences before deleting the
catalog entry. Removing a Telegram copy retains its message reference.
Removing a source hides remote-only tracks except favorites and playlist
members. An inaccessible original cannot restore a deleted local copy.

TDLib's database key is random. Android wraps it with Keystore AES-GCM; desktop
stores it in Windows Credential Manager, macOS Keychain or Linux Secret Service.
An existing database never gets a replacement key when its stored key is
unavailable. Android backups are disabled. Audio and catalog data use private
per-user storage, not separate per-file encryption. Logout removes Telegram catalog records and
their playlist references; imports remain. No other app's session is used,
and network payloads are not logged.

## UI and resource use

Compose renders four destinations with a saved last destination. English and
Russian use the same XML resources; desktop reads them directly, while Android
uses its resource system and Android 13+ per-app language selection.
Playlist edits and batch operations use transactions. Track lists are lazy,
and artwork requests have two concurrent slots with low TDLib priority.

Music filters occupy one aligned row: the source selector takes the remaining
width beside the local-file toggle and sort button. Playback and selection use
a separate row with a labelled primary action and 48 dp secondary touch targets.
Narrow screens and increased text size use an icon for shuffle/random play.
Compact playlists also replace the primary label with its play icon so their
four actions remain reachable; selection uses the same layout without scrolling.
The static `spotygram_mark` vector supplies the same sharply separated RGB
equalizer to the header, sign-in and adaptive launcher icon; no shader or
animation is needed for branding.

The light and dark appearances share a near-white/near-black base, small pools
of incident spectral light, and the same optical control material. `Design.kt`
owns its geometry, tint, diffusion, refraction, highlights and press response.
Haze Glass 2.0.1 consumes explicitly captured background and content layers;
glass consumers are not included in those captures. Artwork supplies a soft
reflection near the player. There is no animated wallpaper or effect per music,
chat or playlist row. Lists remain lazy and scroll beneath the floating dock,
with end padding that keeps their final actions reachable.

Search, source filters, navigation, transport, switches and sheets use that
material. Dense surfaces fully diffuse the captured text rather than overlaying
a sharp copy. The secondary refraction-detail pass is disabled; primary edge
refraction, dispersion and lighting remain. Android 13+ supports the full
RuntimeShader optics; desktop uses Haze's Skia glass renderer. Older Android versions use Haze's tint/lighting/rim
fallback with denser tint for text-heavy surfaces. Reduced-motion handling comes
from Compose and Haze's system policy.
Press/focus optics belong to controls with interaction sources; whole sheets do
not warp when an inner control is tapped. Sheet glass is attached inside the
native placement/drag transform, together with its handle and content.
Playlist name/search fields share a row with the keyboard open and keep their
composition identity while widths change, so adapting the layout preserves focus.
The default theme follows Android until the user chooses light or dark.
At 840 dp, navigation moves into a side rail; landscape players use artwork
and controls in separate columns. Music lists remain bounded to 720 dp.

SQLite work, import and page parsing run off the UI thread. During indexing,
library snapshots publish at most twice per second plus completion. Progress
timers belong to visible player components, not the entire app screen.
Android's network callback informs TDLib of connectivity changes; desktop TDLib
manages reconnection itself. Desktop Wi-Fi detection runs only for an active
download queue. There is no idle network polling loop. Media3 buffers target
15–30 seconds, with a one-second start and two-second rebuffer threshold.
libmpv reads ahead up to 30 seconds within an 8 MiB demuxer limit, with a
one-second initial cache wait. These are configuration values, not measured promises.

Desktop has a single-instance file lock and event-driven file activation, native
import dialogs, system key storage and Nucleus media controls: MPRIS on Linux,
SMTC on Windows and MPNowPlayingInfoCenter on macOS. Its visible window owns a
lifecycle; minimizing/hiding stops UI progress observations and chat-picker work.
Audio and explicit downloads continue. Quit flushes the queue and preferences,
closes TDLib and releases the audio engine. A bundled jlink runtime avoids a
system Java requirement. The 256 MiB heap limit does not cap native memory;
resource observations are kept in the verification record.

## Release checks

`AppUpdates` owns a small StateFlow and separate preferences. Activity
`onStart` may trigger one unauthenticated request to the fixed GitHub latest
release endpoint; desktop foreground entry does the same. Automatic attempts have a persisted 24-hour interval;
manual attempts have a one-minute floor and respect a saved rate-limit
cooldown. The timestamp is committed on IO before the request. One main-thread
reservation prevents overlap, and failures do not trigger retries. A backward
clock change rebases the timestamp and suppresses that attempt.

ETag/304 reuses saved release metadata. Connect/read timeouts are 5/8 seconds,
the response is limited to 256 KiB, and redirects are rejected. Accepted
releases must be non-draft, non-prerelease, tagged `vMAJOR.MINOR.PATCH`, with
an uploaded application asset for the host target. Version comparison is numeric. Release
links are constructed under the fixed repository, not taken from arbitrary
response URLs. The naming contract is in the [build guide](build.md).

A newer version produces a dismissible library banner with Download and install;
the same action is in Settings. Dismissal persists for that version. Automatic
failures and no-update results stay silent. Release metadata retains the selected
APK's size and GitHub SHA-256 digest; download URLs are constructed under the
fixed repository. GitHub receives the connection IP and app version, not
Telegram account data or the music library.

`UpdateInstaller` delegates user-requested transfers to Android DownloadManager.
It persists one download ID and an immutable copy of the selected asset metadata.
Only a resumed Activity polls progress, once per second while that download is
active. Leaving the app stops polling, not the system transfer. Cold launch
restores the download or ready APK without automatically opening the installer.
A permission-protected receiver routes a system download-notification click
back to the update settings. There is no additional foreground service or
periodic release-check job.

Completed bytes are copied into the app's private update cache and verified on
IO: exact size and SHA-256, matching package/version, increasing Android version
code, and the same current signing certificate set as the installed app. Debug
builds intentionally reject the production package. Android's installer performs
its own final package/signature validation. A non-exported FileProvider grants
temporary read access to the verified APK, not to the music or session directories.

Installation uses Android's normal unknown-source permission and installer UI.
Returning from the permission screen continues the explicit request once; denial
or installer cancellation does not reopen it in a loop. Ready APKs can be retried
without downloading, with verification repeated before handoff. Cancel joins
in-flight work before removing the DownloadManager entry and private files.
After a successful app update, the next launch clears the old APK and metadata.

Desktop's installer resumes a requested transfer, checks the SHA-256 and detached
Ed25519 manifest signature, then checks every extracted file and its target/version.
Extraction bounds paths, expanded size, entry count and links. After an explicit
restart confirmation, a cache-owned helper waits for the old process, copies the
verified replacement into the same filesystem and replaces the installation.
The new app acknowledges catalog startup; failed startup restores the previous
application. Data and credentials are outside this tree. macOS code signing is
ad hoc; the manifest's release signature supplies the update channel's publisher
identity independently of Apple notarization.

## Verification and limits

The [verification record](verification.md) keeps concrete observations and
untested cases separately. Source review does not substitute for a successful
account login, a real remote download or a physical-device measurement.
The app supports one Telegram account on each installation, Android ARM64/x86-64,
Windows/Linux x86-64 and macOS x86-64/ARM64. Secret chats, voice notes and playlist
sync are outside its scope.
