package app.spotygram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    track: Track,
    state: Playing,
    player: Player?,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
    onQueue: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: (Int) -> Unit,
) {
    val progress = observePlayer(player, positionUpdates = true)
    var scrub by remember(track.id) { mutableStateOf<Float?>(null) }
    val largeText = LocalConfiguration.current.fontScale > 1.2f
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val cover =
            minOf(
                if (wide) maxWidth * 0.43f else maxWidth - 48.dp,
                if (wide) maxHeight * 0.7f
                else
                    minOf(
                        maxHeight * 0.42f,
                        (maxHeight - if (largeText) 414.dp else 390.dp).coerceAtLeast(96.dp),
                    ),
                340.dp,
            )
        // A real artwork reflection provides context-dependent incident colour for the controls.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp).glassContent(0.5f),
            contentAlignment = Alignment.Center,
        ) {
            Artwork(
                track,
                160.dp,
                Modifier.blur(48.dp, BlurredEdgeTreatment.Unbounded).alpha(0.18f),
            )
        }
        Column(
            Modifier.widthIn(max = if (wide) 1000.dp else 600.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(onClick = onBack) {
                    Icon(Icons.Rounded.KeyboardArrowDown, tr(R.string.close_player))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tr(R.string.playing_from),
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (track.chatId == 0L) tr(R.string.from_device)
                        else chatTitle(track.chatId, track.source),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlassIconButton(onClick = onMore) {
                    Icon(Icons.Rounded.MoreVert, tr(R.string.track_actions))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (wide) 32.dp else 0.dp),
            ) {
                if (!wide) Spacer(Modifier.width(0.dp))
                Box(
                    Modifier.then(if (wide) Modifier else Modifier.weight(1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Artwork(track, cover, Modifier.glassContent())
                }
                if (wide)
                    Column(Modifier.weight(1f)) {
                        PlayerDetails(track, onLike)
                        PlayerTransport(
                            track,
                            state,
                            progress,
                            player,
                            scrub,
                            { scrub = it },
                            { onDownload() },
                            { onQueue() },
                            { onShuffle() },
                            onRepeat,
                        )
                    }
            }
            if (!wide) {
                Spacer(Modifier.height(20.dp))
                PlayerDetails(track, onLike)
                PlayerTransport(
                    track,
                    state,
                    progress,
                    player,
                    scrub,
                    { scrub = it },
                    { onDownload() },
                    { onQueue() },
                    { onShuffle() },
                    onRepeat,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PlayerDetails(track: Track, onLike: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.subtitle,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GlassIconButton(
            onClick = onLike,
            tint = if (track.liked) spectrumAccent(1) else Color.Transparent,
        ) {
            Icon(
                if (track.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (track.liked) tr(R.string.unlike) else tr(R.string.like),
                tint =
                    if (track.liked) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTransport(
    track: Track,
    state: Playing,
    progress: Playing,
    player: Player?,
    scrub: Float?,
    onScrub: (Float?) -> Unit,
    onDownload: () -> Unit,
    onQueue: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        val duration = progress.duration.takeIf { it > 0 } ?: track.duration * 1000L
        Slider(
            value =
                scrub
                    ?: progress.position
                        .toFloat()
                        .coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { onScrub(it) },
            onValueChangeFinished = {
                scrub?.let { player?.seekTo(it.toLong()) }
                onScrub(null)
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = duration > 0,
            thumb = {
                Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            },
            track = { slider ->
                val fraction =
                    (slider.value / duration.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier.fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                seconds((scrub?.toLong() ?: progress.position) / 1000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                seconds(duration / 1000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .padding(vertical = 12.dp)
                .liquidGlass(38.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onShuffle) {
                Icon(
                    if (state.random) Icons.Rounded.Casino else Icons.Rounded.Shuffle,
                    tr(R.string.change_playback_order, playbackOrderLabel(state)),
                    tint =
                        if (state.shuffle) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    if (progress.position > 3000) player?.seekTo(0)
                    else player?.seekToPreviousMediaItem()
                }
            ) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    tr(R.string.previous_track),
                    Modifier.size(36.dp),
                )
            }
            GlassIconButton(
                onClick = { togglePlayback(player) },
                size = 64.dp,
                tint = spectrumAccent(0),
            ) {
                if (state.loading)
                    CircularProgressIndicator(
                        Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        strokeWidth = 3.dp,
                    )
                else
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) tr(R.string.pause) else tr(R.string.play),
                        Modifier.size(42.dp),
                    )
            }
            IconButton(onClick = { player?.seekToNextMediaItem() }) {
                Icon(Icons.Rounded.SkipNext, tr(R.string.next_track), Modifier.size(36.dp))
            }
            IconButton(
                onClick = {
                    onRepeat(
                        when (state.repeat) {
                            Player.REPEAT_MODE_OFF ->
                                if (state.random) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    )
                }
            ) {
                Icon(
                    if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                    else Icons.Rounded.Repeat,
                    tr(R.string.repeat_mode),
                    tint =
                        if (state.repeat != Player.REPEAT_MODE_OFF)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton(
                onClick = onDownload,
                enabled = !track.local || track.temporary,
                modifier = Modifier.weight(1f),
            ) {
                if (!track.local || track.temporary) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    when {
                        track.temporary -> tr(R.string.keep_on_device)
                        track.local -> tr(R.string.on_device)
                        else -> tr(R.string.download)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassButton(onClick = onQueue, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.QueueMusic, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr(R.string.queue), maxLines = 1)
            }
        }
    }
}

@Composable
fun QueueSheet(library: LibraryState, state: Playing, app: SpotygramApp) {
    val revision by app.queueRevision.collectAsStateWithLifecycle()
    val queue =
        remember(revision) {
            app.playback?.snapshot() ?: QueueSnapshot(emptyList(), emptyList(), -1)
        }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        Text(
            tr(if (state.random) R.string.random_pool else R.string.queue),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            if (state.random) tr(R.string.random_pool_help, trackCount(queue.ids.size))
            else if (state.shuffle) tr(R.string.shuffled_queue) else trackCount(queue.ids.size),
            Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.random)
            queue.ids.getOrNull(queue.next)?.let(library.byId::get)?.let {
                Text(
                    tr(R.string.next_named, it.title),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 500.dp)) {
            val order = queue.order
            items(
                order.size,
                key = { position -> "${order[position]}:${queue.ids[order[position]]}" },
            ) { position ->
                val index = order[position]
                val track = library.byId[queue.ids[index]]
                if (track != null)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                app.playback?.select(index)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color =
                                        if (index == queue.current)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    track.subtitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (!state.shuffle)
                            IconButton(
                                onClick = { app.playback?.moveUp(index) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, tr(R.string.move_up))
                            }
                        IconButton(onClick = { app.playback?.remove(index) }) {
                            Icon(Icons.Rounded.Close, tr(R.string.remove_from_queue))
                        }
                    }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun playbackOrderLabel(state: Playing): String =
    tr(
        when {
            state.random -> R.string.random_mode
            state.shuffle -> R.string.shuffle_no_repeats
            else -> R.string.ordered_mode
        }
    )
