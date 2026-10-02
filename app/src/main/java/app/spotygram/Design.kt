package app.spotygram

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.SurfaceProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
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
    else
        listOf(
            Color(0xFF086D79),
            Color(0xFFAE4450),
            Color(0xFF785A1A),
            Color(0xFF277347),
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
        background = Color(0xFF080A0E),
        onBackground = Color(0xFFF1F6F3),
        surface = Color(0xFF171A21),
        onSurface = Color(0xFFF1F6F3),
        surfaceVariant = Color(0xFF282D36),
        onSurfaceVariant = Color(0xFFB0B8C3),
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
        background = Color(0xFFF9FAFC),
        onBackground = Color(0xFF1D3032),
        surface = Color.White,
        onSurface = Color(0xFF1D3032),
        surfaceVariant = Color(0xFFE8EDF2),
        onSurfaceVariant = Color(0xFF596575),
        secondaryContainer = Color(0xFFD7F0E9),
        onSecondaryContainer = Color(0xFF193F42),
        outline = Color(0xFF789394),
    )

/** Shared captures contain only the environment and content, never their glass consumers. */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }
val LocalDockInset = staticCompositionLocalOf { 0.dp }

@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glassContent(zIndex: Float = 1f): Modifier =
    LocalGlassState.current?.let { hazeSource(it, zIndex = zIndex) } ?: this

/** Small pools of incident light give clear glass a visible environment without a wallpaper. */
@Composable
fun PrismBackdrop(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(modifier.background(MaterialTheme.colorScheme.background)) {
        val w = size.width
        val h = size.height
        val glows =
            listOf(
                Triple(Cyan, Offset(w * 0.03f, h * 0.21f), w * 0.40f),
                Triple(Amber, Offset(w * 1.04f, h * 0.24f), w * 0.35f),
                Triple(Blue, Offset(w * 0.04f, h * 0.96f), w * 0.46f),
                Triple(Green, Offset(w * 0.48f, h * 1.02f), w * 0.32f),
                Triple(Red, Offset(w * 1.02f, h * 0.92f), w * 0.39f),
            )
        glows.forEach { (color, center, radius) ->
            val lightRadius = minOf(radius, 220.dp.toPx())
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = if (dark) 0.17f else 0.13f), Color.Transparent),
                    center = center,
                    radius = lightRadius,
                ),
                radius = lightRadius,
                center = center,
            )
        }
    }
}

