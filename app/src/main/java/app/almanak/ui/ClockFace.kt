package app.almanak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.clock.ClockFormat
import app.almanak.ui.theme.Fonts
import app.almanak.ui.theme.LocalAlmanakTheme
import java.time.ZonedDateTime
import java.util.Locale

/** TV overscan safe area: ~5 % of the 960×540 dp canvas. */
val SafeArea = Modifier.padding(horizontal = 48.dp, vertical = 32.dp)

/** Theme colours, read from [LocalAlmanakTheme]; usable anywhere in composition (not inside draw lambdas). */
val Bg: Color @Composable @ReadOnlyComposable get() = LocalAlmanakTheme.current.background
val Fg: Color @Composable @ReadOnlyComposable get() = LocalAlmanakTheme.current.fg
val FgMuted: Color @Composable @ReadOnlyComposable get() = LocalAlmanakTheme.current.fgMuted
val FgFaint: Color @Composable @ReadOnlyComposable get() = LocalAlmanakTheme.current.fgFaint
val Accent: Color @Composable @ReadOnlyComposable get() = LocalAlmanakTheme.current.accent

object Type {
    /** Display numerals in the theme's hero face. Pair with [cropToBaseline] so captions can sit close. */
    @Composable @ReadOnlyComposable
    fun hero(size: TextUnit, color: Color = Fg) = LocalAlmanakTheme.current.hero(size, color)

    /** The theme's caption voice: italic serif (Nocturne, Paper) or light Inter (Minimal). */
    @Composable @ReadOnlyComposable
    fun serifItalic(size: TextUnit, color: Color = FgMuted) = LocalAlmanakTheme.current.caption(size, color)

    @Composable @ReadOnlyComposable
    fun label(size: TextUnit = 15.sp, color: Color = FgMuted) = TextStyle(
        fontFamily = Fonts.Inter, fontWeight = FontWeight(500), fontSize = size, letterSpacing = 0.12.em, color = color,
        fontFeatureSettings = "tnum",
    )

    @Composable @ReadOnlyComposable
    fun number(size: TextUnit, color: Color = Fg) = TextStyle(
        fontFamily = Fonts.Inter, fontWeight = FontWeight(300), fontSize = size, color = color, fontFeatureSettings = "tnum",
    )
}

/** Time over date, the Editorial pairing chosen in slice 1. */
@Composable
fun ClockBlock(
    now: ZonedDateTime,
    use24h: Boolean,
    locale: Locale,
    modifier: Modifier = Modifier,
    timeSize: TextUnit = 210.sp,
    dateSize: TextUnit = 44.sp,
    align: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    val clock = ClockFormat.time(now, use24h, locale)
    Column(modifier, horizontalAlignment = align) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(clock.time, Modifier.cropToBaseline(), style = Type.hero(timeSize))
            clock.meridiem?.let {
                Text(it, Modifier.padding(start = 12.dp, bottom = (timeSize.value * 0.05f).dp), style = Type.label(22.sp))
            }
        }
        Text(
            ClockFormat.longDate(now, locale),
            Modifier.paddingFromBaseline(top = (dateSize.value * 1.6f).dp),
            style = Type.serifItalic(dateSize),
        )
    }
}

/**
 * Ends the layout at the text's baseline, dropping the font's descent. Display numerals have no descenders,
 * and Fraunces' deep descent would otherwise push captions far from the digits.
 */
fun Modifier.cropToBaseline(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[LastBaseline]
    layout(placeable.width, if (baseline == AlignmentLine.Unspecified) placeable.height else baseline) {
        placeable.place(0, 0)
    }
}
