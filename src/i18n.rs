use std::borrow::Cow;

pub fn tracks(language: &str, count: usize) -> String {
    let noun = if language == "ru" {
        if (11..=14).contains(&(count % 100)) {
            "записей"
        } else {
            match count % 10 {
                1 => "запись",
                2..=4 => "записи",
                _ => "записей",
            }
        }
    } else if count == 1 {
        "track"
    } else {
        "tracks"
    };
    format!("{count} {noun}")
}

pub fn language(preference: &str) -> String {
    if preference == "ru" || preference == "en" {
        return preference.to_owned();
    }
    #[cfg(target_os = "android")]
    let locale = crate::android::call_string("language", "").unwrap_or_default();
    #[cfg(not(target_os = "android"))]
    let locale = std::env::var("LC_ALL")
        .ok()
        .filter(|v| !v.is_empty())
        .or_else(|| std::env::var("LANG").ok())
        .unwrap_or_default();
    if locale.to_lowercase().starts_with("ru") {
        "ru"
    } else {
        "en"
    }
    .into()
}

pub fn tr<'a>(language: &str, text: &'a str) -> Cow<'a, str> {
    if language != "ru" {
        return Cow::Borrowed(text);
    }
    let translated = match text {
        "Music" => "Музыка",
        "Favorites" => "Избранное",
        "Playlists" => "Плейлисты",
        "Playlist" => "Плейлист",
        "Sources" => "Источники",
        "Settings" => "Настройки",
        "Queue" => "Очередь",
        "On device" => "На устройстве",
        "Profile music" => "Музыка профиля",
        "My profile" => "Мой профиль",
        "Profile" => "Профиль",
        "Chat" => "Чат",
        "Music in Telegram" => "Музыка в Telegram",
        "Chat folders" => "Папки чатов",
        "All chats" => "Все чаты",
        "Archive" => "Архив",
        "Choose forum topic" => "Выбрать тему форума",
        "All sources" => "Все источники",
        "Search music" => "Поиск музыки",
        "Search Telegram chats" => "Поиск чатов Telegram",
        "Loading…" => "Загрузка…",
        "▶   Play all" => "▶   Слушать всё",
        "▶   Play" => "▶   Слушать",
        "Play" => "Слушать",
        "Play playlist" => "Слушать плейлист",
        "≡  Queue" => "≡  Очередь",
        "↓  Save" => "↓  Сохранить",
        "New playlist" => "Новый плейлист",
        "Rename playlist" => "Переименовать плейлист",
        "Delete playlist" => "Удалить плейлист",
        "Save playlist" => "Сохранить плейлист",
        "Playlist name" => "Название плейлиста",
        "Add chats" => "Добавить чаты",
        "Add profile" => "Добавить профиль",
        "Add to library" => "В медиатеку",
        "Remove from library" => "Убрать из медиатеки",
        "Open a profile" => "Открыть профиль",
        "Open profile" => "Открыть профиль",
        "Connect Telegram" => "Подключить Telegram",
        "Sign out of Telegram" => "Выйти из Telegram",
        "Sign out" => "Выйти из аккаунта",
        "Continue" => "Продолжить",
        "Sign in with QR code" => "Войти по QR-коду",
        "Use another phone number" => "Другой номер телефона",
        "Resend code" => "Отправить код повторно",
        "Login code" => "Код входа",
        "Password" => "Пароль",
        "Email address" => "Электронная почта",
        "Email code" => "Код из письма",
        "Telegram is connected." => "Telegram подключён.",
        "Preparing a private Telegram session…" => "Подготовка отдельного сеанса Telegram…",
        "Enter your phone number with its country code. This preview uses a separate session. With the same account, playlists, favorites and library settings sync with stable Spotygram." => {
            "Введи номер телефона с кодом страны. У тестовой версии отдельный сеанс. При входе в один аккаунт плейлисты, избранное и настройки медиатеки синхронизируются со стабильным Spotygram."
        }
        "Telegram needs an email address to continue." => {
            "Для продолжения Telegram запрашивает электронную почту."
        }
        "Import audio files" => "Импортировать аудиофайлы",
        "Clear music cache" => "Очистить кеш музыки",
        "Cancel downloads" => "Отменить загрузки",
        "Check Rust preview updates" => "Проверить тестовые обновления",
        "Quit spotygram rust" => "Закрыть spotygram rust",
        "Your next favorite song starts here." => "Здесь начинается твоя музыка.",
        "Import audio files or connect Telegram." => "Добавь файлы или подключи Telegram.",
        "Recording" => "Аудиозапись",
        "This local recording will be uploaded to your Telegram profile." => {
            "Эта локальная запись будет загружена в твой профиль Telegram."
        }
        "To profile" => "В профиль",
        "Remove from profile" => "Убрать из профиля",
        "Add to favorites" => "В избранное",
        "Remove from favorites" => "Убрать из избранного",
        "Add to playlist" => "В плейлист",
        "Add to queue" => "В очередь",
        "Remove from queue" => "Убрать из очереди",
        "Move up" => "Переместить выше",
        "Move down" => "Переместить ниже",
        "Save on device" => "Сохранить на устройстве",
        "Send to Telegram chat" => "Отправить в Telegram",
        "Open Telegram profile" => "Открыть профиль Telegram",
        "Open original message" => "Открыть исходное сообщение",
        "Remove from this playlist" => "Убрать из плейлиста",
        "Remove local copy" => "Удалить локальную копию",
        "Remove copy" => "Удалить копию",
        "Delete imported copy" => "Удалить импортированную копию",
        "Remove downloaded copy" => "Удалить скачанную копию",
        "Choose chats" => "Выбрать чаты",
        "Music sources" => "Источники музыки",
        "Sort music" => "Сортировка",
        "Newest first" => "Сначала новые",
        "Oldest first" => "Сначала старые",
        "Title" => "Название",
        "Artist" => "Исполнитель",
        "Duration" => "Длительность",
        "Send audio" => "Отправить аудио",
        "Confirm send" => "Подтвердить отправку",
        "Send recording" => "Отправить запись",
        "Confirm" => "Подтвердить",
        "Playback speed" => "Скорость воспроизведения",
        "Playback order" => "Порядок воспроизведения",
        "Repeat" => "Повтор",
        "In order" => "По порядку",
        "Reverse order" => "В обратном порядке",
        "Shuffle" => "Перемешать",
        "Random" => "Случайно",
        "Repeat off" => "Без повтора",
        "Repeat all" => "Повторять всё",
        "Repeat one" => "Повторять одну запись",
        "Bookmark threshold" => "Порог закладок",
        "Minutes" => "Минуты",
        "Save threshold" => "Сохранить порог",
        "Create a playlist from the Playlists tab first." => {
            "Сначала создай плейлист на вкладке «Плейлисты»."
        }
        "Delete this playlist from the synced library? Its recordings will stay available." => {
            "Удалить плейлист из синхронизируемой медиатеки? Аудиозаписи останутся доступны."
        }
        "The app-owned copy will be removed from this device. Deleting an import also removes its favorites and playlist membership from the synced library. Original external files stay intact." => {
            "Копия приложения будет удалена с устройства. Удаление импорта также уберёт эту запись из избранного и плейлистов в синхронизируемой медиатеке. Исходный внешний файл сохранится."
        }
        "Remove downloaded Telegram music, including explicit downloads? Imported files, favorites, playlists and sign-in stay intact." => {
            "Удалить скачанную музыку Telegram, включая сохранённые вручную записи? Импортированные файлы, избранное, плейлисты и вход в аккаунт сохранятся."
        }
        "Sign out of this Rust preview? Its Telegram session and local Telegram catalog will be removed. Imported files stay intact." => {
            "Выйти из тестовой версии? Её сеанс Telegram и локальный каталог Telegram будут удалены. Импортированные файлы сохранятся."
        }
        "Rust preview" => "Тестовая Rust-версия",
        "RUST PREVIEW" => "ТЕСТОВАЯ ВЕРСИЯ",
        "NOW PLAYING" => "СЕЙЧАС ИГРАЕТ",
        "Saving and closing…" => "Сохранение и закрытие…",
        "Recording sent" => "Запись отправлена",
        "Music cache cleared" => "Кеш музыки очищен",
        "You have the latest Rust test build" => "Установлена последняя тестовая Rust-версия",
        "A newer Rust test build is available" => "Доступна новая тестовая Rust-версия",
        "spotygram rust · 0.1.0\nA personal testing build. Local data is separate from the stable app." => {
            "spotygram rust · 0.1.0\nЛичная тестовая версия. Локальные данные хранятся отдельно от стабильного приложения."
        }
        _ => "",
    };
    if !translated.is_empty() {
        return Cow::Borrowed(translated);
    }
    for (prefix, ru) in [
        ("Appearance: ", "Оформление: "),
        ("Language: ", "Язык: "),
        ("Hide duplicates: ", "Скрывать дубликаты: "),
        ("Resume long recordings: ", "Продолжать длинные записи: "),
        ("Downloads over Wi-Fi only: ", "Скачивать только по Wi-Fi: "),
        ("Do not keep played tracks: ", "Удалять прослушанный кеш: "),
        ("Keep original sender: ", "Сохранять отправителя: "),
        ("Keep original caption: ", "Сохранять подпись: "),
        ("Bookmark threshold: ", "Порог закладок: "),
        ("On device: ", "На устройстве: "),
    ] {
        if let Some(value) = text.strip_prefix(prefix) {
            return Cow::Owned(format!(
                "{ru}{}",
                value
                    .replace("On", "вкл.")
                    .replace("Off", "выкл.")
                    .replace("system", "системное")
                    .replace("light", "светлое")
                    .replace("dark", "тёмное")
                    .replace("minutes", "мин.")
                    .replace("tracks", "записей")
                    .replace("MiB", "МиБ")
            ));
        }
    }
    if let Some(count) = text.strip_suffix(" tracks") {
        if let Ok(count) = count.parse::<usize>() {
            return Cow::Owned(tracks(language, count));
        }
    }
    if let Some(count) = text.strip_suffix(" selected") {
        if count.parse::<usize>().is_ok() {
            return Cow::Owned(format!("Выбрано: {count}"));
        }
    }
    Cow::Borrowed(text)
}
