package app.almanak

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.almanak.ephemeris.HistoryItem
import app.almanak.settings.Module
import app.almanak.ui.AlmanakRoot
import app.almanak.ui.DisplayConfig
import app.almanak.ui.GuestWifiCard
import app.almanak.ui.SettingsPanel
import app.almanak.ui.WelcomeCard
import app.almanak.ui.debugSetupUrl
import app.almanak.ui.ScrimStyle
import app.almanak.ui.theme.AlmanakTheme
import app.almanak.ui.theme.ThemeId
import app.almanak.wallpaper.SkyPhase
import app.almanak.wallpaper.WallpaperMode
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    private val graph get() = (application as AlmanakApp).graph

    private val vm: AmbientViewModel by viewModels {
        val debug = BuildConfig.DEBUG
        AmbientViewModel.factory(
            graph,
            DebugOverrides(
                offline = debug && intent.getBooleanExtra("offline", false),
                fakeRt = debug && intent.getBooleanExtra("fakeRt", false),
                wallpaper = intent.getStringExtra("wallpaper")?.let { w -> WallpaperMode.entries.firstOrNull { it.name.equals(w, true) } },
                wallpaperUrl = intent.getStringExtra("wallpaperUrl"),
                sky = if (!debug) null else intent.getStringExtra("sky")?.let { s -> SkyPhase.entries.firstOrNull { it.name.equals(s, true) } },
                // e.g. --es history '1789|The storming of the Bastille'  --es saints 'St A;St B'
                history = if (!debug) null else intent.getStringExtra("history")?.split('|', limit = 2)
                    ?.takeIf { it.size == 2 }?.let { (y, t) -> y.toIntOrNull()?.let { listOf(HistoryItem(it, t)) } },
                saints = if (!debug) null else intent.getStringExtra("saints")?.split(';')
                    ?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) debugSetupUrl = intent.getStringExtra("setupUrl")
        // An ambient display: the whole point is to stay on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            var open by rememberSaveable { mutableStateOf(BuildConfig.DEBUG && intent.getBooleanExtra("settings", false)) }
            var wifi by remember { mutableStateOf(BuildConfig.DEBUG && intent.getBooleanExtra("wifi", false)) }
            val rootFocus = remember { FocusRequester() }
            Box(
                Modifier.fillMaxSize().focusRequester(rootFocus).focusable().onKeyEvent { e ->
                    when {
                        open -> false
                        // While the Wi-Fi card is up, any key (BACK included) just puts it away.
                        wifi -> { if (e.type == KeyEventType.KeyUp) wifi = false; true }
                        // On key *up*: opening on key down would deliver the matching key-up to the first row and click it.
                        e.type != KeyEventType.KeyUp -> false
                        e.key in OPENERS -> { open = true; true }
                        e.key in ARROWS && settings.guestWifi.configured -> { wifi = true; true }
                        else -> false
                    }
                },
            ) {
                AlmanakRoot(vm, debugConfig(DisplayConfig.from(settings))) { bus ->
                    if (!settings.onboarded && !open) WelcomeCard(graph.setupApi)
                    GuestWifiCard(settings.guestWifi, wifi && !open)
                    if (open) SettingsPanel(settings, bus, { graph.settings.update(it) }, graph.setupApi) { open = false }
                }
            }
            LaunchedEffect(open) {
                if (!open) runCatching { rootFocus.requestFocus() }
                // Having seen the TV settings counts as set up: the welcome card has done its job.
                else if (!settings.onboarded) graph.settings.update { it.copy(onboarded = true) }
            }
            // Long enough to scan, short enough not to mark the panel.
            LaunchedEffect(wifi) { if (wifi) { delay(60.seconds); wifi = false } }
        }
    }

    private companion object {
        val OPENERS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu)
        val ARROWS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
    }

    /** Debug hooks for variant captures: am start ... --es variant fact-moon --es locale fr-CA --es theme paper */
    private fun debugConfig(base: DisplayConfig): DisplayConfig {
        if (!BuildConfig.DEBUG) return base
        val only = intent.getStringExtra("modules")?.split(',')
            ?.mapNotNull { m -> Module.entries.firstOrNull { it.name.equals(m.trim(), true) } }?.toSet()
        return base.copy(
            theme = intent.getStringExtra("theme")?.let { t -> ThemeId.entries.firstOrNull { it.name.equals(t, true) } }
                ?.let(AlmanakTheme::of) ?: base.theme,
            locale = intent.getStringExtra("locale")?.let(Locale::forLanguageTag) ?: base.locale,
            scrim = if (intent.getStringExtra("scrim").equals("veil", true)) ScrimStyle.Veil else base.scrim,
            variant = intent.getStringExtra("variant"),
            // e.g. --es modules almanac  (comma-separated subset of weather,bus,almanac)
            modules = if (only == null) base.modules else base.modules.map { it.copy(enabled = it.module in only) },
        )
    }
}
