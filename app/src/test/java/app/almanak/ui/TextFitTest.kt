package app.almanak.ui

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import app.almanak.ui.theme.AlmanakTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pure split and size logic, with a fake measure: a text fits when it has at most `chars / scale` characters. */
class TextFitLogicTest {
    private fun fitsIn(chars: Int) = { s: String, scale: Float -> s.length <= chars / scale }

    @Test fun shortTextKeepsFullSize() {
        assertEquals(Fit(1f, listOf("Short")), TextFit.fit("Short", fits = fitsIn(40)))
    }

    @Test fun longerTextShrinksBeforePaging() {
        val text = "x".repeat(45)
        val fit = TextFit.fit(text, fits = fitsIn(40))
        assertEquals(listOf(text), fit.pages)
        assertTrue(fit.scale < 1f && fit.scale >= TextFit.FLOOR)
    }

    @Test fun tooLongTextIsPagedOnWordsAndLosesNothing() {
        val text = (1..30).joinToString(" ") { "word$it" }
        val fit = TextFit.fit(text, fits = fitsIn(40))
        assertEquals(TextFit.FLOOR, fit.scale)
        assertTrue(fit.pages.size > 1)
        assertEquals(text, fit.pages.joinToString(" "))
        fit.pages.forEach { assertTrue(it, it.length <= 40 / TextFit.FLOOR) }
    }

    @Test fun aLowerFloorShrinksFurtherBeforePaging() {
        // A Large module goes down to the same 20 sp as Medium (FLOOR / 1.15) before paging (ALG-224).
        val text = (1..11).joinToString(" ") { "word" }
        assertTrue(TextFit.fit(text, fits = fitsIn(40)).pages.size > 1)
        val fit = TextFit.fit(text, TextFit.FLOOR / 1.15f, fitsIn(40))
        assertEquals(listOf(text), fit.pages)
        assertTrue(fit.scale < TextFit.FLOOR && fit.scale >= TextFit.FLOOR / 1.15f)
        assertEquals(TextFit.SCALES, TextFit.scales(TextFit.FLOOR))
    }

