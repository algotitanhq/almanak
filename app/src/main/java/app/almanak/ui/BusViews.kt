package app.almanak.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.BusLine
import app.almanak.BusState
import app.almanak.R
import app.almanak.RtStatus
import app.almanak.transit.Arrival
import app.almanak.transit.StopSchedule
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Minutes until [a], or the clock time when it is more than an hour away. */
sealed interface Eta {
    data object Now : Eta
    data class Minutes(val n: Long) : Eta
    data class At(val text: String) : Eta
}

fun eta(a: Arrival, now: ZonedDateTime, use24h: Boolean, locale: Locale): Eta {
    val min = Duration.between(now.toInstant(), a.at).toMinutes()
    return when {
        min < 1 -> Eta.Now
        min < 60 -> Eta.Minutes(min)
        else -> Eta.At(a.at.atZone(now.zone).format(DateTimeFormatter.ofPattern(if (use24h) "H:mm" else "h:mm", locale)))
    }
}

/** A departure beyond the 2 h horizon: "next at 6:47", "tomorrow 6:47", or "Mon 6:47" further out. */
@Composable
fun laterLabel(at: Instant, now: ZonedDateTime, use24h: Boolean, locale: Locale): String {
    val t = at.atZone(now.zone)
    val time = t.format(DateTimeFormatter.ofPattern(if (use24h) "H:mm" else "h:mm a", locale))
    return when (t.toLocalDate()) {
        now.toLocalDate() -> stringResource(R.string.bus_next_today, time)
        now.toLocalDate().plusDays(1) -> stringResource(R.string.bus_next_tomorrow, time)
        else -> t.format(DateTimeFormatter.ofPattern("EEE", locale)).trimEnd('.').replaceFirstChar { it.titlecase(locale) } + " " + time
    }
}

@Composable
fun headsignLabel(h: String): String = when (h.lowercase()) {
    "est", "east" -> stringResource(R.string.dir_east)
    "ouest", "west" -> stringResource(R.string.dir_west)
    "nord", "north" -> stringResource(R.string.dir_north)
    "sud", "south" -> stringResource(R.string.dir_south)
    else -> h
}

/** The broadcast mark that distinguishes a realtime prediction from a timetable time. */
@Composable
fun LiveMark(size: Dp, modifier: Modifier = Modifier, color: Color = Accent) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.11f
        drawCircle(color, w * 0.9f, Offset(w, s - w))
        for (r in listOf(0.45f, 0.82f)) {
            drawArc(
                color, startAngle = 270f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(w - r * s, s - w - r * s), size = Size(2 * r * s, 2 * r * s),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun RouteBadge(route: StopSchedule.Route?, size: Int = 15) {
    // The agency colour is used as a small dot only, so a purple line never fights the palette.
    val c = route?.color?.let { runCatching { Color(0xFF000000 or it.toLong(16)) }.getOrNull() } ?: FgMuted
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(c) }
        Spacer(Modifier.width(8.dp))
        Text(route?.shortName ?: "", style = Type.label(size.sp, Fg))
    }
}

@Composable
private fun EtaText(e: Eta, live: Boolean, big: Boolean, modifier: Modifier = Modifier, size: TextUnit = if (big) 56.sp else 18.sp) {
    val color = if (live) Fg else FgMuted
    val numberStyle = if (big) Type.hero(size).copy(color = color) else Type.number(size, color)
    Row(modifier) {
        when (e) {
            Eta.Now -> Text(stringResource(R.string.bus_now), Modifier.alignByBaseline(), style = Type.serifItalic(size * (if (big) 40f / 56 else 20f / 18), color))
            is Eta.Minutes -> Text(e.n.toString(), Modifier.alignByBaseline().cropToBaseline(), style = numberStyle)
            is Eta.At -> Text(e.text, Modifier.alignByBaseline().cropToBaseline(), style = numberStyle)
        }
        if (live) LiveMark(if (big) (size.value / 4).dp else 9.dp, Modifier.padding(start = 4.dp, top = if (big) (size.value / 4).dp else 4.dp))
    }
}

