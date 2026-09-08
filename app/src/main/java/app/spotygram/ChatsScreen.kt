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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

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
                    else Icon(Icons.Rounded.Refresh, tr(R.string.refresh))
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

@Composable
fun ChatPicker(app: SpotygramApp, library: LibraryState, onDone: () -> Unit) {
    val chats by app.chatChoices.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(350)
        searching = true
        try {
            app.searchChats(query)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            app.notices.tryEmit(friendly(e))
        } finally {
            searching = false
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding()
    ) {
        Text(tr(R.string.add_chats), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            placeholder = { Text(tr(R.string.chat_search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (query.startsWith("@") || query.contains("t.me/"))
            TextButton(
                onClick = {
                    app.action {
                        app.addPublicChat(query)
                        onDone()
                    }
                }
            ) {
                Text(tr(R.string.add_query, query))
            }
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
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
        }
        Text(
            tr(R.string.telegram_access_help),
            Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(tr(R.string.done_chats, library.sources.size))
        }
        Spacer(Modifier.height(20.dp))
    }
}
