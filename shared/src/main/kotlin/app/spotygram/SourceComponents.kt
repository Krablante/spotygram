package app.spotygram

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeSourceSelection
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun SourceAvatar(source: Source, library: LibraryState, size: Dp = 44.dp) {
    if (source.profile) {
        ProfileAvatar(library.profiles.firstOrNull { it.id == source.id }, size)
        return
    }
    val app = LocalApp.current
    val photos by app.telegram.chatPhotos.state.collectAsStateWithLifecycle()
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    val refresh by app.telegram.chatPhotos.refresh.collectAsStateWithLifecycle()
    val photo = photos[source.id]
    val saved = source.id == AppText.savedChatId
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(source.id, auth.type, connection, refresh, lifecycle) {
        if (!saved && auth.type == "authorizationStateReady")
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.telegram.chatPhotos.state
                    .map { it[source.id]?.fileId }
                    .distinctUntilChanged()
                    .collectLatest { app.telegram.chatPhotos.load(source.id) }
            }
    }
    val accent = spectrumAccent(((source.id xor (source.id ushr 32)) and 3).toInt())
    Box(
        Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (saved) Icon(Icons.Rounded.Bookmark, null, Modifier.size(size * 0.55f), tint = accent)
        else {
            Text(
                sourceInitial(source.displayTitle),
                color = accent,
                style =
                    if (size < 36.dp) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.titleLarge,
            )
            if (!photo?.path.isNullOrEmpty())
                AsyncImage(
                    File(photo.path),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
        }
    }
}

/** The content type marks one continuous panel; only its visible part owns an optical layer. */
data class SourceGroup(val first: Any, val last: Any)

@Composable
fun SourceGroupList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 12.dp),
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scroll = state
    val density = LocalDensity.current
    val edge = with(density) { 24.dp.roundToPx() }
    val groups by
        remember(scroll, edge) {
            derivedStateOf {
                scroll.layoutInfo.visibleItemsInfo
                    .mapNotNull { it.contentType as? SourceGroup }
                    .distinct()
            }
        }
    Box(modifier.clipToBounds()) {
        groups.forEach { group ->
            key(group) {
                Box(
                    Modifier.layout { measurable, constraints ->
                            // Scroll offsets belong to layout; composition only tracks which
                            // groups are visible, rather than rerunning for every pixel.
                            val info = scroll.layoutInfo
                            val rows = info.visibleItemsInfo
                            val first = rows.firstOrNull { it.contentType == group }
                            val last = rows.lastOrNull { it.contentType == group }
                            val top =
                                when {
                                    first == null -> 0
                                    first.key == group.first -> first.offset
                                    else -> minOf(first.offset, info.viewportStartOffset - edge)
                                }
                            val bottom =
                                when {
                                    last == null -> top
                                    last.key == group.last -> last.offset + last.size
                                    else ->
                                        maxOf(
                                            last.offset + last.size,
                                            info.viewportEndOffset + edge,
                                        )
                                }
                            val height = (bottom - top).coerceAtLeast(0)
                            val panel =
                                measurable.measure(
                                    constraints.copy(minHeight = height, maxHeight = height)
                                )
                            layout(panel.width, 0) {
                                panel.place(0, top - info.viewportStartOffset)
                            }
                        }
                        .fillMaxWidth()
                        .liquidGlass(22.dp)
                )
            }
        }
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Composable
fun SourceRow(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    divider: Boolean = false,
    compact: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        if (divider)
            HorizontalDivider(
                Modifier.padding(start = 72.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
            )
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = if (compact) 56.dp else if (selected != null) 60.dp else 72.dp)
                .background(
                    if (selected == true) MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                    else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(18.dp),
                )
                .clip(RoundedCornerShape(18.dp))
                .then(
                    if (selected != null)
                        Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
                    else Modifier.clickable(onClick = onClick)
                )
                .padding(
                    start = 12.dp,
                    end = 4.dp,
                    top = if (compact) 4.dp else if (selected != null) 6.dp else 10.dp,
                    bottom = if (compact) 4.dp else if (selected != null) 6.dp else 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            avatar()
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    title,
                    style =
                        if (compact) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty())
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            trailing()
        }
    }
}

@Composable
fun SourceGroupTitle(title: String, compact: Boolean = false) {
    Text(
        title,
        Modifier.padding(
            start = 8.dp,
            top = if (compact) 10.dp else 16.dp,
            bottom = if (compact) 4.dp else 8.dp,
        ),
        style = MaterialTheme.typography.titleSmall,
        color = spectrumAccent(0),
    )
}

