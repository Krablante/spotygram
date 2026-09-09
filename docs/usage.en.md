# Using Spotygram

[Documentation](README.md) · **English** · [Русский](usage.md)

Spotygram brings audio from selected chats into a music library. Messages stay
in Telegram; favorites, playlists and downloaded copies live on your device.

## First launch

Install the ARM64 APK from the [latest release](https://github.com/Krablante/spotygram/releases/latest).
Android may ask you to allow installation from the browser or file manager
opening the APK. Android 8.0 or newer is required.

Sign in to Telegram with your phone number. The code may arrive in an existing
Telegram session rather than by SMS. If you use a two-step verification
password, enter it next. The published APK does not require developer API keys.

You can skip Telegram and start with local files. **Settings → Import audio files**
opens Android's file picker. You can also share audio to Spotygram from another
app. Importing creates a separate copy in the player's library.

## Add music from chats

Open **Chats → Add chats**, choose Saved Messages, groups or channels, then
confirm your selection. An accessible public channel can also be added by
`@username`. Spotygram does not automatically subscribe you to it.

History loads progressively: you can listen to the first songs while more
appear. The app scans all accessible history, not just the last hundred
messages. Interrupted loading resumes from its saved position. Use refresh
or pull down to update the list; unfinished history can be resumed.

Library search uses music already found. If a song is missing, **Search selected
chats** searches Telegram. Secret chats, voice notes and videos are excluded
from the music library.

## Listen

The four bottom destinations are **Chats**, **Favorites**, **Playlists** and
**Music**. Your last destination is remembered. Tap a song to play; the small
bottom player opens the full player. Lock-screen controls work too.

The full player has seeking, a queue and repeat controls. The order button to
the left of Previous cycles through three modes:

| Mode | How the next song is chosen |
| --- | --- |
| In order | The next entry in the collection |
| Shuffle | A random order with no repeats within a pass |
| Absolute random, the dice icon | An independent draw every time; even consecutive repeats are possible |

Random mode continues until stopped. Previous retraces recent listening
history. The queue shows the selection pool and the next prepared song, not
a promise to play the whole collection in order. **Play next** in a song's menu
takes priority over the random choice.

Repeat one works in all modes. Repeat all works in ordered and shuffled modes;
random is already endless. Reopening the app restores the queue and position
without starting playback automatically.

## Favorites and playlists

The heart beside a song adds it to **Favorites**. Tap again to remove the mark;
the resulting message offers Undo. The file stays in place and playback
continues.

In **Playlists → New playlist**, enter a name and select songs. You can create
an empty playlist and add music later through its add-tracks action.

Long-press a song or tap **Select** to choose several songs at once. Taps then
select rather than play. Searching does not discard your existing selection.
Back exits selection first.

Open a playlist's action menu, or long-press the playlist, to rename, delete
or reorder it. In the ordering view, hold the handle beside a song and drag;
up/down arrows are also available. Removing a song from a playlist or deleting
the playlist does not delete the audio files.

## Listen offline

By default, a fully downloaded song gains an offline icon and stays on the device. Use
**Download** on a song or collection to save music in advance. Settings lets
you restrict these downloads to Wi-Fi and resume interrupted downloads.
The Wi-Fi restriction does not apply to ordinary playback.

**On device** shows retained local files, excluding temporary playback copies. When several messages reference one
file, it appears once. Separate copies with the same title are not merged.
Search and source filters may narrow the list further.

## Listen without keeping every song

Enable **Settings → Do not keep played tracks** to use temporary playback copies.
It is off by default. Previously saved files, explicit downloads and imported
audio remain saved. Temporary copies have a clock icon; **Save on device** in
the player or song menu keeps a copy without downloading it again.

The current, previous and prepared next files remain while the player needs
them. Older temporary copies are removed when no reader uses them. Going
further back may require another download and internet access. Telegram still
uses temporary disk files; this is not RAM-only playback or a strict storage cap.
If cleanup is deferred, Settings offers Retry. Turning the mode off keeps the
remaining copies; it cannot restore those already deleted.

## Free up space

**Remove from device** in a song's menu removes its local copy, not the Telegram
message. Files still in the player's working window or being read cannot be removed.

**Settings → Clear music cache** shows space occupied by downloaded Telegram
audio. Imported files are excluded from that total.

**Cleanup also removes songs you explicitly saved with Download.** Playback
copies and explicit downloads share Telegram's storage. This manual action has
a broader scope than automatic removal of temporary copies. The confirmation
asks to stop playback and clear them; playback and downloads stop, and the
result reports the space freed.

Favorites, playlists, imported files and sign-in remain. Downloading again
requires access to the original message: if it is gone, the deleted copy may
not be recoverable. Cleanup requires a connected Telegram account.

## Language and appearance

The interface follows your phone language: English or Russian, with English
for other languages. On Android 13+, **Settings → Language** opens a per-app
language setting. Earlier Android versions follow the phone's language.
Song and chat names are not translated.

Choose light, dark or system appearance in Settings. Switching themes does
not interrupt playback.

## Updates and keeping your data

Automatic checks are enabled by default. They happen only when opening or
returning to the app, at most once a day. Failed checks count as attempts.
When there is no newer version, the app stays silent.

The update banner offers **Download and install**, also available in Settings.
Its close button dismisses that version; the offer remains in Settings.
Settings also lets you disable automatic checks or **Check for updates** manually. Repeated
manual attempts have a one-minute minimum interval, longer if GitHub limits
requests. Prereleases are excluded.

After you tap the button, Android's DownloadManager fetches the APK for your
device. The app shows progress and cancellation; the transfer can continue
while Spotygram is closed. Reopening picks up its current state.

Before installation, the app checks size, SHA-256, package, version and signing
certificate. If Android asks to allow installation from Spotygram, enable it
and return; the app continues to the installer. Denying permission does not
cause repeated prompts. **Install update** lets you try again later. Android
still requires your confirmation to install.

A ready APK can be installed again without another download, or deleted in
Settings. Cancellation removes temporary files; a successful update cleans up
the APK on the next launch. **View release** remains available for reading the
changes on GitHub. Automatic checks only offer updates: they never start a
download or installer by themselves.

Installing a new official APK
over the old one preserves data. **Uninstalling the app removes its library
and settings.** Playlists are not synced to the cloud.

Telegram sign-in creates a full account session, not access restricted to the
chosen chats. You can revoke it from the official Telegram app's device settings.
