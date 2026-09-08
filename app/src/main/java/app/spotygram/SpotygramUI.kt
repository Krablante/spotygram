package app.spotygram

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

data class Playing(
    val id: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val ids: List<String> = emptyList(),
    val index: Int = 0,
    val order: List<Int> = emptyList(),
)

@Composable
private fun observePlayer(player: Player?): Playing {
    var state by remember { mutableStateOf(Playing()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        fun snapshot() {
            if (player == null) return
            val order = mutableListOf<Int>()
            val timeline = player.currentTimeline
            var window = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
            while (
                window != androidx.media3.common.C.INDEX_UNSET && order.size < player.mediaItemCount
            ) {
                order += window
                window =
                    timeline.getNextWindowIndex(
                        window,
                        Player.REPEAT_MODE_OFF,
                        player.shuffleModeEnabled,
                    )
            }
            state =
                Playing(
                    player.currentMediaItem?.mediaId.orEmpty(),
                    player.isPlaying,
                    player.playbackState == Player.STATE_BUFFERING,
                    player.currentPosition.coerceAtLeast(0),
                    player.duration.coerceAtLeast(0),
                    player.shuffleModeEnabled,
                    player.repeatMode,
                    (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
                    player.currentMediaItemIndex.coerceAtLeast(0),
                    order,
                )
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(p: Player, e: Player.Events) = snapshot()
            }
        player?.addListener(listener)
        snapshot()
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(500)
                if (player != null)
                    state =
                        state.copy(
                            position = player.currentPosition.coerceAtLeast(0),
                            duration = player.duration.coerceAtLeast(0),
                        )
            }
        }
    }
    return state
}

