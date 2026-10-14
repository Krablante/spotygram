package app.spotygram

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeSourceSelection
import kotlinx.coroutines.CancellationException

/** One track header and action list for the narrow sheet and wide anchored menu. */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun TrackActions(
    app: SpotygramApp,
    track: Track,
    order: PlaybackOrder,
    playlistId: Long?,
    onLike: () -> Unit,
    onPlaylist: () -> Unit,
    onOrder: () -> Unit,
    onDownload: () -> Unit,
    onOpenTelegram: () -> Unit,
    onProfileChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val environment = remember { HazeSourceSelection.Behind.where { it.zIndex == 0f } }
    val compact = LocalConfiguration.current.screenWidthDp >= 600
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Artwork(track, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 2, style = MaterialTheme.typography.bodyLarge)
                Text(
                    track.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        CompositionLocalProvider(LocalGlassSourceSelection provides environment) {
            ProfileAudioButton(app, track, onProfileChanged)
        }
        ActionRow(
            if (track.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            tr(if (track.liked) R.string.unlike else R.string.like),
            compact = compact,
        ) {
            onLike()
            onClose()
        }
        ActionRow(
            Icons.Rounded.PlaylistAdd,
            tr(R.string.to_playlist),
            compact = compact,
            onClick = onPlaylist,
        )
        ActionRow(Icons.Rounded.QueuePlayNext, tr(R.string.play_next), compact = compact) {
            app.playback?.addNext(track)
            onClose()
        }
        ActionRow(
            playbackOrderIcon(order),
            tr(R.string.playback_order),
            supportingText = playbackOrderLabel(order),
            compact = compact,
            onClick = onOrder,
        )
        if (track.local && track.temporary)
            ActionRow(Icons.Rounded.Download, tr(R.string.keep_on_device), compact = compact) {
                onDownload()
                onClose()
            }
        if (track.local)
            ActionRow(
                Icons.Rounded.DeleteOutline,
                tr(R.string.remove_from_device),
                compact = compact,
            ) {
                app.action { app.removeLocal(track) }
                onClose()
            }
        else if (track.available)
            ActionRow(Icons.Rounded.Download, tr(R.string.download_to_device), compact = compact) {
                onDownload()
                onClose()
            }
        if (playlistId != null)
            ActionRow(
                Icons.Rounded.PlaylistRemove,
                tr(R.string.remove_from_playlist),
                compact = compact,
            ) {
                app.action { app.library.removeFromPlaylist(playlistId, track.id) }
                onClose()
            }
        if (track.chatId != 0L && track.available)
            ActionRow(
                Icons.Rounded.OpenInNew,
                tr(R.string.open_in_telegram),
                compact = compact,
                onClick = onOpenTelegram,
            )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProfileAudioButton(app: SpotygramApp, track: Track, onChanged: (Boolean) -> Unit) {
    // Telegram profile music requires an Audio file, not an audio sent as Document.
    if (track.document) return
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    val connection by app.telegram.connection.collectAsStateWithLifecycle()
    val revision by app.profiles.audioRevision.collectAsStateWithLifecycle()
    val editing by app.profiles.editingAudio.collectAsStateWithLifecycle()
    var present by remember(track.id) { mutableStateOf<Boolean?>(null) }
    var inspecting by remember(track.id) { mutableStateOf(false) }
    var changing by remember(track.id) { mutableStateOf(false) }
    var error by remember(track.id) { mutableStateOf("") }
    var retry by remember(track.id) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connected = auth.type == "authorizationStateReady" && connection.isEmpty()
    LaunchedEffect(track.id, auth.type, connection, revision, retry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!connected || changing) return@repeatOnLifecycle
            inspecting = true
            error = ""
            try {
                present = app.profiles.containsAudio(track)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                present = null
                error = friendly(e)
            } finally {
                inspecting = false
            }
        }
    }
    if (auth.type != "authorizationStateReady") return
    if (!track.available && !track.local && track.remoteId.isEmpty()) return
    val remove = present == true
    val addLabel = tr(R.string.profile_audio_add_short)
    val removeLabel = tr(R.string.profile_audio_remove_short)
    val labelStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val buttonWidth =
        with(LocalDensity.current) {
                maxOf(
                        textMeasurer.measure(addLabel, labelStyle).size.width,
                        textMeasurer.measure(removeLabel, labelStyle).size.width,
                    )
                    .toDp() + 56.dp
            }
            .coerceAtLeast(128.dp)
    val description = tr(if (remove) R.string.remove_profile_audio else R.string.add_profile_audio)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassButton(
            onClick = {
                val added = !remove
                changing = true
                error = ""
                app.action {
                    try {
                        app.profiles.changeAudio(track, added)
                        present = added
                        onChanged(added)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = friendly(e)
                        // Reinspect after a timeout rather than advertising an unconfirmed state.
                        try {
                            present = app.profiles.containsAudio(track)
                        } catch (inspection: Exception) {
                            if (inspection is CancellationException) throw inspection
                            present = null
                        }
                    } finally {
                        changing = false
                    }
                }
            },
            enabled = connected && present != null && !inspecting && !changing && !editing,
            modifier = Modifier.width(buttonWidth).semantics { contentDescription = description },
            tint = if (remove) spectrumAccent(0) else Color.Transparent,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (inspecting || changing)
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else
                Icon(
                    if (remove) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd,
                    null,
                    Modifier.size(18.dp),
                )
            Spacer(Modifier.width(6.dp))
            Text(
                if (remove) removeLabel else addLabel,
                modifier = Modifier.clearAndSetSemantics {},
                style = labelStyle,
                textAlign = TextAlign.Center,
            )
        }
        val detail =
            when {
                !connected -> tr(R.string.profile_offline)
                error.isNotEmpty() -> error
                track.imported && track.remoteId.isEmpty() && !remove ->
                    tr(R.string.profile_audio_upload)
                else -> ""
            }
        if (detail.isNotEmpty())
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        if (error.isNotEmpty() && present == null && connected && !changing && !inspecting)
            TextButton(onClick = { retry++ }, contentPadding = PaddingValues(0.dp)) {
                Text(tr(R.string.retry_chat_loading))
            }
    }
}
