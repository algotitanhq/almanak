package app.almanak.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.R
import app.almanak.WeatherState
import app.almanak.weather.Sky
import app.almanak.weather.TempUnit
import app.almanak.weather.Weather
import app.almanak.weather.degrees
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Beyond this age, show the discreet "as of" line even if the last refresh technically succeeded. */
private const val STALE_AFTER_MS = 40 * 60_000L

@Composable
fun skyLabel(sky: Sky): String = stringResource(
    when (sky) {
        Sky.Clear -> R.string.sky_clear
        Sky.MostlyClear -> R.string.sky_mostly_clear
        Sky.PartlyCloudy -> R.string.sky_partly_cloudy
        Sky.Overcast -> R.string.sky_overcast
        Sky.Fog -> R.string.sky_fog
        Sky.Drizzle -> R.string.sky_drizzle
        Sky.Rain -> R.string.sky_rain
        Sky.FreezingRain -> R.string.sky_freezing_rain
        Sky.Snow -> R.string.sky_snow
        Sky.Thunder -> R.string.sky_thunder
    },
)

fun hourLabel(t: LocalDateTime, use24h: Boolean, locale: Locale): String =
    t.format(DateTimeFormatter.ofPattern(if (use24h) "H:00" else "h a", locale))

fun dayLabel(w: Weather.Day, locale: Locale): String =
    w.localDate.format(DateTimeFormatter.ofPattern("EEE", locale)).trimEnd('.').replaceFirstChar { it.titlecase(locale) }

/** "as of 20:15" when the data isn't live or is getting old; null when everything is fresh. */
@Composable
fun staleness(state: WeatherState, nowMillis: Long, use24h: Boolean, locale: Locale): String? {
    val data = state.data ?: return null
    if (state.live && nowMillis - data.fetchedAtMillis < STALE_AFTER_MS) return null
    val at = Instant.ofEpochMilli(data.fetchedAtMillis).atZone(ZoneId.systemDefault())
    return stringResource(R.string.as_of, at.format(DateTimeFormatter.ofPattern(if (use24h) "H:mm" else "h:mm a", locale)))
}

@Composable
fun CurrentConditions(now: Weather.Now, unit: TempUnit, glyph: Dp, tempSize: Int, modifier: Modifier = Modifier) {
    val sky = Sky.of(now.code)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(sky, now.isDay, glyph, stroke = 2.dp)
            Spacer(Modifier.width(16.dp))
            Text(now.tempC.degrees(unit), style = Type.hero(tempSize.sp))
        }
        Text(
            "${skyLabel(sky)}, ${stringResource(R.string.feels_like, now.feelsC.degrees(unit))}",
            style = Type.serifItalic(26.sp),
        )
    }
}

/** Variant B: a horizontal band — now on the left, a temperature line for the next 12 hours on the right. */
@Composable
fun WeatherBand(state: WeatherState, now: LocalDateTime, nowMillis: Long, unit: TempUnit, use24h: Boolean, locale: Locale, modifier: Modifier = Modifier) {
    val data = state.data
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        if (data == null) {
            Text(stringResource(R.string.weather_unavailable), style = Type.serifItalic(26.sp, FgFaint))
            return@Row
        }
        Column {
            CurrentConditions(data.now, unit, glyph = 56.dp, tempSize = 72)
            staleness(state, nowMillis, use24h, locale)?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.label(13.sp, FgFaint)) }
        }
        Spacer(Modifier.width(56.dp))
        // Hours and days alternate with the minute: one slow crossfade per minute, nothing else moves.
        Crossfade(targetState = now.minute % 2 == 1, animationSpec = tween(1_500), label = "forecast", modifier = Modifier.weight(1f)) { days ->
            if (days) {
                DailyStrip(data.days.filter { it.localDate.isAfter(now.toLocalDate()) }.take(5), unit, locale, Modifier.fillMaxWidth().height(120.dp))
            } else {
                TemperatureLine(data.upcomingHours(now, 13), unit, use24h, locale, Modifier.fillMaxWidth().height(120.dp))
            }
        }
    }
}

