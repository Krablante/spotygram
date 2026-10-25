# Third-party software

[Documentation](README.md) · **English** · [Русский](third-party.ru.md)

| Component | Source/version | License |
| --- | --- | --- |
| Makepad | [44ecb822](https://github.com/makepad/makepad/tree/44ecb822b48106fa76bde4dfd309e5a8a7ff1932) | MIT and bundled component notices |
| TDLib Android | [d1085f9c](https://github.com/tdlib/td/tree/d1085f9cebc5a62379991ae1652673954f229c1f) | Boost 1.0 |
| OpenSSL Android | openssl-3.5.6 | Apache 2.0 |
| Desktop TDLib | prebuilt-tdlib 0.1008067.0 | Boost 1.0 and component notices |
| libmpv / FFmpeg | [mpv-prebuilt 20260906162952](https://github.com/jason-yau/mpv-prebuilt/releases/tag/20260906162952), libmpv 0.41.0 LGPL binaries | LGPL 2.1+ and component notices |
| AndroidX Media3 | Pinned in `android/build.gradle.kts` | Apache 2.0 |
| SQLite / rusqlite | Pinned in Cargo.lock | Public domain / MIT |
| Tokio, serde, keyring, souvlaki, rfd and other Rust crates | Exact revisions and versions in Cargo.lock | License expressions and notices included in packages |
| Lofty | Pinned in Cargo.lock | MIT or Apache 2.0 |
| Fonts | Roboto Flex for application text; Makepad's international fallbacks: IBM Plex, Noto, LXGW and Font Awesome; JetBrains Mono for the optional code face | SIL OFL 1.1; copyright/license metadata and notices included in packages |
| Rounded icons | [Google Material Icons](https://github.com/google/material-design-icons), rounded 24 px | Apache 2.0 |
| Header stickers | [Microsoft Fluent Emoji](https://github.com/microsoft/fluentui-emoji), the same PNG selection as the Kotlin app | MIT |

Native library notices remain with their bundles. The build also includes Rust
dependency license texts and font metadata in `licenses/` (Android assets).
Desktop LGPL libraries are separate replaceable files; their sources and build
instructions are linked from the pinned mpv-prebuilt release. No desktop JVM or
Kotlin runtime is included.

The Takopi identity is a photograph supplied by the owner, prepared with its
background removed. The software's MIT license grants no rights to the character
or photograph; its notice is bundled. Application SVGs supplement the rounded
Material set. Development audio is private build/verification state,
not part of the repository or released application. The application contains no
bundled music catalog.
