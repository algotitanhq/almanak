package app.almanak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.almanak.clock.minuteTicks
import app.almanak.ephemeris.HistoryItem
import app.almanak.ephemeris.EphemerisState
import app.almanak.settings.City
import app.almanak.transit.Arrival
import app.almanak.transit.Departures
import app.almanak.transit.LineKey
import app.almanak.transit.RtResult
import app.almanak.transit.RtSnapshot
import app.almanak.transit.RtStopUpdate
import app.almanak.transit.RtTrip
import app.almanak.transit.StopSchedule
import app.almanak.transit.line
import app.almanak.wallpaper.Backdrop
import app.almanak.wallpaper.PhotoRef
import app.almanak.wallpaper.SkyPhase
import app.almanak.wallpaper.WallpaperMode
import app.almanak.wallpaper.skyPhase
import app.almanak.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Last known weather; [live] is false when the latest refresh failed and [data] comes from the cache. */
data class WeatherState(val data: Weather?, val live: Boolean)

enum class RtStatus { Starting, Live, NoFeed, NoKey, Unauthorized, Offline }

/** [later]: when [arrivals] is empty, the next scheduled departure beyond the 2 h horizon (null if none this week). */
data class BusLine(
    val stopId: String,
    val stopName: String,
    val route: StopSchedule.Route?,
    val headsign: String,
    val arrivals: List<Arrival>,
    val later: java.time.Instant? = null,
)

/** [lines] is empty until the static schedule has been extracted once ([hasSchedule]), or with no stop set ([noStops]). */
data class BusState(val lines: List<BusLine>, val status: RtStatus, val hasSchedule: Boolean, val noStops: Boolean = false)

/** Debug-only overrides for captures; null/false in normal use. */
data class DebugOverrides(
    val offline: Boolean = false,
    val fakeRt: Boolean = false,
    val wallpaper: WallpaperMode? = null,
    val wallpaperUrl: String? = null,
    val sky: SkyPhase? = null,
    /** Replace today's "on this day" items and saints (text-fit captures, ALG-221). */
    val history: List<HistoryItem>? = null,
    val saints: List<String>? = null,
)

