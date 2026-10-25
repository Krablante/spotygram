use std::borrow::Cow;

pub fn speed(language: &str, value: f64) -> String {
    let number = format!("{value:.2}");
    let number = number.trim_end_matches('0').trim_end_matches('.');
    format!(
        "{}×",
        if language == "ru" {
            number.replace('.', ",")
        } else {
            number.to_owned()
        }
    )
}

pub fn bytes(language: &str, value: u64) -> String {
    let mut amount = value as f64;
    let mut unit = 0;
    while amount >= 1024. && unit < 3 {
        amount /= 1024.;
        unit += 1;
    }
    let number = if unit == 0 {
        value.to_string()
    } else {
        format!("{amount:.1}")
            .trim_end_matches('0')
            .trim_end_matches('.')
            .to_owned()
    };
    let units = if language == "ru" {
        ["Б", "КиБ", "МиБ", "ГиБ"]
    } else {
        ["B", "KiB", "MiB", "GiB"]
    };
    format!(
        "{} {}",
        if language == "ru" {
            number.replace('.', ",")
        } else {
            number
        },
        units[unit]
    )
}

pub fn files(language: &str, value: u64) -> String {
    let word = if language == "ru" {
        if (11..=14).contains(&(value % 100)) {
            "файлов"
        } else {
            match value % 10 {
                1 => "файл",
                2..=4 => "файла",
                _ => "файлов",
            }
        }
    } else if value == 1 {
        "file"
    } else {
        "files"
    };
    format!("{value} {word}")
}

