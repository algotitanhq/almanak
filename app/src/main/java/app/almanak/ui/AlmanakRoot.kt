package app.almanak.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.almanak.AmbientViewModel
import app.almanak.settings.ModuleSetting
import app.almanak.settings.Settings
import app.almanak.ui.theme.AlmanakTheme
import app.almanak.ui.theme.LocalAlmanakTheme
import app.almanak.weather.TempUnit
import java.util.Locale

/** How the ambient screen is dressed; shared by the activity and the screensaver. */
data class DisplayConfig(
    val modules: List<ModuleSetting> = Settings.DEFAULT_MODULES,
    val theme: AlmanakTheme = AlmanakTheme.Nocturne,
    val locale: Locale = Locale.US,
    val unit: TempUnit = TempUnit.Celsius,
    val scrim: ScrimStyle = ScrimStyle.Local,
    val variant: String? = null,
) {
    companion object {
        fun from(s: Settings) = DisplayConfig(
            modules = s.modules, theme = AlmanakTheme.of(s.theme), locale = s.locale, unit = s.unit,
        )
    }
}

/** The whole ambient UI for one [AmbientViewModel]. Used as-is by MainActivity and AlmanakDream. */
@Composable
fun AlmanakRoot(vm: AmbientViewModel, config: DisplayConfig, overlay: @Composable (bus: app.almanak.BusState) -> Unit = {}) {
    val now by vm.clock.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val bus by vm.bus.collectAsStateWithLifecycle()
    val ephemeris by vm.ephemeris.collectAsStateWithLifecycle()
    val backdrop by vm.backdrop.collectAsStateWithLifecycle()
    val use24h = DateFormat.is24HourFormat(LocalContext.current)
    CompositionLocalProvider(LocalAlmanakTheme provides config.theme) {
        ProvideAppLocale(config.locale) {
            AmbientScreen(
                AmbientUi(now, weather, bus, ephemeris, backdrop, config.scrim, use24h, config.locale, config.unit, config.modules),
                config.variant,
            )
            overlay(bus)
        }
    }
}
