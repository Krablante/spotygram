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
import dev.chrisbanes.haze.glass.GlassStyle
import java.io.File

private val Red = Color(0xFFED6478)
private val Amber = Color(0xFFF4B65A)
private val Yellow = Color(0xFFE9D578)
private val Green = Color(0xFF61C697)
private val Cyan = Color(0xFF53BDD0)
private val Blue = Color(0xFF618FF0)
private val Violet = Color(0xFFAB80DC)

val Spectrum = listOf(Red, Amber, Yellow, Green, Cyan, Blue, Violet)

@Composable
fun spectrumAccent(index: Int): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) listOf(Cyan, Red, Amber, Violet)[index]
    else listOf(
        Color(0xFF166D9C), Color(0xFFBA4057), Color(0xFF96611F), Color(0xFF6955A4),
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
        primary = Color(0xFFF4F5F8),
        onPrimary = Color(0xFF111319),
        secondary = Color(0xFF78CBDF),
        tertiary = Color(0xFFFF859B),
        background = Color(0xFF0B0D12),
        onBackground = Color(0xFFF4F5F8),
        surface = Color(0xFF1B1E26),
        onSurface = Color(0xFFF4F5F8),
        surfaceVariant = Color(0xFF292D38),
        onSurfaceVariant = Color(0xFFADB3C1),
        secondaryContainer = Color(0xFF263A48),
        onSecondaryContainer = Color(0xFFE3F3F7),
        outline = Color(0xFF7F899B),
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF191C26),
        onPrimary = Color.White,
        secondary = Color(0xFF166D9C),
        tertiary = Color(0xFFB74059),
        background = Color(0xFFFAFAFC),
        onBackground = Color(0xFF191C26),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF191C26),
        surfaceVariant = Color(0xFFEDEEF3),
        onSurfaceVariant = Color(0xFF5C6271),
        secondaryContainer = Color(0xFFE2EFF6),
        onSecondaryContainer = Color(0xFF193B4E),
        outline = Color(0xFF888F9E),
    )

/** Quiet paper/ink base; artwork and interactive states carry the palette. */
@Composable
fun PrismBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.background))
}

/** Optical edge and specular reflection for the small set of interactive surfaces. */
@Composable
fun Modifier.liquidGlass(radius: Dp = 24.dp, strong: Boolean = false): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) Color(0xFF7E8EA9) else Color(0xFFB9C7D7)
    val opacity = if (strong) if (dark) 0.18f else 0.34f else if (dark) 0.12f else 0.22f
    return this.drawWithCache {
        val r = radius.toPx()
        val corner = CornerRadius(r, r)
        val fill = Brush.linearGradient(listOf(base.copy(alpha = opacity * 1.2f),
            base.copy(alpha = opacity * 0.53f), base.copy(alpha = opacity * 0.82f)))
        val edge = Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.29f else 0.89f),
            if (dark) Blue.copy(alpha = 0.10f) else Color(0xFF61748D).copy(alpha = 0.22f),
            if (dark) Color.White.copy(alpha = 0.12f)
            else Color(0xFF75879F).copy(alpha = 0.20f),
        ))
        onDrawBehind {
            drawRoundRect(fill, cornerRadius = corner)
            drawRoundRect(edge, cornerRadius = corner, style = Stroke(1.dp.toPx()))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.24f else 0.82f),
                    Color.Transparent), startY = 0f, endY = size.height * 0.58f),
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius((r - 2.dp.toPx()).coerceAtLeast(0f)),
                style = Stroke(1.3.dp.toPx()),
            )
        }
    }
}

/** The dock refracts its captured content; light stays subtle on both appearances. */
@Composable
fun dockGlassStyle(radius: Dp): GlassStyle {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    return GlassStyle.regular.then {
        backgroundColor(background)
        tint(if (dark) Color(0xFF171C28).copy(alpha = 0.94f)
            else Color(0xFFF4F6FA).copy(alpha = 0.90f))
        if (dark) whitePoint(0.05f)
        shape(RoundedCornerShape(radius))
        specularIntensity(if (dark) 0.63f else 0.72f)
        ambientResponse(if (dark) 0.28f else 0.30f)
        edgeShadow(Color.Black.copy(alpha = if (dark) 0.12f else 0.14f))
    }
}

/** Keep moving row text from competing with controls on renderers with limited blur. */
@Composable
fun Modifier.dockVeil(radius: Dp): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return this.background(
        (if (dark) Color(0xFF10141D) else Color(0xFFF8F9FC))
            .copy(alpha = if (dark) 0.78f else 0.76f),
        RoundedCornerShape(radius),
    ).drawWithCache {
        val inset = 1.dp.toPx()
        onDrawBehind {
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.35f else 0.94f),
                        Color.Transparent), endY = size.height * 0.65f),
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = CornerRadius(radius.toPx() - inset),
                style = Stroke(0.8.dp.toPx()),
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
