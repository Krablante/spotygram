# Architecture

Spotygram is a single-process, single-module Android application. It connects directly to Telegram and plays media locally. There is no backend.

```text
Compose screens ── MediaController ── MediaSessionService / ExoPlayer
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
- `PlaybackService` owns ExoPlayer and the media session. The activity connects through MediaController. Queue and position persist locally; restoration does not autoplay.
- `TelegramDataSource` reads TDLib's downloaded file ranges directly with random access. Missing ranges wait on TDLib updates, with a bounded timeout. There is no second audio cache or HTTP proxy.
- `DownloadService` runs explicit offline downloads sequentially as a user-visible foreground data-sync service. The queue persists; interruption can be resumed from settings. Wi-Fi-only applies to explicit downloads, not playback.

## Library behavior

A Telegram track is identified by `(chat_id, message_id)`, not a process-local file ID. Messages discovered or resolved in the current TDLib session supply reusable file IDs; after a process restart they are resolved on demand. Seeking and replay do not resolve the same known message repeatedly. Multiple messages containing the same file may remain separate tracks; file downloads are managed by TDLib.

Source discovery automatically follows every `next_from_message_id` for Telegram's audio and document filters. The API's 100-message page size is not a library cap; short or non-audio document pages do not terminate discovery. Each filter has a durable history cursor and a separate newest-message checkpoint. History resumes after interruption, including existing 0.1.0 cursors. Catch-up advances its newest checkpoint only after all intervening pages have been saved, so an interrupted refresh cannot skip a gap. Schema 3 adds these checkpoints without resetting existing data. Explicit server search likewise retrieves all pages, without changing history checkpoints. Incomplete history remains labelled until it finishes.

The app refreshes when Telegram becomes ready, including reconnection. Music indexing does not wait for chat-picker loading. The picker loads main and archived chat lists progressively and uses chat metadata already delivered by TDLib rather than requesting every chat again. Android's default-network callback tells TDLib about Wi-Fi/mobile/network loss; there is no network polling loop. Request timeouts surface as errors rather than being swallowed as coroutine cancellation.

This version indexes Telegram `messageAudio` and `messageDocument` with an audio MIME type or recognized audio extension (MP3, M4A, FLAC, OGG, Opus, AAC, WAV, AIFF, ALAC). Voice notes and videos are excluded. Duration and embedded title/artist for generic audio documents are read when their download completes; before that, the filename is shown. Local audio can also be imported using the system picker or Android Share. No online recognition, embeddings, external cover search, or transcoding are needed.

Artwork uses Telegram thumbnails or embedded artwork. If absent, a static, deterministic colored music tile is rendered; there is no generated fake album cover in the app.

## Storage and account boundaries

Playback downloads remain on disk; a complete file gains an offline indicator. TDLib's automatic storage optimizer is disabled so it cannot silently evict a track presented as saved. Users remove local audio explicitly. App uninstall removes app-private files. There is no silent export into a public music folder.

Removing a source hides its remote-only tracks, retaining downloaded tracks and the ability to add the source again. Removing a currently playing local file is refused until the user switches tracks. Permanent Telegram message deletion marks a track unavailable for refetch; an existing local copy is still playable.

The TDLib database key is random and wrapped by Android Keystore AES-GCM. Backups are disabled. Audio and the app's metadata database use Android's private file boundary, not a claim of separate per-file encryption. Network and TDLib payloads are not logged. Account logout clears Telegram catalog records and playlist references; imported local music remains.

## UI and performance

Two destinations: music and chats. Search is in the library; the mini-player opens a full player. Queue, track actions, source picker, and settings use bottom sheets. Light, dark, and system modes share the same components.

Lazy lists display metadata; thumbnails are decoded by Coil. Artwork is requested for displayed items only, with two concurrent waiters and low TDLib download priority, instead of downloading every cover while indexing. Playback progress updates only while the activity is visible. SQLite and media import run off the main thread; page parsing also runs off the UI thread. Metadata discovery is sequential and cancellable, without artificial per-page sleeps; catalog snapshots publish at most twice per second during a page loop, plus completion. Audio is not prefetched during indexing. Track lookup uses a snapshot-scoped ID map, and playlist filtering uses indexed membership. Load control targets 15–30 seconds of buffered audio with a one-second startup threshold and two-second rebuffer threshold; actual buffer occupancy and startup depend on format, loader granularity and network. No continuous polling job keeps the application alive after playback and downloads finish.

Shuffle uses Fisher–Yates with Android's `SecureRandom`, including an unbiased bounded draw for the first track of a shuffled collection. The selected/current track anchors a pass; every other queue entry appears once in random order. Re-enabling shuffle and replacing a shuffled queue draw a fresh order. Repeat-all draws another order when reaching the last entry, anchored at that entry to avoid an immediate repeat. Manual selections and repeat-one retain their usual meaning; explicit play-next places its entry after the current entry even in shuffle mode. The exact order is saved with queue changes and restored across process restarts. Position saves do not rebuild or serialize the full queue every five seconds, and their timer runs only during playback.

## Deliberate limits

One Telegram account, no secret chats, no chat editor, no YouTube integration, no desktop runtime, no playlist sync, no 32-bit ARM package. Platform decoders determine supported formats. Network availability and device-specific Android background policies still affect playback; actual checked scenarios live in `verification.md`.
