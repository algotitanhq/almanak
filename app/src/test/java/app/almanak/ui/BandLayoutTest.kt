package app.almanak.ui

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.almanak.BusLine
import app.almanak.BusState
import app.almanak.RtStatus
import app.almanak.WeatherState
import app.almanak.ephemeris.EphemerisState
import app.almanak.ephemeris.HistoryItem
import app.almanak.ephemeris.Moon
import app.almanak.ephemeris.Sun
import app.almanak.settings.Module
import app.almanak.settings.ModuleSetting
import app.almanak.settings.ModuleSize
import app.almanak.settings.Zone
import app.almanak.transit.Arrival
import app.almanak.transit.StopSchedule
import app.almanak.ui.theme.AlmanakTheme
import app.almanak.ui.theme.LocalAlmanakTheme
import app.almanak.wallpaper.Backdrop
import app.almanak.weather.TempUnit
import app.almanak.weather.Weather
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
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

/**
 * The whole ambient screen with many bus lines (ALG-224): with 4, 5 or 6 lines in the right column, at Medium and
 * Large size, the widest real event texts show whole (one page, nothing cut), clear of the bus column and above the
 * weather band. storagebits has at least four lines; the fit of ALG-221 was measured beside two.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi", application = Application::class)
class BandLayoutTest {
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = ComponentActivity::class.java.name
                packageName = app.packageName
            })
        }
    }).around(compose)

    private val zone = ZoneId.of("America/Montreal")
    private val now = ZonedDateTime.of(2026, 10, 24, 12, 40, 0, 0, zone)

    /** [n] lines, every one with three departures (the tallest compact line: a big ETA). */
    private fun bus(n: Int) = BusState(
        (1..n).map { i ->
            val route = "${100 + 50 * i}"
            BusLine("s$i", "Stop $i", StopSchedule.Route(route, "009EE0"), if (i % 2 == 0) "North" else "East",
                listOf(7L, 28L, 51L).map { m -> Arrival("t$i$m", "s$i", route, "East", now.plusMinutes(m).toInstant(), realtime = true) })
        },
        RtStatus.Live, hasSchedule = true,
    )

    private val weather = WeatherState(
        Weather(
            fetchedAtMillis = now.toInstant().toEpochMilli(),
            now = Weather.Now(1.0, -2.0, 1, true, 10.0, 60),
            hours = (0..24).map { h -> Weather.Hour(now.toLocalDateTime().withMinute(0).plusHours(h.toLong()).toString(), 5.0 + h % 6, 1, 10, h < 5) },
            days = (0..6).map { d -> Weather.Day(now.toLocalDate().plusDays(d.toLong()).toString(), 3, 12.0, 2.0, 10, "07:20", "18:00") },
        ),
        live = true,
    )

    private fun ui(text: String, lines: Int, size: ModuleSize, locale: Locale) = AmbientUi(
        now = now,
        weather = weather,
        bus = bus(lines),
        ephemeris = EphemerisState(now.toLocalDate(), Sun.times(now.toLocalDate(), 45.5, -73.57, zone), Moon.phase(now.toInstant(), zone), emptyList(), listOf(HistoryItem(1397, text))),
        backdrop = Backdrop.Plain,
        scrim = ScrimStyle.Local,
        use24h = false,
        locale = locale,
        unit = TempUnit.Celsius,
        modules = listOf(
            ModuleSetting(Module.Almanac, zone = Zone.UnderClock, size = size),
            ModuleSetting(Module.Bus, zone = Zone.Right, size = size),
            ModuleSetting(Module.Weather, zone = Zone.Bottom),
        ),
    )

    private fun texts(): List<Pair<SemanticsNode, TextLayoutResult>> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val out = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(out)
            node to out.single()
        }

    private fun Rect.overlaps(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    @Test fun manyBusLinesLeaveTheEventWhole() {
        compose.mainClock.autoAdvance = false
        val locales = mapOf("en" to Locale.US, "fr" to Locale.CANADA_FRENCH, "es" to Locale("es", "ES"))
        var shown by mutableStateOf(ui(TextFitLayoutTest.LONGEST[0], 4, ModuleSize.Medium, Locale.US))
        compose.setContent {
            CompositionLocalProvider(LocalAlmanakTheme provides AlmanakTheme.Nocturne) {
                ProvideAppLocale(shown.locale) { AmbientScreen(shown, variant = "fact-history") }
            }
        }
        val failures = mutableListOf<String>()
        for (lines in listOf(4, 5, 6)) for (size in listOf(ModuleSize.Medium, ModuleSize.Large)) for (text in TextFitLayoutTest.LONGEST.take(6)) {
            val lang = if (text.startsWith("Nace")) "es" else if (text.startsWith("The")) "en" else "fr"
            compose.runOnUiThread { shown = ui(text, lines, size, locales.getValue(lang)) }
            compose.waitForIdle()
            // Past the page fade, so the text of the previous case is gone.
            compose.mainClock.advanceTimeBy(TextFit.FADE_MILLIS + 500L)
            compose.waitForIdle()
            val case = "$lines lines, $size, \"${text.take(30)}…\""
            val all = texts()
            val event = all.filter { it.second.layoutInput.text.text == text }
            if (event.size != 1) {
                val where = all.filter { it.second.layoutInput.text.text.let { t -> t.length > 20 || t.matches(Regex("""\d{3}|\d+°""")) } }
                    .joinToString { "${it.second.layoutInput.text.text.take(12)}@${it.first.boundsInRoot}" }
                failures += "$case: not shown whole on one page ($where)"
                continue
            }
            val (node, layout) = event.single()
            if (layout.hasVisualOverflow) failures += "$case: cut"
            val box = node.boundsInRoot
            // Route labels ("150"…) mark the bus column; degree signs the weather band.
            val routes = all.filter { it.second.layoutInput.text.text.matches(Regex("""\d{3}""")) }.map { it.first.boundsInRoot }
            val band = all.filter { it.second.layoutInput.text.text.endsWith("°") }.map { it.first.boundsInRoot }
            if (routes.isEmpty()) failures += "$case: no bus line shown"
            routes.firstOrNull { it.overlaps(box) }?.let { failures += "$case: overlaps the bus column at $it (text $box)" }
            band.firstOrNull { it.top < box.bottom }?.let { failures += "$case: reaches the weather band at $it (text $box)" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun fullBlocksKeepTheTextBesideTheColumn() {
        // Three full blocks reach below the clock: the text keeps to their left, as in ALG-221, and is still whole.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAlmanakTheme provides AlmanakTheme.Nocturne) {
                AmbientScreen(ui(TextFitLayoutTest.LONGEST[0], 3, ModuleSize.Medium, Locale.US), variant = "fact-history")
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val all = texts()
        val event = all.single { it.second.layoutInput.text.text == TextFitLayoutTest.LONGEST[0] }
        assertFalse(event.second.hasVisualOverflow)
        val routes = all.filter { it.second.layoutInput.text.text.matches(Regex("""\d{3}""")) }.map { it.first.boundsInRoot }
        assertTrue(routes.isNotEmpty())
        assertTrue(routes.none { it.overlaps(event.first.boundsInRoot) })
    }
}
