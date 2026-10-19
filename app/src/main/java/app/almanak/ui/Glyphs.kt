package app.almanak.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.almanak.weather.Sky
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Line-drawn weather glyphs on a 24-unit grid, in the same language as the brand mark: hairline strokes,
 * round caps, ember only for the sun and lightning.
 */
@Composable
fun WeatherGlyph(sky: Sky, isDay: Boolean, size: Dp, modifier: Modifier = Modifier, stroke: Dp = 1.5.dp, color: Color = Fg) {
    val accent = Accent
    Canvas(modifier.size(size)) {
        drawWeather(sky, isDay, stroke.toPx(), color, accent)
    }
}

fun DrawScope.drawWeather(sky: Sky, isDay: Boolean, strokePx: Float, ink: Color, accent: Color, box: Float = size.minDimension) {
    val u = box / 24f
    val line = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float, c: Color = ink) =
        drawLine(c, p(x1, y1), p(x2, y2), strokePx, StrokeCap.Round)

    fun sun(cx: Float, cy: Float, r: Float, rays: Boolean = true) {
        drawCircle(accent, r * u, p(cx, cy), style = line)
        if (rays) for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4
            seg(cx + cos(a) * (r + 2.2f), cy + sin(a) * (r + 2.2f), cx + cos(a) * (r + 4f), cy + sin(a) * (r + 4f), accent)
        }
    }

    fun moon(cx: Float, cy: Float, r: Float) {
        // Crescent = disc minus a disc offset up-right.
        val outer = Path().apply { addOval(Rect(p(cx - r, cy - r), p(cx + r, cy + r))) }
        val bite = Path().apply { addOval(Rect(p(cx - r * 0.45f, cy - r * 1.35f), p(cx + r * 1.55f, cy + r * 0.65f))) }
        drawPath(Path.combine(PathOperation.Difference, outer, bite), ink, style = line)
    }

    fun celestial(cx: Float, cy: Float, r: Float, rays: Boolean = true) =
        if (isDay) sun(cx, cy, r, rays) else moon(cx, cy, r + 1f)

    /** Three-lobed cloud whose flat base sits on [baseY], spanning [left]..[right]. */
    fun cloudPath(left: Float, right: Float, baseY: Float): Path {
        val w = right - left
        val rL = w * 0.2f
        val rT = w * 0.29f
        val rR = w * 0.24f
        val cL = Offset(left + rL, baseY - rL)
        val cT = Offset(left + w * 0.46f, baseY - rL - rT * 0.62f)
        val cR = Offset(right - rR, baseY - rR)
        fun rect(c: Offset, r: Float) = Rect(p(c.x - r, c.y - r), p(c.x + r, c.y + r))
        return Path().apply {
            moveTo(cL.x * u, baseY * u)
            arcTo(rect(cL, rL), 90f, 160f, false)
            arcTo(rect(cT, rT), 195f, 150f, false)
            arcTo(rect(cR, rR), 280f, 170f, false)
            close()
        }
    }

    fun cloud(left: Float, right: Float, baseY: Float) = drawPath(cloudPath(left, right, baseY), ink, style = line)

    /** Sun or moon peeking from behind a cloud: the body is clipped by the cloud outline. */
    fun behindCloud(cx: Float, cy: Float, r: Float, rays: Boolean, left: Float, right: Float, baseY: Float) {
        val c = cloudPath(left, right, baseY)
        clipPath(c, ClipOp.Difference) { celestial(cx, cy, r, rays) }
        drawPath(c, ink, style = line)
    }

    when (sky) {
        Sky.Clear -> celestial(12f, 12f, 5f)
        Sky.MostlyClear -> behindCloud(10f, 10f, 4.2f, rays = true, left = 11f, right = 22f, baseY = 20f)
        Sky.PartlyCloudy -> behindCloud(9f, 8.5f, 3.8f, rays = false, left = 4.5f, right = 21f, baseY = 19.5f)
        Sky.Overcast -> cloud(3f, 21f, 17.5f)
        Sky.Fog -> {
            cloud(4f, 20f, 13f)
            seg(4f, 16.5f, 20f, 16.5f)
            seg(6f, 19.5f, 18f, 19.5f)
        }
        Sky.Drizzle -> {
            cloud(3f, 21f, 14f)
            listOf(8f, 12f, 16f).forEach { x -> drawCircle(ink, strokePx * 0.75f, p(x, 18.5f)) }
        }
        Sky.Rain -> {
            cloud(3f, 21f, 14f)
            listOf(8f, 12f, 16f).forEach { x -> seg(x, 17f, x - 1.5f, 21f) }
        }
        Sky.FreezingRain -> {
            cloud(3f, 21f, 14f)
            seg(8f, 17f, 6.5f, 21f)
            drawCircle(ink, strokePx * 0.75f, p(12f, 19f))
            seg(16f, 17f, 14.5f, 21f)
        }
        Sky.Snow -> {
            cloud(3f, 21f, 14f)
            listOf(7.5f to 18f, 12f to 20.5f, 16.5f to 18f).forEach { (x, y) ->
                seg(x - 1.3f, y, x + 1.3f, y); seg(x, y - 1.3f, x, y + 1.3f)
            }
        }
        Sky.Thunder -> {
            cloud(3f, 21f, 14f)
            val bolt = Path().apply {
                moveTo(13f * u, 15f * u); lineTo(10.5f * u, 19f * u); lineTo(13.5f * u, 19f * u); lineTo(11f * u, 23f * u)
            }
            drawPath(bolt, accent, style = line)
        }
    }
}

/** A bus seen from the front, in the weather glyphs' line: marks the bus block so the times read as buses. */
@Composable
fun BusGlyph(size: Dp, modifier: Modifier = Modifier, stroke: Dp = 1.5.dp, color: Color = FgMuted) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val s = stroke.toPx()
        val line = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), s, StrokeCap.Round)
        // Body, destination sign, windshield, headlights, wheels.
        drawRoundRect(color, p(5f, 3f), Size(14f * u, 16f * u), CornerRadius(2.5f * u), style = line)
        seg(9f, 5.5f, 15f, 5.5f)
        drawRoundRect(color, p(7f, 8f), Size(10f * u, 5.5f * u), CornerRadius(1f * u), style = line)
        drawCircle(color, s * 0.9f, p(8.5f, 16f))
        drawCircle(color, s * 0.9f, p(15.5f, 16f))
        seg(8f, 19f, 8f, 21f)
        seg(16f, 19f, 16f, 21f)
    }
}
