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

A Telegram track is identified by `(chat_id, message_id)`, not a process-local file ID. Before remote playback, the message is resolved again. Multiple messages containing the same file may remain separate tracks; file downloads are managed by TDLib.

Initial source discovery fetches up to 100 messages from each of Telegram's audio and document filters. Older history is opt-in and paginated, with independent cursors for each filter. Refresh catches up from the newest known music message. Explicit server search fetches up to 100 matches per filter per selected chat and adds audio results to the local index. The UI must not present an incomplete local library as a complete search of all Telegram history.

This version indexes Telegram `messageAudio` and `messageDocument` with an audio MIME type or recognized audio extension (MP3, M4A, FLAC, OGG, Opus, AAC, WAV, AIFF, ALAC). Voice notes and videos are excluded. Duration and embedded title/artist for generic audio documents are read when their download completes; before that, the filename is shown. Local audio can also be imported using the system picker or Android Share. No online recognition, embeddings, external cover search, or transcoding are needed.

Artwork uses Telegram thumbnails or embedded artwork. If absent, a static, deterministic colored music tile is rendered; there is no generated fake album cover in the app.

## Storage and account boundaries

Playback downloads remain on disk; a complete file gains an offline indicator. TDLib's automatic storage optimizer is disabled so it cannot silently evict a track presented as saved. Users remove local audio explicitly. App uninstall removes app-private files. There is no silent export into a public music folder.

Removing a source hides its remote-only tracks, retaining downloaded tracks and the ability to add the source again. Removing a currently playing local file is refused until the user switches tracks. Permanent Telegram message deletion marks a track unavailable for refetch; an existing local copy is still playable.

The TDLib database key is random and wrapped by Android Keystore AES-GCM. Backups are disabled. Audio and the app's metadata database use Android's private file boundary, not a claim of separate per-file encryption. Network and TDLib payloads are not logged. Account logout clears Telegram catalog records and playlist references; imported local music remains.

## UI and performance

Two destinations: music and chats. Search is in the library; the mini-player opens a full player. Queue, track actions, source picker, and settings use bottom sheets. Light, dark, and system modes share the same components.

Lazy lists display metadata; thumbnails are decoded by Coil. Playback progress updates only while the activity is visible. SQLite and media import run off the main thread. Metadata discovery is sequential and cancellable; audio is not prefetched during indexing. No continuous polling job keeps the application alive after playback and downloads finish.

## Deliberate limits

One Telegram account, no secret chats, no chat editor, no YouTube integration, no desktop runtime, no playlist sync, no 32-bit ARM package. Platform decoders determine supported formats. Network availability and device-specific Android background policies still affect playback; actual checked scenarios live in `verification.md`.
