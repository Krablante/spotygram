package app.spotygram

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeSourceSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

@OptIn(ExperimentalHazeApi::class, FlowPreview::class)
@Composable
fun SharingSheet(
    app: SpotygramApp,
    track: Track,
    onClose: () -> Unit,
    onSent: (ShareRecipient, JSONObject) -> Unit,
    onFailed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
) {
    val library by app.library.state.collectAsStateWithLifecycle()
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    val folders by app.telegram.chatFolders.collectAsStateWithLifecycle()
    val rightsRevision by app.telegram.recipientRevision.collectAsStateWithLifecycle()
    val pinned by app.sharing.pinned.collectAsStateWithLifecycle()
    val settings by app.sharing.defaults.collectAsStateWithLifecycle()
    val sending by app.sharing.sending.collectAsStateWithLifecycle()
    val online = auth.type == "authorizationStateReady" && connection.isEmpty()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val environment = remember { HazeSourceSelection.Behind.where { it.zIndex == 0f } }
    var query by rememberSaveable(track.id) { mutableStateOf("") }
    var folder by rememberSaveable(track.id) { mutableIntStateOf(0) }
    var limit by remember(track.id, folder) { mutableIntStateOf(100) }
    var refresh by remember { mutableIntStateOf(0) }
    var prepared by remember(track.id) { mutableStateOf(false) }
    var recording by remember(track.id) { mutableStateOf<ShareRecording?>(null) }
    var quick by remember(track.id) { mutableStateOf<List<ShareRecipient>>(emptyList()) }
    var page by remember(query, folder) { mutableStateOf(SharePage(emptyList(), false)) }
    var remote by remember(query) { mutableStateOf<List<ShareRecipient>>(emptyList()) }
    var selected by remember(track.id) { mutableStateOf<ShareRecipient?>(null) }
    var topics by remember(selected?.id) { mutableStateOf<List<ShareTopic>>(emptyList()) }
    var topic by remember(selected?.id) { mutableStateOf<ShareTopic?>(null) }
    var choosingTopic by remember(track.id) { mutableStateOf(false) }
    var configuring by rememberSaveable(track.id) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var displayedRights by remember(track.id) { mutableLongStateOf(-1L) }
    var error by remember(track.id) { mutableStateOf("") }
    val scroll = rememberLazyListState()
    val keyboard = imeVisible()
    val compact = maxHeight != null
    val height = maxHeight ?: (LocalConfiguration.current.screenHeightDp.dp * 0.82f)
    val search = query.trim()

    LaunchedEffect(track.id, online, refresh, lifecycle) {
        prepared = false
        if (!online) {
            quick = emptyList()
            page = SharePage(emptyList(), false)
            loading = false
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = true
            error = ""
            try {
                app.sharing.prepare()
                recording = app.sharing.recording(track)
                prepared = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = friendly(e)
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(track.id, prepared, online, search, folder, limit, refresh, lifecycle) {
        if (!prepared || !online) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(
                    app.telegram.chatRevision,
                    app.telegram.recipientRevision,
                    app.sharing.pinned,
                    app.sharing.recent,
                ) { directory, rights, pins, recent ->
                    Triple(directory to rights, pins, recent)
                }
                .sample(300)
                .onStart {
                    emit(
                        Triple(
                            app.telegram.chatRevision.value to app.telegram.recipientRevision.value,
                            pinned,
                            app.sharing.recent.value,
                        )
                    )
                }
                .distinctUntilChanged()
                .collectLatest { change ->
                    loading = true
                    if (displayedRights != change.first.second) {
                        quick = emptyList()
                        page = SharePage(emptyList(), false)
                        remote = emptyList()
                    }
                    try {
                        recording = app.sharing.recording(track)
                        selected?.let { current ->
                            val available = app.sharing.recipient(current.id, track.document)
                            if (available == null) {
                                selected = null
                                choosingTopic = false
                                error = tr(R.string.share_permission_lost)
                            } else selected = available
                        }
                        quick = app.sharing.quick(track.document)
                        page = app.sharing.list(folder, search, limit, track.document)
                        displayedRights = change.first.second
                        if (page.recipients.isEmpty() && page.more && search.isEmpty()) limit += 100
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        recording = null
                        error = friendly(e)
                    } finally {
                        loading = false
                    }
                }
        }
    }
    LaunchedEffect(prepared, online, search, rightsRevision, refresh, lifecycle) {
        if (!prepared || !online || search.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            searching = true
            try {
                delay(350)
                remote =
                    app.sharing.list(folder, search, 100, track.document, server = true).recipients
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = friendly(e)
            } finally {
                searching = false
            }
        }
    }
    LaunchedEffect(choosingTopic, selected?.id, online, rightsRevision, refresh, lifecycle) {
        val recipient = selected ?: return@LaunchedEffect
        if (!choosingTopic || !online) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = true
            try {
                topics = app.sharing.topics(recipient.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = friendly(e)
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(scroll, page.more, search) {
        if (!page.more || search.isNotEmpty()) return@LaunchedEffect
        snapshotFlow {
            val info = scroll.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 2 } ==
                true
        }
            .distinctUntilChanged()
            .collect { if (it && !loading) limit += 100 }
    }
    LaunchedEffect(folder, search, choosingTopic) { scroll.scrollToItem(0) }
    LaunchedEffect(folders) {
        if (folder > 0 && folders.none { it.id == folder }) folder = 0
    }
    BackHandler(enabled = configuring || choosingTopic) {
        if (configuring) configuring = false else choosingTopic = false
    }

    fun choose(value: ShareRecipient) {
        if (sending) return
        selected = value
        error = ""
        choosingTopic = value.forum
    }
    val recipients = remember(page, remote) { (page.recipients + remote).distinctBy { it.id } }
    val author = settings.author && track.chatId != 0L
    val conflict = author && !settings.caption && recording?.hasCaption == true
    val protected = recording?.let { if (author) !it.forward else !it.copy } == true

    CompositionLocalProvider(LocalGlassSourceSelection provides environment) {
        if (configuring) {
            SharingSettings(
                app,
                onBack = { configuring = false },
                onClose = onClose,
                modifier = modifier.heightIn(max = height),
            )
        } else
            Column(
                modifier.fillMaxWidth().heightIn(max = height).navigationBarsPadding().imePadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (choosingTopic)
                        IconButton(onClick = { choosingTopic = false }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr(R.string.back))
                        }
                    Text(
                        tr(
                            if (choosingTopic) R.string.share_choose_topic else R.string.share_audio
                        ),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, tr(R.string.close)) }
                }
                if (!keyboard)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Artwork(track, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${track.subtitle} · ${seconds(track.duration.toLong())}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                if (!choosingTopic)
                    key("sharing-search") {
                        MusicSearch(query, { query = it }, tr(R.string.share_search_hint))
                    }
                if (loading || searching || sending)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                SourceGroupList(Modifier.weight(1f).padding(horizontal = 16.dp), state = scroll) {
                    if (!online)
                        item {
                            Text(
                                tr(
                                    if (auth.type == "authorizationStateReady")
                                        R.string.share_offline
                                    else R.string.telegram_required
                                ),
                                Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (error.isNotEmpty())
                        item {
                            Text(
                                error,
                                Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!sending)
                                TextButton(
                                    onClick = {
                                        error = ""
                                        refresh++
                                    }
                                ) {
                                    Text(tr(R.string.retry_chat_loading))
                                }
                        }
                    if (choosingTopic) {
                        item { SourceGroupTitle(selected?.displayTitle.orEmpty(), compact) }
                        val group =
                            topics
                                .takeIf { it.isNotEmpty() }
                                ?.let {
                                    SourceGroup("topic:${it.first().id}", "topic:${it.last().id}")
                                }
                        items(topics, key = { "topic:${it.id}" }, contentType = { group }) { value
                            ->
                            SourceRow(
                                value.title,
                                "",
                                avatar = {
                                    Icon(
                                        Icons.Rounded.Tag,
                                        null,
                                        Modifier.size(36.dp).padding(6.dp),
                                    )
                                },
                                onClick = {
                                    if (!sending) {
                                        topic = value
                                        choosingTopic = false
                                    }
                                },
                                selected = topic?.id == value.id,
                                compact = true,
                                divider = value != topics.first(),
                                trailing = { RadioButton(topic?.id == value.id, onClick = null) },
                            )
                        }
                        if (topics.isEmpty() && !loading)
                            item {
                                Text(tr(R.string.share_no_topics), Modifier.padding(12.dp))
                            }
                    } else {
                        if (search.isEmpty() && !keyboard && quick.isNotEmpty())
                            item {
                                SourceGroupTitle(tr(R.string.share_quick_access), compact = true)
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val width =
                                        (maxWidth / 4).coerceAtLeast(
                                            if (LocalConfiguration.current.fontScale > 1.2f) 112.dp
                                            else 72.dp
                                        )
                                    LazyRow(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                        items(quick, key = { it.id }) { value ->
                                            Column(
                                                Modifier.width(width)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .selectable(
                                                        selected?.id == value.id,
                                                        role = Role.RadioButton,
                                                        enabled = !sending,
                                                        onClick = { choose(value) },
                                                    )
                                                    .padding(horizontal = 3.dp, vertical = 6.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Box {
                                                    SourceAvatar(
                                                        Source(value.id, value.title),
                                                        library,
                                                        44.dp,
                                                    )
                                                    if (selected?.id == value.id)
                                                        Icon(
                                                            Icons.Rounded.CheckCircle,
                                                            null,
                                                            Modifier.align(Alignment.BottomEnd)
                                                                .size(18.dp)
                                                                .background(
                                                                    MaterialTheme.colorScheme
                                                                        .surface,
                                                                    CircleShape,
                                                                ),
                                                            tint = spectrumAccent(0),
                                                        )
                                                }
                                                Text(
                                                    value.displayTitle,
                                                    Modifier.padding(top = 8.dp),
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                                Text(
                                                    value.description,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        if (search.isEmpty())
                            item {
                                LazyRow(
                                    Modifier.fillMaxWidth().liquidGlass(22.dp).padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(
                                        listOf(ShareFolder(0, tr(R.string.share_main))) +
                                            folders +
                                            ShareFolder(-1, tr(R.string.share_archive))
                                    ) { value ->
                                        Box(
                                            Modifier.clip(RoundedCornerShape(20.dp))
                                                .heightIn(min = 48.dp)
                                                .then(
                                                    if (folder == value.id)
                                                        Modifier.liquidGlass(
                                                            20.dp,
                                                            tint = spectrumAccent(0),
                                                        )
                                                    else Modifier
                                                )
                                                .selectable(
                                                    folder == value.id,
                                                    role = Role.Tab,
                                                    enabled = !sending,
                                                    onClick = { folder = value.id },
                                                )
                                                .padding(horizontal = 15.dp, vertical = 12.dp)
                                        ) {
                                            Text(
                                                value.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                color =
                                                    if (folder == value.id) spectrumAccent(0)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        val group =
                            recipients
                                .takeIf { it.isNotEmpty() }
                                ?.let { SourceGroup(it.first().id, it.last().id) }
                        items(recipients, key = { it.id }, contentType = { group }) { value ->
                            SourceRow(
                                value.displayTitle,
                                value.description,
                                avatar = {
                                    SourceAvatar(
                                        Source(value.id, value.title),
                                        library,
                                        if (compact) 36.dp else 40.dp,
                                    )
                                },
                                onClick = { choose(value) },
                                selected = selected?.id == value.id,
                                divider = value != recipients.first(),
                                compact = compact,
                                trailing = {
                                    RadioButton(selected?.id == value.id, onClick = null)
                                },
                            )
                        }
                        if (
                            recipients.isEmpty() &&
                                !loading &&
                                !searching &&
                                prepared &&
                                error.isEmpty()
                        )
                            item {
                                Text(
                                    tr(R.string.share_no_recipients),
                                    Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        if (page.more && !loading && search.isEmpty())
                            item {
                                TextButton(onClick = { limit += 100 }) {
                                    Text(tr(R.string.share_more_chats))
                                }
                            }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
                ) {
                    if (protected)
                        Text(
                            tr(R.string.share_protected),
                            Modifier.padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (conflict) {
                        Text(
                            tr(R.string.share_caption_conflict),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { app.sharing.changeDefaults(caption = true) }) {
                            Text(tr(R.string.share_enable_caption))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val summary =
                            tr(
                                if (author) R.string.share_with_author else R.string.share_from_you
                            ) +
                                " · " +
                                tr(
                                    if (settings.caption && recording?.hasCaption == true)
                                        R.string.share_with_caption
                                    else R.string.share_without_caption
                                )
                        Text(
                            summary,
                            Modifier.weight(1f).padding(start = 6.dp),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        selected?.let { value ->
                            IconButton(
                                enabled = !sending,
                                onClick = { app.sharing.pin(value.id) },
                            ) {
                                Icon(
                                    Icons.Rounded.PushPin,
                                    tr(
                                        if (value.id in pinned) R.string.share_unpin
                                        else R.string.share_pin
                                    ),
                                    tint =
                                        if (value.id in pinned) spectrumAccent(0)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { configuring = true }, enabled = !sending) {
                            Icon(Icons.Rounded.Settings, tr(R.string.share_settings))
                        }
                    }
                    GlassButton(
                        onClick = {
                            if (app.sharing.sending.value) return@GlassButton
                            val recipient = selected ?: return@GlassButton
                            val chosenTopic = topic
                            error = ""
                            app.action {
                                try {
                                    val sent = app.sharing.send(track, recipient, chosenTopic)
                                    onSent(recipient, sent)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    error = app.sharing.error(e)
                                    onFailed(error)
                                }
                            }
                        },
                        enabled =
                            online &&
                                prepared &&
                                recording != null &&
                                !sending &&
                                displayedRights == rightsRevision &&
                                selected != null &&
                                !protected &&
                                !conflict &&
                                (!selected!!.forum || topic != null),
                        modifier = Modifier.fillMaxWidth(),
                        tint = spectrumAccent(0),
                    ) {
                        if (sending)
                            CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (sending) tr(R.string.share_sending)
                            else
                                selected?.let {
                                    tr(R.string.share_send_to, it.displayTitle) +
                                        (topic?.let { value -> " · ${value.title}" } ?: "")
                                } ?: tr(R.string.share_choose_recipient),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (track.imported && track.remoteId.isEmpty())
                        Text(
                            tr(R.string.share_upload_hint),
                            Modifier.fillMaxWidth().padding(top = 7.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
    }
}

@Composable
fun SharingSettings(
    app: SpotygramApp,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaults by app.sharing.defaults.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        SettingsHeader(R.string.share_settings, onClose, onBack)
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                tr(R.string.share_default_help),
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                    Triple(
                        R.string.share_keep_author,
                        R.string.share_keep_author_help,
                        defaults.author,
                    ),
                    Triple(
                        R.string.share_keep_caption,
                        R.string.share_keep_caption_help,
                        defaults.caption,
                    ),
                )
                .forEachIndexed { index, (label, help, checked) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                checked,
                                role = Role.Switch,
                                onValueChange = {
                                    if (index == 0) app.sharing.changeDefaults(author = it)
                                    else app.sharing.changeDefaults(caption = it)
                                },
                            )
                            .padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(tr(label))
                            Text(
                                tr(help),
                                Modifier.padding(top = 5.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GlassSwitch(checked, onCheckedChange = null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            Text(
                tr(R.string.share_author_caption_rule),
                Modifier.padding(vertical = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                tr(R.string.share_channel_identity_help),
                Modifier.padding(bottom = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
