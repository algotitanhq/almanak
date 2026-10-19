package app.almanak.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text
import app.almanak.BusState
import app.almanak.R
import app.almanak.RtStatus
import app.almanak.settings.City
import app.almanak.settings.DevicePlaces
import app.almanak.settings.Module
import app.almanak.settings.ModuleSetting
import app.almanak.settings.ModuleSize
import app.almanak.settings.Settings
import app.almanak.settings.Zone
import app.almanak.ui.theme.LocalAlmanakTheme
import app.almanak.ui.theme.ThemeId
import app.almanak.wallpaper.WallpaperMode
import app.almanak.weather.TempUnit
import app.almanak.web.SetupApi
import kotlinx.coroutines.launch

/** One settings row: a label, its current value, and what OK does (null = read-only). */
private data class Row(val label: String, val value: String, val onClick: (() -> Unit)? = null)

private data class Section(val title: String, val rows: List<Row>)

/**
 * TV settings as a right-hand drawer, entirely DPAD-driven: every row cycles its value with OK. Changes are saved
 * immediately, so the ambient screen beside it updates live. While open, the phone setup server runs and its QR
 * is shown.
 */
@Composable
fun SettingsPanel(
    settings: Settings,
    bus: BusState,
    update: suspend ((Settings) -> Settings) -> Unit,
    api: SetupApi,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // On TV, Compose turns BACK on a focused item into "exit focus", so a plain BackHandler only fires on the
    // second press. Catch it before the focus system does: one BACK closes the panel.
    val closeOnBack = Modifier.onPreviewKeyEvent { e ->
        if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onClose(); true } else false
    }
    val scope = rememberCoroutineScope()
    fun set(t: (Settings) -> Settings) { scope.launch { update(t) } }

    val url = rememberSetupUrl(api)

    val sections = sections(settings, bus, api.places, ::set)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    val theme = LocalAlmanakTheme.current
    val list = @Composable { modifier: Modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
            sections.forEachIndexed { si, section ->
                item(key = "h$si") {
                    Text(section.title.uppercase(), Modifier.padding(start = 12.dp, top = if (si == 0) 0.dp else 18.dp, bottom = 6.dp), style = Type.label(12.sp))
                }
                items(section.rows, key = { "$si/${it.label}" }) { row ->
                    val isFirst = si == 0 && row === section.rows.first()
                    ListItem(
                        selected = false,
                        onClick = { row.onClick?.invoke() },
                        enabled = true,
                        modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                        headlineContent = { Text(row.label, style = Type.number(17.sp)) },
                        trailingContent = { Text(row.value, style = Type.serifItalic(19.sp, if (row.onClick != null) Fg else FgFaint)) },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Fg,
                            focusedContainerColor = theme.fg.copy(alpha = 0.12f),
                            focusedContentColor = Fg,
                        ),
                        shape = ListItemDefaults.shape(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().then(closeOnBack), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(440.dp).background(theme.background)
                .padding(start = 28.dp, end = 48.dp, top = 32.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = Type.serifItalic(40.sp, Fg))
            Spacer(Modifier.height(12.dp))
            list(Modifier.weight(1f))
            PhoneCard(url, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun PhoneCard(url: String?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (url != null) {
            QrCode(url, 120.dp)
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text(stringResource(R.string.settings_phone), style = Type.serifItalic(20.sp, Fg))
            Text(
                url?.substringBefore("/?") ?: stringResource(R.string.settings_no_network),
                Modifier.padding(top = 4.dp),
                style = Type.label(12.sp, FgFaint),
            )
        }
    }
}

private fun <T> List<T>.after(v: T): T = this[(indexOf(v) + 1).mod(size)]

@Composable
private fun sections(s: Settings, bus: BusState, places: DevicePlaces, set: ((Settings) -> Settings) -> Unit): List<Section> {
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    val zoneLabel = mapOf(
        Zone.Right to stringResource(R.string.zone_right),
        Zone.Bottom to stringResource(R.string.zone_bottom),
        Zone.UnderClock to stringResource(R.string.zone_under),
    )
    val sizeLabel = mapOf(
        ModuleSize.Small to stringResource(R.string.size_small),
        ModuleSize.Medium to stringResource(R.string.size_medium),
        ModuleSize.Large to stringResource(R.string.size_large),
    )
    val moduleName = mapOf(
        Module.Weather to stringResource(R.string.mod_weather),
        Module.Bus to stringResource(R.string.mod_bus),
        Module.Almanac to stringResource(R.string.mod_almanac),
    )
    val languageLabel = mapOf("en" to "English", "fr" to "Français", "es" to "Español")
    val wallpaperLabel = mapOf(
        WallpaperMode.Sky to stringResource(R.string.bg_sky),
        WallpaperMode.Potd to stringResource(R.string.bg_potd),
        WallpaperMode.None to stringResource(R.string.bg_plain),
        WallpaperMode.Url to stringResource(R.string.bg_url),
    )
    val wallpapers = listOfNotNull(WallpaperMode.Sky, WallpaperMode.Potd, WallpaperMode.None, WallpaperMode.Url.takeIf { s.wallpaperUrl.isNotBlank() })
    // The time zone's place (null: "no city chosen"), the zone cities around it, then a city found on the phone.
    // Built from the zone, not the chosen city, so the cycle order stays put while it moves along.
    val here = remember(s.locale) { places.here(s.locale) }
    val nearby = remember(here, s.locale) { places.nearby(here, s.locale) }
    val cities: List<City?> = listOf(null) + nearby + listOfNotNull(s.city?.takeIf { it !in nearby })

    val display = Section(stringResource(R.string.set_display), listOf(
        Row(stringResource(R.string.set_theme), s.theme.name) { set { it.copy(theme = ThemeId.entries.after(it.theme)) } },
        Row(stringResource(R.string.set_language), languageLabel.getValue(s.language)) { set { it.copy(language = Settings.LANGUAGES.after(it.language)) } },
        Row(stringResource(R.string.set_temperature), if (s.unit == TempUnit.Celsius) "°C" else "°F") { set { it.copy(unit = TempUnit.entries.after(it.unit)) } },
        Row(stringResource(R.string.set_background), wallpaperLabel.getValue(s.wallpaper)) { set { it.copy(wallpaper = wallpapers.after(it.wallpaper)) } },
        Row(stringResource(R.string.set_city), s.city?.name ?: stringResource(R.string.city_approx, here.name)) { set { st -> st.copy(city = cities.after(st.city)) } },
        // Typed on the phone (a password with a DPAD is no fun); here it's only a reminder of what the arrows show.
        Row(stringResource(R.string.guest_wifi), if (s.guestWifi.configured) s.guestWifi.ssid else stringResource(R.string.guest_wifi_unset)),
    ))
    val positionFmt = stringResource(R.string.set_position)
    val sizeFmt = stringResource(R.string.set_size)
    val layout = Section(stringResource(R.string.set_layout), Module.entries.flatMap { m ->
        val ms = s.module(m)
        val name = moduleName.getValue(m)
        fun change(t: (ModuleSetting) -> ModuleSetting) = set { st -> st.copy(modules = st.modules.map { if (it.module == m) t(it) else it }) }
        listOf(
            Row(name, if (ms.enabled) on else off) { change { it.copy(enabled = !it.enabled) } },
            Row(positionFmt.format(name), zoneLabel.getValue(ms.zone)) { change { it.copy(zone = Settings.ALLOWED_ZONES.getValue(m).after(it.zone)) } },
            Row(sizeFmt.format(name), sizeLabel.getValue(ms.size)) { change { it.copy(size = ModuleSize.entries.after(it.size)) } },
        )
    })
    val names = bus.lines.associate { it.stopId to it.stopName }
    val transit = Section(stringResource(R.string.set_bus), listOf(Row(stringResource(R.string.set_network), s.agency.name.ifEmpty { stringResource(R.string.guest_wifi_unset) })) + s.stops.map { Row(it, names[it] ?: "…") } + listOf(
        Row(stringResource(R.string.set_lines), s.routes.joinToString(", ").ifEmpty { stringResource(R.string.set_all) }),
        Row(stringResource(R.string.set_realtime), stringResource(when (bus.status) {
            RtStatus.Live -> R.string.rt_live
            RtStatus.Unauthorized -> R.string.rt_rejected
            RtStatus.NoFeed -> R.string.rt_none
            RtStatus.NoKey -> R.string.rt_nokey
            RtStatus.Offline -> R.string.rt_unreachable
            RtStatus.Starting -> R.string.rt_unreachable
        })),
    ))
    // Attribution the data licences ask for (CC BY / CC BY-SA), shown where anyone using the TV can find it.
    val sources = Section(stringResource(R.string.set_sources), listOfNotNull(
        Row(stringResource(R.string.src_weather), "Open-Meteo · CC BY 4.0"),
        Row(stringResource(R.string.src_transit), listOf(s.agency.name.substringBefore(" ·"), s.agency.licence).filter { it.isNotEmpty() }.joinToString(" · "))
            .takeIf { s.agency.configured },
        Row(stringResource(R.string.src_history), "Wikipedia · CC BY-SA 4.0"),
        Row(stringResource(R.string.src_photos), "Wikimedia Commons").takeIf { s.wallpaper == WallpaperMode.Potd },
        if (s.language == "es") Row(stringResource(R.string.name_day), "Wikipedia · CC BY-SA 4.0")
        else Row(stringResource(R.string.name_day), "data.gouv.fr · Licence Ouverte"),
    ))
    return listOf(display, layout, transit, sources)
}
