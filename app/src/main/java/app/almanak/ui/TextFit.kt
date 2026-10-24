package app.almanak.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * How a line of text is shown in a fixed width without ever being cut: the largest size from [TextFit.fit]'s range
 * at which it fits in its lines, else the text split into pages that each fit at the floor size (shown in turn).
 */
data class Fit(val scale: Float, val pages: List<String>)

object TextFit {
    /** Size steps, as fractions of the base size: 26 sp down to 20 sp for the caption, by 1 sp. */
    val SCALES = listOf(1f, 25f / 26, 24f / 26, 23f / 26, 22f / 26, 21f / 26, 20f / 26)
    val FLOOR: Float get() = SCALES.last()

    /**
     * [fits] says whether a text fits in the available lines at a scale. Pages split on spaces only, so they join back
     * to the original text with single spaces; a single word too long for a page still gets a page of its own.
     */
    fun fit(text: String, fits: (String, Float) -> Boolean): Fit {
        SCALES.firstOrNull { fits(text, it) }?.let { return Fit(it, listOf(text)) }
        val pages = mutableListOf<String>()
        var page = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val next = if (page.isEmpty()) word else "$page $word"
            if (page.isEmpty() || fits(next, FLOOR)) page = next else { pages += page; page = word }
        }
        if (page.isNotEmpty()) pages += page
        return Fit(FLOOR, pages)
    }

    /** A page stays this long: two lines read from the couch, then the next. */
    const val PAGE_MILLIS = 8_000L
}

/** [TextFit.fit] with real text layout: [maxLines] lines of [maxWidth] px, nothing beyond. */
fun TextMeasurer.fit(text: String, style: TextStyle, maxWidth: Int, maxLines: Int): Fit = TextFit.fit(text) { s, scale ->
    !measure(s, style.copy(fontSize = style.fontSize * scale), maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth))
        .hasVisualOverflow
}

/**
 * Text that is always shown whole: it shrinks from [style]'s size to [TextFit.FLOOR] of it to fit [maxLines] lines in
 * the width [lead] leaves, and only past that steps through pages by itself. No ellipsis, no clipping, nothing to
 * press: the TV is watched from afar. [lead] is drawn first in the row and told the page shown and the page count.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    lead: @Composable (page: Int, pages: Int) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    var shown by remember { mutableStateOf(0 to 1) }
    Row(modifier, verticalAlignment = verticalAlignment) {
        lead(shown.first, shown.second)
        // Not filled: a short text keeps its own width, so a centred line stays centred.
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val width = constraints.maxWidth
            val fit = remember(text, style, width, maxLines) {
                if (width == Constraints.Infinity) Fit(1f, listOf(text)) else measurer.fit(text, style, width, maxLines)
            }
            var page by remember(fit) { mutableIntStateOf(0) }
            if (fit.pages.size > 1) {
                LaunchedEffect(fit) { while (true) { delay(TextFit.PAGE_MILLIS); page = (page + 1) % fit.pages.size } }
            }
            SideEffect { shown = page to fit.pages.size }
            val sized = style.copy(fontSize = style.fontSize * fit.scale)
            Crossfade(targetState = page, animationSpec = tween(700), label = "page") { p ->
                Text(fit.pages[p.coerceIn(fit.pages.indices)], style = sized, maxLines = maxLines)
            }
        }
    }
}
