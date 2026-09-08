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
    library: LibraryState,
    playingId: String,
    shuffle: Boolean,
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
    fun toggle(track: Track) {
        selected = ArrayList(if (track.id in selected) selected - track.id else selected + track.id)
    }
    LaunchedEffect(selectionReset) {
        if (selectionReset != lastReset) {
            clearSelection()
            lastReset = selectionReset
        }
    }
    LaunchedEffect(library.tracks) { selected = ArrayList(selected.filter { it in library.byId }) }
    BackHandler(enabled = selecting) { clearSelection() }
    val selectedSet = remember(selected) { selected.toSet() }
    val tracks =
        remember(library.tracks, favorites, offline, source, playlist, query, sort) {
            val positions = playlist?.tracks?.withIndex()?.associate { it.value to it.index }
            val result =
                library.tracks.filter { track ->
                    (!favorites || track.liked) &&
                        (!offline || track.local) &&
                        (source == null || track.chatId == source.id) &&
                        (positions == null || track.id in positions) &&
                        (query.isBlank() ||
                            "${track.title} ${track.artist} ${track.source}"
                                .contains(query.trim(), ignoreCase = true))
                }
            if (sort) result.sortedBy { it.title.lowercase() }
            else if (positions != null) result.sortedBy { positions[it.id] } else result
        }
    val visibleIds = remember(tracks) { tracks.map { it.id } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting)
                IconButton(onClick = ::clearSelection) {
                    Icon(Icons.Rounded.Close, "Снять выделение")
                }
            else if (playlist != null || source != null)
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            else if (favorites)
                Icon(
                    Icons.Rounded.Favorite,
                    null,
                    Modifier.padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            Text(
                if (selecting) "Выбрано: ${selected.size}"
                else playlist?.name ?: source?.title ?: if (favorites) "Любимые" else "Музыка",
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
                        Icon(Icons.Rounded.MoreVert, "Действия с плейлистом")
                    }
                else
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        if (busy)
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, "Обновить музыку")
                    }
            }
        }
        MusicSearch(query, { query = it }, if (favorites) "Поиск в любимых" else "Поиск в музыке")
        if (selecting) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        val all = visibleIds.all { it in selectedSet }
                        selected =
                            ArrayList(
                                if (all) selected.filter { it !in visibleIds.toSet() }
                                else (selected + visibleIds).distinct()
                            )
                    },
                    enabled = tracks.isNotEmpty(),
                ) {
                    Text(
                        if (tracks.isNotEmpty() && visibleIds.all { it in selectedSet })
                            "Снять видимые"
                        else "Выбрать все"
                    )
                }
                TextButton(
                    onClick = { onAddToPlaylist(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.PlaylistAdd, null)
                    Spacer(Modifier.width(4.dp))
                    Text("В плейлист")
                }
                IconButton(
                    onClick = { onDownload(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.Download, "Скачать выбранные")
                }
                if (playlist != null)
                    IconButton(
                        onClick = { removeSelected = true },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.PlaylistRemove, "Убрать выбранные из плейлиста")
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
                    Text("Выбрать")
                }
                if (playlist != null)
                    TextButton(onClick = onAddTracks) {
                        Icon(Icons.Rounded.Add, null)
                        Text("Треки")
                    }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onShuffle(tracks) },
                    enabled = tracks.any { it.local || it.available },
                ) {
                    Icon(Icons.Rounded.Shuffle, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Перемешать")
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
                        "Слушать всё",
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
                Text("Поискать в выбранных чатах")
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
                                if (query.isNotBlank() || offline) "Ничего не найдено"
                                else if (favorites) "То, что нравится"
                                else if (playlist != null) "Добавим музыку?"
                                else "Твоя музыка из Telegram",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                if (query.isNotBlank() || offline)
                                    "Измени поиск или выключи фильтр."
                                else if (favorites)
                                    "Нажми сердечко рядом с песней — она появится здесь."
                                else if (playlist != null)
                                    "Выбери сразу несколько песен из своей медиатеки."
                                else "Выбери чаты или добавь аудиофайлы с телефона.",
                                Modifier.padding(vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (playlist != null)
                                Button(onClick = onAddTracks) { Text("Добавить треки") }
                            else if (!favorites && query.isBlank() && !offline && !selecting) {
                                TextButton(onClick = onChats) { Text("Выбрать чаты") }
                                TextButton(onClick = onImport) { Text("Добавить с телефона") }
                            }
                        }
                    }
                items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                    TrackRow(
                        track,
                        track.id == playingId,
                        download.takeIf { it.trackId == track.id },
                        selected = if (selecting) track.id in selectedSet else null,
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
                                if (busy) "Загружаем всю историю…"
                                else "Продолжить загрузку истории"
                            )
                        }
                    }
            }
        }
    }
    if (removeSelected)
        AlertDialog(
            onDismissRequest = { removeSelected = false },
            title = { Text("Убрать ${trackCount(selected.size)}?") },
            text = {
                Text("Только из этого плейлиста. Музыка останется в медиатеке и на телефоне.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveTracks(selected.toList())
                        clearSelection()
                        removeSelected = false
                    }
                ) {
                    Text("Убрать")
                }
            },
            dismissButton = { TextButton(onClick = { removeSelected = false }) { Text("Отмена") } },
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
                source?.title ?: "Все источники",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 148.dp),
            )
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Все источники") },
                onClick = {
                    expanded = false
                    onSource(null)
                },
            )
            library.sources.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.title) },
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
        label = { Text("На телефоне") },
        leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, null, Modifier.size(18.dp)) },
    )
    IconButton(onClick = onSort) {
        Icon(
            Icons.Rounded.SortByAlpha,
            if (sort) "Сначала новые" else "По алфавиту",
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
                    Icon(Icons.Rounded.Close, "Очистить поиск")
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
                onLongClickLabel = "Выбрать трек",
                role = Role.Button,
            )
            .semantics {
                if (selected != null) stateDescription = if (selected) "Выбрано" else "Не выбрано"
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
                        Icons.Rounded.DownloadForOffline,
                        "На телефоне",
                        Modifier.padding(end = 3.dp).size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
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
                    if (track.liked) "Убрать из любимых: ${track.title}"
                    else "В любимые: ${track.title}",
                    tint =
                        if (track.liked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        if (selected == null && onMore != null)
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, "Действия: ${track.title}")
            }
        if (selected != null) Spacer(Modifier.width(12.dp))
    }
}
