package app.spotygram

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.joinAll

@Composable
fun SourcesScreen(
    app: SpotygramApp,
    library: LibraryState,
    busy: Boolean,
    connected: Boolean,
    onConnect: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onProfile: (UserProfile) -> Unit,
    onRemove: (Source) -> Unit,
    onRefresh: () -> Unit,
) {
    var removing by remember { mutableStateOf<Source?>(null) }
    val counts = library.sourceCounts
    val selfId by app.profiles.selfId.collectAsStateWithLifecycle()
    val loading by app.profiles.loading.collectAsStateWithLifecycle()
    val errors by app.profiles.errors.collectAsStateWithLifecycle()
    val self = library.profiles.firstOrNull { it.id == selfId }
    val selectedProfiles =
        remember(library.profiles, selfId) {
            library.profiles.filter { it.selected && it.id != selfId }
        }
    val inlineActions =
        LocalConfiguration.current.screenWidthDp >= 600 &&
            LocalConfiguration.current.fontScale <= 1.2f
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected, lifecycle) {
        if (connected)
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    val me = app.profiles.currentUser()
                    app.profiles.load(me.id)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr(R.string.sources),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (connected && inlineActions) SourceToolbar(busy, onAdd, onRefresh)
        }
        if (!connected) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    tr(R.string.connect_your_telegram),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    tr(R.string.connect_help),
                    Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GlassButton(onClick = onConnect, tint = spectrumAccent(3)) {
                    Text(tr(R.string.connect))
                }
            }
        } else {
            if (!inlineActions)
                Box(Modifier.padding(start = 20.dp, bottom = 8.dp)) {
                    SourceToolbar(busy, onAdd, onRefresh)
                }
            SourceGroupList(
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = LocalDockInset.current + 12.dp),
            ) {
                item(key = "self") {
                    SourceRow(
                        tr(R.string.my_profile),
                        tr(R.string.profile_music) +
                            if (self != null && self.loadedAt != 0L) " · ${trackCount(self.count)}"
                            else "",
                        avatar = { ProfileAvatar(self, 44.dp) },
                        onClick = { self?.let(onProfile) },
                        modifier =
                            Modifier.liquidGlass(22.dp, tint = spectrumAccent(0))
                                .heightIn(min = 76.dp),
                        trailing = {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                if (selfId in loading)
                                    CircularProgressIndicator(
                                        Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                    )
                                else Icon(Icons.Rounded.ChevronRight, null)
                            }
                        },
                    )
                    if (errors[selfId] != null && self?.loadedAt == 0L)
                        Text(
                            tr(R.string.profile_open_to_retry),
                            Modifier.padding(horizontal = 24.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (selectedProfiles.isNotEmpty()) {
                    item { SourceGroupTitle(tr(R.string.profiles)) }
                    val group =
                        SourceGroup(
                            selectedProfiles.first().source().key,
                            selectedProfiles.last().source().key,
                        )
                    items(selectedProfiles, key = { it.source().key }, contentType = { group }) {
                        profile ->
                        SourceRow(
                            profile.title,
                            (if (profile.loadedAt != 0L) trackCount(profile.count) + " · "
                            else "") + tr(R.string.in_library),
                            avatar = { ProfileAvatar(profile, 44.dp) },
                            onClick = { onProfile(profile) },
                            divider = profile.id != selectedProfiles.first().id,
                            trailing = {
                                SourceMenu(profile.source()) { removing = profile.source() }
                            },
                        )
                    }
                }
                if (library.sources.isNotEmpty()) item { SourceGroupTitle(tr(R.string.chats)) }
                if (library.sources.isNotEmpty()) {
                    val group = SourceGroup(library.sources.first().key, library.sources.last().key)
                    items(library.sources, key = { it.key }, contentType = { group }) { source ->
                        SourceRow(
                            source.displayTitle,
                            trackCount(counts[source.id] ?: 0) +
                                if (source.fullyIndexed) ""
                                else if (busy) tr(R.string.history_loading_suffix)
                                else tr(R.string.history_incomplete_suffix),
                            avatar = { SourceAvatar(source, library) },
                            onClick = { onOpen(source.id) },
                            divider = source.id != library.sources.first().id,
                            trailing = { SourceMenu(source) { removing = source } },
                        )
                    }
                }
                if (library.sources.isEmpty() && selectedProfiles.isEmpty())
                    item {
                        Text(
                            tr(R.string.sources_empty),
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
    removing?.let { source ->
        GlassDialog(
            onDismissRequest = { removing = null },
            title = {
                Text(
                    tr(
                        if (source.profile) R.string.remove_profile_source
                        else R.string.remove_chat_title
                    )
                )
            },
            text = {
                Text(
                    tr(
                        if (source.profile) R.string.remove_profile_help
                        else R.string.remove_chat_help,
                        source.displayTitle,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(source)
                        removing = null
                    }
                ) {
                    Text(tr(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(tr(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SourceToolbar(busy: Boolean, onAdd: () -> Unit, onRefresh: () -> Unit) {
    val app = LocalApp.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassButton(onClick = onAdd, tint = spectrumAccent(3)) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(6.dp))
            Text(tr(R.string.add_source))
        }
        GlassIconButton(
            onClick = {
                app.telegram.chatPhotos.retry()
                onRefresh()
            },
            enabled = !busy,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, tr(R.string.refresh_music))
        }
    }
}

@Composable
private fun SourceMenu(source: Source, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, "${tr(R.string.source_actions)}: ${source.displayTitle}")
        }
        DropdownMenu(
            expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color.Transparent,
            modifier = Modifier.liquidGlass(20.dp, strong = true),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        tr(
                            if (source.profile) R.string.remove_profile_source
                            else R.string.remove_source
                        )
                    )
                },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
fun ProfileAvatar(profile: UserProfile?, size: androidx.compose.ui.unit.Dp) {
    val app = LocalApp.current
    val photos by app.profiles.photos.collectAsStateWithLifecycle()
    val selfId by app.profiles.selfId.collectAsStateWithLifecycle()
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    LaunchedEffect(profile?.id, profile?.photo, profile?.loadedAt, auth.type) {
        if (profile != null && profile.id != 0L) app.profiles.loadPhoto(profile)
    }
    val photo = profile?.photo?.takeIf { it.isNotEmpty() } ?: photos[profile?.id]
    val color = spectrumAccent(if (profile?.id == selfId) 0 else ((profile?.id ?: 0L) % 4).toInt())
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile?.title?.let(::sourceInitial) ?: "♪",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
        )
        if (!photo.isNullOrEmpty())
            AsyncImage(
                File(photo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
    }
}

@Composable
fun SourcePicker(
    app: SpotygramApp,
    library: LibraryState,
    onDone: () -> Unit,
    onProfile: (UserProfile) -> Unit,
) {
    var profiles by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val pending = remember { mutableListOf<Job>() }
    val enabled = remember { mutableSetOf<Long>() }
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        onDispose {
            val added = enabled.toSet()
            val jobs = pending.toList()
            if (added.isNotEmpty())
                app.action {
                    jobs.joinAll()
                    if (app.library.state.value.sources.any { it.id in added && !it.fullyIndexed })
                        app.refresh()
                }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr(R.string.add_source_title),
                Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            GlassIconButton(
                onClick = { refresh++ },
                enabled = auth.type == "authorizationStateReady" && connection.isEmpty(),
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    tr(if (profiles) R.string.refresh_profiles else R.string.refresh_chats),
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(onClick = onDone) { Icon(Icons.Rounded.Close, tr(R.string.close)) }
        }
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth()
                .liquidGlass(26.dp)
                .padding(4.dp)
        ) {
            for (value in listOf(false, true)) {
                Box(
                    Modifier.weight(1f)
                        .heightIn(min = 48.dp)
                        .then(
                            if (profiles == value)
                                Modifier.liquidGlass(23.dp, tint = spectrumAccent(3))
                            else Modifier
                        )
                        .clip(RoundedCornerShape(23.dp))
                        .selectable(profiles == value, role = Role.Tab) { profiles = value },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr(if (value) R.string.profiles else R.string.chats),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        if (profiles) ProfilePicker(app, library, onProfile, refreshRequest = refresh)
        else
            ChatPicker(
                app,
                library,
                onDone,
                header = false,
                refreshRequest = refresh,
                onChoice = { choice, selected ->
                    if (selected) enabled += choice.id
                    pending += app.action { app.library.source(choice, selected) }
                },
            )
    }
}

@Composable
private fun ProfilePicker(
    app: SpotygramApp,
    library: LibraryState,
    onProfile: (UserProfile) -> Unit,
    refreshRequest: Int,
) {
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    val selfId by app.profiles.selfId.collectAsStateWithLifecycle()
    val online = auth.type == "authorizationStateReady" && connection.isEmpty()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var local by remember(query) { mutableStateOf<List<UserProfile>>(emptyList()) }
    var remote by remember(query) { mutableStateOf<List<UserProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val search = query.trim()
    LaunchedEffect(search, online, refresh, refreshRequest, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = true
            error = null
            try {
                if (search.isNotEmpty()) delay(350)
                local = app.profiles.search(search, server = false)
                if (online && search.isNotEmpty())
                    remote = app.profiles.search(search, server = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (
                    (e as? TelegramException)?.code != 404 &&
                        e.message.orEmpty() !in listOf("USERNAME_INVALID", "USERNAME_NOT_OCCUPIED")
                )
                    error = friendly(e)
            } finally {
                loading = false
            }
        }
    }
    val results = (local + remote).distinctBy { it.id }.filter { it.id != selfId }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).navigationBarsPadding().imePadding()
    ) {
        MusicSearch(query, { query = it }, tr(R.string.profile_search_hint))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        LazyColumn(
            Modifier.heightIn(max = 400.dp).padding(horizontal = 8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (search.isEmpty()) item { SourceGroupTitle(tr(R.string.recent_profiles)) }
            items(results, key = { it.id }) { profile ->
                ListItem(
                    headlineContent = {
                        Text(profile.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            if (profile.username.isEmpty()) tr(R.string.telegram_profile)
                            else "@${profile.username} · ${tr(R.string.telegram_profile)}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { ProfileAvatar(profile, 44.dp) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { onProfile(profile) },
                )
            }
            if (!loading && results.isEmpty() && error == null)
                item {
                    Text(
                        tr(
                            if (search.isEmpty()) R.string.profile_search_help
                            else R.string.profile_not_found
                        ),
                        Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (!online)
                item {
                    Text(
                        tr(R.string.profile_search_offline),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            error?.let { message ->
                item {
                    Text(message, Modifier.padding(horizontal = 16.dp))
                    TextButton(onClick = { refresh++ }) { Text(tr(R.string.retry_chat_loading)) }
                }
            }
            if (results.isNotEmpty())
                item {
                    Text(
                        tr(R.string.profile_preview_help),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun ChatPicker(
    app: SpotygramApp,
    library: LibraryState,
    onDone: () -> Unit,
    header: Boolean = true,
    refreshRequest: Int = 0,
    onChoice: (ChatChoice, Boolean) -> Unit = { choice, selected ->
        app.action { app.library.source(choice, selected) }
    },
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    val ready = auth.type == "authorizationStateReady"
    val online = ready && connection.isEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var local by remember(search) { mutableStateOf<List<ChatChoice>>(emptyList()) }
    var remote by remember(search) { mutableStateOf<List<ChatChoice>>(emptyList()) }
    var localLoading by remember(search) { mutableStateOf(true) }
    var searching by remember(search) { mutableStateOf(false) }
    var localError by remember(search) { mutableStateOf<String?>(null) }
    var searchError by remember(search) { mutableStateOf<String?>(null) }

    // Loading the directory belongs to the open picker, not to a particular query.
    LaunchedEffect(online, refresh, refreshRequest, lifecycle) {
        if (!online) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = true
            loadError = null
            try {
                app.loadChats()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadError = friendly(e)
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(search, ready, refresh, refreshRequest, lifecycle) {
        if (!ready) {
            local = emptyList()
            remote = emptyList()
            localLoading = false
            return@LaunchedEffect
        }
        // Coalesce native chat updates; no polling or observer survives the picker.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            app.telegram.chatRevision
                .sample(200)
                .onStart { emit(app.telegram.chatRevision.value) }
                .distinctUntilChanged()
                .collectLatest {
                    try {
                        local = app.searchChats(search)
                        localError = null
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        localError = friendly(e)
                    } finally {
                        localLoading = false
                    }
                }
        }
    }
    LaunchedEffect(search, online, refresh, refreshRequest, lifecycle) {
        if (search.isEmpty() || !online) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            searching = true
            searchError = null
            try {
                delay(350)
                remote = app.searchChats(search, onServer = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                searchError = friendly(e)
            } finally {
                searching = false
            }
        }
    }
    val chats =
        remember(local, remote) {
            (local + remote)
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<ChatChoice> { it.id != AppText.savedChatId }
                        .thenBy { it.displayTitle.lowercase() }
                )
        }
    val error = localError ?: loadError ?: searchError
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding()
    ) {
        if (header)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(R.string.add_chats),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                )
                GlassIconButton(
                    onClick = { refresh++ },
                    enabled = online && !loading && !searching,
                ) {
                    Icon(Icons.Rounded.Refresh, tr(R.string.refresh_chats))
                }
            }
        MusicSearch(
            query,
            { query = it },
            tr(R.string.chat_search_hint),
            Modifier.fillMaxWidth(),
        )
        if (loading || searching || localLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (search.startsWith("@") || search.contains("t.me/"))
            TextButton(
                onClick = {
                    app.action {
                        app.addPublicChat(search)
                        onDone()
                        app.refresh()
                    }
                }
            ) {
                Text(tr(R.string.add_query, search))
            }
        SourceGroupList(Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) {
            if (!online)
                item {
                    Text(
                        tr(if (ready) R.string.chat_picker_offline else R.string.telegram_required),
                        Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (error != null && online)
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            tr(
                                if (localError != null || loadError != null)
                                    R.string.chat_list_failed
                                else R.string.chat_search_failed
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { refresh++ }, enabled = !loading && !searching) {
                            Text(tr(R.string.retry_chat_loading))
                        }
                    }
                }
            if (chats.isEmpty() && online && error == null)
                item {
                    Text(
                        tr(
                            when {
                                loading || localLoading -> R.string.loading_chats
                                searching -> R.string.searching_chats
                                search.isEmpty() -> R.string.chat_list_empty
                                else -> R.string.chat_search_empty
                            }
                        ),
                        Modifier.padding(vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            val group =
                chats.takeIf { it.isNotEmpty() }?.let { SourceGroup(it.first().id, it.last().id) }
            items(chats, key = { it.id }, contentType = { group }) { chat ->
                val selected = library.sources.any { it.id == chat.id }
                SourceRow(
                    chat.displayTitle,
                    "",
                    avatar = { SourceAvatar(Source(chat.id, chat.title), library) },
                    onClick = { onChoice(chat, !selected) },
                    divider = chat.id != chats.first().id,
                    trailing = { Checkbox(selected, { onChoice(chat, it) }) },
                )
            }
            item {
                Text(
                    tr(R.string.telegram_access_help),
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GlassButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            tint = spectrumAccent(3),
        ) {
            Text(tr(R.string.done_chats, library.sources.size))
        }
        Spacer(Modifier.height(20.dp))
    }
}
