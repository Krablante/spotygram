package app.spotygram

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private data class MusicView(
    val tracks: List<Track> = emptyList(),
    val ids: List<String> = emptyList(),
    val keys: Set<String> = emptySet(),
    val playable: Boolean = false,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    dev.chrisbanes.haze.ExperimentalHazeApi::class,
)
@Composable
fun MusicScreen(
    hideDuplicates: Boolean,
    library: LibraryState,
    playingId: String,
    order: PlaybackOrder,
    download: DownloadState,
    busy: Boolean,
    favorites: Boolean,
    offline: Boolean,
    source: Source?,
    playlist: Playlist?,
    selectionReset: Int,
    onOffline: (Boolean) -> Unit,
    onSource: (String?) -> Unit,
    onBack: () -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onLike: (Track) -> Unit,
    onMore: (Track, IntRect) -> Unit,
    onImport: () -> Unit,
    onChats: () -> Unit,
    onRefresh: () -> Unit,
    onOrder: (PlaybackOrder) -> Unit,
    onDownload: (List<String>) -> Unit,
    onAddToPlaylist: (List<String>) -> Unit,
    onSearchChats: (String) -> Unit,
    onAddTracks: () -> Unit,
    onPlaylistMenu: () -> Unit,
    onRemoveTracks: (List<String>) -> Unit,
    profile: UserProfile? = null,
    profileFavorites: Boolean = false,
    ownProfile: Boolean = false,
    profileError: String? = null,
    onProfileSelected: (Boolean) -> Unit = {},
    onProfileTelegram: () -> Unit = {},
    onChooseSource: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var lastReset by rememberSaveable { mutableIntStateOf(selectionReset) }
    val configuration = LocalConfiguration.current
    val compactPlaylist =
        playlist != null && (configuration.screenWidthDp < 400 || configuration.fontScale > 1.2f)
    var removeSelected by remember { mutableStateOf(false) }
    var removeProfile by remember { mutableStateOf(false) }
    var sourceTitleOverflow by remember(source?.key) { mutableStateOf(false) }
    var showSourceTitle by remember(source?.key) { mutableStateOf(false) }
    val heading =
        (if (ownProfile) tr(R.string.my_profile) else profile?.title)
            ?: playlist?.name
            ?: source?.displayTitle
            ?: if (favorites) tr(R.string.favorites)
            else if (offline) tr(R.string.on_device) else tr(R.string.music)
    val haptic = LocalHapticFeedback.current
    val scroll = rememberLazyListState(cacheWindow = MusicListCacheWindow)
    val dockInset = LocalDockInset.current
    fun clearSelection() {
        selected = arrayListOf()
        selecting = false
    }
    fun selectionKey(id: String) =
        if (hideDuplicates) library.duplicates.key(id)
        else if (offline) library.byId[id]?.path?.takeIf { it.isNotEmpty() } ?: id else id
    fun toggle(track: Track) {
        val key = selectionKey(track.id)
        selected =
            ArrayList(
                if (selected.any { selectionKey(it) == key })
                    selected.filter { selectionKey(it) != key }
                else selected + track.id
            )
    }
    LaunchedEffect(selectionReset) {
        if (selectionReset != lastReset) {
            clearSelection()
            lastReset = selectionReset
        }
    }
    LaunchedEffect(library.tracks, offline, hideDuplicates) {
        selected = ArrayList(selected.filter { it in library.byId }.distinctBy(::selectionKey))
    }
    BackHandler(enabled = selecting) { clearSelection() }
    val selectedSet =
        remember(selected, library.tracks, offline, hideDuplicates) {
            selected.map(::selectionKey).toSet()
        }
    val view by
        produceState(
            MusicView(),
            library,
            favorites,
            offline,
            source,
            playlist,
            query,
            sort,
            hideDuplicates,
            profileFavorites,
        ) {
            value =
                withContext(Dispatchers.Default) {
                    val search = query.trim()
                    val positions =
                        playlist?.tracks?.withIndex()?.associate { it.value to it.index }
                    val matching =
                        library.tracks.filter { track ->
                            ensureActive()
                            (!favorites || track.liked) &&
                                (!offline || (track.local && !track.temporary)) &&
                                (source != null ||
                                    favorites ||
                                    offline ||
                                    playlist != null ||
                                    track.inLibrary) &&
                                (source == null || track.sourceKey == source.key) &&
                                (profile == null || track.profilePosition >= 0) &&
                                (positions == null || track.id in positions) &&
                                track.matches(search)
                        }
                    val local = if (offline) library.localView(matching) else matching
                    val result =
                        if (hideDuplicates) library.duplicates.view(local)
                        else if (profile != null && profileFavorites)
                            local.map(library.duplicates::display)
                        else local
                    val ordered =
                        if (sort)
                            result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        else if (positions != null) result.sortedBy { positions[it.id] }
                        else if (profile != null) result.sortedBy { it.profilePosition } else result
                    MusicView(
                        ordered,
                        ordered.map { it.id },
                        ordered.map { selectionKey(it.id) }.toSet(),
                        ordered.any { it.local || it.available },
                    )
                }
        }
    val tracks = view.tracks
    val visibleIds = view.ids
    val playable = view.playable
    val visibleKeys = view.keys
    val playingPath =
        if (offline) library.byId[playingId]?.path?.takeIf { it.isNotEmpty() } else null
    Column(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalGlassSourceSelection provides GlassEnvironmentSources) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting)
                    IconButton(onClick = ::clearSelection) {
                        Icon(Icons.Rounded.Close, tr(R.string.clear_selection))
                    }
                else if (playlist != null || source != null)
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back))
                    }
                else if (favorites)
                    Icon(
                        Icons.Rounded.Favorite,
                        null,
                        Modifier.padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                val expandable = !selecting && source != null && sourceTitleOverflow
                Row(
                    Modifier.weight(1f)
                        .heightIn(min = 48.dp)
                        .then(
                            if (expandable)
                                Modifier.clickable(role = Role.Button) { showSourceTitle = true }
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selecting) tr(R.string.selected_count, selected.size) else heading,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { sourceTitleOverflow = it.hasVisualOverflow },
                    )
                    if (expandable)
                        Icon(
                            Icons.Rounded.UnfoldMore,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (!selecting) {
                    if (profile == null)
                        Text(
                            trackCount(tracks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    if (playlist != null)
                        IconButton(onClick = onPlaylistMenu) {
                            Icon(Icons.Rounded.MoreVert, tr(R.string.playlist_actions))
                        }
                    else
                        IconButton(onClick = onRefresh, enabled = !busy) {
                            if (busy)
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, tr(R.string.refresh_music))
                        }
                }
            }
            if (profile != null && !selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(profile, 52.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            if (profile.username.isEmpty() || ownProfile) tr(R.string.profile_music)
                            else "@${profile.username}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (ownProfile)
                                (if (profile.loadedAt != 0L) trackCount(profile.count) + " · "
                                else "") + tr(R.string.profile_order)
                            else
                                tr(R.string.profile_music_source) +
                                    if (profile.loadedAt != 0L) " · ${trackCount(profile.count)}"
                                    else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (profile.selected)
                            TextButton(
                                onClick = { removeProfile = true },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr(R.string.in_library))
                                Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp))
                            }
                        else
                            GlassButton(
                                onClick = { onProfileSelected(true) },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tr(R.string.add_to_library))
                            }
                    }
                }
                if (profileError != null)
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            profileError,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRefresh, enabled = !busy) {
                            Text(tr(R.string.retry_chat_loading))
                        }
                    }
            }
            MusicSearch(
                query,
                { query = it },
                if (profile != null) tr(R.string.search_profile)
                else if (favorites) tr(R.string.search_favorites) else tr(R.string.search_music),
            )
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val all = tracks.isNotEmpty() && visibleKeys.all { it in selectedSet }
                    val selectLabel =
                        tr(if (all) R.string.deselect_visible else R.string.select_all)
                    val selectVisible = {
                        selected =
                            ArrayList(
                                if (all) selected.filter { selectionKey(it) !in visibleKeys }
                                else (selected + visibleIds).distinctBy(::selectionKey)
                            )
                    }
                    GlassButton(
                        onClick = selectVisible,
                        enabled = tracks.isNotEmpty(),
                        modifier = Modifier.weight(1f).semantics { this.selected = all },
                        tint = if (all) spectrumAccent(0) else Color.Transparent,
                    ) {
                        if (compactPlaylist)
                            Icon(
                                if (all) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                                selectLabel,
                            )
                        else Text(selectLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(
                        onClick = { onAddToPlaylist(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.PlaylistAdd, tr(R.string.to_playlist))
                    }
                    IconButton(
                        onClick = { onDownload(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.Download, tr(R.string.download_selected))
                    }
                    if (playlist != null)
                        IconButton(
                            onClick = { removeSelected = true },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Rounded.PlaylistRemove,
                                tr(R.string.remove_selected_playlist),
                            )
                        }
                }
            } else {
                if (!favorites && playlist == null && profile == null)
                    MusicFilters(
                        library,
                        source,
                        offline,
                        sort,
                        onSource,
                        onOffline,
                        { sort = !sort },
                        onChooseSource,
                        onChats,
                    )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassButton(
                        modifier = Modifier.weight(1f),
                        tint = spectrumAccent(0),
                        onClick = {
                            val first =
                                when {
                                    order.shuffled -> randomTrack(tracks)
                                    order == PlaybackOrder.REVERSED ->
                                        tracks.lastOrNull { it.local || it.available }
                                    else -> tracks.firstOrNull { it.local || it.available }
                                }
                            first?.let { onPlay(it, tracks) }
                        },
                        enabled = playable,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            if (compactPlaylist) tr(R.string.play_all) else null,
                            Modifier.size(22.dp),
                        )
                        if (!compactPlaylist) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tr(R.string.play_all),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    PlaybackOrderControl(order, onOrder, enabled = playable, glass = true)
                    if (playlist != null)
                        IconButton(onClick = onAddTracks) {
                            Icon(Icons.Rounded.PlaylistAdd, tr(R.string.add_tracks))
                        }
                    IconButton(onClick = { selecting = true }, enabled = tracks.isNotEmpty()) {
                        Icon(Icons.Rounded.Checklist, tr(R.string.select))
                    }
                }
            }
            if (
                query.isNotBlank() &&
                    !favorites &&
                    profile == null &&
                    playlist == null &&
                    library.sources.isNotEmpty() &&
                    !selecting
            )
                TextButton(
                    onClick = { onSearchChats(query) },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(tr(R.string.search_selected_chats))
                }
        }
        PullToRefreshBox(
            isRefreshing = busy,
            onRefresh = { if (!selecting) onRefresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                state = scroll,
                modifier = Modifier.fillMaxSize().glassContent(),
                contentPadding = PaddingValues(bottom = dockInset + 12.dp),
            ) {
                if (tracks.isEmpty())
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                if (favorites) Icons.Rounded.FavoriteBorder
                                else Icons.Rounded.LibraryMusic,
                                null,
                                Modifier.padding(16.dp).size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (query.isNotBlank() || offline) tr(R.string.no_results)
                                else if (favorites) tr(R.string.favorites_empty_title)
                                else if (playlist != null) tr(R.string.playlist_empty_title)
                                else if (profile != null)
                                    tr(
                                        if (busy) R.string.loading_profile
                                        else R.string.profile_empty_title
                                    )
                                else tr(R.string.music_empty_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                if (query.isNotBlank() || offline) tr(R.string.search_empty_help)
                                else if (favorites) tr(R.string.favorites_empty_help)
                                else if (playlist != null) tr(R.string.playlist_empty_help)
                                else if (profile != null)
                                    tr(
                                        if (ownProfile) R.string.own_profile_empty_help
                                        else R.string.profile_empty_help
                                    )
                                else tr(R.string.music_empty_help),
                                Modifier.padding(vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (playlist != null)
                                GlassButton(onClick = onAddTracks, tint = spectrumAccent(2)) {
                                    Text(tr(R.string.add_tracks))
                                }
                            else if (profile != null && query.isBlank() && !busy) {
                                TextButton(onClick = onProfileTelegram) {
                                    Text(tr(R.string.open_in_telegram))
                                }
                            } else if (!favorites && query.isBlank() && !offline && !selecting) {
                                TextButton(onClick = onChats) { Text(tr(R.string.choose_chats)) }
                                TextButton(onClick = onImport) {
                                    Text(tr(R.string.import_from_device))
                                }
                            }
                        }
                    }
                items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                    TrackRow(
                        track,
                        track.id == playingId ||
                            playingPath != null && track.path == playingPath ||
                            hideDuplicates &&
                                library.duplicates.key(track.id) ==
                                    library.duplicates.key(playingId),
                        download.takeIf {
                            it.trackId == track.id ||
                                hideDuplicates &&
                                    library.duplicates.key(it.trackId) ==
                                        library.duplicates.key(track.id)
                        },
                        selected = if (selecting) selectionKey(track.id) in selectedSet else null,
                        onClick = { if (selecting) toggle(track) else onPlay(track, tracks) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selecting = true
                            toggle(track)
                        },
                        onLike = { onLike(track) },
                        onMore = { anchor -> onMore(track, anchor) },
                    )
                }
                if (library.sources.any { !it.fullyIndexed } && !favorites && playlist == null)
                    item {
                        TextButton(
                            onClick = onRefresh,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (busy) tr(R.string.loading_history)
                                else tr(R.string.resume_history)
                            )
                        }
                    }
            }
        }
    }
    if (removeProfile && profile != null)
        GlassDialog(
            onDismissRequest = { removeProfile = false },
            title = { Text(tr(R.string.remove_profile_source)) },
            text = { Text(tr(R.string.remove_profile_help, profile.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeProfile = false
                        onProfileSelected(false)
                    }
                ) {
                    Text(tr(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeProfile = false }) { Text(tr(R.string.cancel)) }
            },
        )
    if (showSourceTitle)
        GlassDialog(
            onDismissRequest = { showSourceTitle = false },
            title = { Text(tr(R.string.source_full_name)) },
            text = {
                SelectionContainer {
                    Text(
                        heading,
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceTitle = false }) { Text(tr(R.string.close)) }
            },
        )
    if (removeSelected)
        GlassDialog(
            onDismissRequest = { removeSelected = false },
            title = { Text(tr(R.string.remove_tracks_title, trackCount(selected.size))) },
            text = {
                Text(tr(R.string.remove_tracks_help))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveTracks(selected.toList())
                        clearSelection()
                        removeSelected = false
                    }
                ) {
                    Text(tr(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeSelected = false }) { Text(tr(R.string.cancel)) }
            },
        )
}

