package app.spotygram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

@Composable
private fun PlaylistCover(playlist: Playlist, library: LibraryState) {
    val tracks =
        remember(playlist.tracks, library.tracks) {
            playlist.tracks.asSequence().mapNotNull { library.byId[it] }.take(4).toList()
        }
    Box(
        Modifier.size(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (tracks.none { it.art.isNotBlank() })
            Icon(Icons.Rounded.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
        else if (tracks.size == 1) Artwork(tracks.first(), 56.dp)
        else
            Column {
                repeat(2) { row ->
                    Row {
                        repeat(2) { column -> Artwork(tracks.getOrNull(row * 2 + column), 28.dp) }
                    }
                }
            }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    library: LibraryState,
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
    onMore: (Playlist) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val playlists =
        remember(library.playlists, query) {
            library.playlists.filter { it.name.contains(query.trim(), ignoreCase = true) }
        }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr(R.string.playlists),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text("${library.playlists.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = onCreate,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .heightIn(min = 48.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(tr(R.string.new_playlist))
        }
        if (library.playlists.isNotEmpty())
            MusicSearch(query, { query = it }, tr(R.string.find_playlist))
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (playlists.isEmpty())
                item {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            if (query.isBlank()) tr(R.string.playlists_empty_title)
                            else tr(R.string.no_results),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            tr(R.string.playlists_empty_help),
                            Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            items(playlists, key = { it.id }) { playlist ->
                val haptic = LocalHapticFeedback.current
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpen(playlist.id) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMore(playlist)
                            },
                            onLongClickLabel = tr(R.string.playlist_actions),
                        )
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistCover(playlist, library)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            playlist.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            trackCount(playlist.tracks.count { it in library.byId }),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { onMore(playlist) }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            tr(R.string.playlist_actions_named, playlist.name),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistEditor(
    app: SpotygramApp,
    library: LibraryState,
    addingTo: Long?,
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val playlist = library.playlists.firstOrNull { it.id == addingTo }
    val members = remember(playlist) { playlist?.tracks?.toSet().orEmpty() }
    val chosen = remember(selected) { selected.toSet() }
    val tracks =
        remember(library.tracks, query, members) {
            library.tracks.filter {
                it.id !in members &&
                    "${it.title} ${it.artist} ${it.source}"
                        .contains(query.trim(), ignoreCase = true)
            }
        }
    fun leave() {
        if (!saving) {
            if (name.isNotBlank() || selected.isNotEmpty()) discard = true else onClose()
        }
    }
    BackHandler { leave() }
    LaunchedEffect(library.tracks, members) {
        selected = ArrayList(selected.filter { it in library.byId && it !in members })
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::leave, enabled = !saving) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back))
            }
            Text(
                if (addingTo == null) tr(R.string.new_playlist) else tr(R.string.add_tracks),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        if (compact && addingTo == null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    name,
                    { name = it.take(80) },
                    label = { Text(tr(R.string.playlist_name)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier =
                        Modifier.weight(1f).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                )
                MusicSearch(
                    query,
                    { query = it },
                    tr(R.string.title_or_artist),
                    Modifier.weight(1f),
                )
            }
        else if (addingTo == null)
            OutlinedTextField(
                name,
                { name = it.take(80) },
                label = { Text(tr(R.string.playlist_name)) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        else
            Text(
                playlist?.name ?: tr(R.string.playlist_deleted),
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (!compact || addingTo != null)
            MusicSearch(query, { query = it }, tr(R.string.title_or_artist))
        Row(
            Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr(R.string.selected_count, selected.size),
                Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(
                enabled = !saving && tracks.isNotEmpty(),
                onClick = {
                    val ids = tracks.map { it.id }.toSet()
                    selected =
                        ArrayList(
                            if (ids.all { it in chosen }) selected.filter { it !in ids }
                            else (selected + ids).distinct()
                        )
                },
            ) {
                Text(
                    if (tracks.isNotEmpty() && tracks.all { it.id in chosen })
                        tr(R.string.deselect_visible)
                    else tr(R.string.select_all)
                )
            }
        }
        LazyColumn(Modifier.weight(1f), state = rememberLazyListState()) {
            if (tracks.isEmpty())
                item {
                    Text(
                        if (query.isBlank()) tr(R.string.playlist_all_added)
                        else tr(R.string.no_results),
                        Modifier.padding(24.dp),
                    )
                }
            items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                TrackRow(
                    track,
                    selected = track.id in chosen,
                    onClick = {
                        if (!saving)
                            selected =
                                ArrayList(
                                    if (track.id in chosen) selected - track.id
                                    else selected + track.id
                                )
                    },
                )
            }
        }
        Button(
            enabled =
                !saving &&
                    (if (addingTo == null) name.isNotBlank()
                    else playlist != null && selected.isNotEmpty()),
            onClick = {
                saving = true
                keyboard?.hide()
                val title = name
                val ids = selected.toList()
                app.action {
                    try {
                        val id =
                            if (addingTo == null) app.library.createPlaylist(title, ids)
                            else {
                                app.library.addToPlaylist(addingTo, ids)
                                addingTo
                            }
                        onSaved(id)
                    } finally {
                        saving = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else
                Text(
                    if (addingTo == null)
                        if (selected.isEmpty()) tr(R.string.create_playlist)
                        else tr(R.string.create_tracks, trackCount(selected.size))
                    else tr(R.string.add_tracks_count, trackCount(selected.size))
                )
        }
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(tr(R.string.discard_title)) },
            text = { Text(tr(R.string.discard_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discard = false
                        onClose()
                    }
                ) {
                    Text(tr(R.string.leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) { Text(tr(R.string.continue_action)) }
            },
        )
}

@Composable
fun AddToPlaylistSheet(
    app: SpotygramApp,
    library: LibraryState,
    ids: List<String>,
    onDone: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)
    ) {
        Text(tr(R.string.add_to_playlist), style = MaterialTheme.typography.headlineSmall)
        Text(
            tr(R.string.selected_count, ids.size),
            Modifier.padding(top = 4.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 280.dp)) {
            items(library.playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !saving) {
                            target = playlist.id
                            creating = false
                            keyboard?.hide()
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistCover(playlist, library)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            trackCount(playlist.tracks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(
                        selected = !creating && target == playlist.id,
                        onClick = {
                            if (!saving) {
                                target = playlist.id
                                creating = false
                                keyboard?.hide()
                            }
                        },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        TextButton(
            enabled = !saving,
            onClick = {
                creating = true
                target = null
            },
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(tr(R.string.new_playlist))
        }
        if (creating)
            OutlinedTextField(
                name,
                { name = it.take(80) },
                label = { Text(tr(R.string.playlist_name)) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
        Button(
            enabled =
                !saving &&
                    ids.isNotEmpty() &&
                    (if (creating) name.isNotBlank()
                    else library.playlists.any { it.id == target }),
            onClick = {
                saving = true
                keyboard?.hide()
                val title = name
                val create = creating
                val id = target
                app.action {
                    try {
                        if (create) app.library.createPlaylist(title, ids)
                        else if (id != null) app.library.addToPlaylist(id, ids)
                        onDone()
                    } finally {
                        saving = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                if (saving) tr(R.string.saving)
                else if (creating) tr(R.string.create_and_add)
                else tr(R.string.add_tracks_count, trackCount(ids.size))
            )
        }
    }
}

@Composable
fun PlaylistOrderSheet(
    app: SpotygramApp,
    library: LibraryState,
    playlistId: Long,
    onDone: () -> Unit,
) {
    val playlist = library.playlists.firstOrNull { it.id == playlistId }
    var order by
        remember(playlistId) {
            mutableStateOf(playlist?.tracks.orEmpty().filter { it in library.byId })
        }
    var dragged by remember { mutableStateOf<String?>(null) }
    var beforeDrag by remember { mutableStateOf(emptyList<String>()) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    fun save() {
        val ids = order.toList()
        saving = true
        app.action {
            try {
                app.library.reorderPlaylist(playlistId, ids)
            } finally {
                saving = false
            }
        }
    }
    fun move(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun moveToFinger() {
        val id = dragged ?: return
        val target =
            scroll.layoutInfo.visibleItemsInfo.firstOrNull {
                fingerY >= it.offset && fingerY < it.offset + it.size
            } ?: return
        move(order.indexOf(id), order.indexOf(target.key as String))
    }
    LaunchedEffect(dragged) {
        while (dragged != null) {
            val layout = scroll.layoutInfo
            val delta =
                when {
                    fingerY < layout.viewportStartOffset + edge -> -edge / 5
                    fingerY > layout.viewportEndOffset - edge -> edge / 5
                    else -> 0f
                }
            if (delta != 0f) {
                scroll.scrollBy(delta)
                moveToFinger()
            }
            delay(16)
        }
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Text(tr(R.string.track_order), style = MaterialTheme.typography.headlineSmall)
        Text(
            tr(R.string.reorder_help),
            Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            state = scroll,
            modifier = Modifier.weight(1f, fill = false).heightIn(max = 440.dp),
        ) {
            items(order, key = { it }) { id ->
                library.byId[id]?.let { track ->
                    val index = order.indexOf(id)
                    Row(
                        Modifier.fillMaxWidth()
                            .zIndex(if (dragged == id) 1f else 0f)
                            .graphicsLayer {
                                translationY =
                                    if (dragged == id)
                                        scroll.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == id }
                                            ?.let { fingerY - it.offset - it.size / 2 } ?: 0f
                                    else 0f
                            }
                            .background(
                                if (dragged == id) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(track, 36.dp)
                        Text(
                            track.title,
                            Modifier.weight(1f).padding(start = 8.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(
                            enabled = !saving && dragged == null && index > 0,
                            onClick = {
                                move(index, index - 1)
                                save()
                            },
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, tr(R.string.move_track_up, track.title))
                        }
                        IconButton(
                            enabled = !saving && dragged == null && index < order.lastIndex,
                            onClick = {
                                move(index, index + 1)
                                save()
                            },
                        ) {
                            Icon(
                                Icons.Rounded.ArrowDownward,
                                tr(R.string.move_track_down, track.title),
                            )
                        }
                        Box(
                            Modifier.size(48.dp)
                                .semantics {
                                    contentDescription = tr(R.string.drag_track, track.title)
                                }
                                .pointerInput(id, saving) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            if (!saving) {
                                                beforeDrag = order
                                                dragged = id
                                                scroll.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == id }
                                                    ?.let { fingerY = it.offset + it.size / 2f }
                                                haptic.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                            }
                                        },
                                        onDrag = { change, amount ->
                                            if (dragged == id) {
                                                change.consume()
                                                fingerY += amount.y
                                                moveToFinger()
                                            }
                                        },
                                        onDragEnd = {
                                            if (dragged == id) {
                                                dragged = null
                                                if (order != beforeDrag) save()
                                            }
                                        },
                                        onDragCancel = {
                                            if (dragged == id) {
                                                order = beforeDrag
                                                dragged = null
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.DragHandle, null)
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = onDone,
            enabled = !saving && dragged == null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(if (saving) tr(R.string.saving) else tr(R.string.done))
        }
    }
}
