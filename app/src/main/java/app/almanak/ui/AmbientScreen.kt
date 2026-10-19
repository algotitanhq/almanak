package app.almanak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.almanak.BusState
import app.almanak.settings.Module
import app.almanak.settings.ModuleSetting
import app.almanak.settings.Settings
import app.almanak.settings.Zone
import app.almanak.ephemeris.EphemerisState
import app.almanak.wallpaper.Backdrop
import app.almanak.WeatherState
import app.almanak.weather.Sky
import app.almanak.weather.TempUnit
import java.time.ZonedDateTime
import java.util.Locale

/** Everything the ambient screen shows; one immutable value per frame of state. */
data class AmbientUi(
    val now: ZonedDateTime,
    val weather: WeatherState,
    val bus: BusState,
    val ephemeris: EphemerisState?,
    val backdrop: Backdrop,
    val scrim: ScrimStyle,
    val use24h: Boolean,
    val locale: Locale,
    val unit: TempUnit,
    val modules: List<ModuleSetting> = Settings.DEFAULT_MODULES,
)

@Composable
fun AmbientScreen(ui: AmbientUi, variant: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Bg)) {
        BackdropLayer(ui.backdrop, ui.scrim)
        val drift = burnInDrift(ui.now.hour * 60 + ui.now.minute)
        Box(Modifier.fillMaxSize().offset(drift.x, drift.y)) {
            Foreground(ui, variant)
            // Licence attribution for the photo (CC BY-SA etc.), in the bottom margin under the weather band.
            (ui.backdrop as? Backdrop.Photo)?.credit?.let {
                Text(it, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 12.dp), style = Type.label(10.sp, FgFaint), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Foreground(ui: AmbientUi, variant: String?) {
    run {
        when (variant?.lowercase()) {
            "glyphs" -> GlyphSheet()
            else -> BandLayout(
                ui,
                // Debug: "fact-moon" pins the caption to one fact for captures.
                pinned = variant?.removePrefix("fact-")?.let { v -> Fact.entries.firstOrNull { it.name.equals(v, true) } },
            )
        }
    }
}

@Composable
private fun BandLayout(ui: AmbientUi, pinned: Fact? = null) {
    fun inZone(z: Zone) = ui.modules.filter { it.enabled && it.zone == z }
    val right = inZone(Zone.Right)
    val bottom = inZone(Zone.Bottom)
    val under = inZone(Zone.UnderClock)
    if (right.isEmpty() && bottom.isEmpty()) {
        // Quiet: just time, date and the almanac line, centred as in slice 1.
        Box(Modifier.fillMaxSize().then(SafeArea), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ClockBlock(ui.now, ui.use24h, ui.locale, timeSize = 210.sp, dateSize = 44.sp)
                under.forEach { ModuleIn(it, ui, pinned, Modifier.padding(top = 24.dp)) }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().then(SafeArea)) {
        // The top row takes what the bottom zone leaves: nothing above can push the band off-screen.
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                ClockBlock(ui.now, ui.use24h, ui.locale, timeSize = 170.sp, dateSize = 40.sp, align = Alignment.Start)
                under.forEach { ModuleIn(it, ui, pinned, Modifier.padding(top = 18.dp, end = 24.dp)) }
            }
            if (right.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    right.forEach { ModuleIn(it, ui, pinned) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            bottom.forEach { ModuleIn(it, ui, pinned, Modifier.fillMaxWidth()) }
        }
    }
}

/** One module, drawn for its zone and scaled to its size. */
@Composable
private fun ModuleIn(m: ModuleSetting, ui: AmbientUi, pinned: Fact?, modifier: Modifier = Modifier) {
    Scaled(m.size.scale) {
        when (m.module) {
            Module.Almanac -> ui.ephemeris?.let { EphemerisCaption(it, ui.now, ui.use24h, ui.locale, modifier, pinned) }
            Module.Bus -> when (m.zone) {
                Zone.Bottom -> BusRow(ui.bus, ui.now, ui.use24h, ui.locale, modifier)
                else -> BusStack(ui.bus, ui.now, ui.use24h, ui.locale, modifier)
            }
            Module.Weather -> {
                val now = ui.now.toLocalDateTime()
                val millis = ui.now.toInstant().toEpochMilli()
                when (m.zone) {
                    Zone.Right -> WeatherAside(ui.weather, now, millis, ui.unit, ui.use24h, ui.locale, modifier)
                    else -> WeatherBand(ui.weather, now, millis, ui.unit, ui.use24h, ui.locale, modifier)
                }
            }
        }
    }
}

/** Uniformly scales dp and sp inside: a module's size setting without touching its layout code. */
@Composable
private fun Scaled(scale: Float, content: @Composable () -> Unit) {
    if (scale == 1f) return content()
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale), content = content)
}

/** Debug sheet to eyeball every glyph, day and night. */
@Composable
private fun GlyphSheet() {
    Column(Modifier.fillMaxSize().then(SafeArea), verticalArrangement = Arrangement.SpaceEvenly) {
        listOf(true, false).forEach { day ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Sky.entries.forEach { sky ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        WeatherGlyph(sky, day, 64.dp, stroke = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(sky.name, style = Type.label(11.sp))
                    }
                }
            }
        }
    }
}