/** One optical material, with diffusion appropriate to a control or a text-heavy sheet. */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.liquidGlass(
    radius: Dp = 24.dp,
    strong: Boolean = false,
    tint: Color = Color.Transparent,
    interaction: MutableInteractionSource? = null,
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val state = LocalGlassState.current
    val background = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(radius)
    val fullOptics = Build.VERSION.SDK_INT >= 33
    val reactive = interaction != null
    val style =
        remember(dark, radius, strong, tint, background, fullOptics, reactive) {
            GlassStyle.clear.then {
                backgroundColor(if (fullOptics) background else Color.Transparent)
                // Without diffusion on older Android, a denser tint protects labels over text.
                tint(
                    if (tint != Color.Transparent)
                        tint.copy(alpha = if (fullOptics) 0.14f else 0.28f)
                    else if (dark)
                        Color(0xFF18232F)
                            .copy(
                                alpha =
                                    if (!fullOptics) if (strong) 0.88f else 0.24f
                                    else if (strong) 0.38f else 0.12f
                            )
                    else
                        Color.White.copy(
                            alpha =
                                if (!fullOptics) if (strong) 0.88f else 0.24f
                                else if (strong) 0.32f else 0.10f
                        )
                )
                optics(
                    blurRadius = if (strong) 28.dp else 5.dp,
                    refractionStrength = 0.9f,
                    refractionDisplacement = 48.dp,
                    refractionHeightFraction = 0.38f,
                    depth = 1f,
                    refractionFoldStrength = 0.3f,
                    refractionDetailIntensity = 0f,
                    refractionProfile = RefractionProfile.Edge(24.dp),
                )
                shape(shape)
                specularIntensity(if (dark) 0.80f else 0.9f)
                ambientResponse(if (dark) 0.22f else 0.16f)
                surfaceProfile(SurfaceProfile.Circle)
                edgeSoftness(1.5.dp)
                chromaticAberrationStrength(0.12f)
                edgeShadow(Color.Black.copy(alpha = if (dark) 0.25f else 0.20f))
                lightPosition(Alignment.TopStart)
                whitePoint(if (dark) -0.04f else -0.08f)
                contrast(0.08f)
                chromaMultiplier(1.15f)
                if (reactive)
                    pressed {
                        lightingIntensity(0.9f)
                        refractionMultiplier(1.12f)
                        scale(0.97f)
                    }
                if (reactive) focused { lightingIntensity(0.45f) }
            }
        }
    val optical =
        if (state != null)
            Modifier.hazeGlass(HazeInput.Sources(state), style, interactionSource = interaction)
        else Modifier.background(background.copy(alpha = 0.72f), shape)
    return this.shadow(
            if (strong) 10.dp else 5.dp,
            shape,
            ambientColor = Color.Black.copy(alpha = 0.08f),
            spotColor = Color.Black.copy(alpha = 0.12f),
        )
        .then(optical)
        .drawWithCache {
            val r = radius.toPx()
            val corner = CornerRadius(r, r)
            val edge =
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.45f else 0.98f),
                        Color.White.copy(alpha = 0.08f),
                        Cyan.copy(alpha = 0.16f),
                        Color.White.copy(alpha = if (dark) 0.25f else 0.80f),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            onDrawBehind {
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.10f else 0.26f),
                            Color.Transparent,
                            Color.Black.copy(alpha = if (dark) 0.12f else 0.035f),
                        )
                    ),
                    cornerRadius = corner,
                )
                drawRoundRect(edge, cornerRadius = corner, style = Stroke(1.dp.toPx()))
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.20f else 0.95f),
                            Cyan.copy(alpha = 0.12f),
                            Color.Black.copy(alpha = if (dark) 0.08f else 0.12f),
                            Color.White.copy(alpha = if (dark) 0.20f else 0.72f),
                        ),
                        startY = 0f,
                        endY = size.height * 0.52f,
                    ),
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size =
                        androidx.compose.ui.geometry.Size(
                            size.width - 4.dp.toPx(),
                            size.height - 4.dp.toPx(),
                        ),
                    cornerRadius = CornerRadius((r - 2.dp.toPx()).coerceAtLeast(0f)),
                    style = Stroke((if (dark) 1.dp else if (strong) 1.6.dp else 2.4.dp).toPx()),
                )
            }
        }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Transparent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .liquidGlass(24.dp, tint = tint, interaction = if (enabled) interaction else null)
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) {
            content()
        }
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Color.Transparent,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .liquidGlass(size / 2, tint = tint, interaction = if (enabled) interaction else null)
            .clip(RoundedCornerShape(size / 2))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
        ) {
            content()
        }
    }
}

@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title =
            if (title != null) {
                {
                    MatchDialogSystemBars()
                    title()
                }
            } else null,
        text = text,
        containerColor = Color.Transparent,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.liquidGlass(28.dp, strong = true),
    )
}

@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val thumb by animateDpAsState(if (checked) 27.dp else 3.dp, label = "switch lens")
    Box(
        Modifier.size(width = 60.dp, height = 48.dp)
            .then(
                if (onCheckedChange != null)
                    Modifier.toggleable(
                        checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(54.dp, 30.dp)
                .liquidGlass(
                    15.dp,
                    tint = if (checked) spectrumAccent(0) else Color.Transparent,
                )
        ) {
            Box(
                Modifier.offset(x = thumb, y = 3.dp)
                    .size(24.dp)
                    .liquidGlass(12.dp, strong = true)
                    .background(
                        Color.White.copy(alpha = if (enabled) 0.55f else 0.18f),
                        RoundedCornerShape(12.dp),
                    )
            )
        }
    }
}

@Composable
fun glassFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
    )

@Composable
fun GlassSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(state) { data ->
        Row(
            Modifier.widthIn(max = 720.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .liquidGlass(20.dp, strong = true)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss {
                        data.dismiss()
                        true
                    }
                }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                data.visuals.message,
                Modifier.weight(1f).padding(vertical = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) {
                    Text(label, color = spectrumAccent(0))
                }
            }
            if (data.visuals.withDismissAction)
                IconButton(onClick = { data.dismiss() }) {
                    Icon(
                        Icons.Rounded.Close,
                        stringResource(android.R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
fun SpotygramTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes =
            Shapes(
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
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                headlineLarge =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                headlineMedium =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 24.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleLarge =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
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
        modifier
            .size(size)
            .clip(RoundedCornerShape((size * 0.23f).coerceAtMost(24.dp)))
            .background(
                Brush.linearGradient(
                    listOf(
                        tint,
                        tint.copy(alpha = 0.84f),
                        Spectrum[(palettes.indexOf(tint) + 3) % Spectrum.size].copy(alpha = 0.72f),
                    )
                )
            ),
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
