package app.spotygram

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import java.text.NumberFormat

@Composable
fun playbackSpeedLabel(speed: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    val format =
        remember(locale) {
            NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
        }
    return "${format.format(speed.toDouble())}×"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedSheet(state: Playing, player: Player?, onClose: () -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val speed = drag ?: state.speed
    val label = playbackSpeedLabel(speed)
    val enabled = player?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true
    val accent = MaterialTheme.colorScheme.secondary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    val description = tr(R.string.playback_speed)
    fun change(value: Float) {
        player?.setPlaybackSpeed(playbackSpeedStep(value))
    }

    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.speed),
                Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, tr(R.string.close)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                onClick = { change(speed - PLAYBACK_SPEED_STEP) },
                enabled = enabled && speed > MIN_PLAYBACK_SPEED,
            ) {
                Icon(Icons.Rounded.Remove, tr(R.string.decrease_speed))
            }
            Text(
                label,
                Modifier.widthIn(min = 88.dp),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            GlassIconButton(
                onClick = { change(speed + PLAYBACK_SPEED_STEP) },
                enabled = enabled && speed < MAX_PLAYBACK_SPEED,
            ) {
                Icon(Icons.Rounded.Add, tr(R.string.increase_speed))
            }
        }
        Slider(
            value = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED),
            onValueChange = { value ->
                drag = playbackSpeedStep(value)
                change(value)
            },
            onValueChangeFinished = { drag = null },
            valueRange = MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED,
            steps = 73,
            enabled = enabled,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = description
                    stateDescription = label
                },
            thumb = {
                Box(
                    Modifier.size(24.dp)
                        .background(accent, CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            },
            track = { slider ->
                val fraction =
                    ((slider.value - MIN_PLAYBACK_SPEED) /
                            (MAX_PLAYBACK_SPEED - MIN_PLAYBACK_SPEED))
                        .coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                    drawLine(
                        inactive,
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        size.height,
                        androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    drawLine(
                        accent,
                        Offset(0f, size.height / 2),
                        Offset(size.width * fraction, size.height / 2),
                        size.height,
                        androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            },
        )
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(24.dp)) {
            listOf(0.3f, 1f, 2f, 3f, 4f).forEach { tick ->
                val fraction =
                    (tick - MIN_PLAYBACK_SPEED) / (MAX_PLAYBACK_SPEED - MIN_PLAYBACK_SPEED)
                Box(
                    Modifier.offset(x = (maxWidth - 28.dp) * fraction).width(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        playbackSpeedLabel(tick),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0.5f, 1f, 1.5f, 2f, 3f).forEach { preset ->
                val selected = speed == preset
                OutlinedButton(
                    onClick = { change(preset) },
                    enabled = enabled,
                    modifier =
                        Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            this.selected = selected
                        },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    border =
                        BorderStroke(
                            1.dp,
                            if (selected) androidx.compose.ui.graphics.Color.Transparent
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        ),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            containerColor =
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor =
                                if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Text(playbackSpeedLabel(preset), fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}
