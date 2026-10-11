# Стороннее ПО

[Документация](README.ru.md) · [English](third-party.md) · **Русский**

| Компонент | Версия / источник | Лицензия |
| --- | --- | --- |
| TDLib Android | `d1085f9cebc5a62379991ae1652673954f229c1f`, [tdlib/td](https://github.com/tdlib/td) | Boost 1.0 |
| OpenSSL Android | `openssl-3.5.6` | Apache 2.0 |
| Kotlin / kotlinx.coroutines | 2.4.20 / 1.10.2 | Apache 2.0 |
| Compose Multiplatform / Skiko | Версии закреплены в Gradle | Apache 2.0 |
| AndroidX / Media3 | Версии в `app/build.gradle.kts` | Apache 2.0 |
| Coil / Haze / Haze Glass | 3.3.0 / 2.0.1 / 2.0.1 | Apache 2.0 |
| SQLite внутри TDLib | Версия поставки TDLib | Public domain |
| Готовый TDLib desktop | `prebuilt-tdlib` 0.1008067.0 | Boost 1.0; компоненты сохраняют свои лицензии |
| libmpv / FFmpeg desktop | [mpv-prebuilt](https://github.com/jason-yau/mpv-prebuilt/releases/tag/20260906162952), LGPL-сборки libmpv 0.41.0 | LGPL 2.1+ и лицензии компонентов |
| SQLite JDBC | 3.50.3.0 | Apache 2.0 / BSD-2-Clause; SQLite — public domain |
| JNA / JNA Platform | 5.17.0 | Apache 2.0 или LGPL 2.1+ |
| java-keyring | 1.0.0 | BSD-3-Clause |
| jaudiotagger | 3.0.1 | LGPL 2.1+ |
| Nucleus media-control / core-runtime | 2.5.0, [Nucleus](https://github.com/NucleusFramework/Nucleus/tree/v2.5.0) | MIT; встроенный код сохраняет свои уведомления |
| Apache Commons Compress / ZXing core | 1.27.1 / 3.5.4 | Apache 2.0 |
| JSON-java | 20250517 | Public domain |
| Встроенный Java-рантайм desktop | JDK 21 сборочного runner | GPL 2 с Classpath Exception и уведомления компонентов |

Нативные библиотеки Android собираются из upstream-исходников; `JsonClient.java`
содержит сокращённые совместимые JNI-декларации. Лицензии нативных компонентов
включены в assets APK. Desktop использует закреплённые готовые сборки с проверкой
контрольных сумм. Их лицензии, лицензия Spotygram и дополнительные уведомления
находятся в ресурсах приложения в `licenses/`. Java-зависимости сохраняют
уведомления внутри JAR; лицензии Java находятся в `legal/` встроенного рантайма.

LGPL-компоненты поставляются отдельными библиотеками/JAR и допускают замену.
Исходники и инструкции сборки звуковых библиотек доступны в указанном релизе
mpv-prebuilt; исходники jaudiotagger — в
[Maven Central](https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/).

Иконки UI — Material Icons, работу со шрифтами предоставляет Compose.
Логотипов Spotify и Telegram, встроенной музыки и сгенерированных обложек нет.
Демонстрационная запись [The Entertainer Скотта Джоплина](https://commons.wikimedia.org/wiki/File:%22The_Entertainer%22,_by_Scott_Joplin_(1902,_Ragtime_piano).opus)
помечена public domain на Wikimedia Commons; она не входит в репозиторий или приложение.