/** Variant A: top-right stack, one block per stop; the next bus large, the following ones small. */
@Composable
fun BusStack(state: BusState, now: ZonedDateTime, use24h: Boolean, locale: Locale, modifier: Modifier = Modifier) {
    if (state.noStops) return // nothing to show until a stop is set on the phone
    val compact = state.hasSchedule && state.lines.size > FULL_BLOCKS_MAX
    Column(modifier, horizontalAlignment = Alignment.End) {
        // Over compact lines, the glyph sits above their route dots; over full blocks, at the column's edge.
        Box(if (compact) Modifier.width(COMPACT_WIDTH) else Modifier) {
            BusGlyph(30.dp, Modifier.padding(bottom = 10.dp))
        }
        if (!state.hasSchedule) {
            Text(stringResource(R.string.bus_loading), style = Type.serifItalic(22.sp, FgFaint))
            return@Column
        }
        val scheduledOnly = state.status != RtStatus.Live && state.status != RtStatus.Starting
        // More stops (or a larger size) than the column can hold: show only the blocks that fit entirely.
        FitColumn(spacing = if (compact) 12.dp else 24.dp, align = Alignment.End, footer = if (scheduledOnly) {
            { Text(stringResource(R.string.bus_scheduled), Modifier.padding(top = 10.dp), style = Type.label(13.sp, FgFaint)) }
        } else null) {
            state.lines.forEach { if (compact) CompactLine(it, now, use24h, locale) else StackLine(it, now, use24h, locale) }
        }
    }
}

/** Bottom-zone variant: the same per-direction blocks, side by side and left-aligned. */
@Composable
fun BusRow(state: BusState, now: ZonedDateTime, use24h: Boolean, locale: Locale, modifier: Modifier = Modifier) {
    if (state.noStops) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        BusGlyph(30.dp, Modifier.padding(top = 4.dp))
        BusRowLines(state, now, use24h, locale)
    }
}

@Composable
private fun BusRowLines(state: BusState, now: ZonedDateTime, use24h: Boolean, locale: Locale, modifier: Modifier = Modifier) {
    if (!state.hasSchedule) {
        Text(stringResource(R.string.bus_loading), modifier, style = Type.serifItalic(22.sp, FgFaint))
        return
    }
    val scheduledOnly = state.status != RtStatus.Live && state.status != RtStatus.Starting
    if (state.lines.size > FULL_BLOCKS_MAX) {
        // Too many for one row of full blocks: compact lines, wrapping as the band's width allows.
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.lines.forEach { CompactLine(it, now, use24h, locale) }
            if (scheduledOnly) Text(stringResource(R.string.bus_scheduled), Modifier.align(Alignment.Bottom), style = Type.label(13.sp, FgFaint))
        }
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Bottom) {
        state.lines.forEach { StackLine(it, now, use24h, locale, Alignment.Start) }
        if (scheduledOnly) {
            Text(stringResource(R.string.bus_scheduled), style = Type.label(13.sp, FgFaint))
        }
    }
}

/** Up to this many blocks get the full two-line layout; beyond, every block becomes a [CompactLine]. */
private const val FULL_BLOCKS_MAX = 3

// CompactLine's columns: route and direction | next bus | the two after (room for two clock times, live marks).
private val COMPACT_LABEL = 128.dp
private val COMPACT_NEXT = 96.dp
private val COMPACT_REST = 150.dp
private val COMPACT_WIDTH = COMPACT_LABEL + COMPACT_NEXT + COMPACT_REST

/**
 * One block on a single line, for many stops: route and direction, the next bus, then the two after it. Fixed
 * column widths so stacked lines read as a table.
 */
