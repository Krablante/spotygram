package app.spotygram

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun orderListIcon(reverse: Boolean): ImageVector =
    ImageVector.Builder("PlaybackOrder${if (reverse) "Up" else "Down"}", 24.dp, 24.dp, 24f, 24f)
        .apply {
            path(fill = SolidColor(Color.Black)) {
                for (top in listOf(5f, 11f, 17f)) {
                    moveTo(3f, top)
                    lineTo(12f, top)
                    lineTo(12f, top + 2f)
                    lineTo(3f, top + 2f)
                    close()
                }
                fun y(value: Float) = if (reverse) 24f - value else value
                moveTo(17f, y(3f))
                lineTo(19f, y(3f))
                lineTo(19f, y(15f))
                lineTo(21f, y(13f))
                lineTo(22.4f, y(14.4f))
                lineTo(18f, y(18.8f))
                lineTo(13.6f, y(14.4f))
                lineTo(15f, y(13f))
                lineTo(17f, y(15f))
                close()
            }
        }
        .build()

private val orderedIcon = orderListIcon(false)
private val reversedIcon = orderListIcon(true)

fun playbackOrderIcon(order: PlaybackOrder): ImageVector =
    when (order) {
        PlaybackOrder.ORDERED -> orderedIcon
        PlaybackOrder.REVERSED -> reversedIcon
        PlaybackOrder.SHUFFLE -> Icons.Rounded.Shuffle
        PlaybackOrder.RANDOM -> Icons.Rounded.Casino
    }

fun playbackOrderLabel(order: PlaybackOrder): String =
    tr(
        when (order) {
            PlaybackOrder.ORDERED -> R.string.ordered_mode
            PlaybackOrder.REVERSED -> R.string.reversed_mode
            PlaybackOrder.SHUFFLE -> R.string.shuffle_no_repeats
            PlaybackOrder.RANDOM -> R.string.random_mode
        }
    )

/** The same explicit choice is used by the player, collection and track actions. */
@Composable
fun PlaybackOrderControl(
    order: PlaybackOrder,
    onOrder: (PlaybackOrder) -> Unit,
    enabled: Boolean = true,
    glass: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    Box {
        val click = { expanded = true }
        val icon: @Composable () -> Unit = {
            Icon(
                playbackOrderIcon(order),
                tr(R.string.change_playback_order, playbackOrderLabel(order)),
                tint =
                    if (order != PlaybackOrder.ORDERED) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (glass) GlassIconButton(onClick = click, enabled = enabled, content = icon)
        else IconButton(onClick = click, enabled = enabled, content = icon)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 0.dp,
            modifier = Modifier.liquidGlass(24.dp, strong = true),
        ) {
            PlaybackOrderChoices(order) {
                expanded = false
                onOrder(it)
            }
        }
    }
}

@Composable
fun PlaybackOrderChoices(order: PlaybackOrder, onOrder: (PlaybackOrder) -> Unit) {
    PlaybackOrder.entries.forEach { choice ->
        DropdownMenuItem(
            text = { Text(playbackOrderLabel(choice)) },
            leadingIcon = { Icon(playbackOrderIcon(choice), null) },
            trailingIcon = {
                if (order == choice) Icon(Icons.Rounded.Check, tr(R.string.selected_mode))
            },
            onClick = { onOrder(choice) },
        )
    }
}
