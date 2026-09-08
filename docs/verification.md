# Verification record

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
