# Architecture

Spotygram is a single-process, single-module Android application. It connects directly to Telegram and plays media locally. There is no backend.

```text
Compose screens ── MediaController ── MediaSession / ExoPlayer (≤3 items)
      │                                      │
      ├── PlaybackService (logical ID queue) ─┘
      │                                      │
      └── SpotygramApp ── SQLite              └── TelegramDataSource
                  │                                  │
                  └──────── TDLib / JNI ──────────────┘
                                  │
                           Telegram + disk
```

## Responsibilities

- `Telegram` owns one TDLib instance, one blocking receive coroutine, asynchronous JSON requests, authorization, and file state. TDLib's official JSON JNI API avoids committing a large generated Java schema.
- `Library` owns SQLite. It stores track references and metadata, selected chats, playlist membership, favorite flags, and pending downloads. Media bytes do not go in SQLite. UI reads observable immutable snapshots, not TDLib's private database.
- `SpotygramApp` coordinates source selection, incremental discovery, metadata updates, file import, and user actions. It never reads other applications' Telegram session files.
- `PlaybackService` owns the logical ID queue, ExoPlayer and the media session. Queue edits stay in-process; MediaController handles transport controls against a window of at most three items (previous/current/next). Queue and position persist locally; restoration does not autoplay.
- `TelegramDataSource` reads TDLib's downloaded file ranges directly with random access. Missing ranges wait on TDLib updates, with a bounded timeout. There is no second audio cache or HTTP proxy.
- `DownloadService` runs explicit offline downloads sequentially as a user-visible foreground data-sync service. The queue persists; interruption can be resumed from settings. Wi-Fi-only applies to explicit downloads, not playback.

## Library behavior

A Telegram track is identified by `(chat_id, message_id)`, not a process-local file ID. Messages discovered or resolved in the current TDLib session supply reusable file IDs; after a process restart they are resolved on demand. Seeking and replay do not resolve the same known message repeatedly. Multiple messages containing the same file may remain separate tracks; file downloads are managed by TDLib.

The On device view groups those references by their nonempty local file path, using a snapshot-scoped `localGroups` index. Settings takes its count and size from the same unique-file projection. Search/source matching happens before grouping, so a matching alias is still discoverable. No byte hashing, title-based merging, schema migration or catalog deletion is involved; separate physical copies remain separate entries even if their titles match.

The visible row reflects any favorite flag in its file group. Adding a favorite marks only the chosen reference, not every alias; removing it in On device clears the group's flags. The previous liked IDs are captured inside the same SQLite transaction and Undo restores exactly those IDs. Selection keys and the playing highlight use the file path in this view, while playlist operations still receive real catalog IDs. Starting playback from the view uses its deduplicated collection; existing explicitly built queues are not silently rewritten. Removing a local Telegram copy clears all matching-path offline flags, and the current-file guard recognizes aliases of the currently selected file.

Source discovery automatically follows every `next_from_message_id` for Telegram's audio and document filters. The API's 100-message page size is not a library cap; short or non-audio document pages do not terminate discovery. Each filter has a durable history cursor and a separate newest-message checkpoint. History resumes after interruption, including existing 0.1.0 cursors. Catch-up advances its newest checkpoint only after all intervening pages have been saved, so an interrupted refresh cannot skip a gap. Schema 3 adds these checkpoints without resetting existing data. Explicit server search likewise retrieves all pages, without changing history checkpoints. Incomplete history remains labelled until it finishes.

The app refreshes when Telegram becomes ready, including reconnection. Music indexing does not wait for chat-picker loading. The picker loads main and archived chat lists progressively and uses chat metadata already delivered by TDLib rather than requesting every chat again. Android's default-network callback tells TDLib about Wi-Fi/mobile/network loss; there is no network polling loop. Request timeouts surface as errors rather than being swallowed as coroutine cancellation.

This version indexes Telegram `messageAudio` and `messageDocument` with an audio MIME type or recognized audio extension (MP3, M4A, FLAC, OGG, Opus, AAC, WAV, AIFF, ALAC). Voice notes and videos are excluded. Duration and embedded title/artist for generic audio documents are read when their download completes; before that, the filename is shown. Local audio can also be imported using the system picker or Android Share. No online recognition, embeddings, external cover search, or transcoding are needed.

Artwork uses Telegram thumbnails or embedded artwork. If absent, a static, deterministic colored music tile is rendered; there is no generated fake album cover in the app.

## Storage and account boundaries

Playback downloads remain on disk; a complete file gains an offline indicator. TDLib's automatic storage optimizer is disabled so it cannot silently evict a track presented as saved. Users remove local audio explicitly. App uninstall removes app-private files. There is no silent export into a public music folder.

