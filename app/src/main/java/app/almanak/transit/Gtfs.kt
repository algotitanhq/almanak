package app.almanak.transit

import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

/**
 * The slice of a GTFS feed that matters for a handful of stops: a few hundred departures instead of the
 * 200 MB stop_times.txt. This is what gets cached on disk and used offline.
 */
@Serializable
data class StopSchedule(
    val feedVersion: String,
    val feedEndDate: String,
    val stops: Map<String, String>,          // stop_id -> stop name
    val routes: Map<String, Route>,          // route_id -> route
    val departures: List<ScheduledDeparture>,
    val services: Map<String, Service>,      // service_id -> calendar
) {
    @Serializable
    data class Route(val shortName: String, val color: String)

    val feedEnd: LocalDate get() = LocalDate.parse(feedEndDate, GTFS_DATE)
}

@Serializable
data class ScheduledDeparture(
    val tripId: String,
    val routeId: String,
    val headsign: String,
    val serviceId: String,
    val stopId: String,
    val stopSequence: Int,
    /** Seconds after "noon minus 12 h" of the service day; may exceed 24 h for after-midnight trips. */
    val seconds: Int,
)

@Serializable
data class Service(
    val days: Int,              // bit 0 = Monday … bit 6 = Sunday
    val start: String,
    val end: String,
    val added: Set<String> = emptySet(),
    val removed: Set<String> = emptySet(),
) {
    fun runsOn(date: LocalDate): Boolean {
        val d = date.format(GTFS_DATE)
        if (d in removed) return false
        if (d in added) return true
        if (d < start || d > end) return false
        return days and (1 shl (date.dayOfWeek.value - 1)) != 0
    }
}

val GTFS_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

object GtfsExtractor {
    /**
     * Reads only what is needed for [stopIds] (optionally restricted to [routeIds]) from a GTFS zip.
     * Streams each file line by line; memory stays proportional to the result, not to the feed.
     */
    fun extract(zipFile: File, stopIds: Set<String>, routeIds: Set<String> = emptySet()): StopSchedule =
        ZipFile(zipFile).use { zip ->
            /** [keep] is a cheap raw-line prefilter that skips CSV parsing for lines that can't match. */
            fun rows(name: String, keep: ((String) -> Boolean)? = null, block: (Row) -> Unit) {
                val entry = zip.getEntry(name) ?: return
                BufferedReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8), 1 shl 16).use { r ->
                    val header = parseCsvLine(r.readLine()?.removePrefix("\uFEFF") ?: return)
                    val index = header.withIndex().associate { (i, h) -> h.trim() to i }
                    r.lineSequence().forEach { line ->
                        if (line.isNotBlank() && (keep == null || keep(line))) block(Row(index, parseCsvLine(line)))
                    }
                }
            }

            // stop_times first: it defines which trips we care about.
            class StopTime(val tripId: String, val stopId: String, val seq: Int, val secs: Int)
            val stopTimes = ArrayList<StopTime>()
            // ~6 M lines for a big city's feed: substring-test before splitting.
            rows("stop_times.txt", keep = { line -> stopIds.any { line.contains(it) } }) { row ->
                val stop = row["stop_id"]
                // pickup_type 1 = no boarding (a trip ending here): a bus nobody at this stop can take.
                if (stop in stopIds && row["pickup_type"] != "1") {
                    val time = row["departure_time"].ifEmpty { row["arrival_time"] }
                    if (time.isNotEmpty()) stopTimes += StopTime(row["trip_id"], stop, row["stop_sequence"].toInt(), parseGtfsTime(time))
                }
            }
            val tripIds = stopTimes.mapTo(HashSet()) { it.tripId }

            class Trip(val routeId: String, val serviceId: String, val headsign: String)
            val trips = HashMap<String, Trip>()
            rows("trips.txt") { row ->
                val id = row["trip_id"]
                if (id in tripIds && (routeIds.isEmpty() || row["route_id"] in routeIds)) {
                    trips[id] = Trip(row["route_id"], row["service_id"], row["trip_headsign"])
                }
            }

            val departures = stopTimes.mapNotNull { st ->
                val trip = trips[st.tripId] ?: return@mapNotNull null
                ScheduledDeparture(st.tripId, trip.routeId, trip.headsign, trip.serviceId, st.stopId, st.seq, st.secs)
            }.sortedBy { it.seconds }

            val routeSet = departures.mapTo(HashSet()) { it.routeId }
            val routes = HashMap<String, StopSchedule.Route>()
            rows("routes.txt") { row ->
                if (row["route_id"] in routeSet) {
                    routes[row["route_id"]] = StopSchedule.Route(row["route_short_name"], row["route_color"].ifEmpty { "FFFFFF" })
                }
            }

            val stops = HashMap<String, String>()
            rows("stops.txt") { row -> if (row["stop_id"] in stopIds) stops[row["stop_id"]] = row["stop_name"] }

            val serviceSet = departures.mapTo(HashSet()) { it.serviceId }
            val services = HashMap<String, Service>()
            rows("calendar.txt") { row ->
                val id = row["service_id"]
                if (id in serviceSet) {
                    val days = DayOfWeek.entries.foldIndexed(0) { i, acc, d ->
                        if (row[d.name.lowercase()] == "1") acc or (1 shl i) else acc
                    }
                    services[id] = Service(days, row["start_date"], row["end_date"])
                }
            }
            rows("calendar_dates.txt") { row ->
                val id = row["service_id"]
                if (id in serviceSet) {
                    val s = services[id] ?: Service(0, "99999999", "00000000")
                    services[id] = when (row["exception_type"]) {
                        "1" -> s.copy(added = s.added + row["date"])
                        "2" -> s.copy(removed = s.removed + row["date"])
                        else -> s
                    }
                }
            }

            var version = ""
            var end = "99991231"
            rows("feed_info.txt") { row ->
                version = row["feed_version"]
                end = row["feed_end_date"].ifEmpty { end }
            }

            StopSchedule(version, end, stops, routes, departures, services)
        }

    internal class Row(private val index: Map<String, Int>, private val cells: List<String>) {
        operator fun get(column: String): String = index[column]?.let { cells.getOrNull(it) }?.trim() ?: ""
    }
}

/** "25:03:10" -> 90190. */
fun parseGtfsTime(s: String): Int {
    val (h, m, sec) = s.trim().split(':').map(String::toInt)
    return h * 3600 + m * 60 + sec
}

/** RFC 4180-ish: handles quoted fields and doubled quotes, which GTFS producers do use in names. */
internal fun parseCsvLine(line: String): List<String> {
    if ('"' !in line) return line.split(',')
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
            else -> sb.append(c)
        }
        i++
    }
    out += sb.toString()
    return out
}
