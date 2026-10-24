# Сборка Rust-версии

[English](build.md) · [Русский](build.ru.md) · [Документация](README.ru.md)

Используй ветку `rust-rewrite`. Исходники публичны. Учётные данные, ключи
подписи, сеансы Telegram и собранные пакеты должны храниться вне репозитория.

## Компьютеры

Нужны Rust **1.97.0**, Python **3.12+**, инструменты C/C++ для платформы и Git.
На Windows установи Visual Studio C++ Build Tools и Windows SDK. На macOS —
инструменты командной строки Xcode. Ревизия Makepad закреплена в Cargo.toml,
версии зависимостей — в Cargo.lock.

На Ubuntu 24.04 установи нативные зависимости сборки:

```sh
sudo apt-get install build-essential pkg-config libx11-dev libxcursor-dev \
  libxrandr-dev libxi-dev libxinerama-dev libxkbcommon-dev libwayland-dev \
  libasound2-dev libpulse-dev libegl1-mesa-dev libgl1-mesa-dev \
  libgles2-mesa-dev libdbus-1-dev
```

Выбери приватный каталог данных сборки и запусти упаковщик:

```sh
export SPOTYGRAM_STATE="$HOME/.local/state/spotygram"
python3 -B tools/build-rust.py
```

PowerShell:

```powershell
$env:SPOTYGRAM_STATE = Join-Path $env:LOCALAPPDATA 'spotygram-build'
python -B tools/build-rust.py
```

Для упаковки Windows и macOS также нужен Pillow (`python3 -m pip install Pillow==12.0.0`).
Упаковщик создаёт иконку, собирает нативное приложение, добавляет ресурсы Makepad,
закреплённые библиотеки TDLib/libmpv и локально подписывает macOS-приложение.
Приватные аудиозависимости Linux не включают libstdc++ и libgcc, чтобы графический
драйвер использовал C++ runtime системы.
В macOS нативные библиотеки лежат в `Contents/Frameworks`, а ресурсы Makepad и
лицензии — в `Contents/Resources`, чтобы подпись bundle корректно их обрабатывала.

Каталог приложения появляется в `SPOTYGRAM_STATE/rust/images/<target>`, архивы
и SHA-256 — в `SPOTYGRAM_STATE/rust/releases/<version>`. Desktop-пакеты переносимы;
стабильные установщики и Java runtime в них не входят. `--image-only` пропускает
создание архива, `--native-only` готовит только нативные desktop-зависимости.

Для входа в Telegram задай `TELEGRAM_API_ID` и `TELEGRAM_API_HASH` в окружении
сборки или в файле `SPOTYGRAM_STATE/telegram.env`:

```dotenv
TELEGRAM_API_ID=your_application_id
TELEGRAM_API_HASH=your_application_hash
```

Для собственного распространения получи API-данные приложения на
https://my.telegram.org. Этот файл нельзя коммитить. Сборка без API-данных
импортирует и воспроизводит локальные файлы, а при подключении Telegram сообщает
об отсутствии конфигурации. Публикуемые сборки владельца используют существующую
приватную конфигурацию.

## Android

Собирай на Linux с JDK **21**, Android SDK platform **37**, NDK
**28.2.13676358**, Ninja, CMake и инструментами командной строки Android SDK.
Gradle wrapper загружает Gradle 9.3.1, Android-проект использует AGP 9.1.1.
Приложение нацелено на Android 36 и поддерживает API 26+.

```sh
export SPOTYGRAM_STATE="$HOME/.local/state/spotygram"
export ANDROID_HOME=/absolute/path/to/android-sdk
rustup target add aarch64-linux-android x86_64-linux-android
python3 -B tools/build-rust.py --android
```

Упаковщик использует кеш закреплённых нативных зависимостей или собирает C API
TDLib со статическим OpenSSL. Rust использует линкеры NDK и выравнивание страниц
16 КиБ. Общий интерфейс, логика и JNI-мост собираются в `libmakepad.so`, TDLib —
в `libtdjson.so`. Java-файлы платформы Makepad, ресурсы и нативные результаты
готовятся вне исходников. Затем Gradle собирает и проверяет подписанные APK.
Для одной архитектуры добавь `--abi arm64-v8a` или `--abi x86_64`.

Подпись релизов владельца использует существующие приватные файлы:

```text
SPOTYGRAM_STATE/release.jks
SPOTYGRAM_STATE/signing.properties
```

В properties задаётся `storePassword`; существующий ключ `spotygram` использует
тот же пароль. Другой распространитель должен предоставить своё хранилище
ключей. При обновлении того же Rust-приложения сохраняй ключ владельца.
Экспериментальный ID — `app.spotygram.rust`, поэтому установка не заменяет
`app.spotygram`.

## Проверки и публикация

По умолчанию используются два worker. На Linux сборка также ограничивается
двумя CPU. Нативную сборку, Gradle и эмулятор запускай последовательно.
Release-профиль включает оптимизацию, thin LTO и удаление отладочных символов.

```sh
cargo fmt --all -- --check
cargo check --locked -j2
```

Эти проверки сами по себе не подтверждают корректность скриптов Makepad,
отрисовки и поведения устройств. Запусти упакованное release-приложение
с отдельным профилем, пройди его сценарии и посмотри реальные кадры GPU.
Звук, фон, батарею и плавность проверяй на физических устройствах.
Для проверки нельзя открывать посторонние сеансы Telegram владельца.

Ручной GitHub workflow **Rust preview desktop builds** собирает четыре
desktop-варианта из выбранного ref. Он использует существующие секреты API
Telegram и сохраняет архивы с контрольными суммами. Автоматической публикации нет.
Поле `target` позволяет пересобрать одну платформу; по умолчанию выбрано `all`.

Создай черновик релиза с тегом `rust-v<version>` из `rust-rewrite`. Загрузи
подписанные APK, desktop-архивы и соответствующие суммы. Проверь комплект,
затем опубликуй с включённым **prerelease** и выключенным **latest**. Обычные
числовые теги и канал последнего стабильного релиза относятся к `main`.
