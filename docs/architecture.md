# Architecture

[Documentation](README.md) · **English** · [Русский](architecture.ru.md)

Spotygram runs in one Android process and one app module. It connects directly
to Telegram and reads audio from local files. There is no backend or local
HTTP proxy between the player and its data.

```text
Compose UI ── SpotygramApp ── Library / SQLite
                   │
                   ├── Telegram / TDLib ── Telegram servers
                   │
                   ├── PlaybackService / Media3
                   │         └── TelegramDataSource ── downloaded file ranges
                   │
                   └── AppUpdates ── GitHub releases
```

## Where work belongs

`SpotygramApp` coordinates user actions, discovery and imports. `Telegram`
owns the TDLib instance, authorization, JSON requests and update stream.
`Library` owns the music index and publishes immutable snapshots to the UI.
Screens do not read TDLib's database.

`PlaybackService` owns ExoPlayer and the queue. The UI uses MediaController
for transport controls and the in-process service for full-queue edits.
`DownloadService` handles explicitly requested downloads sequentially, as
a visible foreground service. No extra service is needed for updates or
cache cleanup.

## From messages to tracks

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
the user, server message and account IDs; these chats have no HTTPS message
link. Other chats retain TDLib's link lookup. Transient notices replace older
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
**On device** groups nonempty local paths, after search and source filtering;
Settings uses the same collection for count and size. No file hashing or
title-based merging is involved. Favorites, the playing highlight and removal
guards account for multiple references to one file. Undo restores the exact
previous favorite IDs. A newly launched local collection is deduplicated;
an explicitly built queue is not silently rewritten.

## A large queue, a small media session

The full logical queue stays inside the service. Android's media session sees
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

## Files and account state

`TelegramDataSource` reads available ranges directly from TDLib's downloaded
file. Missing ranges wait for file updates with a timeout. There is no second
media cache. Fully downloaded files remain available offline; TDLib's
automatic storage optimizer is disabled.

The app deliberately does not distinguish a playback copy from an explicit
offline download. `MusicCache` queries storage only when its screen opens or
the user refreshes it. Confirmed cleanup asks TDLib to optimize audio/document
storage, excluding imports. It first stops playback and downloads, then
reconciles missing paths in one SQLite transaction. Favorites, playlists and
sign-in remain. The logical queue is restored paused, without inaccessible
entries. Late download updates validate file presence before marking a file
offline again.

Removing an imported file removes its queued occurrences before deleting the
catalog entry. Removing a Telegram copy retains its message reference.
Removing a source hides remote-only tracks except favorites and playlist
members. An inaccessible original cannot restore a deleted local copy.

TDLib's database key is random and wrapped by Android Keystore AES-GCM.
Android backups are disabled. Audio and catalog data use app-private storage,
not separate per-file encryption. Logout removes Telegram catalog records and
their playlist references; imports remain. No other app's session is used,
and network payloads are not logged.

## UI and resource use

Compose renders four destinations with a saved last destination. English and
Russian use Android resources; Android 13+ supports per-app language selection.
Playlist edits and batch operations use transactions. Track lists are lazy,
and artwork requests have two concurrent slots with low TDLib priority.

SQLite work, import and page parsing run off the UI thread. During indexing,
library snapshots publish at most twice per second plus completion. Progress
timers belong to visible player components, not the entire app screen.
Android's network callback informs TDLib of connectivity changes; there is
no network polling loop. Playback buffers target 15–30 seconds, with a
one-second start and two-second rebuffer threshold. These are configuration
values, not measured promises about a phone or connection.

## Release checks

`AppUpdates` owns a small StateFlow and separate preferences. Activity
`onStart` may trigger one unauthenticated request to the fixed GitHub latest
release endpoint. Automatic attempts have a persisted 24-hour interval;
manual attempts have a one-minute floor and respect a saved rate-limit
cooldown. The timestamp is committed on IO before the request. One main-thread
reservation prevents overlap, and failures do not trigger retries. A backward
clock change rebases the timestamp and suppresses that attempt.

ETag/304 reuses saved release metadata. Connect/read timeouts are 5/8 seconds,
the response is limited to 256 KiB, and redirects are rejected. Accepted
releases must be non-draft, non-prerelease, tagged `vMAJOR.MINOR.PATCH`, with
an uploaded APK for the device ABI. Version comparison is numeric. Release
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

## Verification and limits

The [verification record](verification.md) keeps concrete observations and
untested cases separately. Source review does not substitute for a successful
account login, a real remote download or a physical-device measurement.
The app currently supports one Telegram account and two Android ABIs;
secret chats, voice notes and playlist sync are outside its scope.
