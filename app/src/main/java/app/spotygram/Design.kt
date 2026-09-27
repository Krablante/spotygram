package app.spotygram

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.io.File

private val Sea = Color(0xFF70CFC5)
private val Warm = Color(0xFFD9B695)

@Composable
fun glassAccent(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) Color(0xFF91DED2) else Color(0xFF226E69)
}

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
        primary = Color(0xFFF4F7F6),
        onPrimary = Color(0xFF132024),
        secondary = Color(0xFF91DED2),
        tertiary = Color(0xFFF0B0A4),
        background = Color(0xFF0B0D10),
        onBackground = Color(0xFFF4F5F5),
        surface = Color(0xFF20252A),
        onSurface = Color(0xFFF4F5F5),
        surfaceVariant = Color(0xFF353B40),
        onSurfaceVariant = Color(0xFFB6C1C2),
        secondaryContainer = Color(0xFF294A49),
        onSecondaryContainer = Color(0xFFE5F8F3),
        outline = Color(0xFF829396),
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF202B2F),
        onPrimary = Color.White,
        secondary = Color(0xFF226E69),
        tertiary = Color(0xFFA44047),
        background = Color(0xFFEFF1F2),
        onBackground = Color(0xFF202B2F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF202B2F),
        surfaceVariant = Color(0xFFE5E9E9),
        onSurfaceVariant = Color(0xFF526366),
        secondaryContainer = Color(0xFFDCEFEB),
        onSecondaryContainer = Color(0xFF1C4948),
        outline = Color(0xFF74888B),
    )

/** Quiet tonal ground: artwork and controls supply the color, not the page wallpaper. */
@Composable
fun PrismBackdrop(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(modifier.background(MaterialTheme.colorScheme.background)) {
        val w = size.width
        val h = size.height
        val top = Offset(w * 0.85f, -h * 0.05f)
        val bottom = Offset(-w * 0.3f, h * 1.03f)
        drawCircle(
            Brush.radialGradient(
                listOf(Sea.copy(alpha = if (dark) 0.12f else 0.075f), Color.Transparent),
                center = top, radius = w * 1.25f,
            ),
            radius = w * 1.25f, center = top,
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Warm.copy(alpha = if (dark) 0.075f else 0.06f), Color.Transparent),
                center = bottom, radius = w * 1.1f,
            ),
            radius = w * 1.1f, center = bottom,
        )
    }
}

/** Layered optical surface with a light-catching rim and a denser core for readable content. */
@Composable
fun Modifier.liquidGlass(radius: Dp = 24.dp, strong: Boolean = false, selected: Boolean = false): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (selected) {
        if (dark) Color(0xFF31504D) else Color(0xFFDCEFEB)
    } else if (dark) Color(0xFF262D32) else Color.White
    val opacity = if (strong) if (dark) 0.90f else 0.93f else if (dark) 0.76f else 0.80f
    return this.drawWithCache {
        val r = radius.toPx()
        val corner = CornerRadius(r, r)
        val fill = Brush.linearGradient(
            listOf(base.copy(alpha = opacity),
                base.copy(alpha = opacity * 0.80f),
                base.copy(alpha = opacity * 0.94f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        val edge = Brush.linearGradient(
            listOf(Color.White.copy(alpha = if (dark) 0.52f else 0.98f),
                Sea.copy(alpha = if (dark) 0.30f else 0.28f),
                Color.White.copy(alpha = if (dark) 0.10f else 0.35f),
                Warm.copy(alpha = if (dark) 0.24f else 0.32f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        onDrawBehind {
            drawRoundRect(fill, cornerRadius = corner)
            drawRoundRect(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.12f else 0.38f),
                        Color.Transparent, Sea.copy(alpha = if (dark) 0.045f else 0.055f)),
                    start = Offset.Zero, end = Offset(size.width, size.height * 1.6f),
                ), cornerRadius = corner,
            )
            drawRoundRect(edge, cornerRadius = corner, style = Stroke(1.dp.toPx()))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.55f), Color.Transparent),
                    startY = 0f, endY = size.height * 0.52f),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius((r - 2.dp.toPx()).coerceAtLeast(0f)),
                style = Stroke(1.2.dp.toPx()),
            )
        }
    }
}

@Composable
fun SpotygramTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(30.dp),
            extraLarge = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        ),
        typography =
            Typography(
                headlineSmall =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 24.sp,
                        lineHeight = 28.sp,
                         fontWeight = FontWeight.SemiBold,
                    ),
                headlineLarge =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 32.sp,
                        lineHeight = 36.sp,
                         fontWeight = FontWeight.SemiBold,
                    ),
                headlineMedium =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                         fontWeight = FontWeight.SemiBold,
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
    val app = LocalContext.current.applicationContext as SpotygramApp
    val auth by app.telegram.auth.collectAsStateWithLifecycle()
    LaunchedEffect(track?.id, track?.art, auth.type) {
        if (track != null) app.loadArtwork(track)
    }
    val palettes =
        listOf(
            Color(0xFF486F91),
            Color(0xFFA75F56),
            Color(0xFF477F67),
            Color(0xFF7F6495),
            Color(0xFF987B4C),
            Color(0xFF4A8793),
        )
    val tint =
        palettes[
            ((track?.title?.hashCode() ?: 0).toLong().let { kotlin.math.abs(it) } % palettes.size)
                .toInt()]
    Box(
        modifier.size(size).clip(RoundedCornerShape((size * 0.23f).coerceAtMost(24.dp)))
            .background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.88f),
                Color(0xFF6AAEA8)))),
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
