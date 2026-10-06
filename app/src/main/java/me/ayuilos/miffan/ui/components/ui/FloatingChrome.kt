package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import me.ayuilos.miffan.ui.theme.LocalDarkMode
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3

/** Frosted glass for chrome floating over a scrolling `hazeSource`, shared by both chat modes. */
@Composable
fun Modifier.glass(hazeState: HazeState, shape: Shape): Modifier {
    // Keep the theme's hue, with a tonal step from the page (including AMOLED black).
    val glassColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val style = HazeBlurStyle.Material3 {
        blurRadius(20.dp)
        noiseFactor(0.04f)
        colorEffects(listOf(HazeColorEffect.tint(glassColor.copy(alpha = 0.8f))))
        fallbackColorEffect(HazeColorEffect.tint(glassColor.copy(alpha = 0.9f)))
    }
    return clip(shape).hazeBlur(input = HazeInput.Sources(hazeState), style = style)
}

/**
 * Soft shadow drawn only outside [shape], so floating glass stands apart from content of the same
 * tone without the shadow muddying the translucent glass. Place it under the glass at the same size.
 */
@Composable
fun GlassShadow(shape: Shape, modifier: Modifier = Modifier) {
    // A dark page leaves little room to darken, so the dark-mode shadow is wider and much denser.
    val shadow = if (LocalDarkMode.current) {
        Shadow(radius = 36.dp, spread = 4.dp, color = Color.Black.copy(alpha = 0.95f), offset = DpOffset(0.dp, 8.dp))
    } else {
        Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f), offset = DpOffset(0.dp, 6.dp))
    }
    Spacer(
        modifier
            .drawWithContent {
                val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent)) }
                clipPath(outline, ClipOp.Difference) { this@drawWithContent.drawContent() }
            }
            .dropShadow(shape, shadow)
    )
}

@Composable
fun GlassSurface(
    hazeState: HazeState,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val glassModifier = modifier.glass(hazeState, shape)
    val contentColor = MaterialTheme.colorScheme.onSurface
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, modifier = glassModifier, shape = shape, color = Color.Transparent,
            contentColor = contentColor, content = content)
    } else {
        Surface(modifier = glassModifier, shape = shape, color = Color.Transparent, contentColor = contentColor, content = content)
    }
}

@Composable
fun GlassIconButton(
    hazeState: HazeState,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    GlassSurface(hazeState, CircleShape, modifier.size(48.dp), onClick = onClick, enabled = enabled) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription) }
    }
}

enum class EdgeScrimPosition { Top, Bottom }

/**
 * Blurs content progressively as it nears a screen edge and fades it into the page color, so floating
 * chrome sits on a soft backdrop instead of cutting the list off. It never takes touches.
 */
@Composable
fun EdgeBlurScrim(hazeState: HazeState, position: EdgeScrimPosition, height: Dp, modifier: Modifier = Modifier) {
    val top = position == EdgeScrimPosition.Top
    val page = MaterialTheme.colorScheme.background
    val style = HazeBlurStyle {
        blurRadius(16.dp)
        colorEffects(emptyList())
        progressive(HazeProgressive.verticalGradient(startIntensity = if (top) 1f else 0f, endIntensity = if (top) 0f else 1f))
    }
    val fade = listOf(page.copy(alpha = 0.8f), page.copy(alpha = 0f))
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = style)
            .background(Brush.verticalGradient(if (top) fade else fade.reversed()))
    )
}