@Composable
@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
private fun MusicFilters(
    library: LibraryState,
    source: Source?,
    offline: Boolean,
    sort: Boolean,
    onSource: (String?) -> Unit,
    onOffline: (Boolean) -> Unit,
    onSort: () -> Unit,
    onChooseSource: () -> Unit,
    onManageSources: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widePopup = configuration.screenWidthDp >= 600
    val popup = if (expanded) remember { widePopup } else widePopup
    var availableHeight by remember { mutableStateOf(400.dp) }
    val menuWidth = minOf(360.dp, (configuration.screenWidthDp - 32).dp)
    val menuPosition = remember(density) { SourceMenuPosition(with(density) { 8.dp.roundToPx() }) }
    BackHandler(enabled = expanded) { expanded = false }
    val sourceLabel = source?.displayTitle ?: tr(R.string.all_sources)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.weight(1f).onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                availableHeight =
                    with(density) {
                        maxOf(bounds.top, configuration.screenHeightDp.dp.toPx() - bounds.bottom)
                            .toDp()
                            .minus(24.dp)
                            .coerceIn(180.dp, 560.dp)
                    }
            }
        ) {
            GlassButton(
                onClick = { if (popup) expanded = true else onChooseSource() },
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = sourceLabel },
            ) {
                if (source != null) {
                    SourceAvatar(source, library, 28.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    sourceLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            if (expanded && popup)
                Popup(
                    popupPositionProvider = menuPosition,
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    CompositionLocalProvider(
                        LocalGlassSourceSelection provides
                            dev.chrisbanes.haze.HazeSourceSelection.Behind
                    ) {
                        SourceSelectionPanel(
                            library,
                            source,
                            onSource = {
                                expanded = false
                                onSource(it)
                            },
                            onManage = {
                                expanded = false
                                onManageSources()
                            },
                            onClose = { expanded = false },
                            modifier =
                                Modifier.width(menuWidth)
                                    .liquidGlass(24.dp, strong = true)
                                    .padding(vertical = 8.dp),
                            maxHeight = availableHeight,
                        )
                    }
                }
        }
        GlassIconButton(
            onClick = { onOffline(!offline) },
            modifier = Modifier.semantics { selected = offline },
            tint = if (offline) spectrumAccent(0) else Color.Transparent,
        ) {
            Icon(Icons.Rounded.DownloadForOffline, tr(R.string.on_device))
        }
        IconButton(onClick = onSort) {
            Icon(
                Icons.Rounded.SortByAlpha,
                if (sort) tr(R.string.newest_first) else tr(R.string.alphabetical),
                tint =
                    if (sort) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private class SourceMenuPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x =
            if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        val below = anchorBounds.bottom + margin
        val y =
            if (below + popupContentSize.height <= windowSize.height - margin) below
            else anchorBounds.top - margin - popupContentSize.height
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
@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
fun MusicSearch(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focus = LocalFocusManager.current
    BasicTextField(
        value,
        onChange,
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = 50.dp)
            .liquidGlass(
                25.dp,
                interaction = interaction,
                sourceSelection = GlassEnvironmentSources,
            )
            .clip(RoundedCornerShape(25.dp)),
        singleLine = true,
        interactionSource = interaction,
        textStyle =
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        decorationBox = { input ->
            Row(
                Modifier.padding(start = 16.dp, end = if (value.isEmpty()) 16.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    null,
                    Modifier.size(21.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 14.dp)) {
                    if (value.isEmpty())
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    input()
                }
                if (value.isNotEmpty())
                    IconButton(onClick = { onChange("") }) {
                        Icon(Icons.Rounded.Close, tr(R.string.clear_search))
                    }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    active: Boolean = false,
    download: DownloadState? = null,
    selected: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onMore: ((IntRect) -> Unit)? = null,
) {
    val narrow = LocalConfiguration.current.screenWidthDp < 360
    var menuCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected == true || active)
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = if (selected == true) 0.14f else 0.08f
                    )
                else Color.Transparent
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = tr(R.string.select_track),
                role = Role.Button,
            )
            .semantics {
                if (selected != null)
                    stateDescription =
                        if (selected) tr(R.string.selected) else tr(R.string.not_selected)
            }
            .heightIn(min = 64.dp)
            .padding(
                start = if (selected == null) 16.dp else 4.dp,
                end = 4.dp,
                top = 4.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null)
            Checkbox(
                selected,
                onCheckedChange = null,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        Artwork(track, 44.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (active) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.local)
                    Icon(
                        if (track.temporary) Icons.Rounded.Schedule
                        else Icons.Rounded.DownloadForOffline,
                        tr(if (track.temporary) R.string.temporary_audio else R.string.on_device),
                        Modifier.padding(end = 3.dp).size(13.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                Text(
                    if (!track.available && !track.local)
                        tr(
                            if (track.imported) R.string.audio_unavailable
                            else if (track.profileUserId != 0L) R.string.profile_audio_unavailable
                            else R.string.message_deleted
                        )
                    else track.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = if (narrow) Modifier.weight(1f) else Modifier,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (narrow)
                    Text(
                        if (track.duration > 0) seconds(track.duration.toLong()) else "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
            }
        }
        if (download != null)
            CircularProgressIndicator(
                progress = { download.progress },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        else if (!narrow)
            Text(
                if (track.duration > 0) seconds(track.duration.toLong()) else "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (selected == null && onLike != null)
            IconToggleButton(checked = track.liked, onCheckedChange = { onLike() }) {
                Icon(
                    if (track.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (track.liked) tr(R.string.unlike_named, track.title)
                    else tr(R.string.like_named, track.title),
                    tint =
                        if (track.liked) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        if (selected == null && onMore != null)
            IconButton(
                onClick = { onMore(menuCoordinates.popupBounds()) },
                modifier = Modifier.onGloballyPositioned { menuCoordinates = it },
            ) {
                Icon(Icons.Rounded.MoreVert, tr(R.string.track_actions_named, track.title))
            }
        if (selected != null) Spacer(Modifier.width(12.dp))
    }
}
