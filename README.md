<p align="center">
  <strong>English</strong> · <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <img src="docs/banner.svg" alt="Spotygram — Android music player" width="100%">
</p>

<p align="center">
  <strong>The songs are in your chats. This is where you listen.</strong>
</p>

<p align="center">
  <a href="https://github.com/Krablante/spotygram/releases/latest"><strong>Download APK</strong></a> ·
  <a href="docs/usage.en.md">User guide</a> ·
  <a href="docs/README.md">Documentation</a>
</p>

## From a chat to a music library

A song in Saved Messages, an album in a channel, a few recordings from a friend.
Telegram holds the files, but finding the next song should not mean scrolling
through a conversation.

Spotygram is an Android player for that collection. Choose the chats you want
to listen to, and their audio appears in one searchable library. Save favorites,
make playlists, download music for later. You can also import files from your
phone and use the player without connecting Telegram.

<p align="center">
  <img src="docs/screenshots/library-dark.png" width="30%" alt="Music library in the dark theme">
  &nbsp;
  <img src="docs/screenshots/player-light.png" width="30%" alt="Full player in the light theme">
  &nbsp;
  <img src="docs/screenshots/playlists-light.png" width="30%" alt="Playlists">
</p>

## Put it on your phone

Download **`spotygram-…-arm64.apk`** from the [latest release](https://github.com/Krablante/spotygram/releases/latest).
It requires **Android 8.0 or newer** and a 64-bit ARM device. The separate
`x86_64` APK is for x86-64 Android environments; there is no 32-bit ARM build.

Install the APK, connect your Telegram account, then open **Chats → Add chats**.
Choose Saved Messages, groups or channels containing music. You do not need
developer API keys to use the published APK. Prefer local files? Skip sign-in
and choose **Settings → Import audio files**.

Install future APKs over the existing app to keep your library. Spotygram checks
for new releases when you open it, at most once a day. It stays quiet unless
there is an update. You can dismiss that version's reminder, turn automatic
checks off, or check manually in Settings. **Download and install** is available
in both the update banner and Settings: it downloads the right APK, verifies it
and opens Android's installation confirmation. There is no need to find the
file on GitHub. Nothing downloads until you choose it.

## While you listen

Playback continues in the background, with controls on the lock screen.
The small player opens the full player and queue. Long-press a song to select
several tracks for a playlist or download; drag tracks to reorder a playlist.

The order button cycles through **in order**, **shuffle**, and **absolute random**.
Shuffle plays a pass without repeats. The dice mode draws each next song
independently, so the same song can come up twice in a row. It keeps going
until you stop it; Previous retraces your recent listening history.

Downloaded songs are available under **On device**. The interface comes in
English and Russian, with light, dark and system themes.

Repeated recordings are hidden by default. Turn **Settings → Hide duplicates**
off to see separate entries again. This only changes the lists; nothing is deleted.

Prefer not to keep every song? Turn on **Do not keep played tracks** in Settings.
The player keeps a temporary working set and removes older playback copies.
Existing files, explicit downloads and imports stay saved; **Save on device**
keeps a temporary copy. The mode is off by default and still uses temporary
disk space. Returning to older songs may require another download.

## Your files and your account

Music, playlists and the Telegram session live on the device. Spotygram has no
backend, separate registration or analytics. It connects directly to Telegram;
release checks contact GitHub, which sees your connection IP and app version.

A Telegram sign-in grants a **full account session**. Choosing source chats
limits what Spotygram indexes, not what that session is permitted to access.
You can revoke it from Telegram's device settings.

**Clear music cache also removes Telegram songs you explicitly downloaded.**
Imported files, favorites, playlists and sign-in remain. If the original
message is no longer accessible, a deleted local copy may not be recoverable.
Uninstalling Spotygram removes its local music and settings; playlists are
not backed up to the cloud.

## Under the cover

One Kotlin/Compose app, TDLib for Telegram, Media3 for playback, SQLite for the
library. No web view or streaming proxy. The player exposes at most three
items to Android's media session while keeping the full queue inside the app.

For the details, start with [architecture](docs/architecture.md) or
[building from source](docs/build.md). The [documentation index](docs/README.md)
links both language editions.

This is an early public release. Secret chats, voice notes and playlist sync
are not supported. The [verification record](docs/verification.md) separates
observed behavior from untested cases, including physical-device and
authenticated Telegram checks. Screenshots show the real app with local
demonstration recordings, not music bundled with the APK.

## License

[MIT](LICENSE). Dependencies retain [their own licenses](docs/third-party.md).
Spotygram is an unofficial app using the Telegram API and is not affiliated
with Telegram or Spotify.