@Composable
private fun CompactLine(line: BusLine, now: ZonedDateTime, use24h: Boolean, locale: Locale) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(COMPACT_LABEL), verticalAlignment = Alignment.CenterVertically) {
            RouteBadge(line.route)
            Spacer(Modifier.width(10.dp))
            Text(headsignLabel(line.headsign), style = Type.serifItalic(20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val first = line.arrivals.firstOrNull()
        if (first == null) {
            // Nothing within 2 h: the next timetable departure, faint, across the two time columns.
            Text(
                line.later?.let { laterLabel(it, now, use24h, locale) } ?: "—",
                Modifier.width(COMPACT_NEXT + COMPACT_REST).padding(start = 24.dp),
                style = Type.serifItalic(20.sp, FgFaint),
                maxLines = 1,
            )
            return@Row
        }
        Row(Modifier.width(COMPACT_NEXT), horizontalArrangement = Arrangement.End) {
            val e = eta(first, now, use24h, locale)
            EtaText(e, first.realtime, big = true, Modifier.alignByBaseline(), size = 36.sp)
            if (e is Eta.Minutes) Text(stringResource(R.string.bus_min), Modifier.alignByBaseline().padding(start = 4.dp), style = Type.label(13.sp))
        }
        Row(Modifier.width(COMPACT_REST).padding(start = 14.dp)) {
            line.arrivals.drop(1).forEachIndexed { i, a ->
                if (i > 0) Text(" · ", Modifier.alignByBaseline(), style = Type.number(16.sp, FgFaint))
                EtaText(eta(a, now, use24h, locale), a.realtime, big = false, Modifier.alignByBaseline(), size = 16.sp)
            }
        }
    }
}

@Composable
private fun StackLine(line: BusLine, now: ZonedDateTime, use24h: Boolean, locale: Locale, align: Alignment.Horizontal = Alignment.End) {
    Column(horizontalAlignment = align) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteBadge(line.route)
            Spacer(Modifier.width(10.dp))
            Text(headsignLabel(line.headsign), style = Type.serifItalic(22.sp))
        }
        val first = line.arrivals.firstOrNull()
        if (first == null) {
            Spacer(Modifier.height(8.dp))
            Text(line.later?.let { laterLabel(it, now, use24h, locale) } ?: stringResource(R.string.bus_none), style = Type.serifItalic(22.sp, FgFaint))
            return@Column
        }
        Spacer(Modifier.height(4.dp))
        // Next bus: the one number that matters, read in natural order ("8 min").
        Row {
            val e = eta(first, now, use24h, locale)
            EtaText(e, first.realtime, big = true, Modifier.alignByBaseline())
            if (e is Eta.Minutes) Text(stringResource(R.string.bus_min), Modifier.alignByBaseline().padding(start = 6.dp), style = Type.label(15.sp))
        }
        val rest = line.arrivals.drop(1)
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                Text(stringResource(R.string.bus_then) + "  ", Modifier.alignByBaseline(), style = Type.serifItalic(20.sp, FgFaint))
                rest.forEachIndexed { i, a ->
                    if (i > 0) Text("  ·  ", Modifier.alignByBaseline(), style = Type.number(18.sp, FgFaint))
                    val e = eta(a, now, use24h, locale)
                    EtaText(e, a.realtime, big = false, Modifier.alignByBaseline())
                    if (e is Eta.Minutes) Text(" " + stringResource(R.string.bus_min), Modifier.alignByBaseline(), style = Type.label(12.sp, FgFaint))
                }
            }
        }
    }
}

/**
 * A column that places only the children that fit completely in the available height (never a half-cut block),
 * followed by an optional [footer] that is always kept.
 */
@Composable
fun FitColumn(
    spacing: androidx.compose.ui.unit.Dp,
    align: Alignment.Horizontal,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(content, footer ?: {}),
        modifier = modifier,
    ) { (items, foot), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val footPlaceables = foot.map { it.measure(loose) }
        val footHeight = footPlaceables.sumOf { it.height }
        val gap = spacing.roundToPx()
        val shown = ArrayList<androidx.compose.ui.layout.Placeable>()
        var used = 0
        for (m in items) {
            val p = m.measure(loose)
            val need = (if (shown.isEmpty()) 0 else gap) + p.height
            if (constraints.hasBoundedHeight && used + need + footHeight > constraints.maxHeight) break
            shown += p
            used += need
        }
        val all = shown + footPlaceables
        val width = (all.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, used + footHeight) {
            var y = 0
            shown.forEachIndexed { i, p ->
                if (i > 0) y += gap
                p.place(align.align(p.width, width, layoutDirection), y)
                y += p.height
            }
            footPlaceables.forEach { p -> p.place(align.align(p.width, width, layoutDirection), y); y += p.height }
        }
    }
}
