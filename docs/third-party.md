# Third-party software

| Component | Version / source | License |
| --- | --- | --- |
| TDLib | `d1085f9cebc5a62379991ae1652673954f229c1f`, [tdlib/td](https://github.com/tdlib/td) | Boost 1.0 |
| OpenSSL | `openssl-3.5.6`, [openssl/openssl](https://github.com/openssl/openssl) | Apache 2.0 |
| Kotlin | 2.2.10 | Apache 2.0 |
| kotlinx.coroutines | 1.10.2 | Apache 2.0 |
| Compose | BOM 2025.08.01 | Apache 2.0 |
| AndroidX / Media3 | Exact versions in `app/build.gradle.kts` | Apache 2.0 |
| Coil | 3.3.0 | Apache 2.0 |
| SQLite (TDLib) | Upstream bundled version | Public domain |

Native libraries are compiled from upstream sources, not downloaded from an unofficial binary distributor. `JsonClient.java` is a reduced JNI declaration compatible with the upstream interface. Native license texts and attribution notices are included in APK assets.

UI icons use Material Icons. Fonts are supplied by Android. There are no Spotify logos, Telegram logos, bundled music files, or AI-generated album covers.

The manually imported development recording is [The Entertainer by Scott Joplin](https://commons.wikimedia.org/wiki/File:%22The_Entertainer%22,_by_Scott_Joplin_(1902,_Ragtime_piano).opus), marked public domain on Wikimedia Commons. The audio is not part of the repository or APK.
