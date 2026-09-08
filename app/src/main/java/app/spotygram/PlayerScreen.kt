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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
) {
    var scrub by remember(track.id) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val cover = minOf(maxWidth - 48.dp, maxHeight * 0.43f, 360.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "ИГРАЕТ ИЗ",
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        track.source,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Действия с треком") }
            }
            Spacer(Modifier.height(24.dp))
            Artwork(track, cover, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                IconButton(onClick = onLike) {
                    Icon(
                        if (track.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (track.liked) "Убрать из любимых" else "В любимые",
                        tint =
                            if (track.liked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val duration = state.duration.takeIf { it > 0 } ?: track.duration * 1000L
            Slider(
                value =
                    scrub
                        ?: state.position
                            .toFloat()
                            .coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { player?.seekTo(it.toLong()) }
                    scrub = null
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                enabled = duration > 0,
                thumb = {
                    Box(
                        Modifier.size(12.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
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
                    seconds((scrub?.toLong() ?: state.position) / 1000),
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
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player?.shuffleModeEnabled = !state.shuffle }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        "Перемешивание",
                        tint =
                            if (state.shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        if (state.position > 3000) player?.seekTo(0)
                        else player?.seekToPreviousMediaItem()
                    }
                ) {
                    Icon(Icons.Rounded.SkipPrevious, "Предыдущий трек", Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = { togglePlayback(player) },
                    modifier = Modifier.size(72.dp),
                ) {
                    if (state.loading)
                        CircularProgressIndicator(
                            Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                        )
                    else
                        Icon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.playing) "Пауза" else "Воспроизвести",
                            Modifier.size(42.dp),
                        )
                }
                IconButton(onClick = { player?.seekToNextMediaItem() }) {
                    Icon(Icons.Rounded.SkipNext, "Следующий трек", Modifier.size(36.dp))
                }
                IconButton(
                    onClick = {
                        player?.repeatMode =
                            when (state.repeat) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                    }
                ) {
                    Icon(
                        if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                        else Icons.Rounded.Repeat,
                        "Повтор",
                        tint =
                            if (state.repeat != Player.REPEAT_MODE_OFF)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDownload, enabled = !track.local) {
                    Icon(
                        if (track.local) Icons.Rounded.DownloadForOffline
                        else Icons.Rounded.Download,
                        null,
                        Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (track.local) "На телефоне" else "Скачать")
                }
                TextButton(onClick = onQueue) {
                    Icon(Icons.Rounded.QueueMusic, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Очередь")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun QueueSheet(library: LibraryState, state: Playing, player: Player?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        Text("Очередь", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (state.shuffle) "Перемешанная очередь" else trackCount(state.ids.size),
            Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 500.dp)) {
            val order = state.order.ifEmpty { state.ids.indices.toList() }
            items(
                order.size,
                key = { position -> "${order[position]}:${state.ids[order[position]]}" },
            ) { position ->
                val index = order[position]
                val track = library.tracks.firstOrNull { it.id == state.ids[index] }
                if (track != null)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                player?.seekToDefaultPosition(index)
                                player?.prepare()
                                player?.play()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color =
                                        if (index == state.index) MaterialTheme.colorScheme.primary
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
                                onClick = { player?.moveMediaItem(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, "Переместить выше")
                            }
                        IconButton(onClick = { player?.removeMediaItem(index) }) {
                            Icon(Icons.Rounded.Close, "Убрать из очереди")
                        }
                    }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
