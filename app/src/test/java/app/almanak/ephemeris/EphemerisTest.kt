package app.almanak.ephemeris

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class EphemerisTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mtl = ZoneId.of("America/Toronto")

    /** Compares full date-times: a time-of-day-only check once hid an off-by-one-day bug. */
    private fun near(expected: LocalTime, actual: ZonedDateTime?, minutes: Long = 2, date: LocalDate) {
        assertEquals(date, actual!!.toLocalDate())
        val diff = abs(Duration.between(expected, actual.toLocalTime()).toMinutes())
        assertTrue("expected ~$expected, got ${actual.toLocalTime()}", diff <= minutes)
    }

    @Test fun `sunrise and sunset match Open-Meteo for Montreal`() {
        // Open-Meteo for 45.49,-73.56 on 2026-10-08: 07:01 / 18:21 (test fixture).
        val t = Sun.times(LocalDate.of(2026, 10, 8), 45.4922, -73.5610, mtl)
        val d = LocalDate.of(2026, 10, 8)
        near(LocalTime.of(7, 1), t.sunrise, minutes = 1, date = d)
        near(LocalTime.of(18, 21), t.sunset, minutes = 1, date = d)
        assertEquals(11L, t.daylight!!.toHours())
    }

    @Test fun `summer solstice in standard time zones still works`() {
        val t = Sun.times(LocalDate.of(2026, 6, 21), 45.5019, -73.5674, mtl)
        val d = LocalDate.of(2026, 6, 21)
        near(LocalTime.of(5, 7), t.sunrise, minutes = 5, date = d)
        near(LocalTime.of(20, 47), t.sunset, minutes = 5, date = d)
    }

    @Test fun `polar night has no sunrise`() {
        val t = Sun.times(LocalDate.of(2026, 12, 21), 80.0, 15.0, ZoneId.of("UTC"))
        assertNull(t.sunrise)
        assertNull(t.daylight)
    }

    @Test fun `moon phase around October 2026`() {
        val m = Moon.phase(ZonedDateTime.of(2026, 10, 8, 21, 0, 0, 0, mtl).toInstant(), mtl)
        // New moon 2026-10-10, full moon 2026-10-26 (local dates).
        assertEquals(LocalDate.of(2026, 10, 10), m.nextNew)
        assertEquals(LocalDate.of(2026, 10, 26), m.nextFull)
        assertEquals(MoonPhaseName.WaningCrescent, m.name)
        assertTrue(m.illumination < 0.15)
    }

    @Test fun `true phases match the 2026 eclipses`() {
        fun near(expected: String, actual: java.time.Instant) {
            val diff = abs(Duration.between(java.time.Instant.parse(expected), actual).toMinutes())
            assertTrue("expected ~$expected, got $actual", diff <= 30)
        }
        fun k(year: Double) = Math.floor((year - 2000.0) * 12.3685).toInt()
        // Annular solar eclipse 2026-02-17 and total solar eclipse 2026-08-12 (new moons);
        // total lunar eclipse 2026-03-03 and partial lunar eclipse 2026-08-28 (full moons).
        val newMoons = (k(2026.0)..k(2027.0)).map { Moon.jdInstant(Moon.trueJde(it.toDouble(), full = false)) }
        val fullMoons = (k(2026.0)..k(2027.0)).map { Moon.jdInstant(Moon.trueJde(it + 0.5, full = true)) }
        near("2026-02-17T12:01:00Z", newMoons.minBy { abs(Duration.between(it, java.time.Instant.parse("2026-02-17T12:00:00Z")).toMinutes()) })
        near("2026-08-12T17:37:00Z", newMoons.minBy { abs(Duration.between(it, java.time.Instant.parse("2026-08-12T17:00:00Z")).toMinutes()) })
        near("2026-03-03T11:38:00Z", fullMoons.minBy { abs(Duration.between(it, java.time.Instant.parse("2026-03-03T11:00:00Z")).toMinutes()) })
        near("2026-08-28T04:18:00Z", fullMoons.minBy { abs(Duration.between(it, java.time.Instant.parse("2026-08-28T04:00:00Z")).toMinutes()) })
    }

    @Test fun `saints for a day`() {
        val saints = Saints { File("src/main/res/raw/saints.json").readText() }
        assertEquals(listOf("Ste Pélagie la Pénitente"), saints.on(LocalDate.of(2026, 10, 8)))
        assertEquals("Noël", saints.on(LocalDate.of(2026, 12, 25)).first())
        assertTrue(saints.on(LocalDate.of(2028, 2, 29)).isNotEmpty())
    }

    @Test fun `spanish santoral covers every day, main saint first`() {
        val santoral = Saints { File("src/main/res/raw/saints_es.json").readText() }
        assertEquals("San José de Nazaret", santoral.on(LocalDate.of(2026, 3, 19)).first())
        assertEquals("San Francisco de Asís", santoral.on(LocalDate.of(2026, 10, 4)).first())
        assertEquals("Natividad del Señor", santoral.on(LocalDate.of(2026, 12, 25)).first())
        val year = generateSequence(LocalDate.of(2028, 1, 1)) { it.plusDays(1) }.takeWhile { it.year == 2028 }
        assertTrue(year.all { santoral.on(it).isNotEmpty() })
    }

    @Test fun `on this day drops notes about the feed's picture`() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body("""{"selected":[
            {"text":"Nace Tita Merello (en la imagen), actriz argentina.","year":1904},
            {"text":"The Kursk sinks (aftermath pictured).","year":2000},
            {"text":"Fondation de Montréal (photo) par Maisonneuve.","year":1642},
            {"text":"Se funda la ciudad (Palencia) de 3.9 in (10 cm).","year":1500}]}""").build())
        val src = OnThisDaySource(OkHttpClient(), tmp.newFile(), server.url("/feed").toString())
        assertEquals(listOf(
            "Se funda la ciudad (Palencia) de 3.9 in (10 cm).",
            "Fondation de Montréal par Maisonneuve.",
            "Nace Tita Merello, actriz argentina.",
            "The Kursk sinks.",
        ), src.fetch(LocalDate.of(2026, 10, 11), "es")!!.map { it.text })
        server.close()
    }

    @Test fun `on this day keeps short entries, sorted, and caches per day and language`() = runTest {
        val server = MockWebServer().apply { start() }
        val long = "x".repeat(OnThisDaySource.MAX_CHARS + 1)
        server.enqueue(MockResponse.Builder().body("""{"selected":[
            {"text":"la Pologne interdit Solidarność.","year":1982},
            {"text":"$long","year":1900},
            {"text":"début du grand incendie de Chicago.","year":1871},
            {"text":"sans année"}]}""").build())
        val src = OnThisDaySource(OkHttpClient(), tmp.newFile(), server.url("/feed").toString())
        val day = LocalDate.of(2026, 10, 8)
        val items = src.fetch(day, "fr")!!
        assertEquals(listOf(1871, 1982), items.map { it.year })
        assertEquals("Début du grand incendie de Chicago.", items[0].text)
        assertEquals("/feed/fr/onthisday/selected/10/08", server.takeRequest().url.encodedPath)
        // Second call is served from the cache: no new request.
        assertEquals(items, src.fetch(day, "fr"))
        assertEquals(1, server.requestCount)
        assertNull(src.cached(day.plusDays(1), "fr"))
        // The cache is per language: switching the UI language refetches.
        assertNull(src.cached(day, "es"))
        server.close()
    }
}
