package app.almanak

import android.app.Application
import app.almanak.ephemeris.OnThisDaySource
import app.almanak.ephemeris.Saints
import app.almanak.settings.City
import app.almanak.settings.DevicePlaces
import app.almanak.settings.Settings
import app.almanak.settings.SettingsStore
import app.almanak.settings.realtimeKeyFor
import app.almanak.transit.GtfsStore
import app.almanak.transit.GtfsSyncWorker
import app.almanak.transit.RealtimeSource
import app.almanak.wallpaper.PhotoSource
import app.almanak.web.SetupApi
import app.almanak.weather.WeatherSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class AlmanakApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        val (stops, routes) = graph.transitSelection()
        GtfsSyncWorker.schedule(this, now = graph.gtfs.needsSync(stops, routes, LocalDate.now()))
        // A new network or stop selection needs a new extraction (from the cached zip when possible).
        graph.scope.launch {
            graph.settings.settings.map { Triple(it.agency.gtfsUrls, it.stops, it.routes) }.distinctUntilChanged().drop(1).collect {
                GtfsSyncWorker.schedule(this@AlmanakApp, now = true)
            }
        }
    }
}

/** Manual dependency wiring: one instance of each long-lived collaborator. */
class AppGraph(app: Application) {
    /** Lives as long as the process; for settings and other app-wide observers. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsStore(File(app.filesDir, "settings.json"), scope)

    // No HTTP disk cache: every source keeps its own small on-disk result, and the GTFS zip would be stored twice.
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val weather = WeatherSource(http, File(app.filesDir, "weather.json"))
    val gtfs = GtfsStore(http, app.filesDir, app.cacheDir, feedUrls = { settings.settings.value.agency.gtfsUrls })
    val realtime = RealtimeSource(
        http,
        agency = { settings.settings.value.agency },
        apiKey = {
            settings.settings.value.let { s ->
                realtimeKeyFor(s.agency.id, s.realtimeKey, BuildConfig.REALTIME_API_KEY, BuildConfig.REALTIME_KEY_AGENCY, BuildConfig.LEGACY_REALTIME_KEY)
            }
        },
    )
    private val saintsFr = Saints(app.resources, R.raw.saints)
    private val saintsEs = Saints(app.resources, R.raw.saints_es)
    /** No English calendar: English keeps the French one, as before Spanish existed. */
    fun saints(language: String): Saints = if (language == "es") saintsEs else saintsFr
    val onThisDay = OnThisDaySource(http, File(app.filesDir, "onthisday.json"))
    val photos = PhotoSource(http, File(app.cacheDir, "wallpapers"))
    val places = DevicePlaces(app.resources)
    val setupApi = SetupApi(settings, http, { gtfs.zipFile.takeIf { gtfs.zipSource() in current.agency.gtfsUrls } }, app.resources, places)

    fun transitSelection(): Pair<Set<String>, Set<String>> =
        settings.settings.value.let { it.stops.toCollection(LinkedHashSet()) to it.routes.toSet() }

    val current: Settings get() = settings.settings.value

    /** The chosen city, or the time zone's until one is chosen. */
    fun place(s: Settings): City = s.city ?: places.here(s.locale)
}