pub fn tracks(language: &str, count: usize) -> String {
    let noun = if language == "ru" {
        if (11..=14).contains(&(count % 100)) {
            "треков"
        } else {
            match count % 10 {
                1 => "трек",
                2..=4 => "трека",
                _ => "треков",
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
        "Favorites" => "Любимые",
        "Playlists" => "Плейлисты",
        "Playlist" => "Плейлист",
        "Sources" => "Источники",
        "Profiles" => "Профили",
        "Add" => "Добавить",
        "Add source" => "Добавить источник",
        "Choose source" => "Выбрать источник",
        "Manage sources" => "Управление источниками",
        "Quick access" => "Быстрый доступ",
        "Saved messages" => "Избранное",
        "Private chat" => "Личный чат",
        "Group" => "Группа",
        "Channel" => "Канал",
        "From you" => "От вашего имени",
        "Original sender" => "Исходный автор",
        "Original caption" => "Исходная подпись",
        "No caption" => "Без подписи",
        "Send to" => "Отправить в",
        "Choose a chat" => "Выберите чат",
        "Chats" => "Чаты",
        "Me" => "Я",
        " · In library" => " · В медиатеке",
        "Settings" => "Настройки",
        "Queue" => "Очередь",
        "On device" => "На устройстве",
        "Download" => "Скачать",
        "Keep on device" => "Сохранить",
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
        "Search music" => "Поиск в музыке",
        "Search profile" => "Поиск в профиле",
        "Search favorites" => "Поиск в любимых",
        "Find a playlist" => "Найти плейлист",
        "Select all" => "Выбрать всё",
        "Deselect all" => "Снять выбор",
        "In library" => "В медиатеке",
        "Profile music in Telegram" => "Музыка профиля Telegram",
        "Search sources" => "Поиск источников",
        "Play all" => "Слушать всё",
        "Appearance" => "Оформление",
        "Resume long recordings" => "Запоминать позицию",
        "Remember audio positions" => "Запоминать позицию",
        "Audio duration threshold" => "Порог длительности",
        "Hide duplicates" => "Скрывать дубли",
        "Do not keep played tracks" => "Не сохранять прослушанное",
        "Downloads over Wi-Fi only" => "Скачивать только по Wi-Fi",
        "Keep original sender" => "Сохранять отправителя",
        "Keep original caption" => "Сохранять подпись",
        "Sharing audio" => "Отправка аудио",
        "Music cache" => "Кеш музыки",
        "Calculating…" => "Подсчитываем…",
        "Downloaded Telegram audio and documents, including saved recordings, will be removed from this device." => {
            "Скачанная музыка и аудиодокументы Telegram, включая сохранённые вручную записи, будут удалены с этого устройства."
        }
        "Imported files, favorites, playlists and sign-in stay intact." => {
            "Импортированные файлы, любимые треки, плейлисты и вход в аккаунт сохранятся."
        }
        "Sender and original caption" => "Автор пересылки и исходная подпись",
        "Language" => "Язык",
        "System" => "Системный",
        "Connect Telegram to play this recording" => {
            "Подключи Telegram, чтобы послушать эту запись"
        }
        "Connect your Telegram account first" => "Сначала подключи аккаунт Telegram",
        "Enter a playlist name of up to 80 characters" => {
            "Введи название плейлиста, не длиннее 80 символов"
        }
        "Recording no longer exists" => "Этой записи больше нет в медиатеке",
        "Choose English, Russian or your device language." => {
            "Выбери английский, русский или язык устройства."
        }
        "Resume long recordings where you left off." => {
            "Длинные записи продолжаются с места остановки."
        }
        "Show one entry per recording in Music, Favorites and On device. Nothing is deleted. Existing playlists and the current queue stay unchanged." => {
            "Одна строка на песню в «Музыке», «Любимых» и «На устройстве». Ничего не удаляется. Готовые плейлисты и текущая очередь не меняются."
        }
        "Keep a temporary playback buffer instead of adding every song to On device. Previously stored files, explicit downloads and imports stay." => {
            "Оставлять временный буфер, не добавляя каждую песню в «На устройстве». Старые файлы, явные скачивания и импорт сохраняются."
        }
        "Applies to downloads, not streaming playback" => {
            "Для кнопки «Скачать», не для прослушивания"
        }
        "Light" => "Светлая",
        "Dark" => "Тёмная",
        "Sharing" => "Отправка музыки",
        "PLAYING FROM" => "ИГРАЕТ ИЗ",
        "From device" => "С устройства",
        "No results" => "Ничего не найдено",
        "Try another search or source." => "Попробуй другой запрос или источник.",
        "Your favorite music is here." => "Здесь будут любимые треки.",
        "Tap the heart next to a recording." => "Нажми на сердечко рядом с треком.",
        "No playlists yet." => "Пока нет плейлистов.",
        "Create a playlist for your music." => "Создай плейлист для своей музыки.",
        "This playlist is empty." => "В плейлисте пока нет музыки.",
        "Add recordings from your library." => "Добавь треки из медиатеки.",
        "Continue from where you stopped." => "Продолжать с того места, где остановился.",
        "Show one copy of the same recording." => "Показывать одну копию одинаковых записей.",
        "Saved music and imported recordings." => "Сохранённая музыка и импортированные файлы.",
        "Keep saved recordings; remove old temporary listening copies." => {
            "Сохранённые треки останутся. Старые временные копии будут удаляться."
        }
        "Manage downloaded music on this device." => {
            "Управление скачанной музыкой на этом устройстве."
        }
        "Mobile data is still used for playback." => {
            "Для прослушивания мобильный интернет доступен."
        }
        "Search Telegram chats" => "Поиск чатов Telegram",
        "Loading…" => "Загрузка…",
        "▶   Play all" => "▶   Слушать всё",
        "▶   Play" => "▶   Слушать",
        "Play" => "Слушать",
        "Play playlist" => "Слушать плейлист",
        "≡  Queue" => "≡  Очередь",
        "↓  Save" => "↓  Сохранить",
        "New playlist" => "Новый плейлист",
        "Create playlist" => "Создать плейлист",
        "Add recordings" => "Добавить треки",
        "Keep what you love" => "То, что нравится",
        "Tap the heart next to a song to find it here." => {
            "Нажми сердечко рядом с песней — она появится здесь."
        }
        "Add some music?" => "Добавим музыку?",
        "Choose several songs from your library at once." => {
            "Выбери сразу несколько песен из своей медиатеки."
        }
        "No music available on this profile" => "В профиле нет доступной музыки",
        "Add songs to your profile in Telegram, then refresh this list." => {
            "Добавь песни в свой профиль в Telegram, затем обнови этот список."
        }
        "The profile may have no songs, or its privacy settings may hide them from you." => {
            "В профиле может не быть песен, либо настройки приватности скрывают их от тебя."
        }
        "All available tracks are already in this playlist, or your library is empty." => {
            "Все доступные треки уже в плейлисте, или медиатека пока пуста."
        }
        "Open in Telegram" => "Открыть в Telegram",
        "Title or artist" => "Название или исполнитель",
        "Discard playlist changes?" => "Отменить изменения?",
        "The name and selected recordings have not been saved." => {
            "Название и выбранные треки ещё не сохранены."
        }
        "Discard" => "Не сохранять",
        "Remove" => "Убрать",
        "Rename playlist" => "Переименовать плейлист",
        "Delete playlist" => "Удалить плейлист",
        "Save playlist" => "Сохранить плейлист",
        "Playlist name" => "Название плейлиста",
        "Add chats" => "Добавить чаты",
        "Add profile" => "Добавить профиль",
        "Add to library" => "В медиатеку",
        "Remove from library" => "Убрать из медиатеки",
        "Remove source" => "Убрать источник",
        "Disconnect this source from your library?" => "Убрать этот источник из медиатеки?",
        "Open a profile" => "Открыть профиль",
        "Open profile" => "Открыть профиль",
        "Connect Telegram" => "Подключить Telegram",
        "Sign out of Telegram" => "Выйти из Telegram",
        "Sign out" => "Выйти из аккаунта",
        "Continue" => "Продолжить",
        "Sign in with QR code" => "Войти по QR-коду",
        "Confirm sign-in in Telegram" => "Подтвердите вход в Telegram",
        "In Telegram on your phone, open Settings → Devices → Link Desktop Device and scan this code." => {
            "В Telegram на телефоне откройте «Настройки → Устройства → Подключить устройство» и отсканируйте код."
        }
        "Enter the code sent to you in Telegram." => "Введи код из сообщения Telegram.",
        "Enter the code sent to you by SMS." => "Введи код из SMS.",
        "Enter the code from the Telegram phone call." => "Введи код из звонка Telegram.",
        "Enter the code from the missed call number." => "Введи код из номера пропущенного звонка.",
        "Enter the code provided by Fragment." => "Введи код, полученный через Fragment.",
        "Enter your Telegram sign-in code." => "Введи код входа в Telegram.",
        "Use another phone number" => "Другой номер телефона",
        "Resend code" => "Отправить код повторно",
        "Login code" => "Код входа",
        "Password" => "Пароль",
        "Enter your Telegram password." => "Введи пароль Telegram.",
        "Hint:" => "Подсказка:",
        "Enter the code sent to your email:" => "Введи код, отправленный на почту:",
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
        "A personal testing build. Local data is separate from the stable app." => {
            "Личная тестовая версия. Локальные данные хранятся отдельно от стабильного приложения."
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