Removing a source hides its remote-only tracks except favorites and playlist members, retaining downloaded tracks and the ability to add the source again. Favorite/playlist metadata remains available after clearing a local copy, even if the source was removed. Removing an individual currently playing local file is refused until the user switches tracks. Permanent Telegram message deletion marks a track unavailable for refetch; an existing local copy is still playable.

Deleting an imported recording removes all of its queued entries before its local catalog record is removed. Deleting only the local copy of a Telegram recording retains its remote reference and queue entry. Explicit download batches use one SQLite transaction to enqueue tracks, preserving unique membership and FIFO order without a separate commit per song.

`MusicCache` queries TDLib `getStorageStatistics` only when its screen opens or the user requests refresh. It sums audio/document file statistics, not nominal track sizes or imported files. The app currently does not distinguish streaming copies from explicit offline downloads; the UI states that both are included. Confirmed cleanup uses `optimizeStorage` for those two file types with zero size/count/TTL/immunity limits, and requests deleted-file statistics for the result. It never recursively deletes the TDLib directory, account database, imported audio or artwork.

Cleanup first blocks new playback/download actions, releases the player's physical media window, waits for the download job to cancel, clears its pending queue and cancels active TDLib downloads. Media-session commands are rejected while cleanup is active. The logical playback queue and position remain available; the window is restored paused afterwards, dropping catalog references that are no longer accessible. File-presence reconciliation clears obsolete offline indicators, and late completed-file updates validate the path before publishing it. Favorites and playlist rows are not deleted; schema remains 3. Native-operation errors remain visible, and remaining size is refreshed even after an error. This is an app-scope user action, not a periodic cleaner or a new service.

Reconciliation reads current nonempty paths directly from SQLite, checks them off the main thread, and clears missing paths through one prepared statement inside one transaction. It publishes one library snapshot afterwards. Size-query completion always releases its loading state, including a TDLib-side request cancellation, so retry does not become permanently disabled.

The TDLib database key is random and wrapped by Android Keystore AES-GCM. Backups are disabled. Audio and the app's metadata database use Android's private file boundary, not a claim of separate per-file encryption. Network and TDLib payloads are not logged. Account logout clears Telegram catalog records and playlist references; imported local music remains.

## UI and performance

Four destinations, left to right: chats, favorites, playlists and music. The last destination persists by stable name, independently of button order. `SpotygramUI` coordinates navigation and shared actions; `MusicScreen` renders music, favorites and playlist contents using the same track rows. `PlaylistScreens` owns playlist browsing, creation, batch-add and ordering. `PlayerState` observes the existing MediaController. No additional module, backend or navigation framework is introduced. Light, dark and system modes share the same components.

Android resources provide English defaults, Russian translations and native quantity plurals. The app follows system locale selection; Android 13+ also exposes a per-app language picker through `localeConfig`. `AppText` is a small application-resource accessor for screen callbacks and background notices, not a separate localization engine. Saved Messages is recognized by the current user's chat ID, not by a translated name.

Long-press selects a track with platform haptic feedback; while selecting, taps toggle selection rather than start playback. An explicit Select button exposes the same action. Selection and search are saveable per page and survive rotation; changing destinations clears selection. Search does not discard selected hidden matches. Back first exits selection. Hearts are visible in rows, and removing a favorite offers Undo; favorites are independent of the music screen's offline filter.

Playlist creation and adding tracks use a focused editor with optional initial membership, including empty playlists. Batch additions and removals, create-with-members, deletion and reorder each use a SQLite transaction and publish one library snapshot. Repeated adds cannot duplicate membership. Reordering uses a dedicated long-press drag handle with edge scrolling; up/down buttons provide an alternative. A drag persists on release, not on every move; cancellation restores the starting order. Reorder merges with current DB membership instead of deleting tracks added concurrently. None of these operations modify audio files or Telegram messages. Schema remains version 3.

The compact landscape layout removes the brand row and combines music controls; the editor puts name and search side by side. While typing, keyboard insets reduce the content area and the main bottom navigation/player hide instead of sitting behind the keyboard. Opening a sheet clears the underlying search focus so closing it does not reopen the keyboard. The mini-player otherwise opens the existing full player. Queue, track actions, source picker and settings remain bottom sheets. Playlist covers use existing artwork or a plain icon, not generated artwork.