@Composable
@OptIn(ExperimentalHazeApi::class)
fun SourceSelectionPanel(
    library: LibraryState,
    selected: Source?,
    onSource: (String?) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    val compact = maxHeight != null
    val avatarSize = if (compact) 32.dp else 44.dp
    val selfId by LocalApp.current.profiles.selfId.collectAsStateWithLifecycle()
    val self = library.profiles.firstOrNull { it.id == selfId }
    val search = query.trim()
    val profiles =
        remember(library.profiles, selfId, search) {
            library.profiles.filter {
                it.selected &&
                    it.id != selfId &&
                    "${it.title} @${it.username}".contains(search, ignoreCase = true)
            }
        }
    val chats =
        remember(library.sources, search) {
            library.sources.filter { it.displayTitle.contains(search, ignoreCase = true) }
        }
    val counts = library.sourceCounts
    val showSelf =
        self != null &&
            (search.isEmpty() ||
                "${tr(R.string.my_profile)} ${self.title} @${self.username}"
                    .contains(search, ignoreCase = true))
    val showAll = search.isEmpty() || tr(R.string.all_sources).contains(search, ignoreCase = true)
    LaunchedEffect(search, selected?.key) {
        val specials = (if (showAll) 1 else 0) + (if (showSelf) 1 else 0)
        val index =
            when {
                search.isNotEmpty() || selected == null -> 0
                selected.profile && selected.id == selfId -> if (showAll) 1 else 0
                selected.profile ->
                    profiles
                        .indexOfFirst { it.id == selected.id }
                        .takeIf { it >= 0 }
                        ?.let { specials + 1 + it } ?: 0
                else ->
                    chats
                        .indexOfFirst { it.id == selected.id }
                        .takeIf { it >= 0 }
                        ?.let {
                            specials + (if (profiles.isEmpty()) 0 else profiles.size + 1) + 1 + it
                        } ?: 0
            }
        scroll.scrollToItem(index)
    }
    val height =
        maxHeight ?: (LocalConfiguration.current.screenHeightDp - 120).coerceIn(240, 620).dp
    val environment = remember { HazeSourceSelection.Behind.where { it.zIndex == 0f } }
    CompositionLocalProvider(LocalGlassSourceSelection provides environment) {
        Column(modifier.fillMaxWidth().heightIn(max = height).height(height)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr(R.string.choose_source),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, tr(R.string.close)) }
            }
            MusicSearch(query, { query = it }, tr(R.string.find_source))
            SourceGroupList(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                PaddingValues(top = 6.dp, bottom = 8.dp),
                state = scroll,
            ) {
                val special =
                    SourceGroup(if (showAll) "all" else "self", if (showSelf) "self" else "all")
                if (showAll)
                    item(key = "all", contentType = special) {
                        SourceRow(
                            tr(R.string.all_sources),
                            "",
                            avatar = {
                                Box(
                                    Modifier.size(avatarSize)
                                        .clip(CircleShape)
                                        .background(spectrumAccent(0).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.LibraryMusic, null, tint = spectrumAccent(0))
                                }
                            },
                            selected = selected == null,
                            compact = compact,
                            onClick = { onSource(null) },
                            trailing = { SourceChoiceMark(selected == null, compact) },
                        )
                    }
                if (showSelf)
                    item(key = "self", contentType = special) {
                        SourceRow(
                            tr(R.string.my_profile),
                            tr(R.string.profile_music) +
                                if (self.loadedAt != 0L) " · ${trackCount(self.count)}" else "",
                            avatar = { ProfileAvatar(self, avatarSize) },
                            selected = selected?.profile == true && selected.id == selfId,
                            compact = compact,
                            onClick = { onSource(self.source().key) },
                            divider = showAll,
                            trailing = {
                                SourceChoiceMark(
                                    selected?.profile == true && selected.id == selfId,
                                    compact,
                                )
                            },
                        )
                    }
                if (profiles.isNotEmpty()) {
                    item { SourceGroupTitle(tr(R.string.profiles), compact = true) }
                    val group =
                        SourceGroup(profiles.first().source().key, profiles.last().source().key)
                    items(profiles, key = { it.source().key }, contentType = { group }) { profile ->
                        val source = profile.source()
                        SourceRow(
                            profile.title,
                            if (profile.loadedAt == 0L) tr(R.string.profile_music)
                            else trackCount(profile.count),
                            avatar = { ProfileAvatar(profile, avatarSize) },
                            selected = selected?.key == source.key,
                            compact = compact,
                            onClick = { onSource(source.key) },
                            divider = profile.id != profiles.first().id,
                            trailing = { SourceChoiceMark(selected?.key == source.key, compact) },
                        )
                    }
                }
                if (chats.isNotEmpty()) {
                    item { SourceGroupTitle(tr(R.string.chats), compact = true) }
                    val group = SourceGroup(chats.first().key, chats.last().key)
                    items(chats, key = { it.key }, contentType = { group }) { source ->
                        SourceRow(
                            source.displayTitle,
                            trackCount(counts[source.id] ?: 0),
                            avatar = { SourceAvatar(source, library, avatarSize) },
                            selected = selected?.key == source.key,
                            compact = compact,
                            onClick = { onSource(source.key) },
                            divider = source.id != chats.first().id,
                            trailing = { SourceChoiceMark(selected?.key == source.key, compact) },
                        )
                    }
                }
                if (!showAll && !showSelf && profiles.isEmpty() && chats.isEmpty())
                    item {
                        Text(
                            tr(R.string.source_not_found),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
            )
            TextButton(
                onClick = onManage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Rounded.Widgets, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr(R.string.manage_sources), Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null)
            }
        }
    }
}

internal fun sourceInitial(title: String): String =
    title.trim().let {
        if (it.isEmpty()) "♪" else it.substring(0, it.offsetByCodePoints(0, 1)).uppercase()
    }

@Composable
private fun SourceChoiceMark(selected: Boolean, compact: Boolean) {
    Box(Modifier.size(if (compact) 36.dp else 48.dp), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = spectrumAccent(0))
    }
}