@Composable
private fun TemperatureLine(hours: List<Weather.Hour>, unit: TempUnit, use24h: Boolean, locale: Locale, modifier: Modifier) {
    if (hours.size < 2) return
    val measurer = rememberTextMeasurer()
    val tempStyle = Type.number(18.sp)
    val hourStyle = Type.label(13.sp)
    val fg = Fg
    val fgMuted = FgMuted
    val accent = Accent
    Canvas(modifier) {
        val top = 40.dp.toPx()        // room for glyph + temp label above the line
        val bottom = size.height - 24.dp.toPx() // room for hour labels
        val temps = hours.map { it.tempC }
        val lo = temps.min()
        val span = (temps.max() - lo).coerceAtLeast(4.0)
        val stepX = size.width / (hours.size - 1)
        val pts = hours.mapIndexed { i, h ->
            Offset(i * stepX, (bottom - (h.tempC - lo) / span * (bottom - top - 16.dp.toPx())).toFloat() - 8.dp.toPx())
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val mx = (a.x + b.x) / 2
                cubicTo(mx, a.y, mx, b.y, b.x, b.y)
            }
        }
        drawPath(path, fgMuted, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        hours.forEachIndexed { i, h ->
            if (i % 3 != 0) return@forEachIndexed
            val p = pts[i]
            drawCircle(fg, 3.dp.toPx(), p)
            val t = measurer.measure(h.tempC.degrees(unit), tempStyle)
            val x = (p.x - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, p.y - t.size.height - 6.dp.toPx()))
            val g = 22.dp.toPx()
            translate(left = (p.x - g / 2).coerceIn(0f, size.width - g), top = p.y - t.size.height - 6.dp.toPx() - g - 2.dp.toPx()) {
                drawWeather(Sky.of(h.code), h.isDay, 1.3.dp.toPx(), fg, accent, g)
            }
            val l = measurer.measure(hourLabel(h.localTime, use24h, locale), hourStyle)
            drawText(l, topLeft = Offset((p.x - l.size.width / 2).coerceIn(0f, size.width - l.size.width), size.height - l.size.height))
        }
    }
}

@Composable
private fun DailyStrip(days: List<Weather.Day>, unit: TempUnit, locale: Locale, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        days.forEach { d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WeatherGlyph(Sky.of(d.code), true, 30.dp, stroke = 1.5.dp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(d.maxC.degrees(unit), style = Type.number(22.sp))
                    Spacer(Modifier.width(8.dp))
                    Text(d.minC.degrees(unit), style = Type.number(22.sp, FgFaint))
                }
                Spacer(Modifier.height(8.dp))
                Text(dayLabel(d, locale), style = Type.label(13.sp))
            }
        }
    }
}

/** Right-zone variant: now, then the next hours as a quiet line, right-aligned. */
@Composable
fun WeatherAside(state: WeatherState, now: LocalDateTime, nowMillis: Long, unit: TempUnit, use24h: Boolean, locale: Locale, modifier: Modifier = Modifier) {
    val data = state.data
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (data == null) {
            Text(stringResource(R.string.weather_unavailable), style = Type.serifItalic(22.sp, FgFaint))
            return@Column
        }
        val sky = Sky.of(data.now.code)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(sky, data.now.isDay, 44.dp, stroke = 1.8.dp)
            Spacer(Modifier.width(14.dp))
            Text(data.now.tempC.degrees(unit), Modifier.cropToBaseline(), style = Type.hero(56.sp))
        }
        Spacer(Modifier.height(6.dp))
        Text(skyLabel(sky), style = Type.serifItalic(22.sp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
            data.upcomingHours(now, 12).filterIndexed { i, _ -> i % 3 == 2 }.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WeatherGlyph(Sky.of(h.code), h.isDay, 20.dp, stroke = 1.2.dp)
                    Text(h.tempC.degrees(unit), style = Type.number(16.sp))
                    Text(hourLabel(h.localTime, use24h, locale), style = Type.label(11.sp, FgFaint))
                }
            }
        }
        staleness(state, nowMillis, use24h, locale)?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.label(11.sp, FgFaint)) }
    }
}
