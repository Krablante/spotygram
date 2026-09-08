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
            "Твои чаты",
            Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            "Выбирай, откуда собирать музыку.",
            Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (!connected) {
            Column(Modifier.padding(24.dp)) {
                Text("Подключи свой Telegram", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Аудиофайлы из выбранных чатов появятся в медиатеке. Локальная музыка доступна и без входа.",
                    Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onConnect) { Text("Подключить") }
            }
        } else {
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить чаты")
                }
                IconButton(onClick = onRefresh, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, "Обновить")
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(library.sources, key = { it.id }) { source ->
                    ListItem(
                        headlineContent = {
                            Text(source.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                trackCount(library.tracks.count { it.chatId == source.id }) +
                                    if (source.fullyIndexed) "" else " · часть истории"
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (source.title == "Избранное") Icons.Rounded.Bookmark
                                else Icons.Rounded.Forum,
                                null,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { removing = source }) {
                                Icon(Icons.Rounded.Close, "Убрать ${source.title}")
                            }
                        },
                        modifier = Modifier.clickable { onOpen(source.id) },
                    )
                }
                if (library.sources.isEmpty())
                    item {
                        Text(
                            "Здесь могут быть «Избранное», музыкальный канал или общий чат с друзьями.",
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
            title = { Text("Убрать чат?") },
            text = {
                Text(
                    "Перестанем собирать музыку из «${source.title}». Скачанные треки останутся в медиатеке, сообщения в Telegram не изменятся. Чат можно добавить обратно."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(source)
                        removing = null
                    }
                ) {
                    Text("Убрать")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun ChatPicker(app: SpotygramApp, library: LibraryState, onDone: () -> Unit) {
    val chats by app.chatChoices.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(350)
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
        Text("Добавить чаты", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            placeholder = { Text("Название чата или @канал") },
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
                Text("Добавить $query")
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
                        if (chat.title == "Избранное") Icons.Rounded.Bookmark
                        else Icons.Rounded.Forum,
                        null,
                        Modifier.padding(end = 14.dp),
                    )
                    Text(
                        chat.title,
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Checkbox(selected, { app.action { app.library.source(chat, it) } })
                }
            }
        }
        Text(
            "Выбор ограничивает нашу медиатеку, не разрешения Telegram-сессии.",
            Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Готово · ${library.sources.size} чатов")
        }
        Spacer(Modifier.height(20.dp))
    }
}