    @Test fun aWordLongerThanAPageStillGetsShown() {
        val fit = TextFit.fit("a ${"x".repeat(100)} b", fits = fitsIn(40))
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
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi", application = Application::class)
class TextFitLayoutTest {
    private val compose = createComposeRule()

    /** The rule's host activity, declared here instead of shipping ui-test-manifest's activity in the debug APK. */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = ComponentActivity::class.java.name
                packageName = app.packageName
            })
        }
    }).around(compose)

    private val themes = listOf(AlmanakTheme.Nocturne, AlmanakTheme.Minimal, AlmanakTheme.Paper)
    private val texts = LONGEST + SYNTHETIC
    private val COUNTER = Regex("""\d+/\d+""")

    private fun shownLayouts(): List<TextLayoutResult> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().map { node ->
            val out = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(out)
            out.single()
        }

    /** A measurer and the px per dp of the test screen, taken from a composition and used outside it. */
    private fun measurer(): Pair<TextMeasurer, Float> {
        lateinit var m: TextMeasurer
        var density = 0f
        compose.setContent { m = rememberTextMeasurer(); density = LocalDensity.current.density }
        compose.waitForIdle()
        return m to density
    }

    @Test fun everyPageFitsAndNothingIsLost() {
        val (measurer, density) = measurer()
        val px = (SMALLEST_DP * density).toInt()
        themes.forEach { theme ->
            val style = theme.caption(26.sp, Color.White)
            texts.forEach { text ->
                val fit = measurer.fit(text, style, px, 2)
                assertEquals("${theme.id}: pages must give the whole text back", text, fit.pages.joinToString(" "))
                assertTrue(fit.scale >= TextFit.FLOOR)
                fit.pages.forEach { page ->
                    val r = measurer.measure(page, style.copy(fontSize = style.fontSize * fit.scale), maxLines = 2, constraints = Constraints(maxWidth = px))
                    assertFalse("${theme.id}: \"$page\" overflows at $SMALLEST_DP dp", r.hasVisualOverflow)
                }
            }
        }
    }

    @Test fun heightShorterThanALineFallsBackToTheWidth() {
        // A parent leaving less than a line would otherwise make a page of every word, each still clipped.
        val (measurer, density) = measurer()
        val px = (TYPICAL_DP * density).toInt()
        val style = AlmanakTheme.Nocturne.caption(26.sp, Color.White)
        assertEquals(measurer.fit(LONGEST[0], style, px, 2), measurer.fit(LONGEST[0], style, px, 2, maxHeight = 4))
        // With room for one line but not two, the text pages instead of losing its second line.
        val oneLine = measurer.measure("Ag", style).size.height + 4
        assertTrue(measurer.fit(LONGEST[0], style, px, 2, maxHeight = oneLine).pages.size > 1)
    }

    @Test fun realTextsOnlyShrinkAtATypicalWidth() {
        // Pages are the last resort: at the usual width, every real text fits on two lines by shrinking alone.
        val (measurer, density) = measurer()
        val px = (TYPICAL_DP * density).toInt()
        val paged = themes.flatMap { theme -> LONGEST.filter { measurer.fit(it, theme.caption(26.sp, Color.White), px, 2).pages.size > 1 } }
        assertEquals(emptyList<String>(), paged)
    }

    @Test fun shownTextIsNeverCutAndEveryPageComesAround() {
        compose.mainClock.autoAdvance = false
        // As in FactLine: the year label (with its page counter) then the text, in the row's width.
        compose.setContent {
            // Taller than the test screen: every row gets the height it asks for, as the caption does on the TV.
            Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) {
                themes.forEach { theme ->
                    texts.forEach { text ->
                        FitText(text, theme.caption(26.sp, Color.White), Modifier.width(ROW_DP.dp)) { page, pages ->
                            LeadLabel("1789", Type.label(15.sp, Color.White), page, pages)
                        }
                    }
                }
            }
        }
        val seen = mutableMapOf<String, MutableSet<String>>()
        // Each page in turn, once its fade is over: what the screen draws is never cut nor ellipsized.
        repeat(3) { step ->
            compose.mainClock.advanceTimeBy(if (step == 0) TextFit.FADE_MILLIS + 300L else TextFit.PAGE_MILLIS)
            compose.waitForIdle()
            val shown = shownLayouts().filter { it.layoutInput.text.text != "1789" && !COUNTER.matches(it.layoutInput.text.text) }
            assertEquals(themes.size * texts.size, shown.size)
            shown.forEach {
                val t = it.layoutInput.text.text
                assertFalse("cut: $t", it.hasVisualOverflow)
                assertFalse(t.contains('…'))
                assertTrue(it.lineCount <= 2)
                texts.first { full -> full.contains(t) }.let { full -> seen.getOrPut(full) { mutableSetOf() } += t }
            }
        }
        // The synthetic text is paged at this width: its counter shows, and its pages come round to the whole text.
        val counters = compose.onAllNodes(SemanticsMatcher("page counter") { n ->
            n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().any { COUNTER.matches(it.text) }
        }).fetchSemanticsNodes()
        assertTrue(counters.isNotEmpty())
        val pages = seen.getValue(SYNTHETIC)
        assertTrue("synthetic not paged: $pages", pages.size > 1)
        assertEquals(SYNTHETIC.split(' ').toSet(), pages.flatMap { it.split(' ') }.toSet())
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

        /** The whole row at that width: the text plus the year label and its 18 dp gap. */
        const val ROW_DP = SMALLEST_DP + 60

        /** The same line beside a two-stop bus column at Medium size, as on the default screen (about 600 dp there). */
        const val TYPICAL_DP = 480

        /**
         * The widest texts the app can show, from a scan of every day of the year (2026-10-24): "on this day" in
         * English, French and Spanish (all at the 110-character cap; the two widest of each, as laid out), and the
         * longest saints' lines (French, Spanish). See DECISIONS.md, "Event text never cut".
         */
        val LONGEST = listOf(
            "The kingdoms of Denmark, Sweden, and Norway formed the Kalmar Union, a personal union under Eric of Pomerania.",
            "The American brigantine Mary Celeste was found apparently abandoned under circumstances that remain unknown.",
            "L'armée américaine tue entre 150 et 300 Amérindiens miniconjous et hunkpapas lors du massacre de Wounded Knee.",
            "Construction du camp de concentration de Neuengamme près de Hambourg par un Kommando de Sachsenhausen.",
            "Nace Osamu Tezuka, dibujante de manga y animador japonés, a menudo considerado el «padre del manga moderno».",
            "Nace Michael Schumacher, piloto de automovilismo alemán, ganador de siete campeonatos mundiales de Fórmula 1.",
            "Ste Elisabeth (mère de Jean-Baptiste) · St Zacharie (père de Jean-Baptiste)",
            "Los Tres Santos de las Misiones: san Roque González de Santa Cruz · Santos Acisclo y Victoria de Córdoba",
        )

        /** At the app's 110-character cap, with the widest letters: forces pages. */
        const val SYNTHETIC =
            "Wwwwwwwwwwwww Mmmmmmmmmmmmmm Wwwwwwwwwwwwwwww Mmmmmmmmmmmmmmm Wwwwwwwwwwwwwwwww Mmmmmmmmmmmmmmmm Wwwwwwwwwwwww"
    }
}
