package app.spotygram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private fun volumeIcon(volume: Float) =
    when {
        volume == 0f -> Icons.Rounded.VolumeOff
        volume < 0.5f -> Icons.Rounded.VolumeDown
        else -> Icons.Rounded.VolumeUp
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackVolumeControl(player: Player, state: Playing, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 48.dp).pointerInput(player) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Scroll) {
                        val change = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        if (change != 0f) {
                            player.volume = (player.volume - change * 0.05f).coerceIn(0f, 1f)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { player.toggleMute() }, modifier = Modifier.size(40.dp)) {
            Icon(
                volumeIcon(state.volume),
                tr(if (state.volume == 0f) R.string.unmute else R.string.mute),
            )
        }
        val description = tr(R.string.volume)
        Slider(
            value = state.volume,
            onValueChange = { player.volume = it },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
            thumb = {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            },
            track = { slider ->
                Box(
                    Modifier.fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier.fillMaxWidth(slider.value)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            },
        )
        Text(
            "${(state.volume * 100).roundToInt()}%",
            modifier = Modifier.widthIn(min = 48.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun PlaybackVolumeButton(player: Player, state: Playing, size: Dp = 48.dp) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    Box {
        GlassIconButton(
            onClick = { expanded = true },
            size = size,
            modifier =
                Modifier.semantics {
                    stateDescription = "${(state.volume * 100).roundToInt()}%"
                },
        ) {
            Icon(volumeIcon(state.volume), tr(R.string.volume))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 0.dp,
            modifier = Modifier.liquidGlass(24.dp, strong = true),
        ) {
            PlaybackVolumeControl(player, state, Modifier.width(260.dp).padding(horizontal = 8.dp))
        }
    }
}