Lazy lists display metadata; thumbnails are decoded by Coil. Artwork is requested for displayed items only, with two concurrent waiters and low TDLib download priority, instead of downloading every cover while indexing. Playback progress updates only while the activity is visible. SQLite and media import run off the main thread; page parsing also runs off the UI thread. Metadata discovery is sequential and cancellable, without artificial per-page sleeps; catalog snapshots publish at most twice per second during a page loop, plus completion. Audio is not prefetched during indexing. Track lookup uses a snapshot-scoped ID map, and playlist filtering uses indexed membership. Load control targets 15–30 seconds of buffered audio with a one-second startup threshold and two-second rebuffer threshold; actual buffer occupancy and startup depend on format, loader granularity and network. No continuous polling job keeps the application alive after playback and downloads finish.

Shuffle uses Fisher–Yates over compact source indices with Android's `SecureRandom`, including an unbiased bounded draw for the first track. The selected/current entry anchors a pass; every other queue entry appears once in random order. Repeat-all prepares another pass at the boundary and avoids immediately repeating the last entry. At most two index passes are retained for backward navigation. Explicit play-next inserts an occurrence after the current entry, including in shuffle mode.

The full queue never becomes a Media3 timeline or a Binder payload. Transitions slide the three-item window while retaining the current media source. Only the open queue sheet observes queue revisions and resolves visible rows through the library ID map. Half-second position updates are scoped to the mini-player/full player, not the whole navigation screen.

`PlaybackOrder` distinguishes ordered, shuffled and absolute-random playback. Random playback samples one source index with `SecureRandom.nextInt(size)`, without excluding the current or previous entries. It retains at most 32 previous selections, current and next (34 indices), and does not build a full permutation. Back/forward can retrace that retained history; new future entries are independent draws. Repeat-all is unnecessary and normalized to off on entering random mode; the repeat button switches between off and repeat-one. Explicit play-next overrides the pending draw. The queue sheet labels the full list as a selection pool, shows the actual next entry, and caches source indices instead of rebuilding them on every transition.

Immutable queue snapshots are serialized by one conflated IO writer. The ID list and path use `playback_queue` preferences; small cursor/position/mode updates use `playback_position`, linked by snapshot version. Position ticks run only during playback and never rewrite the large list. Restoration migrates the former `settings` queue keys, filters missing tracks and remains paused; neither the catalog nor account data is reset.

The stable order name is saved alongside the former shuffle flag; older saved queues map to ordered/shuffled without losing their pass. Random history lives in the small position snapshot, so each random transition saves at most 34 indices rather than rewriting the source list. Its cursor identifies a history occurrence, allowing the same source index to occur repeatedly.

The full player's scroll content has a minimum height equal to the safe viewport. Remaining space is distributed between content blocks, keeping bottom actions near the bottom on tall phones. On smaller heights or larger text, natural content height takes over and the screen scrolls. Artwork stays bounded; there is no screen-size polling, device-specific offset or custom layout engine.

## Release checks

`AppUpdates` owns a small StateFlow and separate `updates` preferences. Activity `onStart` asks for a check; no Application-start check, timer, WorkManager task, service, network callback or periodic job is added. The automatic attempt interval is 24 hours, shared with manual attempts. Manual requests have a 60-second floor and respect a persisted GitHub rate-limit cooldown. One main-thread reservation suppresses overlapping requests. The attempt timestamp is committed on IO before making the request, so failures and process restarts cannot cause immediate automatic retries. A backward wall-clock change rebases the attempt and suppresses that check.

One unauthenticated HTTPS request goes to GitHub's fixed `/repos/Krablante/spotygram/releases/latest` endpoint, with an ETag when available. HTTP 304 reuses persisted metadata. Connect/read timeouts are 5/8 seconds, response size is capped at 256 KiB, redirects are rejected, and there are no automatic retries. No GitHub token, Telegram account data, analytics or device identifiers are sent. GitHub sees the connection IP and app version in User-Agent. The response must describe an ordinary non-draft release tagged `vMAJOR.MINOR.PATCH` with an uploaded nonempty APK for the device ABI. Versions compare numerically, not lexically. The release URL is constructed under the fixed repository rather than accepting an arbitrary server-provided URL.

Only a newer version produces a nonmodal banner in the main library UI; its dismissal persists for that version. Disabling automatic checks also hides the banner. Settings always allows a manual check and access to a cached newer release, including a dismissed one. Automatic failures/no-update results never emit a snackbar or notification. No APK downloader, installer permission or background wakeup is introduced: the user opens the release in their browser and chooses the APK/install action. Installing an equal or newer version naturally removes the old offer. Prereleases are excluded; normal releases constitute this app's update channel.

## Deliberate limits

One Telegram account, no secret chats, no chat editor, no YouTube integration, no desktop runtime, no playlist sync, no 32-bit ARM package. Platform decoders determine supported formats. Network availability and device-specific Android background policies still affect playback; actual checked scenarios live in `verification.md`.
