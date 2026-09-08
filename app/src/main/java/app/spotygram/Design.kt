package app.spotygram

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import java.io.File

val Green = Color(0xFF1ED760)

/** A modal has its own window; changing the app theme must update that window too. */
@Composable
fun MatchDialogSystemBars() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    LaunchedEffect(view, light) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}

private val Dark =
    darkColorScheme(
        primary = Green,
        onPrimary = Color(0xFF062910),
        background = Color(0xFF121212),
        onBackground = Color.White,
        surface = Color(0xFF121212),
        onSurface = Color(0xFFF7F7F7),
        surfaceVariant = Color(0xFF242424),
        onSurfaceVariant = Color(0xFFB3B3B3),
        secondaryContainer = Color(0xFF292929),
        onSecondaryContainer = Color.White,
        outline = Color(0xFF555555),
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF087F36),
        onPrimary = Color.White,
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF121212),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF121212),
        surfaceVariant = Color(0xFFECECEC),
        onSurfaceVariant = Color(0xFF626262),
        secondaryContainer = Color(0xFFE8E8E8),
        onSecondaryContainer = Color(0xFF121212),
        outline = Color(0xFF969696),
    )

@Composable
fun SpotygramTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography =
            Typography(
                headlineLarge =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 32.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                headlineMedium =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                titleLarge =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                titleMedium =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                bodyLarge =
                    androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium =
                    androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                labelLarge =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            ),
        content = content,
    )
}

@Composable
fun Artwork(track: Track?, size: Dp, modifier: Modifier = Modifier) {
    val palettes =
        listOf(
            Color(0xFF355047),
            Color(0xFF57485E),
            Color(0xFF745340),
            Color(0xFF405973),
            Color(0xFF60623C),
        )
    val tint =
        palettes[
            ((track?.title?.hashCode() ?: 0).toLong().let { kotlin.math.abs(it) } % palettes.size)
                .toInt()]
    Box(
        modifier.size(size).clip(RoundedCornerShape(4.dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            null,
            Modifier.size(size * 0.42f),
            tint = Color.White.copy(alpha = 0.65f),
        )
        if (!track?.art.isNullOrBlank())
            AsyncImage(
                File(track!!.art),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
    }
}
