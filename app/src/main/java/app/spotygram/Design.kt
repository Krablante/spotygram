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
import androidx.compose.ui.graphics.Path
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

private val Red = Color(0xFFF07175)
private val Amber = Color(0xFFF6AF58)
private val Yellow = Color(0xFFE9D96C)
private val Green = Color(0xFF72C987)
private val Cyan = Color(0xFF58C9D2)
private val Blue = Color(0xFF5E9DEB)
private val Violet = Color(0xFFA08CE4)

val Spectrum = listOf(Red, Amber, Yellow, Green, Cyan, Blue, Violet)

@Composable
fun spectrumAccent(index: Int): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) listOf(Cyan, Red, Yellow, Green)[index]
    else listOf(
        Color(0xFF086D79), Color(0xFFAE4450), Color(0xFF785A1A), Color(0xFF277347),
    )[index]
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
        primary = Color(0xFFF3F6F4),
        onPrimary = Color(0xFF172A2C),
        secondary = Color(0xFF8ADDE6),
        tertiary = Color(0xFFFFA2A2),
        background = Color(0xFF101B1D),
        onBackground = Color(0xFFF1F6F3),
        surface = Color(0xFF253437),
        onSurface = Color(0xFFF1F6F3),
        surfaceVariant = Color(0xFF35494C),
        onSurfaceVariant = Color(0xFFC0CFCD),
        secondaryContainer = Color(0xFF294C50),
        onSecondaryContainer = Color(0xFFEAF7F2),
        outline = Color(0xFF87A5A4),
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF253A3D),
        onPrimary = Color.White,
        secondary = Color(0xFF137B8A),
        tertiary = Color(0xFFB84750),
        background = Color(0xFFF0F3F1),
        onBackground = Color(0xFF1D3032),
        surface = Color(0xFFFAFCFA),
        onSurface = Color(0xFF1D3032),
        surfaceVariant = Color(0xFFE0EAE7),
        onSurfaceVariant = Color(0xFF52686B),
        secondaryContainer = Color(0xFFD7F0E9),
        onSecondaryContainer = Color(0xFF193F42),
        outline = Color(0xFF789394),
    )

/** One static color field sits behind the scrolling content and translucent controls. */
@Composable
fun PrismBackdrop(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(modifier.background(MaterialTheme.colorScheme.background)) {
        val w = size.width
        val h = size.height
        val glows = listOf(
            Triple(Blue, Offset(w * 0.06f, h * 0.13f), w * 0.88f),
            Triple(Amber, Offset(w * 0.91f, h * 0.18f), w * 0.84f),
            Triple(Green, Offset(w * 0.02f, h * 0.47f), w * 0.90f),
            Triple(Cyan, Offset(w * 0.87f, h * 0.55f), w * 0.87f),
            Triple(Violet, Offset(w * 0.12f, h * 0.79f), w * 0.85f),
            Triple(Red, Offset(w * 0.97f, h * 0.89f), w * 0.94f),
        )
        glows.forEach { (color, center, radius) ->
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = if (dark) 0.30f else 0.24f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        val grid = 56.dp.toPx()
        val gridColor = if (dark) Color.White.copy(alpha = 0.035f)
            else Color(0xFF223A40).copy(alpha = 0.055f)
        for (x in 0..(w / grid).toInt())
            drawLine(gridColor, Offset(x * grid, 0f), Offset(x * grid, h), 0.6.dp.toPx())
        for (y in 0..(h / grid).toInt())
            drawLine(gridColor, Offset(0f, y * grid), Offset(w, y * grid), 0.6.dp.toPx())

        val rainbow = Brush.horizontalGradient(Spectrum)
        val ribbon = Path().apply {
            moveTo(-w * 0.15f, h * 0.30f)
            cubicTo(w * 0.26f, h * 0.16f, w * 0.72f, h * 0.29f, w * 1.15f, h * 0.13f)
        }
        drawPath(ribbon, Brush.horizontalGradient(Spectrum.map {
            it.copy(alpha = if (dark) 0.19f else 0.24f)
        }), style = Stroke(width = 55.dp.toPx()))
        drawPath(ribbon, rainbow, alpha = if (dark) 0.80f else 0.75f,
            style = Stroke(width = 12.dp.toPx()))
        drawPath(ribbon, Brush.horizontalGradient(Spectrum.map {
            it.copy(alpha = if (dark) 0.30f else 0.48f)
        }), style = Stroke(width = 2.dp.toPx()))

        val lowerRibbon = Path().apply {
            moveTo(-w * 0.16f, h * 0.96f)
            cubicTo(w * 0.22f, h * 0.82f, w * 0.66f, h * 1.00f, w * 1.17f, h * 0.83f)
        }
        drawPath(lowerRibbon, Brush.horizontalGradient(Spectrum.reversed().map {
            it.copy(alpha = if (dark) 0.22f else 0.27f)
        }), style = Stroke(width = 74.dp.toPx()))
        drawPath(lowerRibbon, Brush.horizontalGradient(Spectrum.reversed()),
            alpha = if (dark) 0.55f else 0.53f, style = Stroke(width = 8.dp.toPx()))
    }
}

/** A tinted optical rim and soft inner reflection; drawn once per surface, without per-row blur. */
@Composable
fun Modifier.liquidGlass(radius: Dp = 24.dp, strong: Boolean = false): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) Color(0xFF829DA0) else Color.White
    val opacity = if (strong) if (dark) 0.29f else 0.80f else if (dark) 0.18f else 0.53f
    val rim = if (dark) Color.White.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.96f)
    return this.drawWithCache {
        val r = radius.toPx()
        val corner = CornerRadius(r, r)
        val fill = Brush.linearGradient(
            listOf(base.copy(alpha = opacity),
                base.copy(alpha = opacity * 0.52f),
                base.copy(alpha = opacity * 0.80f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        val edge = Brush.linearGradient(
            listOf(rim, Cyan.copy(alpha = 0.55f), Yellow.copy(alpha = 0.50f),
                Red.copy(alpha = 0.50f), rim.copy(alpha = if (dark) 0.32f else 0.7f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        onDrawBehind {
            drawRoundRect(fill, cornerRadius = corner)
            drawRoundRect(edge, cornerRadius = corner, style = Stroke(0.9.dp.toPx()))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.15f else 0.38f), Color.Transparent),
                    startY = 0f, endY = size.height * 0.52f),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius((r - 2.dp.toPx()).coerceAtLeast(0f)),
                style = Stroke(1.dp.toPx()),
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
            .background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.84f),
                Spectrum[(palettes.indexOf(tint) + 3) % Spectrum.size].copy(alpha = 0.72f)))),
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
