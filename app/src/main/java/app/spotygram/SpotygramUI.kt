package app.spotygram

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpotygramUI(
    app: SpotygramApp,
    player: MediaController?,
    playerConnecting: Boolean,
    onStartPlayback: (List<Track>, Int, PlaybackOrder?) -> Unit,
    onImport: () -> Unit,
    onDownload: (List<String>) -> Unit,
) {
    val library by app.library.state.collectAsStateWithLifecycle()
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val busy by app.busy.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    val download by app.downloading.collectAsStateWithLifecycle()
    val mode by app.playbackMode.collectAsStateWithLifecycle()
    val hideDuplicates by app.hideDuplicates.collectAsStateWithLifecycle()
    val cache by app.musicCache.state.collectAsStateWithLifecycle()
    val playing =
        observePlayer(player)
            .copy(shuffle = mode.shuffle, random = mode.random, repeat = mode.repeat)
    var localMode by rememberSaveable { mutableStateOf(app.prefs.getBoolean("local_mode", false)) }
    var connect by rememberSaveable { mutableStateOf(false) }
    val destinationKeys = listOf("music", "favorites", "playlists", "chats")
    var tab by rememberSaveable {
        mutableIntStateOf(
            destinationKeys
                .indexOf(app.prefs.getString("last_destination", "music"))
                .coerceAtLeast(0)
        )
    }
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
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val pages = rememberSaveableStateHolder()
    val glassState = rememberHazeState()
    val playlist = library.playlists.firstOrNull { it.id == playlistId }
    val source = library.sources.firstOrNull { it.id == sourceId }
    val offlineView = tab == 0 && offline
    val groupedView = hideDuplicates && playlist == null && tab in 0..1
    val current =
        library.byId[playing.id]?.let {
            if (groupedView) library.duplicates.display(it)
            else if (offlineView && it.local) library.localDisplay(it) else it
        }
    LaunchedEffect(Unit) { app.notices.collectLatest { snackbar.showSnackbar(it) } }
    LaunchedEffect(playerConnecting) {
        if (playerConnecting)
            snackbar.showSnackbar(
                tr(R.string.player_connecting),
                duration = SnackbarDuration.Indefinite,
            )
    }
    LaunchedEffect(Unit) {
        app.updates.showSettings.collect { show ->
            if (show) {
                sheet = "settings"
                app.updates.showSettings.value = false
            }
        }
    }
    LaunchedEffect(tab) {
        app.prefs.edit().putString("last_destination", destinationKeys[tab]).apply()
    }
    LaunchedEffect(auth.type) { if (auth.type == "authorizationStateReady") connect = false }
    LaunchedEffect(sheet) { if (sheet.isNotEmpty()) focus.clearFocus() }
    val authVisible =
        connect ||
            (!localMode && auth.type != "authorizationStateReady" && library.tracks.isEmpty())

    fun like(track: Track) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        snackbar.currentSnackbarData?.dismiss()
        val sameFile = offlineView && track.local
        app.action {
            val aliases =
                if (groupedView) library.duplicates.aliases(track.id).map { it.id } else emptyList()
            val (liked, previousLikes) = app.library.like(track, sameFile, aliases)
            if (!liked)
                scope.launch {
                    if (
                        snackbar.showSnackbar(
                            tr(R.string.removed_favorite),
                            tr(R.string.undo),
                            duration = SnackbarDuration.Short,
                        ) == SnackbarResult.ActionPerformed
                    )
                        app.action { app.library.restoreLikes(previousLikes) }
                }
        }
    }
    fun play(track: Track, tracks: List<Track>) {
        val available = tracks.filter { it.local || it.available }
        val index = available.indexOfFirst { it.id == track.id }
        if (index < 0) {
            app.notices.tryEmit(tr(R.string.no_local_copy))
            return
        }
        onStartPlayback(available, index, null)
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
    CompositionLocalProvider(
        LocalGlassState provides glassState,
        LocalDockInset provides
            (if (keyboardVisible) 12.dp
            else
                (if (current != null) 72.dp else 8.dp) +
                    (if (wide) 0.dp else 70.dp) +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxSize().glassContent(0f)) {
                    PrismBackdrop(Modifier.fillMaxSize())
                    if (current != null && !fullPlayer) {
                        Artwork(
                            current,
                            160.dp,
                            Modifier.align(Alignment.BottomStart)
                                .offset(x = (-32).dp, y = 36.dp)
                                .blur(40.dp, BlurredEdgeTreatment.Unbounded)
                                .alpha(0.22f),
                        )
                    }
                }
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
                            onSpeed = { sheet = "speed" },
                            onShuffle = { app.playback?.setMode(order = mode.order.next()) },
                            onRepeat = { app.playback?.setMode(repeat = it) },
                        )
                    else ->
                        Scaffold(
                            modifier = Modifier.imePadding(),
                            containerColor = Color.Transparent,
                            snackbarHost = { if (sheet.isEmpty()) GlassSnackbarHost(snackbar) },
                            bottomBar = {
                                if (!keyboardVisible)
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .padding(
                                                start = if (wide) 96.dp else 0.dp,
                                                bottom = 6.dp,
                                            )
                                            .navigationBarsPadding(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        if (current != null)
                                            MiniPlayer(
                                                current,
                                                player,
                                                onOpen = { fullPlayer = true },
                                                onToggle = { togglePlayback(player) },
                                                onPrevious = { player?.seekToPreviousMediaItem() },
                                                onNext = { player?.seekToNextMediaItem() },
                                            )
                                        if (!wide)
                                            BoxWithConstraints(
                                                modifier =
                                                    Modifier.widthIn(max = 720.dp)
                                                        .fillMaxWidth()
                                                        .padding(
                                                            horizontal = 12.dp,
                                                            vertical = 5.dp,
                                                        )
                                                        .height(62.dp)
                                                        .liquidGlass(31.dp, strong = true)
                                                        .padding(horizontal = 5.dp)
                                            ) {
                                                val cellWidth = maxWidth / 4
                                                val offset by
                                                    animateDpAsState(
                                                        cellWidth * tab,
                                                        spring(
                                                            dampingRatio = 0.82f,
                                                            stiffness = 550f,
                                                        ),
                                                        label = "tab lens",
                                                    )
                                                Box(
                                                    Modifier.offset(x = offset, y = 5.dp)
                                                        .width(cellWidth)
                                                        .height(52.dp)
                                                        .liquidGlass(
                                                            26.dp,
                                                            tint = spectrumAccent(tab),
                                                        )
                                                )
                                                Row(
                                                    Modifier.fillMaxSize(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    val destinations =
                                                        listOf(
                                                            0 to
                                                                (tr(R.string.music) to
                                                                    Icons.Rounded.MusicNote),
                                                            1 to
                                                                (tr(R.string.favorites) to
                                                                    Icons.Rounded.Favorite),
                                                            2 to
                                                                (tr(R.string.playlists) to
                                                                    Icons.Rounded.PlaylistPlay),
                                                            3 to
                                                                (tr(R.string.chats) to
                                                                    Icons.Rounded.Forum),
                                                        )
                                                    destinations.forEach { (index, destination) ->
                                                        val (label, icon) = destination
                                                        val selected = tab == index
                                                        val accent = spectrumAccent(index)
                                                        Column(
                                                            Modifier.weight(1f)
                                                                .height(52.dp)
                                                                .clip(RoundedCornerShape(26.dp))
                                                                .selectable(
                                                                    selected,
                                                                    role = Role.Tab,
                                                                ) {
                                                                    navigate(index)
                                                                },
                                                            horizontalAlignment =
                                                                Alignment.CenterHorizontally,
                                                            verticalArrangement =
                                                                Arrangement.Center,
                                                        ) {
                                                            Icon(
                                                                icon,
                                                                label,
                                                                Modifier.size(23.dp),
                                                                tint =
                                                                    if (selected) accent
                                                                    else
                                                                        MaterialTheme.colorScheme
                                                                            .onSurfaceVariant,
                                                            )
                                                            Text(
                                                                label,
                                                                fontSize = 11.sp,
                                                                lineHeight = 14.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                color =
                                                                    if (selected) accent
                                                                    else
                                                                        MaterialTheme.colorScheme
                                                                            .onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                    }
                            },
                        ) { padding ->
                            Column(
                                Modifier.padding(
                                        top = padding.calculateTopPadding(),
                                        start = if (wide) 96.dp else 0.dp,
                                    )
                                    .fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                UpdateBanner(app)
                                if (cache.clearing) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(
                                        tr(R.string.cache_clearing),
                                        Modifier.padding(horizontal = 16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (!keyboardVisible)
                                    Row(
                                        Modifier.widthIn(max = 720.dp)
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(start = 16.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Image(
                                            painterResource(R.drawable.spotygram_mark),
                                            null,
                                            Modifier.size(28.dp),
                                        )
                                        Text(
                                            "Spotygram",
                                            Modifier.weight(1f).padding(start = 8.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        GlassIconButton(
                                            onClick = { sheet = "settings" },
                                            modifier = Modifier.padding(end = 8.dp),
                                        ) {
                                            Icon(Icons.Rounded.Settings, tr(R.string.settings))
                                        }
                                    }
                                if (
                                    connection.isNotBlank() &&
                                        auth.type == "authorizationStateReady"
                                )
                                    Text(
                                        when (connection) {
                                            "connectionStateWaitingForNetwork" ->
                                                tr(R.string.no_network)
                                            "connectionStateUpdating" ->
                                                tr(R.string.telegram_updating)
                                            else -> tr(R.string.telegram_connecting)
                                        },
                                        Modifier.padding(horizontal = 16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                Box(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth()) {
                                    pages.SaveableStateProvider("$tab:$playlistId:$sourceId") {
                                        when {
                                            tab == 0 || tab == 1 || tab == 2 && playlist != null ->
                                                MusicScreen(
                                                    hideDuplicates = groupedView,
                                                    library,
                                                    playing.id,
                                                    playing.shuffle,
                                                    playing.random,
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
                                                    onPlay = { track, tracks ->
                                                        play(track, tracks)
                                                    },
                                                    onLike = { like(it) },
                                                    onMore = {
                                                        selectedTrackId = it.id
                                                        sheet = "track"
                                                    },
                                                    onImport = onImport,
                                                    onChats = { navigate(3) },
                                                    onRefresh = { app.action { app.refresh() } },
                                                    onShuffle = { tracks ->
                                                        randomTrack(tracks)?.let {
                                                            val available = tracks.filter { t ->
                                                                t.local || t.available
                                                            }
                                                            onStartPlayback(
                                                                available,
                                                                available.indexOf(it),
                                                                if (mode.random)
                                                                    PlaybackOrder.RANDOM
                                                                else PlaybackOrder.SHUFFLE,
                                                            )
                                                        }
                                                    },
                                                    onDownload = onDownload,
                                                    onAddToPlaylist = { addToPlaylist(it) },
                                                    onSearchChats = { q ->
                                                        app.action { app.searchMusic(q) }
                                                    },
                                                    onAddTracks = { editPlaylist(playlistId) },
                                                    onPlaylistMenu = {
                                                        managedPlaylist = playlist
                                                        sheet = "playlist"
                                                    },
                                                    onRemoveTracks = { ids ->
                                                        playlistId?.let { id ->
                                                            app.action {
                                                                app.library.removeFromPlaylist(
                                                                    id,
                                                                    ids,
                                                                )
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
                                                            app.library.source(
                                                                ChatChoice(s.id, s.title),
                                                                false,
                                                            )
                                                        }
                                                    },
                                                    onRefresh = { app.action { app.refresh() } },
                                                )
                                        }
                                    }
                                }
                            }
                        }
                }
                if (wide && !fullPlayer && !editor && !authVisible) {
                    Column(
                        Modifier.align(Alignment.CenterStart)
                            .padding(start = 16.dp)
                            .width(76.dp)
                            .liquidGlass(32.dp, strong = true)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        listOf(
                                R.string.music to Icons.Rounded.MusicNote,
                                R.string.favorites to Icons.Rounded.Favorite,
                                R.string.playlists to Icons.Rounded.PlaylistPlay,
                                R.string.chats to Icons.Rounded.Forum,
                            )
                            .forEachIndexed { index, (label, icon) ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 68.dp)
                                        .then(
                                            if (tab == index)
                                                Modifier.liquidGlass(
                                                    26.dp,
                                                    tint = spectrumAccent(index),
                                                )
                                            else Modifier
                                        )
                                        .clip(RoundedCornerShape(26.dp))
                                        .selectable(tab == index, role = Role.Tab) {
                                            navigate(index)
                                        },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        icon,
                                        null,
                                        tint =
                                            if (tab == index) spectrumAccent(index)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(tr(label), fontSize = 10.sp, maxLines = 1)
                                }
                            }
                    }
                }
                if ((fullPlayer || editor || authVisible) && sheet.isEmpty())
                    Box(
                        Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        GlassSnackbarHost(snackbar)
                    }
                if (sheet.isNotEmpty())
                    ModalBottomSheet(
                        onDismissRequest = { sheet = "" },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = Color.Transparent,
                        dragHandle = null,
                    ) {
                        // Material must travel inside the sheet's native placement/drag transform.
                        Column(Modifier.fillMaxWidth().liquidGlass(30.dp, strong = true)) {
                            if (sheet == "speed")
                                Box(
                                    Modifier.align(Alignment.CenterHorizontally)
                                        .padding(top = 10.dp, bottom = 2.dp)
                                        .size(32.dp, 4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.4f
                                            ),
                                            RoundedCornerShape(2.dp),
                                        )
                                )
                            else
                                BottomSheetDefaults.DragHandle(
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                    color =
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.5f
                                        ),
                                )
                            MatchDialogSystemBars()
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurface
                            ) {
                                GlassSnackbarHost(snackbar)
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
                                                        app.notices.emit(
                                                            tr(R.string.no_pending_downloads)
                                                        )
                                                    else onDownload(ids)
                                                }
                                            },
                                            onCache = { sheet = "cache" },
                                        )
                                    "cache" ->
                                        CacheScreen(
                                            app,
                                            auth.type == "authorizationStateReady",
                                            onBack = { sheet = "settings" },
                                            onConnect = {
                                                sheet = ""
                                                connect = true
                                                app.telegram.start()
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
                                    "queue" -> QueueSheet(library, playing, app)
                                    "speed" ->
                                        PlaybackSpeedSheet(
                                            playing,
                                            player,
                                            onClose = { sheet = "" },
                                        )
                                    "addPlaylist" ->
                                        AddToPlaylistSheet(
                                            app,
                                            library,
                                            playlistTracks,
                                            onDone = {
                                                sheet = ""
                                                selectionReset++
                                                app.notices.tryEmit(tr(R.string.added_to_playlist))
                                            },
                                        )
                                    "order" ->
                                        managedPlaylist?.let { p ->
                                            PlaylistOrderSheet(
                                                app,
                                                library,
                                                p.id,
                                                onDone = { sheet = "" },
                                            )
                                        }
                                    "playlist" ->
                                        managedPlaylist?.let { p ->
                                            Column(
                                                Modifier.navigationBarsPadding()
                                                    .padding(horizontal = 16.dp)
                                            ) {
                                                Text(
                                                    p.name,
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    modifier = Modifier.padding(bottom = 12.dp),
                                                )
                                                ActionRow(
                                                    Icons.Rounded.PlaylistAdd,
                                                    tr(R.string.add_tracks),
                                                ) {
                                                    editPlaylist(p.id)
                                                }
                                                ActionRow(
                                                    Icons.Rounded.SwapVert,
                                                    tr(R.string.change_order),
                                                ) {
                                                    sheet = "order"
                                                }
                                                ActionRow(Icons.Rounded.Edit, tr(R.string.rename)) {
                                                    renameText = p.name
                                                    sheet = ""
                                                    rename = true
                                                }
                                                ActionRow(
                                                    Icons.Rounded.DeleteOutline,
                                                    tr(R.string.delete_playlist),
                                                ) {
                                                    sheet = ""
                                                    delete = true
                                                }
                                                Spacer(Modifier.height(12.dp))
                                            }
                                        }
                                    "track" ->
                                        library.byId[selectedTrackId]
                                            ?.let {
                                                if (groupedView) library.duplicates.display(it)
                                                else if (offlineView && it.local)
                                                    library.localDisplay(it)
                                                else it
                                            }
                                            ?.let { track ->
                                                Column(
                                                    Modifier.navigationBarsPadding()
                                                        .padding(horizontal = 12.dp)
                                                        .verticalScroll(rememberScrollState())
                                                ) {
                                                    ListItem(
                                                        colors =
                                                            ListItemDefaults.colors(
                                                                containerColor = Color.Transparent
                                                            ),
                                                        headlineContent = {
                                                            Text(track.title, maxLines = 2)
                                                        },
                                                        supportingContent = {
                                                            Text(track.subtitle)
                                                        },
                                                        leadingContent = { Artwork(track, 48.dp) },
                                                    )
                                                    ActionRow(
                                                        if (track.liked) Icons.Rounded.Favorite
                                                        else Icons.Rounded.FavoriteBorder,
                                                        if (track.liked) tr(R.string.unlike)
                                                        else tr(R.string.like),
                                                    ) {
                                                        like(track)
                                                        sheet = ""
                                                    }
                                                    ActionRow(
                                                        Icons.Rounded.PlaylistAdd,
                                                        tr(R.string.to_playlist),
                                                    ) {
                                                        addToPlaylist(listOf(track.id))
                                                    }
                                                    ActionRow(
                                                        Icons.Rounded.QueuePlayNext,
                                                        tr(R.string.play_next),
                                                    ) {
                                                        app.playback?.addNext(track)
                                                        sheet = ""
                                                    }
                                                    if (track.local && track.temporary)
                                                        ActionRow(
                                                            Icons.Rounded.Download,
                                                            tr(R.string.keep_on_device),
                                                        ) {
                                                            onDownload(listOf(track.id))
                                                            sheet = ""
                                                        }
                                                    if (track.local)
                                                        ActionRow(
                                                            Icons.Rounded.DeleteOutline,
                                                            tr(R.string.remove_from_device),
                                                        ) {
                                                            app.action { app.removeLocal(track) }
                                                            sheet = ""
                                                        }
                                                    else if (track.available)
                                                        ActionRow(
                                                            Icons.Rounded.Download,
                                                            tr(R.string.download_to_device),
                                                        ) {
                                                            onDownload(listOf(track.id))
                                                            sheet = ""
                                                        }
                                                    playlistId?.let { id ->
                                                        ActionRow(
                                                            Icons.Rounded.PlaylistRemove,
                                                            tr(R.string.remove_from_playlist),
                                                        ) {
                                                            app.action {
                                                                app.library.removeFromPlaylist(
                                                                    id,
                                                                    track.id,
                                                                )
                                                            }
                                                            sheet = ""
                                                        }
                                                    }
                                                    if (track.chatId != 0L && track.available)
                                                        ActionRow(
                                                            Icons.Rounded.OpenInNew,
                                                            tr(R.string.open_in_telegram),
                                                        ) {
                                                            snackbar.currentSnackbarData?.dismiss()
                                                            app.action {
                                                                try {
                                                                    app.startActivity(
                                                                        Intent(
                                                                                Intent.ACTION_VIEW,
                                                                                Uri.parse(
                                                                                    app.messageLink(
                                                                                        track
                                                                                    )
                                                                                ),
                                                                            )
                                                                            .addFlags(
                                                                                Intent
                                                                                    .FLAG_ACTIVITY_NEW_TASK
                                                                            )
                                                                    )
                                                                } catch (
                                                                    _:
                                                                        android.content.ActivityNotFoundException) {
                                                                    error(
                                                                        tr(
                                                                            R.string
                                                                                .telegram_app_missing
                                                                        )
                                                                    )
                                                                }
                                                            }
                                                            sheet = ""
                                                        }
                                                    Spacer(Modifier.height(12.dp))
                                                }
                                            }
                                }
                            }
                        }
                    }
                if (rename)
                    GlassDialog(
                        onDismissRequest = { rename = false },
                        title = { Text(tr(R.string.playlist_name)) },
                        text = {
                            OutlinedTextField(
                                renameText,
                                { renameText = it.take(80) },
                                singleLine = true,
                                label = { Text(tr(R.string.name)) },
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = renameText.isNotBlank(),
                                onClick = {
                                    val id = managedPlaylist?.id
                                    val name = renameText
                                    if (id != null)
                                        app.action { app.library.renamePlaylist(id, name) }
                                    rename = false
                                },
                            ) {
                                Text(tr(R.string.save))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { rename = false }) { Text(tr(R.string.cancel)) }
                        },
                    )
                if (delete)
                    GlassDialog(
                        onDismissRequest = { delete = false },
                        title = { Text(tr(R.string.delete_playlist_title)) },
                        text = {
                            Text(tr(R.string.delete_playlist_help, managedPlaylist?.name))
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
                                Text(tr(R.string.delete))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { delete = false }) { Text(tr(R.string.cancel)) }
                        },
                    )
                if (confirmLogout)
                    GlassDialog(
                        onDismissRequest = { confirmLogout = false },
                        title = { Text(tr(R.string.logout_title)) },
                        text = {
                            Text(tr(R.string.logout_help))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmLogout = false
                                    app.action { app.logout() }
                                }
                            ) {
                                Text(tr(R.string.leave))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmLogout = false }) {
                                Text(tr(R.string.cancel))
                            }
                        },
                    )
            }
        }
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
    player: MediaController?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val state = observePlayer(player, positionUpdates = true)
    val narrow = LocalConfiguration.current.screenWidthDp < 360
    Column(
        Modifier.widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .liquidGlass(26.dp, strong = true)
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, if (narrow) 36.dp else 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!narrow || state.speed != 1f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (!narrow)
                            Text(
                                track.subtitle,
                                Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        if (state.speed != 1f)
                            Text(
                                playbackSpeedLabel(state.speed),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                            )
                    }
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Rounded.SkipPrevious, tr(R.string.previous_track))
            }
            GlassIconButton(onClick = onToggle, tint = spectrumAccent(0)) {
                if (state.loading)
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) tr(R.string.pause) else tr(R.string.continue_action),
                    )
            }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, tr(R.string.next_track)) }
        }
        LinearProgressIndicator(
            progress = {
                if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                else 0f
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp),
            trackColor = Color.Transparent,
        )
    }
}
