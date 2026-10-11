# Third-party software

[Documentation](README.md) · **English** · [Русский](third-party.ru.md)

| Component | Version / source | License |
| --- | --- | --- |
| TDLib Android | `d1085f9cebc5a62379991ae1652673954f229c1f`, [tdlib/td](https://github.com/tdlib/td) | Boost 1.0 |
| OpenSSL Android | `openssl-3.5.6`, [openssl/openssl](https://github.com/openssl/openssl) | Apache 2.0 |
| Kotlin | 2.4.20 | Apache 2.0 |
| kotlinx.coroutines | 1.10.2 | Apache 2.0 |
| Compose Multiplatform / Skiko | Pinned Gradle dependencies in root and desktop build files | Apache 2.0 |
| AndroidX / Media3 | Exact versions in `app/build.gradle.kts` | Apache 2.0 |
| Coil | 3.3.0 | Apache 2.0 |
| Haze / Haze Glass | 2.0.1, [chrisbanes/haze](https://github.com/chrisbanes/haze) | Apache 2.0 |
| SQLite (TDLib) | Upstream bundled version | Public domain |
| Desktop TDLib prebuilts | `prebuilt-tdlib` 0.1008067.0 | Boost 1.0 for TDLib; bundled components retain their licenses |
| libmpv / FFmpeg desktop runtime | [mpv-prebuilt](https://github.com/jason-yau/mpv-prebuilt/releases/tag/20260906162952), libmpv 0.41.0 LGPL assets | LGPL 2.1+ and component licenses |
| SQLite JDBC | 3.50.3.0 | Apache 2.0 / BSD-2-Clause; SQLite is public domain |
| JNA / JNA Platform | 5.17.0 | Apache 2.0 or LGPL 2.1+ |
| java-keyring | 1.0.4 | BSD-3-Clause |
| jaudiotagger | 3.0.1 | LGPL 2.1+ |
| Nucleus media-control / core-runtime | 2.5.0, [Nucleus](https://github.com/NucleusFramework/Nucleus/tree/v2.5.0) | MIT; bundled code retains its notices |
| Apache Commons Compress | 1.28.0; Commons Lang 3.18.0, IO 2.20.0 and Codec 1.19.0 | Apache 2.0 |
| ZXing core | 3.5.4 | Apache 2.0 |
| JSON-java | 20250517 | Public domain |
| Bundled desktop Java runtime | JDK 21 distribution used by the build runner | GPL 2 with Classpath Exception and component notices |
| Windows installer packaging | [WiX Toolset 3 sources](https://github.com/wixtoolset/wix3/tree/wix3141rtm), supplied by the Windows build runner | [Microsoft Reciprocal License](https://github.com/wixtoolset/wix3/blob/wix3141rtm/LICENSE.TXT); copyright .NET Foundation and contributors |

Android native libraries are compiled from upstream sources. `JsonClient.java`
is a reduced JNI declaration compatible with the upstream interface. Native
license texts and notices are included in APK assets. Desktop uses pinned,
checksum-verified prebuilts without a local native toolchain. Their license texts,
Spotygram's license and additional notices are in application resources under
`licenses/`; JVM dependencies retain their notices inside their JARs and Java's
notices are under the bundled runtime's `legal/` directory.

LGPL dependencies are supplied as separate libraries/JARs and can be replaced.
Sources and build instructions for the pinned audio binaries are available from
the linked mpv-prebuilt release; jaudiotagger sources are available through
[Maven Central](https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/).

UI icons use Material Icons. Compose supplies platform font handling. There are
no Spotify logos, Telegram logos, bundled music files or AI-generated album covers.

The manually imported development recording is [The Entertainer by Scott Joplin](https://commons.wikimedia.org/wiki/File:%22The_Entertainer%22,_by_Scott_Joplin_(1902,_Ragtime_piano).opus), marked public domain on Wikimedia Commons. The audio is not part of the repository or APK.
