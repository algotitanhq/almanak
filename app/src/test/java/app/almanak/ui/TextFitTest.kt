package app.almanak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.ui.theme.AlmanakTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pure split and size logic, with a fake measure: a text fits when it has at most `chars / scale` characters. */
class TextFitLogicTest {
    private fun fitsIn(chars: Int) = { s: String, scale: Float -> s.length <= chars / scale }

    @Test fun shortTextKeepsFullSize() {
        assertEquals(Fit(1f, listOf("Short")), TextFit.fit("Short", fitsIn(40)))
    }

    @Test fun longerTextShrinksBeforePaging() {
        val text = "x".repeat(45)
        val fit = TextFit.fit(text, fitsIn(40))
        assertEquals(listOf(text), fit.pages)
        assertTrue(fit.scale < 1f && fit.scale >= TextFit.FLOOR)
    }

    @Test fun tooLongTextIsPagedOnWordsAndLosesNothing() {
        val text = (1..30).joinToString(" ") { "word$it" }
        val fit = TextFit.fit(text, fitsIn(40))
        assertEquals(TextFit.FLOOR, fit.scale)
        assertTrue(fit.pages.size > 1)
        assertEquals(text, fit.pages.joinToString(" "))
        fit.pages.forEach { assertTrue(it, it.length <= 40 / TextFit.FLOOR) }
    }

    @Test fun aWordLongerThanAPageStillGetsShown() {
        val fit = TextFit.fit("a ${"x".repeat(100)} b", fitsIn(40))
        assertEquals(listOf("a", "x".repeat(100), "b"), fit.pages)
    }
}

/**
 * The caption as the TV lays it out (bundled fonts, real line breaking): the longest event texts the app can show
 * never overflow their two lines at the smallest width the almanac line gets, in every theme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the app's own starts WorkManager and the data graph, which a layout test needs none of.
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi", application = android.app.Application::class)
class TextFitLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val themes = listOf(AlmanakTheme.Nocturne, AlmanakTheme.Minimal, AlmanakTheme.Paper)
    private val texts = LONGEST + SYNTHETIC

    private fun shownLayouts(): List<TextLayoutResult> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().map { node ->
            val out = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(out)
            out.single()
        }

    @Test fun everyPageFitsAndNothingIsLost() {
        val fits = mutableListOf<Triple<AlmanakTheme, String, Fit>>()
        val overflows = mutableListOf<String>()
        compose.setContent {
            val measurer = rememberTextMeasurer()
            val px = with(LocalDensity.current) { SMALLEST_DP.dp.roundToPx() }
            themes.forEach { theme ->
                val style = theme.caption(26.sp, Color.White)
                texts.forEach { text ->
                    val fit = measurer.fit(text, style, px, 2)
                    fits += Triple(theme, text, fit)
                    fit.pages.forEach { page ->
                        val r = measurer.measure(page, style.copy(fontSize = style.fontSize * fit.scale), maxLines = 2, constraints = Constraints(maxWidth = px))
                        if (r.hasVisualOverflow) overflows += "${theme.id}: $page"
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(themes.size * texts.size, fits.size)
        fits.forEach { (theme, text, fit) ->
            assertEquals("${theme.id}: pages must give the whole text back", text, fit.pages.joinToString(" "))
            assertTrue(fit.scale >= TextFit.FLOOR)
        }
        assertEquals(emptyList<String>(), overflows)
    }

    @Test fun realTextsOnlyShrinkAtATypicalWidth() {
        // Pages are the last resort: at the usual width, every real text fits on two lines by shrinking alone.
        val pages = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            val measurer = rememberTextMeasurer()
            val px = with(LocalDensity.current) { TYPICAL_DP.dp.roundToPx() }
            themes.forEach { theme -> LONGEST.forEach { pages += it to measurer.fit(it, theme.caption(26.sp, Color.White), px, 2).pages.size } }
        }
        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, Int>>(), pages.filter { it.second > 1 })
    }

    @Test fun shownTextIsNeverCut() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column {
                themes.forEach { theme -> texts.forEach { FitText(it, theme.caption(26.sp, Color.White), Modifier.width(SMALLEST_DP.dp)) } }
            }
        }
        // Each page in turn, once its crossfade is over: what the screen draws is never cut nor ellipsized.
        repeat(3) { step ->
            compose.mainClock.advanceTimeBy(if (step == 0) 1_000 else TextFit.PAGE_MILLIS)
            compose.waitForIdle()
            val shown = shownLayouts()
            assertEquals(themes.size * texts.size, shown.size)
            shown.forEach {
                assertFalse("cut at $SMALLEST_DP dp: ${it.layoutInput.text}", it.hasVisualOverflow)
                assertFalse(it.layoutInput.text.text.contains('…'))
                assertTrue(it.lineCount <= 2)
            }
        }
    }

    @Test fun theOldCaptionDidCutTheseTexts() {
        // Guards the test itself: at a fixed 26 sp on two lines, the worst case overflows (the ALG-220 bug).
        compose.setContent {
            Text(SYNTHETIC, Modifier.width(SMALLEST_DP.dp), style = AlmanakTheme.Nocturne.caption(26.sp, Color.White), maxLines = 2)
        }
        compose.waitForIdle()
        assertTrue(shownLayouts().single().hasVisualOverflow)
    }

    companion object {
        /**
         * The narrowest the text gets, in dp at Medium size: the line under the clock (960 dp less the 48 dp margins,
         * 24 dp gap) beside the widest right column (compact bus block at Large, 430 dp), at Large size (÷ 1.15),
         * less the year label (about 60 dp). See DECISIONS.md, "Event text never cut".
         */
        const val SMALLEST_DP = 280

        /** The same line beside a two-stop bus column at Medium size, as on the default screen (about 600 dp there). */
        const val TYPICAL_DP = 480

        /** The longest "on this day" texts and saints' lines found in the sources (scan of 2026-10-24, PR body). */
        val LONGEST = listOf(
            "Los Tres Santos de las Misiones: san Roque González de Santa Cruz · Santos Acisclo y Victoria de Córdoba",
        )

        /** At the app's 110-character cap, with the widest letters: forces pages. */
        const val SYNTHETIC =
            "Wwwwwwwwwwwww Mmmmmmmmmmmmmm Wwwwwwwwwwwwwwww Mmmmmmmmmmmmmmm Wwwwwwwwwwwwwwwww Mmmmmmmmmmmmmmmm Wwwwwwwwwwwww"
    }
}
