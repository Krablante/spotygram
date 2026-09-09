package app.spotygram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    hideDuplicates: Boolean,
    library: LibraryState,
    playingId: String,
    shuffle: Boolean,
    random: Boolean,
    download: DownloadState,
    busy: Boolean,
    favorites: Boolean,
    offline: Boolean,
    source: Source?,
    playlist: Playlist?,
    selectionReset: Int,
    onOffline: (Boolean) -> Unit,
    onSource: (Long?) -> Unit,
    onBack: () -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onLike: (Track) -> Unit,
    onMore: (Track) -> Unit,
    onImport: () -> Unit,
    onChats: () -> Unit,
    onRefresh: () -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onDownload: (List<String>) -> Unit,
    onAddToPlaylist: (List<String>) -> Unit,
    onSearchChats: (String) -> Unit,
    onAddTracks: () -> Unit,
    onPlaylistMenu: () -> Unit,
    onRemoveTracks: (List<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var lastReset by rememberSaveable { mutableIntStateOf(selectionReset) }
    val compact = LocalConfiguration.current.screenHeightDp < 480
    var removeSelected by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val scroll = rememberLazyListState()
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
    val tracks =
        remember(
            library.tracks,
            favorites,
            offline,
            source,
            playlist,
            query,
            sort,
            hideDuplicates,
        ) {
            val positions = playlist?.tracks?.withIndex()?.associate { it.value to it.index }
            val matching =
                library.tracks.filter { track ->
                    (!favorites || track.liked) &&
                        (!offline || (track.local && !track.temporary)) &&
                        (source == null || track.chatId == source.id) &&
                        (positions == null || track.id in positions) &&
                        (query.isBlank() ||
                            "${track.title} ${track.artist} ${track.source}"
                                .contains(query.trim(), ignoreCase = true))
                }
            val local = if (offline) library.localView(matching) else matching
            val result = if (hideDuplicates) library.duplicates.view(local) else local
            if (sort) result.sortedBy { it.title.lowercase() }
            else if (positions != null) result.sortedBy { positions[it.id] } else result
        }
    val visibleIds = remember(tracks) { tracks.map { it.id } }
    val visibleKeys =
        remember(tracks, library.tracks, offline, hideDuplicates) {
            tracks.map { selectionKey(it.id) }.toSet()
        }
    val playingPath =
        if (offline) library.byId[playingId]?.path?.takeIf { it.isNotEmpty() } else null
    Column(Modifier.fillMaxSize()) {
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
            Text(
                if (selecting) tr(R.string.selected_count, selected.size)
                else
                    playlist?.name
                        ?: source?.displayTitle
                        ?: if (favorites) tr(R.string.favorites) else tr(R.string.music),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!selecting) {
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
        MusicSearch(
            query,
            { query = it },
            if (favorites) tr(R.string.search_favorites) else tr(R.string.search_music),
        )
        if (selecting) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        val all = visibleKeys.all { it in selectedSet }
                        selected =
                            ArrayList(
                                if (all) selected.filter { selectionKey(it) !in visibleKeys }
                                else (selected + visibleIds).distinctBy(::selectionKey)
                            )
                    },
                    enabled = tracks.isNotEmpty(),
                ) {
                    Text(
                        if (tracks.isNotEmpty() && visibleKeys.all { it in selectedSet })
                            tr(R.string.deselect_visible)
                        else tr(R.string.select_all)
                    )
                }
                TextButton(
                    onClick = { onAddToPlaylist(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.PlaylistAdd, null)
                    Spacer(Modifier.width(4.dp))
                    Text(tr(R.string.to_playlist))
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
                        Icon(Icons.Rounded.PlaylistRemove, tr(R.string.remove_selected_playlist))
                    }
            }
        } else {
            if (!compact && !favorites && playlist == null)
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicFilters(
                        library,
                        source,
                        offline,
                        sort,
                        onSource,
                        onOffline,
                        { sort = !sort },
                    )
                }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compact && !favorites && playlist == null)
                    MusicFilters(
                        library,
                        source,
                        offline,
                        sort,
                        onSource,
                        onOffline,
                        { sort = !sort },
                    )
                TextButton(onClick = { selecting = true }, enabled = tracks.isNotEmpty()) {
                    Text(tr(R.string.select))
                }
                if (playlist != null)
                    TextButton(onClick = onAddTracks) {
                        Icon(Icons.Rounded.Add, null)
                        Text(tr(R.string.tracks))
                    }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onShuffle(tracks) },
                    enabled = tracks.any { it.local || it.available },
                ) {
                    Icon(if (random) Icons.Rounded.Casino else Icons.Rounded.Shuffle, null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr(if (random) R.string.random_play else R.string.shuffle_play))
                }
                IconButton(
                    onClick = {
                        if (shuffle) onShuffle(tracks)
                        else
                            tracks
                                .firstOrNull { it.local || it.available }
                                ?.let { onPlay(it, tracks) }
                    },
                    enabled = tracks.any { it.local || it.available },
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        tr(R.string.play_all),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (
            query.isNotBlank() &&
                !favorites &&
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
        PullToRefreshBox(
            isRefreshing = busy,
            onRefresh = { if (!selecting) onRefresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                state = scroll,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
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
                                else tr(R.string.music_empty_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                if (query.isNotBlank() || offline) tr(R.string.search_empty_help)
                                else if (favorites) tr(R.string.favorites_empty_help)
                                else if (playlist != null) tr(R.string.playlist_empty_help)
                                else tr(R.string.music_empty_help),
                                Modifier.padding(vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (playlist != null)
                                Button(onClick = onAddTracks) { Text(tr(R.string.add_tracks)) }
                            else if (!favorites && query.isBlank() && !offline && !selecting) {
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
                        onMore = { onMore(track) },
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
    if (removeSelected)
        AlertDialog(
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
private fun MusicFilters(
    library: LibraryState,
    source: Source?,
    offline: Boolean,
    sort: Boolean,
    onSource: (Long?) -> Unit,
    onOffline: (Boolean) -> Unit,
    onSort: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                source?.displayTitle ?: tr(R.string.all_sources),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 148.dp),
            )
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(tr(R.string.all_sources)) },
                onClick = {
                    expanded = false
                    onSource(null)
                },
            )
            library.sources.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.displayTitle) },
                    onClick = {
                        expanded = false
                        onSource(s.id)
                    },
                )
            }
        }
    }
    FilterChip(
        selected = offline,
        onClick = { onOffline(!offline) },
        label = { Text(tr(R.string.on_device)) },
        leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, null, Modifier.size(18.dp)) },
    )
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

@Composable
fun MusicSearch(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty())
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Rounded.Close, tr(R.string.clear_search))
                }
        },
        shape = RoundedCornerShape(8.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
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
    onMore: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else androidx.compose.ui.graphics.Color.Transparent
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
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.local)
                    Icon(
                        if (track.temporary) Icons.Rounded.Schedule
                        else Icons.Rounded.DownloadForOffline,
                        tr(if (track.temporary) R.string.temporary_audio else R.string.on_device),
                        Modifier.padding(end = 3.dp).size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                Text(
                    if (!track.available && !track.local) tr(R.string.message_deleted)
                    else track.subtitle,
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
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        else
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
                        if (track.liked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        if (selected == null && onMore != null)
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, tr(R.string.track_actions_named, track.title))
            }
        if (selected != null) Spacer(Modifier.width(12.dp))
    }
}
