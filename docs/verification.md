# Verification record

## 0.3.0 — languages, navigation and bounded playback queue

Manual UI and runtime inspection used the Android 16 x86-64 emulator at 480×1040/192 dpi. Builds and emulator runs were sequential, with two-core CPU and bounded memory limits. Commands had short timeouts; long builds ran as asynchronously polled units. No test files, fixture suites or automation scaffolds were added to the project.

| Surface | Observation |
| --- | --- |
| Builds | Debug and minified release APKs, debug Lint and release Lint passed. EN/RU resource validation found matching keys and format arguments across 213 resources, including the track-count plural. |
| Artifacts | ARM64/x86-64 signature checks and 16 KB zip alignment passed. The signing certificate remains `b295569f9c5d00f2d54aa1e22d07a8e379ec90a06aa6f2c5c8b288a030b5550a`. Manifest: `app.spotygram`, versionCode 4, versionName 0.3.0, minSdk 26. Installed release SHA-256 matched `ebe51fe7fa78eef3061b38c81caca57e0a014b2f156f43998f1ffe69c1cab5d5`. |
| Update and migration | Signed release installed over 0.2.0 without clearing data. Both local recordings, both favorites and Night/Road playlists remained visible. The legacy queue restored The Entertainer at 93680 ms, non-playing. Debug legacy migration separately retained 29629 ms. |
| Navigation | The visible order is Chats, Favorites, Playlists, Music. After selecting Favorites, inspecting that screen, force-stopping and reopening the debug app, Favorites remained selected. |
| Languages | System-default English, explicit Russian and explicit English rendered translated screens, local-source labels and accessibility text. Observed plurals included `1 трек`, `3 трека` and `2 tracks`. Release Settings → Language opened Android's native selector with English/Russian entries. Requesting German through the locale-manager command produced English app text. The release override was returned to English. |
| Transition correction | An intermediate debug build crashed on system Next because timeline edits ran inside a Media3 transition callback. Window sliding was deferred until that notification completes. Repeating system Next twice then advanced logical cursor 0 → 1 → 2 without the error. |
| Repeat | With three independently imported local entries, repeat-all prepared a second pass and system Next crossed cursor 2 → 3. Repeat-one followed by a seek to the end retained cursor 3 and restarted the current recording. This checks behavior, not the statistical quality of shuffle. |
| Long queue | A temporary debug-only queue contained 10000 references to the three existing local recordings; no catalog rows or audio files were added. Shuffle produced 10000 distinct source indices. Three system Next actions reached cursor 3 while the media-session timeline stayed at 2–3 items. The full queue sheet opened and scrolled; removing a visible non-current occurrence left 9999 entries and playback continued. After pause/force-stop/relaunch, all 9999 entries and shuffle restored at 51324 ms without autoplay. The original debug queue/position were then restored and the two temporary backup files removed. |
| Final release playback | The minified release played The Entertainer, then system Next with shuffle enabled reached Fur Elise/sebion and PLAYING. With Wi-Fi/data disabled and `mWakefulness=Asleep`, playback advanced to 59755 ms. Network settings were restored and playback paused afterwards. |
| UI evidence | All six README screenshots were recaptured from the signed release and visually inspected, showing English and Russian, light/dark themes, the new navigation order and language setting. |
| Runtime logs | AndroidRuntime and ExoPlayerImplInternal error logs were empty after the corrected build's debug and release scenarios. Initial emulator startup still occasionally stalled installation/UI hierarchy inspection; timed-out operations were retried only after checking actual state. |

The long-queue check is deliberately synthetic queue state, not 10000 unique tracks or an authenticated large Telegram chat. It demonstrates bounded Media3/IPC representation and local queue operations, not large-catalog indexing speed, network playback, phone latency or battery behavior. The original user-reported phone crash was not accompanied by a device stack trace, so its exact cause is not claimed as reproduced. Physical ARM64 execution, Android versions below 13 and authorized Telegram flows remain unverified locally.

## 0.2.0 — four destinations and touch interaction

Verification remains manual UI operation and runtime inspection, without test files or automated test scaffolds. Android 16 x86-64 emulator, 480×1040 at 192 dpi. Compilation and emulator runs were sequential under a two-core CPU limit. The debug package is separate from the signed release package.