fun togglePlayback(player: Player?) {
    if (player == null) return
    if (player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        player.pause()
    else {
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotygramUI(
    app: SpotygramApp,
    player: MediaController?,
    onImport: () -> Unit,
    onDownload: (List<String>) -> Unit,
) {
    val library by app.library.state.collectAsStateWithLifecycle()
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val busy by app.busy.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    val download by app.downloading.collectAsStateWithLifecycle()
    val playing = observePlayer(player)
    val current = library.tracks.firstOrNull { it.id == playing.id }
    var localMode by rememberSaveable { mutableStateOf(app.prefs.getBoolean("local_mode", false)) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var playlistDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var playlistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var confirmLogout by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { app.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(auth.type) { if (auth.type == "authorizationStateReady") connect = false }
    val authVisible =
        connect ||
            (!localMode && auth.type != "authorizationStateReady" && library.tracks.isEmpty())
    val scope = rememberCoroutineScope()
    fun play(track: Track, tracks: List<Track>) {
        if (player == null) {
            app.notices.tryEmit("Плеер подключается…")
            return
        }
        val available = tracks.filter { it.local || it.available }
        val index = available.indexOfFirst { it.id == track.id }
        if (index < 0) {
            app.notices.tryEmit("Нет локальной копии. Исходное сообщение удалено.")
            return
        }
        player.setMediaItems(available.map { it.mediaItem() }, index, 0)
        player.prepare()
        player.play()
    }
    BackHandler(enabled = fullPlayer || sourceId != null || playlistId != null || connect) {
        when {
            fullPlayer -> fullPlayer = false
            connect -> connect = false
            else -> {
                sourceId = null
                playlistId = null
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (authVisible) {
            AuthScreen(
                app,
                auth,
                onLocal = {
                    localMode = true
                    app.localMode()
                    connect = false
                },
                onBack = {
                    localMode = true
                    app.localMode()
                    connect = false
                },
            )
        } else if (fullPlayer && current != null) {
            PlayerScreen(
                current,
                playing,
                player,
                onBack = { fullPlayer = false },
                onMore = {
                    selectedTrack = current
                    sheet = "track"
                },
                onLike = { app.action { app.library.like(current) } },
                onDownload = { onDownload(listOf(current.id)) },
                onQueue = { sheet = "queue" },
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        if (current != null)
                            MiniPlayer(
                                current,
                                playing,
                                onOpen = { fullPlayer = true },
                                onToggle = { togglePlayback(player) },
                                onNext = { player?.seekToNextMediaItem() },
                            )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.background,
                            tonalElevation = 0.dp,
                        ) {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = {
                                    tab = 0
                                    sourceId = null
                                    playlistId = null
                                },
                                icon = { Icon(Icons.Rounded.LibraryMusic, null) },
                                label = { Text("Музыка") },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        indicatorColor = Color.Transparent
                                    ),
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = {
                                    tab = 1
                                    sourceId = null
                                    playlistId = null
                                },
                                icon = { Icon(Icons.Rounded.Forum, null) },
                                label = { Text("Чаты") },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        indicatorColor = Color.Transparent
                                    ),
                            )
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.GraphicEq,
                                null,
                                Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Text(
                            "Spotygram",
                            Modifier.padding(start = 10.dp).weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = onImport) {
                            Icon(Icons.Rounded.Add, "Добавить аудио с телефона")
                        }
                        IconButton(onClick = { sheet = "settings" }) {
                            Icon(Icons.Rounded.Settings, "Настройки")
                        }
                    }
                    if (connection.isNotBlank() && auth.type == "authorizationStateReady")
                        Text(
                            connection,
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    if (tab == 0) {
                        val playlist = library.playlists.firstOrNull { it.id == playlistId }
                        val source = library.sources.firstOrNull { it.id == sourceId }
                        MusicScreen(
                            library,
                            playing,
                            download,
                            busy,
                            filter,
                            onFilter = { filter = it },
                            playlist,
                            source,
                            onBack = {
                                playlistId = null
                                sourceId = null
                            },
                            onPlay = ::play,
                            onMore = {
                                selectedTrack = it
                                sheet = "track"
                            },
                            onImport = onImport,
                            onChats = { tab = 1 },
                            onRefresh = { app.action { app.refresh() } },
                            onOlder = { app.action { app.refresh(true) } },
                            onPlaylists = { sheet = "playlists" },
                            onDownload = onDownload,
                            onSearchChats = { q -> app.action { app.searchMusic(q) } },
                        )
                    } else
                        ChatsScreen(
                            library,
                            busy,
                            auth.type == "authorizationStateReady",
                            onConnect = {
                                connect = true
                                app.telegram.start()
                            },
                            onAdd = {
                                sheet = "chats"
                                app.action { app.loadChats() }
                            },
                            onOpen = {
                                sourceId = it
                                tab = 0
                            },
                            onRemove = { s ->
                                app.action { app.library.source(ChatChoice(s.id, s.title), false) }
                            },
                            onRefresh = { app.action { app.refresh() } },
                        )
                }
            }
        }
        if (fullPlayer || authVisible)
            Box(
                Modifier.fillMaxSize().safeDrawingPadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SnackbarHost(snackbar)
            }
        if (sheet.isNotEmpty())
            ModalBottomSheet(
                onDismissRequest = { sheet = "" },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                SnackbarHost(snackbar)
                when (sheet) {
                    "settings" ->
                        SettingsSheet(
                            app,
                            library,
                            auth.type == "authorizationStateReady",
                            onConnect = {
                                sheet = ""
                                connect = true
                                app.telegram.start()
                            },
                            onLogout = { confirmLogout = true },
                            onImport = onImport,
                            onLocal = {
                                filter = "local"
                                tab = 0
                                sheet = ""
                            },
                            onResume = {
                                app.action {
                                    val ids = app.library.pending()
                                    if (ids.isEmpty())
                                        app.notices.emit("Нет незавершённых загрузок")
                                    else onDownload(ids)
                                }
                            },
                        )
                    "chats" ->
                        ChatPicker(
                            app,
                            library,
                            onDone = {
                                sheet = ""
                                app.action { app.refresh() }
                            },
                        )
                    "queue" -> QueueSheet(library, playing, player)
                    "playlists" ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .navigationBarsPadding()
                        ) {
                            Text("Плейлисты", style = MaterialTheme.typography.headlineMedium)
                            ActionRow(Icons.Rounded.Add, "Новый плейлист") {
                                playlistName = ""
                                playlistDialog = true
                            }
                            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                                items(library.playlists, key = { it.id }) { list ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ListItem(
                                            headlineContent = { Text(list.name) },
                                            supportingContent = {
                                                Text(trackCount(list.tracks.size))
                                            },
                                            leadingContent = {
                                                Icon(Icons.Rounded.QueueMusic, null)
                                            },
                                            modifier =
                                                Modifier.weight(1f).clickable {
                                                    playlistId = list.id
                                                    sourceId = null
                                                    tab = 0
                                                    sheet = ""
                                                },
                                        )
                                        IconButton(
                                            onClick = {
                                                app.action { app.library.deletePlaylist(list.id) }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                "Удалить плейлист ${list.name}",
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    "addPlaylist" ->
                        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
                            Text("Добавить в плейлист", style = MaterialTheme.typography.titleLarge)
                            ActionRow(Icons.Rounded.Add, "Новый плейлист") {
                                playlistName = ""
                                playlistDialog = true
                            }
                            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                                items(library.playlists, key = { it.id }) { p ->
                                    ActionRow(Icons.Rounded.QueueMusic, p.name) {
                                        selectedTrack?.let { t ->
                                            app.action {
                                                app.library.addToPlaylist(p.id, t.id)
                                                app.notices.emit("Добавлено: ${p.name}")
                                            }
                                        }
                                        sheet = ""
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    "track" ->
                        selectedTrack?.let { initial ->
                            val track =
                                library.tracks.firstOrNull { it.id == initial.id } ?: initial
                            Column(Modifier.padding(horizontal = 12.dp).navigationBarsPadding()) {
                                ListItem(
                                    headlineContent = { Text(track.title, maxLines = 2) },
                                    supportingContent = { Text(track.subtitle) },
                                    leadingContent = { Artwork(track, 56.dp) },
                                )
                                HorizontalDivider(
                                    Modifier.padding(vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                ActionRow(
                                    if (track.liked) Icons.Rounded.Favorite
                                    else Icons.Rounded.FavoriteBorder,
                                    if (track.liked) "Убрать из любимых" else "В любимые",
                                ) {
                                    app.action { app.library.like(track) }
                                    sheet = ""
                                }
                                ActionRow(Icons.Rounded.PlaylistAdd, "В плейлист") {
                                    sheet = "addPlaylist"
                                }
                                ActionRow(Icons.Rounded.QueuePlayNext, "Слушать следующим") {
                                    player?.let { p ->
                                        p.addMediaItem(
                                            (p.currentMediaItemIndex + 1).coerceIn(
                                                0,
                                                p.mediaItemCount,
                                            ),
                                            track.mediaItem(),
                                        )
                                    }
                                    sheet = ""
                                }
                                if (track.local)
                                    ActionRow(Icons.Rounded.DeleteOutline, "Удалить с телефона") {
                                        app.action { app.removeLocal(track) }
                                        sheet = ""
                                    }
                                else if (track.available)
                                    ActionRow(Icons.Rounded.Download, "Скачать на телефон") {
                                        onDownload(listOf(track.id))
                                        sheet = ""
                                    }
                                if (playlistId != null)
                                    ActionRow(
                                        Icons.Rounded.PlaylistRemove,
                                        "Убрать из этого плейлиста",
                                    ) {
                                        app.action {
                                            app.library.removeFromPlaylist(playlistId!!, track.id)
                                        }
                                        sheet = ""
                                    }
                                if (track.chatId != 0L && track.available)
                                    ActionRow(Icons.Rounded.OpenInNew, "Открыть в Telegram") {
                                        app.action {
                                            val link = app.messageLink(track)
                                            app.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                        sheet = ""
                                    }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                }
            }
        if (playlistDialog)
            AlertDialog(
                onDismissRequest = { playlistDialog = false },
                title = { Text("Новый плейлист") },
                text = {
                    OutlinedTextField(
                        playlistName,
                        { playlistName = it.take(80) },
                        label = { Text("Название") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = playlistName.isNotBlank(),
                        onClick = {
                            app.action {
                                val id = app.library.createPlaylist(playlistName)
                                if (sheet == "addPlaylist")
                                    selectedTrack?.let { app.library.addToPlaylist(id, it.id) }
                            }
                            playlistDialog = false
                            if (sheet == "addPlaylist") sheet = ""
                        },
                    ) {
                        Text("Создать")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { playlistDialog = false }) { Text("Отмена") }
                },
            )
        if (confirmLogout)
            AlertDialog(
                onDismissRequest = { confirmLogout = false },
                title = { Text("Выйти из Telegram?") },
                text = {
                    Text(
                        "Сессия будет завершена. Telegram-треки и их связи с плейлистами будут удалены из медиатеки. Импортированные файлы останутся."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmLogout = false
                            sheet = ""
                            app.action { app.logout() }
                        }
                    ) {
                        Text("Выйти")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
                },
            )
    }
}

@Composable
fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(text, Modifier.padding(start = 18.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MusicScreen(
    library: LibraryState,
    playing: Playing,
    download: DownloadState,
    busy: Boolean,
    filter: String,
    onFilter: (String) -> Unit,
    playlist: Playlist?,
    source: Source?,
    onBack: () -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onMore: (Track) -> Unit,
    onImport: () -> Unit,
    onChats: () -> Unit,
    onRefresh: () -> Unit,
    onOlder: () -> Unit,
    onPlaylists: () -> Unit,
    onDownload: (List<String>) -> Unit,
    onSearchChats: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(false) }
    val tracks =
        remember(library.tracks, filter, query, playlist, source, sort) {
            val matching =
                library.tracks.filter { t ->
                    (playlist == null || t.id in playlist.tracks) &&
                        (source == null || t.chatId == source.id) &&
                        (filter != "local" || t.local) &&
                        (filter != "liked" || t.liked) &&
                        (query.isBlank() ||
                            "${t.title} ${t.artist} ${t.source}".contains(query, ignoreCase = true))
                }
            if (sort) matching.sortedBy { it.title.lowercase() }
            else if (playlist != null) matching.sortedBy { playlist.tracks.indexOf(it.id) }
            else matching
        }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (playlist != null || source != null)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Вся музыка")
                }
            Text(
                playlist?.name ?: source?.title ?: "Твоя музыка",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRefresh, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, "Обновить")
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            placeholder = { Text("Что хочешь послушать?") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon =
                if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, "Очистить поиск")
                        }
                    }
                } else null,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("all" to "Все", "local" to "На телефоне", "liked" to "Любимые").forEach {
                (key, label) ->
                FilterChip(
                    selected = filter == key,
                    onClick = { onFilter(key) },
                    label = { Text(label) },
                    shape = CircleShape,
                )
            }
            AssistChip(onClick = onPlaylists, label = { Text("Плейлисты") }, shape = CircleShape)
        }
        if (query.isNotBlank() && library.sources.isNotEmpty())
            TextButton(
                onClick = { onSearchChats(query) },
                enabled = !busy,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(if (busy) "Ищем…" else "Поискать в выбранных чатах")
            }
        Row(
            Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                trackCount(tracks.size),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { sort = !sort }) {
                Icon(
                    Icons.Rounded.SortByAlpha,
                    if (sort) "Сначала новые" else "По алфавиту",
                    tint =
                        if (sort) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (playlist != null || source != null)
                IconButton(
                    onClick = { onDownload(tracks.map { it.id }) },
                    enabled = tracks.any { !it.local && it.available },
                ) {
                    Icon(Icons.Rounded.Download, "Скачать подборку")
                }
            FilledIconButton(
                onClick = { tracks.firstOrNull()?.let { onPlay(it, tracks) } },
                enabled = tracks.isNotEmpty(),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, "Слушать всё")
            }
        }
        if (tracks.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (query.isNotBlank()) Icons.Rounded.SearchOff else Icons.Rounded.LibraryMusic,
                    null,
                    Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    if (query.isNotBlank()) "Ничего не найдено"
                    else if (filter == "local") "Здесь будет музыка без сети"
                    else if (filter == "liked") "Сохраняй то, что нравится"
                    else "Начнём с твоей музыки",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (query.isNotBlank())
                        "Поиск идёт по загруженной части медиатеки. Можно обновить чаты или загрузить больше истории."
                    else if (filter == "liked") "Нажми сердечко у трека — он появится здесь."
                    else "Выбери чаты с аудио или добавь файлы с телефона.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (query.isBlank() && filter == "all") {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onChats) { Text("Выбрать чаты") }
                    TextButton(onClick = onImport) { Text("Добавить с телефона") }
                }
                if (library.sources.any { !it.fullyIndexed }) {
                    TextButton(onClick = onOlder, enabled = !busy) {
                        Text(if (busy) "Загружаем…" else "Загрузить ещё из истории чатов")
                    }
                }
            }
        } else
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(
                        track,
                        track.id == playing.id,
                        download.takeIf { it.trackId == track.id },
                        onClick = { onPlay(track, tracks) },
                        onMore = { onMore(track) },
                    )
                }
                if (library.sources.any { !it.fullyIndexed })
                    item {
                        TextButton(
                            onClick = onOlder,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        ) {
                            Text(if (busy) "Загружаем…" else "Загрузить ещё из истории чатов")
                        }
                    }
            }
    }
}

@Composable
fun TrackRow(
    track: Track,
    active: Boolean,
    download: DownloadState? = null,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, 52.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.local) {
                    Icon(
                        Icons.Rounded.DownloadForOffline,
                        "На телефоне",
                        Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (!track.available && !track.local) "Сообщение удалено" else track.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (download != null)
            CircularProgressIndicator(
                progress = { download.progress },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        else
            Text(
                if (track.duration > 0) seconds(track.duration.toLong()) else "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Действия: ${track.title}") }
    }
}

@Composable
private fun MiniPlayer(
    track: Track,
    state: Playing,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, 42.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    track.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggle) {
                if (state.loading)
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) "Пауза" else "Продолжить",
                    )
            }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Следующий трек") }
        }
        LinearProgressIndicator(
            progress = {
                if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                else 0f
            },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            trackColor = Color.Transparent,
        )
    }
}
