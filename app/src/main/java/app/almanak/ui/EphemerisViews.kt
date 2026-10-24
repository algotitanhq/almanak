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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.R
import app.almanak.clock.ClockFormat
import app.almanak.ephemeris.EphemerisState
import app.almanak.ephemeris.MoonPhase
import app.almanak.ephemeris.MoonPhaseName
import app.almanak.weather.Sky
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** The Moon as it looks tonight: outline plus the lit part, terminator drawn as a half-ellipse. */
@Composable
fun MoonGlyph(moon: MoonPhase, size: Dp, modifier: Modifier = Modifier, color: Color = Fg, stroke: Dp = 1.5.dp) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2 - stroke.toPx()
        val c = Offset(this.size.width / 2, this.size.height / 2)
        // Earthshine: the dark side stays faintly visible so a thin crescent still reads as a moon.
        drawCircle(color.copy(alpha = 0.12f), r, c)
        drawCircle(color.copy(alpha = 0.45f), r, c, style = Stroke(stroke.toPx()))
        // Lit limb on the right while waxing (northern hemisphere); mirrored while waning.
        // Crescents keep a minimum width so they stay visible at 24 dp.
        val k = (r * cos(2 * PI * moon.phase)).toFloat().coerceIn(-0.78f * r, 0.78f * r)
        val lit = Path().apply {
            moveTo(c.x, c.y - r)
            arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 180f, false)
            arcTo(Rect(c.x - abs(k), c.y - r, c.x + abs(k), c.y + r), 90f, if (k > 0) -180f else 180f, false)
            close()
        }
        scale(if (moon.waxing) 1f else -1f, 1f, pivot = c) { drawPath(lit, color.copy(alpha = 0.9f)) }
    }
}

@Composable
fun moonLabel(n: MoonPhaseName): String = stringResource(
    when (n) {
        MoonPhaseName.New -> R.string.moon_new
        MoonPhaseName.WaxingCrescent -> R.string.moon_waxing_crescent
        MoonPhaseName.FirstQuarter -> R.string.moon_first_quarter
        MoonPhaseName.WaxingGibbous -> R.string.moon_waxing_gibbous
        MoonPhaseName.Full -> R.string.moon_full
        MoonPhaseName.WaningGibbous -> R.string.moon_waning_gibbous
        MoonPhaseName.LastQuarter -> R.string.moon_last_quarter
        MoonPhaseName.WaningCrescent -> R.string.moon_waning_crescent
    },
)

private fun hm(t: ZonedDateTime?, use24h: Boolean, locale: Locale) =
    t?.format(DateTimeFormatter.ofPattern(if (use24h) "H:mm" else "h:mm a", locale)) ?: "—"

private fun shortDate(d: java.time.LocalDate, locale: Locale) =
    d.format(DateTimeFormatter.ofPattern(ClockFormat.monthDayPattern(locale, "MMM"), locale))

/** One fact about today. Each kind is a separate entry so the rotation can step through them. */
enum class Fact { Sun, Moon, Calendar, Saint, History }

fun EphemerisState.facts(): List<Fact> = buildList {
    add(Fact.Sun); add(Fact.Moon); add(Fact.Calendar)
    if (saints.isNotEmpty()) add(Fact.Saint)
    if (history.isNotEmpty()) add(Fact.History)
}

@Composable
fun FactLine(e: EphemerisState, fact: Fact, minuteOfDay: Int, use24h: Boolean, locale: Locale, modifier: Modifier = Modifier) {
    val text = Type.serifItalic(26.sp)
    Row(modifier, verticalAlignment = if (fact == Fact.History) Alignment.Top else Alignment.CenterVertically) {
        when (fact) {
            Fact.Sun -> {
                WeatherGlyph(Sky.Clear, isDay = true, size = 26.dp, stroke = 1.5.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.sunrise_sunset, hm(e.sun.sunrise, use24h, locale), hm(e.sun.sunset, use24h, locale)), style = text)
                e.sun.daylight?.let {
                    Text("   " + stringResource(R.string.daylight, it.toHours().toInt(), (it.toMinutes() % 60).toInt()), style = Type.label(14.sp, FgFaint))
                }
            }
            Fact.Moon -> {
                MoonGlyph(e.moon, 24.dp)
                Spacer(Modifier.width(12.dp))
                Text(moonLabel(e.moon.name), style = text)
                val next = if (e.moon.waxing) stringResource(R.string.moon_next_full, shortDate(e.moon.nextFull, locale))
                else stringResource(R.string.moon_next_new, shortDate(e.moon.nextNew, locale))
                Text("   $next", style = Type.label(14.sp, FgFaint))
            }
            Fact.Calendar -> Text(stringResource(R.string.day_week, e.dayOfYear, e.daysInYear, e.isoWeek), style = text)
            // Never cut (ALG-221): FitText shrinks the text to two lines, then pages it.
            Fact.Saint -> FitText(e.saints.take(2).joinToString(" · "), text, verticalAlignment = Alignment.CenterVertically) { page, pages ->
                LeadLabel(stringResource(R.string.name_day), Type.label(14.sp, FgFaint), page, pages)
            }
            Fact.History -> {
                // Walk through the day's events: a different one each time History comes around.
                val item = e.history[(minuteOfDay / e.facts().size) % e.history.size]
                FitText(item.text, text) { page, pages -> LeadLabel("${item.year}", Type.label(15.sp, Accent), page, pages) }
            }
        }
    }
}

/** The label before a fitted text (year, "name day"), with "1/2" under it while the text is paged. */
@Composable
internal fun LeadLabel(label: String, style: TextStyle, page: Int, pages: Int) {
    Column(Modifier.padding(end = 18.dp)) {
        Text(label, style = style)
        if (pages > 1) Text("${page + 1}/$pages", style = Type.label(12.sp, FgFaint))
    }
}

/** A single caption under the date, stepping through the facts once a minute. */
@Composable
fun EphemerisCaption(e: EphemerisState, now: ZonedDateTime, use24h: Boolean, locale: Locale, modifier: Modifier = Modifier, pinned: Fact? = null) {
    val facts = e.facts()
    val minute = now.hour * 60 + now.minute
    Crossfade(targetState = pinned?.takeIf { it in facts } ?: facts[minute % facts.size], animationSpec = tween(1_500), label = "fact", modifier = modifier) { fact ->
        FactLine(e, fact, minute, use24h, locale)
    }
}