| Surface | Observation |
| --- | --- |
| Debug build | Debug APK compilation/packaging and Android Lint passed before manual checks and after the keyboard/landscape fixes. |
| Final build/artifacts | Final minified release build and release Lint passed. ARM64/x86-64 signatures and 16 KB zip alignment passed. Manifest reports `app.spotygram`, versionCode 3, versionName 0.2.0, minSdk 26. The installed final x86-64 APK matched SHA-256 `9428e0dda5404da946b10e1119d6de6ae9a43157a3a22e25872af1e57fa1139a`. |
| Release update | Signed 0.2.0 installed over 0.1.1 without clearing data. The three local tracks, one favorite and `Night` playlist remained; schema stayed at 3. Queue `[1,2,0]`, selected index 1 and 71262 ms restored in the non-playing state. |
| Favorites | Heart tap populated the separate Favorites destination. Removing the heart removed the row; the snackbar Undo restored it. The action did not start playback. |
| Long-press | Holding a song entered selection with `Выбрано: 1`, without starting playback. A second tap selected another song. Searching `nomatch` left `Выбрано: 2`; clearing search and adding to an existing playlist added both. |
| Playlist creation | Created `Evening` from one selected song; it contained that song immediately. Created empty `Road` through the full editor, then used its Add Tracks action. Batch-adding two more imported entries produced three members. |
| Membership uniqueness | Adding an already included song to `Road` again left three members, rather than four. |
| Drag reorder | Held the first drag handle, moved it to the third row and released. SQLite positions changed from `[681640e6…,76a007a7…,8e28a8a1…]` to `[76a007a7…,8e28a8a1…,681640e6…]`. The up-arrow then moved the second row to the first position. |
| Rename/delete | Renamed `Road` to `Travel`; all three members remained. Deleted the newly created debug-only `Evening` through its confirmation dialog: the three local tracks remained and no orphan playlist memberships remained. |
| Editor cancellation | Selected a song in the editor, rotated back to portrait and observed `Выбрано: 1`. Back showed a discard confirmation. Confirming exit created no additional playlist. |
| Keyboard | Initial inspection caught search focus reopening the keyboard after closing the add sheet. After the fix, repeating the operation ended with `mInputShown=false`, usable bottom navigation and unchanged playlist membership. While typing in search, bottom navigation/player were absent rather than covered by the keyboard. |
| Layout | Font scale 1.3 kept all four portrait navigation labels readable. Initial landscape inspection exposed a near-zero-height list; the revised compact layout displayed scrollable track rows, and the playlist editor placed name/search side by side. Font scale and orientation were restored afterwards. |
| Logs | Inspected AndroidRuntime and ExoPlayerImplInternal error logs contained no Spotygram error during the debug scenarios. The emulator again showed a boot-time System UI ANR outside Spotygram. |
| Final queue deletion check | Imported a temporary duplicate recording and placed its ID twice in a four-entry queue. Deleting that non-current import removed both entries: the queue became two valid IDs, the current ID was unchanged, and next/play reached PLAYING on the remaining recording without a source error. Final-process AndroidRuntime/ExoPlayerImplInternal error logs were empty. |
| Final offline/background check | With Wi-Fi/mobile data disabled and `mWakefulness=Asleep`, the final release media session remained PLAYING and advanced to 62503 ms. Network settings were restored and the emulator stopped afterwards. This checks local decoding/session progression, not audible output or remote streaming. |

These observations used local recordings and independently imported entries, not a seeded database or a generated fixture suite. Artwork/network behavior and large-library throughput are not inferred from these small local samples. A user-provided 0.1.1 phone screenshot showed 805 indexed tracks, which demonstrates exceeding the former 100-per-chat symptom, but is not proof that every accessible Telegram message was indexed.

Physical-device haptics, touch latency, battery drain, full authorized Telegram operation and ARM64 execution remain outside the local verification. New gestures use Android's platform feedback API, whose actual sensation was not observed on this audio-less emulator.

The release UI also imported and played Ogg Vorbis [Für Elise by Sebion7125](https://commons.wikimedia.org/wiki/File:Fur_Elise.ogg), licensed [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/). The app read its embedded title `Fur Elise` and artist `sebion`; the media session reported PLAYING with advancing position. This recording and the earlier public-domain *The Entertainer* are local demonstration media only, not APK/repository assets. Screenshots show their real imported metadata and ordinary artwork fallbacks.

Cleaning up two duplicate demonstration imports exposed a pre-existing queue problem: deleted imported IDs remained queued and advancing reached `Source error`. The deletion path was changed to remove all matching queued entries before deleting an imported record. It still refuses to delete the currently selected track. Telegram tracks retain their remote references when only their local copy is removed.

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
