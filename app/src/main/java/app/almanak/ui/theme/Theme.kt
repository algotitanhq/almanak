package app.almanak.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

enum class ThemeId { Nocturne, Minimal, Paper }

/**
 * A visual theme: palette + the two type voices (display numerals, captions). Labels and small numbers are
 * always Inter. [scrim] is what goes between a wallpaper and the text: the theme's own ground, so contrast holds.
 */
@Immutable
data class AlmanakTheme(
    val id: ThemeId,
    val background: Color,
    val fg: Color,
    val fgMuted: Color,
    val fgFaint: Color,
    val accent: Color,
    val scrim: Color,
    val heroFamily: FontFamily,
    val heroWeight: FontWeight,
    val heroTracking: Float,
    val captionFamily: FontFamily,
    val captionStyle: FontStyle,
    val captionWeight: FontWeight,
    val captionScale: Float,
) {
    fun caption(size: TextUnit, color: Color) = TextStyle(
        fontFamily = captionFamily, fontStyle = captionStyle, fontWeight = captionWeight,
        fontSize = size * captionScale, color = color,
    )

    fun hero(size: TextUnit, color: Color = fg) = TextStyle(
        fontFamily = heroFamily, fontWeight = heroWeight, fontSize = size,
        letterSpacing = heroTracking.em, color = color, fontFeatureSettings = "tnum, lnum",
    )

    companion object {
        /** Editorial: warm black, paper ink, Fraunces + Instrument Serif italic. The slice-1 look. */
        val Nocturne = AlmanakTheme(
            ThemeId.Nocturne,
            background = Color(0xFF0E0D0B), fg = Color(0xFFF2EDE4), fgMuted = Color(0xB3F2EDE4), fgFaint = Color(0x73F2EDE4),
            accent = Color(0xFFE89E5C), scrim = Color(0xFF0E0D0B),
            heroFamily = Fonts.Fraunces, heroWeight = FontWeight(300), heroTracking = -0.02f,
            captionFamily = Fonts.InstrumentSerif, captionStyle = FontStyle.Italic, captionWeight = FontWeight.Normal, captionScale = 1f,
        )

        /** Minimal dark: true black, cool grey, Inter only, no italics. Quietest at night. */
        val Minimal = AlmanakTheme(
            ThemeId.Minimal,
            background = Color(0xFF000000), fg = Color(0xFFD9DDE3), fgMuted = Color(0x99D9DDE3), fgFaint = Color(0x59D9DDE3),
            accent = Color(0xFF7FB2D9), scrim = Color(0xFF000000),
            heroFamily = Fonts.Inter, heroWeight = FontWeight(300), heroTracking = -0.045f,
            captionFamily = Fonts.Inter, captionStyle = FontStyle.Normal, captionWeight = FontWeight(300), captionScale = 0.82f,
        )

        /** Warm paper: ink on cream, Fraunces at a heavier optical weight for light-on-dark inversion. */
        val Paper = AlmanakTheme(
            ThemeId.Paper,
            background = Color(0xFFEFE8DA), fg = Color(0xFF1F1B16), fgMuted = Color(0xB31F1B16), fgFaint = Color(0x801F1B16),
            accent = Color(0xFFB4532A), scrim = Color(0xFFEFE8DA),
            heroFamily = Fonts.Fraunces, heroWeight = FontWeight(400), heroTracking = -0.02f,
            captionFamily = Fonts.InstrumentSerif, captionStyle = FontStyle.Italic, captionWeight = FontWeight.Normal, captionScale = 1f,
        )

        fun of(id: ThemeId) = when (id) {
            ThemeId.Nocturne -> Nocturne
            ThemeId.Minimal -> Minimal
            ThemeId.Paper -> Paper
        }
    }
}

val LocalAlmanakTheme = staticCompositionLocalOf { AlmanakTheme.Nocturne }
