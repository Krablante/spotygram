package app.spotygram

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val current = library.byId[playing.id]
    var localMode by rememberSaveable { mutableStateOf(app.prefs.getBoolean("local_mode", false)) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var playlistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var offline by rememberSaveable { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var addingTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var playlistTracks by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectionReset by rememberSaveable { mutableIntStateOf(0) }
    var managedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var rename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val keyboardVisible = WindowInsets.isImeVisible
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val pages = rememberSaveableStateHolder()
    val playlist = library.playlists.firstOrNull { it.id == playlistId }
    val source = library.sources.firstOrNull { it.id == sourceId }
    LaunchedEffect(Unit) { app.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(auth.type) { if (auth.type == "authorizationStateReady") connect = false }
    LaunchedEffect(sheet) { if (sheet.isNotEmpty()) focus.clearFocus() }
    val authVisible =
        connect ||
            (!localMode && auth.type != "authorizationStateReady" && library.tracks.isEmpty())

    fun like(track: Track) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        app.action {
            val liked = app.library.like(track)
            if (!liked)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (
                        snackbar.showSnackbar(
                            "Убрано из любимых",
                            "Отменить",
                            duration = SnackbarDuration.Short,
                        ) == SnackbarResult.ActionPerformed
                    )
                        app.action { app.library.restoreLike(track.id) }
                }
        }
    }
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
    fun addToPlaylist(ids: List<String>) {
        playlistTracks = ArrayList(ids)
        sheet = "addPlaylist"
    }
    fun editPlaylist(id: Long? = null) {
        focus.clearFocus()
        addingTo = id
        editor = true
        sheet = ""
    }
    fun navigate(index: Int) {
        focus.clearFocus()
        selectionReset++
        tab = index
        sourceId = null
        playlistId = null
    }
    BackHandler(
        enabled = !editor && (fullPlayer || sourceId != null || playlistId != null || connect)
    ) {
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
        when {
            authVisible ->
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
            editor ->
                PlaylistEditor(
                    app,
                    library,
                    addingTo,
                    onClose = { editor = false },
                    onSaved = { id ->
                        editor = false
                        navigate(2)
                        playlistId = id
                    },
                )
            fullPlayer && current != null ->
                PlayerScreen(
                    current,
                    playing,
                    player,
                    onBack = { fullPlayer = false },
                    onMore = {
                        selectedTrackId = current.id
                        sheet = "track"
                    },
                    onLike = { like(current) },
                    onDownload = { onDownload(listOf(current.id)) },
                    onQueue = { sheet = "queue" },
                )
            else ->
                Scaffold(
                    modifier = Modifier.imePadding(),
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { if (sheet.isEmpty()) SnackbarHost(snackbar) },
                    bottomBar = {
                        if (!keyboardVisible)
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
                                    modifier = Modifier.height(if (compact) 56.dp else 80.dp),
                                    containerColor = MaterialTheme.colorScheme.background,
                                    tonalElevation = 0.dp,
                                ) {
                                    val destinations =
                                        listOf(
                                            "Музыка" to Icons.Rounded.MusicNote,
                                            "Любимые" to Icons.Rounded.Favorite,
                                            "Плейлисты" to Icons.Rounded.PlaylistPlay,
                                            "Чаты" to Icons.Rounded.Forum,
                                        )
                                    destinations.forEachIndexed { index, (label, icon) ->
                                        NavigationBarItem(
                                            selected = tab == index,
                                            onClick = { navigate(index) },
                                            icon = { Icon(icon, if (compact) label else null) },
                                            label =
                                                if (compact) null
                                                else {
                                                    {
                                                        Text(
                                                            label,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                },
                                            colors =
                                                NavigationBarItemDefaults.colors(
                                                    indicatorColor = Color.Transparent,
                                                    selectedIconColor =
                                                        MaterialTheme.colorScheme.primary,
                                                    selectedTextColor =
                                                        MaterialTheme.colorScheme.primary,
                                                ),
                                        )
                                    }
                                }
                            }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (!compact)
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        null,
                                        Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                                Text(
                                    "Spotygram",
                                    Modifier.weight(1f).padding(start = 8.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                IconButton(onClick = { sheet = "settings" }) {
                                    Icon(Icons.Rounded.Settings, "Настройки")
                                }
                            }
                        if (connection.isNotBlank() && auth.type == "authorizationStateReady")
                            Text(
                                connection,
                                Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        pages.SaveableStateProvider("$tab:$playlistId:$sourceId") {
                            when {
                                tab == 0 || tab == 1 || tab == 2 && playlist != null ->
                                    MusicScreen(
                                        library,
                                        playing.id,
                                        playing.shuffle,
                                        download,
                                        busy,
                                        tab == 1,
                                        if (tab == 0) offline else false,
                                        source,
                                        playlist,
                                        selectionReset,
                                        onOffline = { offline = it },
                                        onSource = { sourceId = it },
                                        onBack = {
                                            playlistId = null
                                            sourceId = null
                                        },
                                        onPlay = ::play,
                                        onLike = ::like,
                                        onMore = {
                                            selectedTrackId = it.id
                                            sheet = "track"
                                        },
                                        onImport = onImport,
                                        onChats = { navigate(3) },
                                        onRefresh = { app.action { app.refresh() } },
                                        onShuffle = { tracks ->
                                            randomTrack(tracks)?.let {
                                                player?.shuffleModeEnabled = true
                                                play(it, tracks)
                                            }
                                        },
                                        onDownload = onDownload,
                                        onAddToPlaylist = ::addToPlaylist,
                                        onSearchChats = { q -> app.action { app.searchMusic(q) } },
                                        onAddTracks = { editPlaylist(playlistId) },
                                        onPlaylistMenu = {
                                            managedPlaylist = playlist
                                            sheet = "playlist"
                                        },
                                        onRemoveTracks = { ids ->
                                            playlistId?.let { id ->
                                                app.action {
                                                    app.library.removeFromPlaylist(id, ids)
                                                }
                                            }
                                        },
                                    )
                                tab == 2 ->
                                    PlaylistsScreen(
                                        library,
                                        onCreate = { editPlaylist() },
                                        onOpen = { playlistId = it },
                                        onMore = {
                                            managedPlaylist = it
                                            sheet = "playlist"
                                        },
                                    )
                                else ->
                                    ChatsScreen(
                                        library,
                                        busy,
                                        auth.type == "authorizationStateReady",
                                        onConnect = {
                                            connect = true
                                            app.telegram.start()
                                        },
                                        onAdd = { sheet = "chats" },
                                        onOpen = {
                                            navigate(0)
                                            sourceId = it
                                        },
                                        onRemove = { s ->
                                            app.action {
                                                app.library.source(ChatChoice(s.id, s.title), false)
                                            }
                                        },
                                        onRefresh = { app.action { app.refresh() } },
                                    )
                            }
                        }
                    }
                }
        }
        if ((fullPlayer || editor || authVisible) && sheet.isEmpty())
            Box(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
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
                MatchDialogSystemBars()
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
                            onLogout = {
                                sheet = ""
                                confirmLogout = true
                            },
                            onImport = {
                                sheet = ""
                                onImport()
                            },
                            onLocal = {
                                navigate(0)
                                offline = true
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
                    "addPlaylist" ->
                        AddToPlaylistSheet(
                            app,
                            library,
                            playlistTracks,
                            onDone = {
                                sheet = ""
                                selectionReset++
                                app.notices.tryEmit("Добавлено в плейлист")
                            },
                        )
                    "order" ->
                        managedPlaylist?.let { p ->
                            PlaylistOrderSheet(app, library, p.id, onDone = { sheet = "" })
                        }
                    "playlist" ->
                        managedPlaylist?.let { p ->
                            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                                ActionRow(Icons.Rounded.PlaylistAdd, "Добавить треки") {
                                    editPlaylist(p.id)
                                }
                                ActionRow(Icons.Rounded.SwapVert, "Изменить порядок") {
                                    sheet = "order"
                                }
                                ActionRow(Icons.Rounded.Edit, "Переименовать") {
                                    renameText = p.name
                                    sheet = ""
                                    rename = true
                                }
                                ActionRow(Icons.Rounded.DeleteOutline, "Удалить плейлист") {
                                    sheet = ""
                                    delete = true
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    "track" ->
                        library.byId[selectedTrackId]?.let { track ->
                            Column(
                                Modifier.navigationBarsPadding()
                                    .padding(horizontal = 12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                ListItem(
                                    headlineContent = { Text(track.title, maxLines = 2) },
                                    supportingContent = { Text(track.subtitle) },
                                    leadingContent = { Artwork(track, 48.dp) },
                                )
                                ActionRow(
                                    if (track.liked) Icons.Rounded.Favorite
                                    else Icons.Rounded.FavoriteBorder,
                                    if (track.liked) "Убрать из любимых" else "В любимые",
                                ) {
                                    like(track)
                                    sheet = ""
                                }
                                ActionRow(Icons.Rounded.PlaylistAdd, "В плейлист") {
                                    addToPlaylist(listOf(track.id))
                                }
                                ActionRow(Icons.Rounded.QueuePlayNext, "Слушать следующим") {
                                    playNext(app.player, track)
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
                                playlistId?.let { id ->
                                    ActionRow(Icons.Rounded.PlaylistRemove, "Убрать из плейлиста") {
                                        app.action { app.library.removeFromPlaylist(id, track.id) }
                                        sheet = ""
                                    }
                                }
                                if (track.chatId != 0L && track.available)
                                    ActionRow(Icons.Rounded.OpenInNew, "Открыть в Telegram") {
                                        app.action {
                                            app.startActivity(
                                                Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse(app.messageLink(track)),
                                                    )
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                        sheet = ""
                                    }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                }
            }
        if (rename)
            AlertDialog(
                onDismissRequest = { rename = false },
                title = { Text("Название плейлиста") },
                text = {
                    OutlinedTextField(
                        renameText,
                        { renameText = it.take(80) },
                        singleLine = true,
                        label = { Text("Название") },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = renameText.isNotBlank(),
                        onClick = {
                            val id = managedPlaylist?.id
                            val name = renameText
                            if (id != null) app.action { app.library.renamePlaylist(id, name) }
                            rename = false
                        },
                    ) {
                        Text("Сохранить")
                    }
                },
                dismissButton = { TextButton(onClick = { rename = false }) { Text("Отмена") } },
            )
        if (delete)
            AlertDialog(
                onDismissRequest = { delete = false },
                title = { Text("Удалить плейлист?") },
                text = {
                    Text(
                        "«${managedPlaylist?.name}» будет удалён. Треки останутся в музыке и на телефоне."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val id = managedPlaylist?.id
                            if (id != null)
                                app.action {
                                    app.library.deletePlaylist(id)
                                    if (playlistId == id) playlistId = null
                                }
                            delete = false
                        }
                    ) {
                        Text("Удалить")
                    }
                },
                dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } },
            )
        if (confirmLogout)
            AlertDialog(
                onDismissRequest = { confirmLogout = false },
                title = { Text("Выйти из Telegram?") },
                text = {
                    Text(
                        "Telegram-треки и их связи с плейлистами будут удалены из медиатеки. Импортированные файлы останутся."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmLogout = false
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
            Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, 40.dp)
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
