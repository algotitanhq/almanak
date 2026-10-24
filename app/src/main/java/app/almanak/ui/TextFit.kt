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

    /** [SCALES] continued by 1/26 steps down to [floor] (a Large module goes down to the same 20 sp, ALG-224). */
    fun scales(floor: Float): List<Float> =
        if (floor >= FLOOR) SCALES.filter { it >= floor }.ifEmpty { listOf(floor) }
        else SCALES + generateSequence(FLOOR - 1f / 26) { it - 1f / 26 }.takeWhile { it > floor + 1e-4f }.toList() + floor

    /**
     * [fits] says whether a text fits in the available lines at a scale. Pages split on spaces only, so they join back
     * to the original text with single spaces; a single word too long for a page still gets a page of its own.
     * [floor] is the smallest scale, at which pages are cut.
     */
    fun fit(text: String, floor: Float = FLOOR, fits: (String, Float) -> Boolean): Fit {
        val steps = scales(floor)
        steps.firstOrNull { fits(text, it) }?.let { return Fit(it, listOf(text)) }
        val smallest = steps.last()
        val pages = mutableListOf<String>()
        var page = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val next = if (page.isEmpty()) word else "$page $word"
            if (page.isEmpty() || fits(next, smallest)) page = next else { pages += page; page = word }
        }
        if (page.isNotEmpty()) pages += page
        return Fit(smallest, pages)
    }

    /** A page stays this long: two lines read from the couch, then the next. */
    const val PAGE_MILLIS = 8_000L
    const val FADE_MILLIS = 700
}

/**
 * [TextFit.fit] with real text layout: [maxLines] lines within [maxWidth] × [maxHeight] px, nothing beyond. [style]
 * has an sp size (scaled per step).
 */
fun TextMeasurer.fit(text: String, style: TextStyle, maxWidth: Int, maxLines: Int, maxHeight: Int = Constraints.Infinity, floor: Float = TextFit.FLOOR): Fit {
    require(style.fontSize.isSp) { "FitText needs an sp font size" }
    fun fits(s: String, scale: Float, height: Int) =
        !measure(s, style.copy(fontSize = style.fontSize * scale), maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth, maxHeight = height))
            .hasVisualOverflow
    // Less height than one line at the floor: pages cannot help, fit the width alone (as before the height check).
    val height = if (maxHeight == Constraints.Infinity || fits("Ag", floor, maxHeight)) maxHeight else Constraints.Infinity
    return TextFit.fit(text, floor) { s, scale -> fits(s, scale, height) }
}

/**
 * Text that is always shown whole: it shrinks from [style]'s size to [floor] of it to fit [maxLines] lines in
 * the width [lead] leaves, and only past that steps through pages by itself. No ellipsis, no clipping, nothing to
 * press: the TV is watched from afar. [lead] is drawn first in the row and told the page shown and the page count.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    floor: Float = TextFit.FLOOR,
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
            // The height too: a parent that leaves less than two lines would otherwise clip the second one.
            val height = constraints.maxHeight
            val fit = remember(text, style, width, height, maxLines, floor) {
                if (width == Constraints.Infinity) Fit(1f, listOf(text)) else measurer.fit(text, style, width, maxLines, height, floor)
            }
            var page by remember(fit) { mutableIntStateOf(0) }
            if (fit.pages.size > 1) {
                LaunchedEffect(fit) { while (true) { delay(TextFit.PAGE_MILLIS); page = (page + 1) % fit.pages.size } }
            }
            SideEffect { shown = page to fit.pages.size }
            val sized = style.copy(fontSize = style.fontSize * fit.scale)
            // Fades on the page's text, so the outgoing page keeps its own words when the text changes.
            Crossfade(targetState = fit.pages[page.coerceIn(fit.pages.indices)], animationSpec = tween(TextFit.FADE_MILLIS), label = "page") {
                Text(it, style = sized, maxLines = maxLines)
            }
        }
    }
}
