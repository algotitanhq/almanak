package app.almanak.weather

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class WeatherSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val fixture = javaClass.getResource("/open-meteo-montreal.json")!!.readText()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun source() = WeatherSource(OkHttpClient(), tmp.root.resolve("w.json"), server.url("/v1/forecast"), clock = { 42L })

    @Test fun `parses a real response and caches it`() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture).build())
        val src = source()
        val w = src.refresh(45.5, -73.5)
        assertNotNull(w)
        assertEquals(42L, w!!.fetchedAtMillis)
        assertEquals(26, w.hours.size)
        assertEquals(6, w.days.size)
        assertEquals(w, src.cached(45.5, -73.5))
        assertNull("another city never sees this cache", src.cached(48.85, 2.35))
        val req = server.takeRequest()
        assertEquals("45.5", req.url.queryParameter("latitude"))
    }

    @Test fun `failure returns null and keeps the previous cache`() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture).build())
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().body("{not json").build())
        val src = source()
        val first = src.refresh(45.5, -73.5)
        assertNull(src.refresh(45.5, -73.5))
        assertNull(src.refresh(45.5, -73.5))
        assertEquals(first, src.cached(45.5, -73.5))
    }

    @Test fun `no cache on first run`() {
        assertNull(source().cached(45.5, -73.5))
    }

    @Test fun `upcoming hours skip the current hour`() = runTest {
        server.enqueue(MockResponse.Builder().body(fixture).build())
        val w = source().refresh(45.5, -73.5)!!
        val now = w.hours[0].localTime.plusMinutes(30)
        val next = w.upcomingHours(now, 3)
        assertEquals(3, next.size)
        assertTrue(next.all { it.localTime.isAfter(now) })
        assertEquals(w.hours[1], next[0])
    }

    @Test fun `wmo codes collapse to drawable skies`() {
        assertEquals(Sky.Clear, Sky.of(0))
        assertEquals(Sky.Rain, Sky.of(81))
        assertEquals(Sky.FreezingRain, Sky.of(66))
        assertEquals(Sky.Snow, Sky.of(86))
        assertEquals(Sky.Thunder, Sky.of(99))
        assertEquals(Sky.Overcast, Sky.of(1234))
    }

    @Test fun `fahrenheit rounding`() {
        assertEquals(50, 10.0.toUnit(TempUnit.Fahrenheit))
        assertEquals("-3°", (-2.6).degrees(TempUnit.Celsius))
    }
}
