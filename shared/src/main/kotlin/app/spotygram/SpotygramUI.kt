package app.spotygram

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpotygramUI(
    app: SpotygramApp,
    player: Player?,
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
    val startupError by app.startupError.collectAsStateWithLifecycle()
    val headerSticker by app.headerSticker.drawable.collectAsStateWithLifecycle()
    val playing = observePlayer(player).copy(order = mode.order, repeat = mode.repeat)
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
    var trackAnchor by remember { mutableStateOf(IntRect.Zero) }
    var playlistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var profileHint by remember { mutableStateOf<UserProfile?>(null) }
    val profileLoading by app.profiles.loading.collectAsStateWithLifecycle()
    val profileErrors by app.profiles.errors.collectAsStateWithLifecycle()
    val selfProfileId by app.profiles.selfId.collectAsStateWithLifecycle()
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
    var deletingImportId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val keyboardVisible = imeVisible()
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val trackPopup = LocalConfiguration.current.screenWidthDp >= 600
    val pages = rememberSaveableStateHolder()
    val glassState = rememberHazeState()
    val playlist = library.playlists.firstOrNull { it.id == playlistId }
    val selectedSource = library.allSources.firstOrNull { it.key == sourceId }
    val activeProfileId =
        profileId
            ?: sourceId?.takeIf { it.startsWith("profile:") }?.substringAfter(':')?.toLongOrNull()
    val profile =
        library.profiles.firstOrNull { it.id == activeProfileId }
            ?: profileHint?.takeIf { it.id == activeProfileId }
    val source = profile?.source() ?: selectedSource
    val offlineView = tab == 0 && offline
    val groupedView =
        hideDuplicates &&
            playlist == null &&
            profile == null &&
            (tab in 0..1 || tab == 3 && selectedSource != null)
    val groupedFavorites = hideDuplicates && (groupedView || profile != null)
    val current =
        library.byId[playing.id]?.let {
            if (groupedFavorites) library.duplicates.display(it)
            else if (offlineView && it.local) library.localDisplay(it) else it
        }
    val selectedTrack =
        library.byId[selectedTrackId]?.let {
            if (groupedFavorites) library.duplicates.display(it)
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
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activeProfileId, auth.type, connection, lifecycle) {
        val id = activeProfileId ?: return@LaunchedEffect
        if (auth.type == "authorizationStateReady")
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                try {
                    app.profiles.load(id)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
    }
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
                if (groupedFavorites) library.duplicates.aliases(track.id).map { it.id }
                else emptyList()
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
        profileId = null
        profileHint = null
        playlistId = null
    }
    fun openProfile(value: UserProfile) {
        navigate(3)
        sheet = ""
        profileId = value.id
        profileHint = value
        app.action { app.profiles.open(value) }
    }
    fun profileChanged(track: Track, added: Boolean) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            if (
                snackbar.showSnackbar(
                    tr(if (added) R.string.profile_audio_added else R.string.profile_audio_removed),
                    tr(R.string.undo),
                    duration = SnackbarDuration.Short,
                ) == SnackbarResult.ActionPerformed
            )
                app.action {
                    app.profiles.changeAudio(track, !added)
                    app.notices.emit(
                        tr(
                            if (added) R.string.profile_audio_removed
                            else R.string.profile_audio_added
                        )
                    )
                }
        }
    }
    val shareContent: @Composable (Modifier, androidx.compose.ui.unit.Dp?) -> Unit =
        { modifier, height ->
            selectedTrack?.let { track ->
                SharingSheet(
                    app,
                    track,
                    onClose = { sheet = "" },
                    onSent = { recipient, message ->
                        if (sheet == "share" && selectedTrackId == track.id) sheet = ""
                        snackbar.currentSnackbarData?.dismiss()
                        scope.launch {
                            if (
                                snackbar.showSnackbar(
                                    tr(R.string.share_sent_to, recipient.displayTitle),
                                    tr(R.string.share_open),
                                    duration = SnackbarDuration.Short,
                                ) == SnackbarResult.ActionPerformed
                            )
                                app.action {
                                    app.platform.openUrl(
                                        app.messageLink(
                                            track.copy(
                                                chatId = message.getLong("chat_id"),
                                                messageId = message.getLong("id"),
                                            )
                                        )
                                    )
                                }
                        }
                    },
                    onFailed = { message -> if (sheet != "share") app.notices.tryEmit(message) },
                    modifier = modifier,
                    maxHeight = height,
                )
            }
        }
    val trackActions: @Composable (Modifier) -> Unit = { modifier ->
        selectedTrack?.let { track ->
            TrackActions(
                app,
                track,
                mode.order,
                playlistId,
                onLike = { like(track) },
                onPlaylist = { addToPlaylist(listOf(track.id)) },
                onOrder = { sheet = "playbackOrder" },
                onDownload = { onDownload(listOf(track.id)) },
                onRemoveLocal = {
                    sheet = ""
                    if (track.imported) deletingImportId = track.id
                    else app.action { app.removeLocal(track) }
                },
                onShare = { sheet = "share" },
                onOpenTelegram = {
                    snackbar.currentSnackbarData?.dismiss()
                    app.action {
                        try {
                            app.platform.openUrl(app.messageLink(track))
                        } catch (_: Exception) {
                            error(tr(R.string.telegram_app_missing))
                        }
                    }
                    sheet = ""
                },
                onProfileChanged = {
                    profileChanged(track, it)
                    sheet = ""
                },
                onClose = { sheet = "" },
                modifier = modifier,
            )
        }
    }
    BackHandler(
        enabled =
            sheet.isEmpty() &&
                !editor &&
                !authVisible &&
                (fullPlayer ||
                    sourceId != null ||
                    profileId != null ||
                    playlistId != null ||
                    connect)
    ) {
        when {
            fullPlayer -> fullPlayer = false
            connect -> connect = false
            else -> {
                sourceId = null
                profileId = null
                profileHint = null
                playlistId = null
            }
        }
    }
    BackHandler(enabled = sheet.isNotEmpty()) { sheet = "" }
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
                    startupError.isNotEmpty() ->
                        Box(
                            Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(startupError, style = MaterialTheme.typography.bodyLarge)
                        }
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
                            onMore = { anchor ->
                                selectedTrackId = current.id
                                trackAnchor = anchor
                                sheet = "track"
                            },
                            onShare =
                                if (auth.type == "authorizationStateReady")
                                    { anchor ->
                                        selectedTrackId = current.id
                                        trackAnchor = anchor
                                        sheet = "share"
                                    }
                                else null,
                            onLike = { like(current) },
                            onDownload = { onDownload(listOf(current.id)) },
                            onQueue = { sheet = "queue" },
                            onSpeed = { sheet = "speed" },
                            onOrder = { app.changePlaybackOrder(it) },
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
                                                    Modifier.offset {
                                                            IntOffset(
                                                                offset.roundToPx(),
                                                                5.dp.roundToPx(),
                                                            )
                                                        }
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
                                                                (tr(R.string.sources) to
                                                                    Icons.Rounded.Widgets),
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
                                if (!keyboardVisible && profile == null)
                                    Row(
                                        Modifier.widthIn(max = 720.dp)
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(start = 16.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Image(
                                            painterResource(headerSticker),
                                            null,
                                            Modifier.size(28.dp),
                                        )
                                        Text(
                                            "spotygram",
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
                                    pages.SaveableStateProvider(
                                        "$tab:$playlistId:$sourceId:$profileId"
                                    ) {
                                        when {
                                            tab == 0 ||
                                                tab == 1 ||
                                                tab == 2 && playlist != null ||
                                                tab == 3 && (source != null || profileId != null) ->
                                                MusicScreen(
                                                    hideDuplicates = groupedView,
                                                    library,
                                                    playing.id,
                                                    playing.order,
                                                    download,
                                                    if (profile != null)
                                                        profile.id in profileLoading
                                                    else busy,
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
                                                        profileId = null
                                                        profileHint = null
                                                    },
                                                    onPlay = { track, tracks ->
                                                        play(track, tracks)
                                                    },
                                                    onLike = { like(it) },
                                                    onMore = { track, anchor ->
                                                        selectedTrackId = track.id
                                                        trackAnchor = anchor
                                                        sheet = "track"
                                                    },
                                                    onImport = onImport,
                                                    onChats = { navigate(3) },
                                                    onChooseSource = { sheet = "sourceFilter" },
                                                    onRefresh = {
                                                        app.action {
                                                            if (profile != null)
                                                                app.profiles.load(
                                                                    profile.id,
                                                                    force = true,
                                                                )
                                                            else app.refresh()
                                                        }
                                                    },
                                                    onOrder = { app.changePlaybackOrder(it) },
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
                                                    profile = profile,
                                                    profileFavorites = groupedFavorites,
                                                    ownProfile = profile?.id == selfProfileId,
                                                    profileError = profileErrors[profile?.id],
                                                    onProfileSelected = { selected ->
                                                        profile?.let {
                                                            app.action {
                                                                app.profiles.select(it, selected)
                                                            }
                                                        }
                                                    },
                                                    onProfileTelegram = {
                                                        profile?.let {
                                                            app.platform.openUrl(
                                                                if (it.username.isNotEmpty())
                                                                    "https://t.me/${it.username}"
                                                                else "tg://user?id=${it.id}"
                                                            )
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
                                                SourcesScreen(
                                                    app,
                                                    library,
                                                    busy,
                                                    auth.type == "authorizationStateReady",
                                                    onConnect = {
                                                        connect = true
                                                        app.telegram.start()
                                                    },
                                                    onAdd = { sheet = "chats" },
                                                    onOpen = {
                                                        sourceId = "chat:$it"
                                                    },
                                                    onProfile = ::openProfile,
                                                    onRemove = { s ->
                                                        app.action {
                                                            if (s.profile)
                                                                library.profiles
                                                                    .firstOrNull { it.id == s.id }
                                                                    ?.let {
                                                                        app.profiles.select(
                                                                            it,
                                                                            false,
                                                                        )
                                                                    }
                                                            else
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
                                R.string.sources to Icons.Rounded.Widgets,
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
                if (sheet in listOf("track", "share") && trackPopup) {
                    val density = LocalDensity.current
                    val margin = with(density) { 8.dp.roundToPx() }
                    Popup(
                        popupPositionProvider =
                            remember(trackAnchor, margin) {
                                TrackMenuPosition(trackAnchor, margin)
                            },
                        onDismissRequest = { sheet = "" },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Column(
                            Modifier.width(
                                    (LocalConfiguration.current.screenWidthDp - 32)
                                        .dp
                                        .coerceAtMost(if (sheet == "share") 420.dp else 380.dp)
                                )
                                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 32).dp)
                                .liquidGlass(24.dp, strong = true)
                                .padding(vertical = 8.dp)
                        ) {
                            if (sheet == "share")
                                shareContent(
                                    Modifier,
                                    (LocalConfiguration.current.screenHeightDp - 64).dp,
                                )
                            else trackActions(Modifier.weight(1f, fill = false))
                        }
                    }
                }
                if (sheet.isNotEmpty() && !(sheet in listOf("track", "share") && trackPopup)) {
                    ModalBottomSheet(
                        onDismissRequest = { sheet = "" },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = Color.Transparent,
                        dragHandle = null,
                    ) {
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
                                            onSharing = { sheet = "sharingSettings" },
                                        )
                                    "sharingSettings" ->
                                        SharingSettings(app, onBack = { sheet = "settings" })
                                    "share" -> shareContent(Modifier, null)
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
                                        SourcePicker(
                                            app,
                                            library,
                                            onDone = {
                                                sheet = ""
                                            },
                                            onProfile = ::openProfile,
                                        )
                                    "sourceFilter" ->
                                        Box(Modifier.navigationBarsPadding().imePadding()) {
                                            SourceSelectionPanel(
                                                library,
                                                source,
                                                onSource = {
                                                    sourceId = it
                                                    sheet = ""
                                                },
                                                onManage = {
                                                    sheet = ""
                                                    navigate(3)
                                                },
                                                onClose = { sheet = "" },
                                            )
                                        }
                                    "queue" -> QueueSheet(library, playing, app)
                                    "speed" ->
                                        PlaybackSpeedSheet(
                                            playing,
                                            player,
                                            onClose = { sheet = "" },
                                        )
                                    "playbackOrder" ->
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .navigationBarsPadding()
                                        ) {
                                            Text(
                                                tr(R.string.playback_order),
                                                style = MaterialTheme.typography.headlineSmall,
                                                modifier = Modifier.padding(bottom = 12.dp),
                                            )
                                            PlaybackOrderChoices(mode.order) {
                                                app.changePlaybackOrder(it)
                                                sheet = ""
                                            }
                                            Spacer(Modifier.height(12.dp))
                                        }
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
                                                ActionRow(
                                                    Icons.Rounded.Edit,
                                                    tr(R.string.rename),
                                                ) {
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
                                    "track" -> trackActions(Modifier.navigationBarsPadding())
                                }
                            }
                        }
                    }
                }
                library.byId[deletingImportId]?.let { track ->
                    GlassDialog(
                        onDismissRequest = { deletingImportId = null },
                        title = { Text(tr(R.string.delete_imported_track)) },
                        text = { Text(tr(R.string.delete_imported_track_help, track.title)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    deletingImportId = null
                                    app.action { app.removeLocal(track) }
                                }
                            ) {
                                Text(tr(R.string.delete))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { deletingImportId = null }) {
                                Text(tr(R.string.cancel))
                            }
                        },
                    )
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

private class TrackMenuPosition(private val anchor: IntRect, private val margin: Int) :
    PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x =
            if (layoutDirection == LayoutDirection.Ltr) anchor.right - popupContentSize.width
            else anchor.left
        val below = anchor.bottom + margin
        val y =
            if (below + popupContentSize.height <= windowSize.height - margin) below
            else anchor.top - margin - popupContentSize.height
        return IntOffset(
            x.coerceIn(
                margin,
                (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin),
            ),
            y.coerceIn(
                margin,
                (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin),
            ),
        )
    }
}

@Composable
fun ActionRow(
    icon: ImageVector,
    text: String,
    supportingText: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = if (compact) 48.dp else 56.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(if (compact) 20.dp else 24.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(
                text,
                style =
                    if (compact) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.bodyLarge,
            )
            if (supportingText != null)
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun MiniPlayer(
    track: Track,
    player: Player?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val state = observePlayer(player, positionUpdates = true)
    val narrow = LocalConfiguration.current.screenWidthDp < 360
    val controlSize =
        if (player?.supportsVolumeControl == true && LocalConfiguration.current.screenWidthDp < 480)
            40.dp
        else 48.dp
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
            IconButton(onClick = onPrevious, modifier = Modifier.size(controlSize)) {
                Icon(Icons.Rounded.SkipPrevious, tr(R.string.previous_track))
            }
            GlassIconButton(onClick = onToggle, tint = spectrumAccent(0), size = controlSize) {
                if (state.loading)
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) tr(R.string.pause) else tr(R.string.continue_action),
                    )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(controlSize)) {
                Icon(Icons.Rounded.SkipNext, tr(R.string.next_track))
            }
            if (player?.supportsVolumeControl == true)
                PlaybackVolumeButton(player, state, controlSize)
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
