package app.almanak.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.almanak.ui.theme.LocalAlmanakTheme
import app.almanak.ui.theme.ThemeId
import app.almanak.wallpaper.Backdrop
import app.almanak.wallpaper.Luma
import app.almanak.wallpaper.SkyPhase
import kotlin.math.cos
import kotlin.math.sin

enum class ScrimStyle { Veil, Local }

/** Background layer: plain theme ground, a generative sky, or a photo under an adaptive scrim. Slow crossfades. */
@Composable
fun BackdropLayer(backdrop: Backdrop, scrim: ScrimStyle, modifier: Modifier = Modifier) {
    val theme = LocalAlmanakTheme.current
    Crossfade(targetState = backdrop, animationSpec = tween(4_000), label = "backdrop", modifier = modifier.fillMaxSize()) { b ->
        when (b) {
            Backdrop.Plain -> Box(Modifier.fillMaxSize().background(theme.background))
            is Backdrop.Sky -> SkyCanvas(b.phase, Modifier.fillMaxSize())
            is Backdrop.Photo -> Box(Modifier.fillMaxSize().background(theme.background)) {
                Image(b.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Scrim(b.luma, scrim, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Scrim strength from the image's brightness behind the text. Dark themes darken the photo; Paper lightens it
 * (cream scrim, dark ink), so the mapping is mirrored.
 */
private fun scrimAlpha(l: Float, light: Boolean, busy: Float = 0f): Float =
    if (light) (0.50f + (0.85f - l) * 0.9f + busy * 0.8f).coerceIn(0.50f, 0.90f)
    else (0.30f + (l - 0.15f) * 1.1f + busy * 0.8f).coerceIn(0.30f, 0.88f)

@Composable
private fun Scrim(luma: Luma, style: ScrimStyle, modifier: Modifier) {
    val theme = LocalAlmanakTheme.current
    val light = theme.id == ThemeId.Paper
    val c = theme.scrim
    Canvas(modifier) {
        when (style) {
            ScrimStyle.Veil -> {
                // One uniform veil sized for the brightest text region, plus a soft vignette.
                val a = scrimAlpha(maxOf(luma.clock, luma.bus, luma.band), light, luma.busy)
                drawRect(c.copy(alpha = a))
                drawRect(Brush.radialGradient(listOf(Color.Transparent, c.copy(alpha = 0.35f)), center = center, radius = size.maxDimension * 0.7f))
            }
            ScrimStyle.Local -> {
                // Gradients where text sits; the middle of the picture stays clearer. The base veil scales with the
                // brightest region so a snowfield can't wash out the muted text (shots/s5-bright-local.png).
                val brightest = maxOf(luma.clock, luma.bus, luma.band)
                val busy = luma.busy
                drawRect(c.copy(alpha = scrimAlpha(brightest, light, busy) * 0.55f))
                drawRect(Brush.horizontalGradient(0f to c.copy(alpha = scrimAlpha(luma.clock, light, busy)), 0.55f to Color.Transparent))
                drawRect(Brush.horizontalGradient(0.45f to Color.Transparent, 0.75f to c.copy(alpha = scrimAlpha(luma.bus, light, busy))))
                drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.copy(alpha = scrimAlpha(luma.band, light, busy))))
            }
        }
    }
}

/** Generative sky, tinted toward the theme ground so it never fights the text. */
@Composable
fun SkyCanvas(phase: SkyPhase, modifier: Modifier = Modifier) {
    val theme = LocalAlmanakTheme.current
    val ground = theme.background
    // Light theme: pastel versions of the same skies.
    val mix = if (theme.id == ThemeId.Paper) 0.88f else 0.0f
    fun t(c: Color) = lerp(c, ground, mix)
    val (top, mid, glow, glowAt) = when (phase) {
        SkyPhase.Night -> Quad(Color(0xFF080A12), Color(0xFF0D0E14), Color(0x332A3A66), Offset(0.82f, 0.12f))
        SkyPhase.Dawn -> Quad(Color(0xFF15162A), Color(0xFF3A2434), Color(0x40E89E5C), Offset(0.18f, 0.95f))
        SkyPhase.Day -> Quad(Color(0xFF1D2933), Color(0xFF141A20), Color(0x268FA9BF), Offset(0.7f, 0.0f))
        SkyPhase.Dusk -> Quad(Color(0xFF1A1428), Color(0xFF3B1F2B), Color(0x40E8865C), Offset(0.85f, 0.95f))
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(0f to t(top), 0.65f to t(mid), 1f to t(ground)))
        drawRect(Brush.radialGradient(listOf(t(glow), Color.Transparent), center = Offset(size.width * glowAt.x, size.height * glowAt.y), radius = size.width * 0.6f))
    }
}

private data class Quad(val a: Color, val b: Color, val c: Color, val d: Offset)

/**
 * Burn-in protection: the whole foreground drifts around a small circle, one step every 3 minutes,
 * moving over 4 s so it never reads as a jump.
 */
@Composable
fun burnInDrift(minuteOfDay: Int, radius: Dp = 7.dp): DpOffset {
    val step = minuteOfDay / 3
    val angle = step * 2.39996 // golden angle: positions never repeat in a visible pattern
    val r = radius * (0.4f + 0.6f * ((step * 7) % 10) / 9f)
    val x by animateDpAsState(r * cos(angle).toFloat(), tween(4_000), label = "driftX")
    val y by animateDpAsState(r * 0.6f * sin(angle).toFloat(), tween(4_000), label = "driftY")
    return DpOffset(x, y)
}
