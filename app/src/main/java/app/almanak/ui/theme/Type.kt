package app.almanak.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.almanak.R

/** Bundled OFL fonts (see third_party/fonts). Variable fonts are pinned to named instances per weight. */
object Fonts {
    private fun fraunces(weight: Int) = Font(
        R.font.fraunces,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.Setting("opsz", 144f),
            FontVariation.Setting("SOFT", 50f),
        ),
    )

    private fun inter(weight: Int) = Font(
        R.font.inter,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.Setting("opsz", 32f),
        ),
    )

    val Fraunces = FontFamily(fraunces(200), fraunces(300), fraunces(400), fraunces(600))
    val Inter = FontFamily(inter(200), inter(300), inter(400), inter(500), inter(600))
    val InstrumentSerif = FontFamily(
        Font(R.font.instrument_serif, FontWeight.Normal),
        Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
    )
}