/**
 * All live data for the ambient screen. Every loop runs only while the UI collects (`WhileSubscribed`), and
 * restarts when the settings it depends on change (city → weather & sun, stops → bus, wallpaper → backdrop, language → on this day).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmbientViewModel(private val graph: AppGraph, private val debug: DebugOverrides = DebugOverrides()) : ViewModel() {

    private val whileVisible = SharingStarted.WhileSubscribed(5_000)
    private val settings = graph.settings.settings
    private val city: Flow<City> = settings.map { graph.place(it) }.distinctUntilChanged()

    val clock: StateFlow<ZonedDateTime> = minuteTicks().stateIn(viewModelScope, whileVisible, ZonedDateTime.now())

    val weather: StateFlow<WeatherState> = city.flatMapLatest { weatherLoop(it) }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, whileVisible, WeatherState(null, live = false))

    /** Every 15 min, backing off on failure; the cache keeps the last good value (for this city only). */
    private fun weatherLoop(c: City) = flow {
        var last = graph.weather.cached(c.lat, c.lon)
        emit(WeatherState(last, live = false))
        var backoff = 1.minutes
        while (true) {
            val fresh = if (debug.offline) null else graph.weather.refresh(c.lat, c.lon)
            if (fresh != null) {
                last = fresh
                backoff = 1.minutes
                emit(WeatherState(fresh, live = true))
                delay(15.minutes)
            } else {
                emit(WeatherState(last, live = false))
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15.minutes)
            }
        }
    }

    /** Today's "on this day" items in the UI language: fetched once a day, retried every 15 min while missing. */
    private val history = settings.map { it.language }.distinctUntilChanged().flatMapLatest { lang ->
        flow {
            while (true) {
                val day = LocalDate.now()
                emit(graph.onThisDay.cached(day, lang).orEmpty())
                val fresh = graph.onThisDay.fetch(day, lang)
                if (fresh != null) emit(fresh)
                val untilTomorrow = Duration.between(LocalDateTime.now(), day.plusDays(1).atTime(0, 0, 30)).toMillis().milliseconds
                delay(if (fresh != null) untilTomorrow else 15.minutes)
            }
        }
    }.flowOn(Dispatchers.IO)

    val ephemeris: StateFlow<EphemerisState?> = combine(clock, history, city, settings.map { it.language }.distinctUntilChanged()) { now, h, c, lang ->
        EphemerisState.compute(now, c.lat, c.lon, graph.saints(lang), debug.history ?: h)
            .let { e -> debug.saints?.let { e.copy(saints = it) } ?: e }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, whileVisible, null)

    /** GTFS-RT every 30 s; a rejected key is retried every 10 min, a network error with backoff. */
    private val realtime = flow<Pair<RtSnapshot?, RtStatus>> {
        emit(null to RtStatus.Starting)
        var backoff = 30.seconds
        while (true) {
            val tripIds = graph.gtfs.schedule.value?.schedule?.departures?.mapTo(HashSet()) { it.tripId }.orEmpty()
            val wait = when (val r = graph.realtime.fetch(tripIds)) {
                is RtResult.Ok -> { emit(r.snapshot to RtStatus.Live); backoff = 30.seconds; 30.seconds }
                RtResult.NoFeed -> { emit(null to RtStatus.NoFeed); 10.minutes }
                RtResult.NoKey -> { emit(null to RtStatus.NoKey); 10.minutes }
                RtResult.Unauthorized -> { emit(null to RtStatus.Unauthorized); 10.minutes }
                is RtResult.Failed -> { emit(null to RtStatus.Offline); backoff.also { backoff = (backoff * 2).coerceAtMost(5.minutes) } }
            }
            delay(wait)
        }
    }

    val bus: StateFlow<BusState> = combine(clock, graph.gtfs.schedule, realtime, settings) { _, cached, (snapshot, status), s ->
        if (s.stops.isEmpty()) return@combine BusState(emptyList(), status, hasSchedule = true, noStops = true)
        val schedule = cached?.schedule ?: return@combine BusState(emptyList(), status, hasSchedule = false)
        val now = ZonedDateTime.now()
        val rt = if (debug.fakeRt) fakeSnapshot(schedule, now) else snapshot
        val upcoming = Departures.upcoming(schedule, rt, now)
        // Stops in the user's order; within a stop, routes in the filter's order, then by direction. Only stops
        // present in the extracted schedule (a just-added stop appears after its extraction).
        val keys = schedule.departures.mapTo(LinkedHashSet()) { it.line }
        val lines = s.stops.filter { it in schedule.stops }.flatMap { stop ->
            keys.filter { it.stopId == stop }
                .sortedWith(compareBy<LineKey>({ s.routes.indexOf(it.routeId).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.routeId }, { it.headsign }))
                .map { k ->
                    val arrivals = upcoming[k].orEmpty()
                    val later = if (arrivals.isEmpty()) Departures.next(schedule, k, now) else null
                    BusLine(stop, schedule.stops[stop].orEmpty(), schedule.routes[k.routeId], k.headsign, arrivals, later)
                }
        }
        BusState(lines, if (debug.fakeRt) RtStatus.Live else status, hasSchedule = true)
    }.stateIn(viewModelScope, whileVisible, BusState(emptyList(), RtStatus.Starting, hasSchedule = graph.gtfs.schedule.value != null))

    /** The background: theme ground, sky following the real sun, or rotating photos (10 min, 4 s crossfade). */
    val backdrop: StateFlow<Backdrop> = settings
        .map { (debug.wallpaper ?: it.wallpaper) to (debug.wallpaperUrl ?: it.wallpaperUrl) }
        .distinctUntilChanged()
        .flatMapLatest { (mode, url) ->
            when (mode) {
                WallpaperMode.None -> flowOf(Backdrop.Plain)
                WallpaperMode.Sky -> skyBackdrop()
                WallpaperMode.Potd, WallpaperMode.Url -> photoBackdrop(mode, url)
            }
        }
        .stateIn(viewModelScope, whileVisible, Backdrop.Sky(SkyPhase.Night))

    private fun skyBackdrop() = ephemeris.map { e ->
        Backdrop.Sky(debug.sky ?: e?.let { skyPhase(ZonedDateTime.now(), it.sun) } ?: SkyPhase.Night)
    }.distinctUntilChanged()

    private fun photoBackdrop(mode: WallpaperMode, url: String) = flow {
        var index = 0
        var shown = false
        while (true) {
            val refs = if (mode == WallpaperMode.Potd) graph.photos.potd(LocalDate.now())
            else listOfNotNull(url.takeIf { it.isNotBlank() }?.let { PhotoRef(it, null) })
            val photo = refs.getOrNull(index % refs.size.coerceAtLeast(1))
                ?.let { graph.photos.load(it, fresh = mode == WallpaperMode.Url) }
            when {
                photo != null -> { emit(photo); shown = true; index++ }
                !shown -> emit(skyNow()) // offline on first run: the sky, not a blank
            }
            delay(if (photo != null) 10.minutes else 2.minutes)
        }
    }

    private fun skyNow(): Backdrop = Backdrop.Sky(ephemeris.value?.let { skyPhase(ZonedDateTime.now(), it.sun) } ?: SkyPhase.Night)

    private fun fakeSnapshot(schedule: StopSchedule, now: ZonedDateTime): RtSnapshot {
        val nextTrips = Departures.upcoming(schedule, null, now, perLine = 1).values.flatten().mapTo(HashSet()) { it.tripId }
        val trips = schedule.departures.filter { it.tripId in nextTrips }
            .associate { d -> d.tripId to RtTrip(false, listOf(RtStopUpdate(d.stopSequence, d.stopId, null, 120, false))) }
        return RtSnapshot(System.currentTimeMillis(), trips)
    }

    companion object {
        fun factory(graph: AppGraph, debug: DebugOverrides = DebugOverrides()): ViewModelProvider.Factory = viewModelFactory {
            initializer { AmbientViewModel(graph, debug) }
        }
    }
}
