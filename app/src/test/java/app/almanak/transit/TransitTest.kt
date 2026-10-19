package app.almanak.transit

import com.google.transit.realtime.GtfsRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransitTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("America/Toronto")

    private fun zip(files: Map<String, String>): File = tmp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        }
    }

    private val feed = mapOf(
        // BOM on the first header, as some producers emit; quoted name with a comma.
        "stops.txt" to "\uFEFFstop_id,stop_name\nA,\"Maple / Elm, est\"\nB,Elsewhere\n",
        "routes.txt" to "route_id,route_short_name,route_color\n160,160,781B7D\n51,51,009EE0\n",
        "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n160,WK,t1,Est\n160,WK,t2,Est\n51,WK,t3,Ouest\n160,SAT,t4,Est\n",
        "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
            "t1,08:00:00,08:00:00,A,5\nt2,25:10:00,25:10:00,A,5\nt3,08:05:00,08:05:00,A,3\nt4,09:00:00,09:00:00,A,5\nt1,08:10:00,08:10:00,B,6\n",
        "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
            "WK,1,1,1,1,1,0,0,20261001,20261031\nSAT,0,0,0,0,0,1,0,20261001,20261031\n",
        "calendar_dates.txt" to "service_id,date,exception_type\nWK,20261012,2\nSAT,20261012,1\n",
        "feed_info.txt" to "feed_publisher_name,feed_version,feed_end_date\nSTM,v1,20261025\n",
    )

    private fun schedule(routes: Set<String> = setOf("160")) = GtfsExtractor.extract(zip(feed), setOf("A"), routes)
    private val A160 = LineKey("A", "160", "Est")

    @Test fun `extracts only the selected stop and routes`() {
        val s = schedule()
        assertEquals(listOf("t1", "t4", "t2"), s.departures.map { it.tripId })
        assertEquals("Maple / Elm, est", s.stops["A"])
        assertEquals("781B7D", s.routes["160"]!!.color)
        assertEquals(setOf("WK", "SAT"), s.services.keys)
        assertEquals(LocalDate.of(2026, 10, 25), s.feedEnd)
        assertEquals(90_600, s.departures.last().seconds)
    }

    @Test fun `no route filter keeps every route at the stop`() {
        assertEquals(4, schedule(emptySet()).departures.size)
    }

    @Test fun `a trip ending at the stop is not a departure from it`() {
        // At a terminus the arriving trip is marked pickup_type 1: nobody there can board it.
        val terminus = feed + ("stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type\n" +
            "t1,08:00:00,08:00:00,A,17,1\nt3,08:05:00,08:05:00,A,1,0\nt4,09:00:00,09:00:00,A,5,\n")
        val s = GtfsExtractor.extract(zip(terminus), setOf("A"), emptySet())
        assertEquals(listOf("t3", "t4"), s.departures.map { it.tripId })
    }

    @Test fun `next departure beyond the horizon, across nights, weekends and holidays`() {
        val s = schedule()
        // Thursday 10:00: next is Thursday's 25:10 trip, i.e. Friday 01:10.
        assertEquals(at(9, 1, 10).toInstant(), Departures.next(s, A160, at(8, 10, 0)))
        // Friday 02:00: Friday's 08:00.
        assertEquals(at(9, 8, 0).toInstant(), Departures.next(s, A160, at(9, 2, 0)))
        // Saturday 10:00: nothing Sunday; Monday the 12th is a holiday running the Saturday service (09:00).
        assertEquals(at(12, 9, 0).toInstant(), Departures.next(s, A160, at(10, 10, 0)))
        assertNull(Departures.next(s, LineKey("A", "999", "Est"), at(8, 10, 0)))
    }

    @Test fun `each direction at a stop is its own line`() {
        val up = Departures.upcoming(schedule(emptySet()), null, at(8, 7, 50))
        assertEquals(setOf(A160, LineKey("A", "51", "Ouest")), up.keys)
        assertEquals(listOf("t3"), up.getValue(LineKey("A", "51", "Ouest")).map { it.tripId })
    }

    @Test fun `calendar exceptions win over weekdays`() {
        val s = schedule()
        val holidayMonday = LocalDate.of(2026, 10, 12)
        assertFalse(s.services["WK"]!!.runsOn(holidayMonday))
        assertTrue(s.services["SAT"]!!.runsOn(holidayMonday))
        assertTrue(s.services["WK"]!!.runsOn(LocalDate.of(2026, 10, 8)))
        assertFalse(s.services["WK"]!!.runsOn(LocalDate.of(2026, 11, 2))) // after end_date
    }

    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone)

    @Test fun `scheduled departures in order, past ones dropped`() {
        // t2 (01:10 next day) is beyond the 2 h horizon.
        val up = Departures.upcoming(schedule(), null, at(8, 7, 50))[A160]!!
        assertEquals(listOf("t1"), up.map { it.tripId })
        assertEquals(at(8, 8, 0).toInstant(), up[0].at)
        assertFalse(up[0].realtime)
        assertTrue(Departures.upcoming(schedule(), null, at(8, 8, 1))[A160].orEmpty().none { it.tripId == "t1" })
    }

    @Test fun `after-midnight trips belong to the previous service day`() {
        // t2 at 25:10 on Thursday's service = Friday 01:10.
        val up = Departures.upcoming(schedule(), null, at(9, 1, 0))[A160]!!
        assertEquals("t2", up.first().tripId)
        assertEquals(at(9, 1, 10).toInstant(), up.first().at)
    }

    private fun rt(now: ZonedDateTime, vararg trips: Pair<String, RtTrip>) =
        RtSnapshot(now.toInstant().toEpochMilli(), trips.toMap())

    @Test fun `realtime delay, exact time, cancellation`() {
        val now = at(8, 7, 50)
        val s = schedule()
        val delayed = Departures.upcoming(s, rt(now, "t1" to RtTrip(false, listOf(RtStopUpdate(5, "A", null, 180, false)))), now)[A160]!!
        assertEquals(at(8, 8, 3).toInstant(), delayed[0].at)
        assertTrue(delayed[0].realtime)

        val exact = at(8, 7, 58).toEpochSecond()
        assertEquals(at(8, 7, 58).toInstant(), Departures.upcoming(s, rt(now, "t1" to RtTrip(false, listOf(RtStopUpdate(5, null, exact, null, false)))), now)[A160]!![0].at)

        val canceled = Departures.upcoming(s, rt(now, "t1" to RtTrip(true, emptyList())), now)[A160].orEmpty()
        assertTrue(canceled.none { it.tripId == "t1" })

        val skipped = Departures.upcoming(s, rt(now, "t1" to RtTrip(false, listOf(RtStopUpdate(5, "A", null, null, true)))), now)[A160].orEmpty()
        assertTrue(skipped.none { it.tripId == "t1" })
    }

    @Test fun `upstream delay propagates to our stop`() {
        val now = at(8, 7, 50)
        val up = Departures.upcoming(schedule(), rt(now, "t1" to RtTrip(false, listOf(RtStopUpdate(2, "X", null, 240, false)))), now)[A160]!!
        assertEquals(at(8, 8, 4).toInstant(), up[0].at)
        assertTrue(up[0].realtime)
    }

    @Test fun `a late bus stays visible after its scheduled time`() {
        val now = at(8, 8, 2)
        val up = Departures.upcoming(schedule(), rt(now, "t1" to RtTrip(false, listOf(RtStopUpdate(5, "A", null, 300, false)))), now)[A160]!!
        assertEquals("t1", up[0].tripId)
    }

    @Test fun `stale realtime is ignored`() {
        val now = at(8, 7, 50)
        val old = RtSnapshot(now.minusMinutes(5).toInstant().toEpochMilli(), mapOf("t1" to RtTrip(true, emptyList())))
        val up = Departures.upcoming(schedule(), old, now)[A160]!!
        assertEquals("t1", up[0].tripId)
        assertFalse(up[0].realtime)
    }

    @Test fun `parses a GTFS-RT feed, keeping only known trips`() {
        val msg = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder().setId("1").setTripUpdate(
                    GtfsRealtime.TripUpdate.newBuilder()
                        .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("t1"))
                        .addStopTimeUpdate(
                            GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(5).setStopId("A")
                                .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(1_800_000_000L).setDelay(60)),
                        ),
                ),
            )
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder().setId("2").setTripUpdate(
                    GtfsRealtime.TripUpdate.newBuilder().setTrip(
                        GtfsRealtime.TripDescriptor.newBuilder().setTripId("other")
                            .setScheduleRelationship(GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED),
                    ),
                ),
            )
            .build()
        val snap = RealtimeSource.parse(msg.toByteArray().inputStream(), setOf("t1"), now = 7L)
        assertEquals(setOf("t1"), snap.trips.keys)
        val u = snap.trips["t1"]!!.updates.single()
        assertEquals(RtStopUpdate(5, "A", 1_800_000_000L, 60, false), u)
        assertEquals(7L, snap.fetchedAtMillis)
    }

    @Test fun `csv parsing handles quotes`() {
        assertEquals(listOf("a", "b, c", "d \"q\""), parseCsvLine("a,\"b, c\",\"d \"\"q\"\"\""))
        assertEquals(90_190, parseGtfsTime("25:03:10"))
    }
}
