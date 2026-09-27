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

private val Violet = Color(0xFF754FE0)
private val Iris = Color(0xFFBEA5FF)
private val Rose = Color(0xFFFF8DAB)
private val Aqua = Color(0xFF71D7DE)
private val Lemon = Color(0xFFF4DF83)

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
        primary = Iris,
        onPrimary = Color(0xFF20133C),
        background = Color(0xFF11131E),
        onBackground = Color(0xFFF7F4FF),
        surface = Color(0xFF242535),
        onSurface = Color(0xFFF7F4FF),
        surfaceVariant = Color(0xFF343549),
        onSurfaceVariant = Color(0xFFB9B8CB),
        secondaryContainer = Color(0xFF443858),
        onSecondaryContainer = Color(0xFFF7F4FF),
        outline = Color(0xFF77758F),
    )
private val Light =
    lightColorScheme(
        primary = Color(0xFF5835BB),
        onPrimary = Color.White,
        background = Color(0xFFF3F1F9),
        onBackground = Color(0xFF242139),
        surface = Color(0xFFFBFAFF),
        onSurface = Color(0xFF242139),
        surfaceVariant = Color(0xFFE5E1F0),
        onSurfaceVariant = Color(0xFF625F75),
        secondaryContainer = Color(0xFFE8DFFA),
        onSecondaryContainer = Color(0xFF30204F),
        outline = Color(0xFF8F89A2),
    )

/** One static color field sits behind the scrolling content and translucent controls. */
@Composable
fun PrismBackdrop(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(modifier.background(MaterialTheme.colorScheme.background)) {
        val w = size.width
        val h = size.height
        val strength = if (dark) 0.38f else 0.34f
        drawCircle(
            Brush.radialGradient(listOf(Violet.copy(alpha = strength), Color.Transparent),
                center = Offset(w * 0.9f, h * 0.12f), radius = w * 0.95f),
            radius = w * 0.95f, center = Offset(w * 0.9f, h * 0.12f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Aqua.copy(alpha = strength * 0.75f), Color.Transparent),
                center = Offset(w * 0.05f, h * 0.55f), radius = w * 0.8f),
            radius = w * 0.8f, center = Offset(w * 0.05f, h * 0.55f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Rose.copy(alpha = strength * 0.78f), Color.Transparent),
                center = Offset(w * 0.92f, h * 0.94f), radius = w * 0.85f),
            radius = w * 0.85f, center = Offset(w * 0.92f, h * 0.94f),
        )
        val ribbon = Path().apply {
            moveTo(-w * 0.25f, h * 0.32f)
            cubicTo(w * 0.2f, h * 0.12f, w * 0.62f, h * 0.28f, w * 1.2f, h * 0.10f)
        }
        drawPath(ribbon,
            Brush.horizontalGradient(listOf(Aqua, Violet, Rose, Lemon).map {
                it.copy(alpha = if (dark) 0.21f else 0.24f)
            }), style = Stroke(width = 42.dp.toPx()))
        drawPath(ribbon,
            Brush.horizontalGradient(listOf(Aqua, Color.White, Rose).map {
                it.copy(alpha = if (dark) 0.32f else 0.48f)
            }), style = Stroke(width = 2.dp.toPx()))
        val lowerRibbon = Path().apply {
            moveTo(-w * 0.2f, h * 0.95f)
            cubicTo(w * 0.3f, h * 0.77f, w * 0.62f, h * 1.02f, w * 1.2f, h * 0.84f)
        }
        drawPath(lowerRibbon,
            Brush.horizontalGradient(listOf(Rose, Lemon, Aqua, Violet).map {
                it.copy(alpha = if (dark) 0.24f else 0.27f)
            }), style = Stroke(width = 60.dp.toPx()))
    }
}

/** A tinted optical rim and soft inner reflection; drawn once per surface, without per-row blur. */
@Composable
fun Modifier.liquidGlass(radius: Dp = 24.dp, strong: Boolean = false): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) Color(0xFF36334F) else Color.White
    val opacity = if (strong) if (dark) 0.79f else 0.80f else if (dark) 0.58f else 0.53f
    val rim = if (dark) Color.White.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.96f)
    return this.drawWithCache {
        val r = radius.toPx()
        val corner = CornerRadius(r, r)
        val fill = Brush.linearGradient(
            listOf(base.copy(alpha = opacity),
                (if (dark) Violet else Rose).copy(alpha = if (dark) 0.14f else 0.08f),
                base.copy(alpha = opacity * 0.85f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        val edge = Brush.linearGradient(
            listOf(rim, Aqua.copy(alpha = 0.37f), Rose.copy(alpha = 0.35f),
                rim.copy(alpha = if (dark) 0.2f else 0.6f)),
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
             Color(0xFF55497D),
             Color(0xFF88607D),
             Color(0xFF5D7194),
             Color(0xFF4C858D),
             Color(0xFF9E7973),
        )
    val tint =
        palettes[
            ((track?.title?.hashCode() ?: 0).toLong().let { kotlin.math.abs(it) } % palettes.size)
                .toInt()]
    Box(
         modifier.size(size).clip(RoundedCornerShape((size * 0.23f).coerceAtMost(24.dp)))
             .background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.72f), Violet.copy(alpha = 0.7f)))),
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
