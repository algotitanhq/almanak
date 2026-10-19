package app.almanak.transit

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

data class Arrival(
    val tripId: String,
    val stopId: String,
    val routeId: String,
    val headsign: String,
    val at: Instant,
    val realtime: Boolean,
)

/** One block on screen: a route in one direction at one stop (a stop served both ways makes two). */
data class LineKey(val stopId: String, val routeId: String, val headsign: String)

val Arrival.line get() = LineKey(stopId, routeId, headsign)
val ScheduledDeparture.line get() = LineKey(stopId, routeId, headsign)

object Departures {
    /** Realtime older than this is ignored: better an honest schedule than a stale prediction. */
    val RT_MAX_AGE: Duration = Duration.ofMinutes(3)

    /**
     * Next arrivals per stop, route and direction, merging the static schedule with realtime when available.
     * Pure: no clock, no IO.
     */
    fun upcoming(
        schedule: StopSchedule,
        rt: RtSnapshot?,
        now: ZonedDateTime,
        perLine: Int = 3,
        horizon: Duration = Duration.ofMinutes(120),
    ): Map<LineKey, List<Arrival>> {
        val live = rt?.takeIf { Duration.ofMillis(now.toInstant().toEpochMilli() - it.fetchedAtMillis) <= RT_MAX_AGE }
        val zone = now.zone
        val from = now.toInstant()
        val to = from + horizon
        // Late buses can still be ahead of us even if their scheduled time has passed.
        val lookBack = from - Duration.ofMinutes(30)
        val today = now.toLocalDate()

        val result = HashMap<LineKey, MutableList<Arrival>>()
        for (serviceDay in listOf(today.minusDays(1), today)) {
            // GTFS times count from "noon minus 12 h" so DST days stay correct.
            val base = serviceDay.atTime(12, 0).atZone(zone).minusHours(12).toInstant()
            for (d in schedule.departures) {
                val service = schedule.services[d.serviceId] ?: continue
                val scheduled = base.plusSeconds(d.seconds.toLong())
                if (scheduled < lookBack || scheduled > to) continue
                if (!service.runsOn(serviceDay)) continue
                val (at, isLive) = applyRealtime(d, scheduled, live) ?: continue
                if (at < from || at > to) continue
                result.getOrPut(d.line) { ArrayList() } += Arrival(d.tripId, d.stopId, d.routeId, d.headsign, at, isLive)
            }
        }
        return result.mapValues { (_, v) -> v.sortedBy { it.at }.take(perLine) }
    }

    /**
     * The next scheduled departure of [line] after [now], looking up to a week ahead: for a line with nothing in
     * [upcoming]'s horizon (a rush-hour-only route at night). Timetable only: realtime doesn't reach that far.
     */
    fun next(schedule: StopSchedule, line: LineKey, now: ZonedDateTime, days: Long = 7): Instant? {
        val zone = now.zone
        val from = now.toInstant()
        val today = now.toLocalDate()
        val ofLine = schedule.departures.filter { it.line == line }
        // Yesterday too: its after-midnight trips are still ahead of us. Service days overlap past midnight, so
        // the earliest across days wins, not the first day with a match.
        return (-1L until days).mapNotNull { offset ->
            val serviceDay = today.plusDays(offset)
            val base = serviceDay.atTime(12, 0).atZone(zone).minusHours(12).toInstant()
            // departures are sorted by time of day: the first that runs that day and is still ahead.
            ofLine.firstOrNull { schedule.services[it.serviceId]?.runsOn(serviceDay) == true && base.plusSeconds(it.seconds.toLong()) > from }
                ?.let { base.plusSeconds(it.seconds.toLong()) }
        }.minOrNull()
    }

    /** Returns the effective time and whether it is realtime, or null if the trip/stop is cancelled. */
    internal fun applyRealtime(d: ScheduledDeparture, scheduled: Instant, rt: RtSnapshot?): Pair<Instant, Boolean>? {
        val trip = rt?.trips?.get(d.tripId) ?: return scheduled to false
        if (trip.canceled) return null
        val exact = trip.updates.firstOrNull { it.stopSequence == d.stopSequence || (it.stopSequence == null && it.stopId == d.stopId) }
        if (exact != null) {
            if (exact.skipped) return null
            exact.timeEpochSec?.let { return Instant.ofEpochSecond(it) to true }
            exact.delaySec?.let { return scheduled.plusSeconds(it.toLong()) to true }
        }
        // GTFS-RT: a delay propagates downstream until the next update.
        val upstream = trip.updates.filter { (it.stopSequence ?: Int.MAX_VALUE) < d.stopSequence && it.delaySec != null }
            .maxByOrNull { it.stopSequence!! }
        return upstream?.let { scheduled.plusSeconds(it.delaySec!!.toLong()) to true } ?: (scheduled to false)
    }
}
