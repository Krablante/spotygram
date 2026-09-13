package app.spotygram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample

@Composable
fun ChatsScreen(
    library: LibraryState,
    busy: Boolean,
    connected: Boolean,
    onConnect: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onRemove: (Source) -> Unit,
    onRefresh: () -> Unit,
) {
    var removing by remember { mutableStateOf<Source?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text(
            tr(R.string.your_chats),
            Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            tr(R.string.choose_sources_help),
            Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
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
                Button(onClick = onConnect) { Text(tr(R.string.connect)) }
            }
        } else {
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr(R.string.add_chats))
                }
                IconButton(onClick = onRefresh, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, tr(R.string.refresh_music))
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(library.sources, key = { it.id }) { source ->
                    ListItem(
                        headlineContent = {
                            Text(
                                source.displayTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                trackCount(library.tracks.count { it.chatId == source.id }) +
                                    if (source.fullyIndexed) ""
                                    else if (busy) tr(R.string.history_loading_suffix)
                                    else tr(R.string.history_incomplete_suffix)
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (source.id == AppText.savedChatId) Icons.Rounded.Bookmark
                                else Icons.Rounded.Forum,
                                null,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { removing = source }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    tr(R.string.remove_source_named, source.displayTitle),
                                )
                            }
                        },
                        modifier = Modifier.clickable { onOpen(source.id) },
                    )
                }
                if (library.sources.isEmpty())
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
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr(R.string.remove_chat_title)) },
            text = {
                Text(tr(R.string.remove_chat_help, source.displayTitle))
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

@OptIn(FlowPreview::class)
@Composable
fun ChatPicker(app: SpotygramApp, library: LibraryState, onDone: () -> Unit) {
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
    LaunchedEffect(online, refresh, lifecycle) {
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
    LaunchedEffect(search, ready, refresh, lifecycle) {
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
    LaunchedEffect(search, online, refresh, lifecycle) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.add_chats),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
            )
            IconButton(onClick = { refresh++ }, enabled = online && !loading && !searching) {
                Icon(Icons.Rounded.Refresh, tr(R.string.refresh_chats))
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            placeholder = { Text(tr(R.string.chat_search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty())
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, tr(R.string.clear_chat_search))
                    }
            },
        )
        if (loading || searching || localLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (search.startsWith("@") || search.contains("t.me/"))
            TextButton(
                onClick = {
                    app.action {
                        app.addPublicChat(search)
                        onDone()
                    }
                }
            ) {
                Text(tr(R.string.add_query, search))
            }
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) {
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
            items(chats, key = { it.id }) { chat ->
                val selected = library.sources.any { it.id == chat.id }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { app.action { app.library.source(chat, !selected) } }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (chat.id == AppText.savedChatId) Icons.Rounded.Bookmark
                        else Icons.Rounded.Forum,
                        null,
                        Modifier.padding(end = 14.dp),
                    )
                    Text(
                        chat.displayTitle,
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Checkbox(selected, { app.action { app.library.source(chat, it) } })
                }
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
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(tr(R.string.done_chats, library.sources.size))
        }
        Spacer(Modifier.height(20.dp))
    }
}
