# Сторонние компоненты

[Документация](README.ru.md) · [English](third-party.md) · **Русский**

| Компонент | Источник/версия | Лицензия |
| --- | --- | --- |
| Makepad | [44ecb822](https://github.com/makepad/makepad/tree/44ecb822b48106fa76bde4dfd309e5a8a7ff1932) | MIT и уведомления включённых компонентов |
| TDLib Android | [d1085f9c](https://github.com/tdlib/td/tree/d1085f9cebc5a62379991ae1652673954f229c1f) | Boost 1.0 |
| OpenSSL Android | openssl-3.5.6 | Apache 2.0 |
| Desktop TDLib | prebuilt-tdlib 0.1008067.0 | Boost 1.0 и уведомления компонентов |
| libmpv / FFmpeg | [mpv-prebuilt 20260906162952](https://github.com/jason-yau/mpv-prebuilt/releases/tag/20260906162952), LGPL-сборки libmpv 0.41.0 | LGPL 2.1+ и уведомления компонентов |
| AndroidX Media3 | Закреплён в `android/build.gradle.kts` | Apache 2.0 |
| SQLite / rusqlite | Закреплены в Cargo.lock | Public domain / MIT |
| Tokio, serde, keyring, souvlaki, rfd и остальные Rust-библиотеки | Точные версии и ревизии в Cargo.lock | Обозначения лицензий и тексты включены в пакеты |
| Lofty | Закреплён в Cargo.lock | MIT или Apache 2.0 |
| Шрифты | Roboto Flex для текста приложения; международные fallback-шрифты Makepad: IBM Plex, Noto, LXGW и Font Awesome; JetBrains Mono для необязательного моноширинного начертания | SIL OFL 1.1; метаданные авторства/лицензий и уведомления включены в пакеты |
| Округлые иконки | [Google Material Icons](https://github.com/google/material-design-icons), rounded 24 px | Apache 2.0 |
| Стикеры шапки | [Microsoft Fluent Emoji](https://github.com/microsoft/fluentui-emoji), тот же набор PNG, что в Kotlin-версии | MIT |

Уведомления нативных библиотек остаются в их пакетах. Сборка также включает тексты
лицензий Rust-зависимостей и метаданные шрифтов в `licenses/` (assets Android).
Компьютерные LGPL-библиотеки поставляются отдельными заменяемыми файлами; ссылки
на исходники и инструкции сборки есть у закреплённого релиза mpv-prebuilt.
Компьютерные JVM и Kotlin runtime не входят в поставку.

Образ Takopi — предоставленная владельцем фотография с удалённым фоном. MIT
программного кода не предоставляет прав на персонажа или фото; уведомление
включено в сборки. Собственные SVG приложения дополняют округлый набор Material.
Аудио для разработки находится в приватном состоянии сборки и проверки, а не в
репозитории или приложении. Встроенного музыкального каталога нет.
