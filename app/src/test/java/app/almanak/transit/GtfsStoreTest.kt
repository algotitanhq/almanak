package app.almanak.transit

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A source that answers 200 with something that is not a GTFS zip is a failed source, like an HTTP error. */
class GtfsStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val agency = MockWebServer().apply { start() }
    private val mirror = MockWebServer().apply { start() }

    @After fun close() {
        agency.close()
        mirror.close()
    }

    private fun zipBytes(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        }
    }.toByteArray()

    private val feed = zipBytes(
        mapOf(
            "stops.txt" to "stop_id,stop_name\nA,Maple\n",
            "routes.txt" to "route_id,route_short_name\n100,100\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n100,WK,t1,North\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nt1,08:00:00,08:00:00,A,1\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                "WK,1,1,1,1,1,0,0,20261001,20261031\n",
        ),
    )
    private val html = "<!DOCTYPE html><html><body>Access denied</body></html>"

    private fun zipResponse(bytes: ByteArray, etag: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/zip").addHeader("ETag", etag)
        .body(okio.Buffer().write(bytes)).build()

    private fun store(cache: File = tmp.newFolder()) = GtfsStore(
        OkHttpClient(), tmp.newFolder(), cache,
        feedUrls = { listOf(agency.url("/gtfs.zip").toString(), mirror.url("/latest.zip").toString()) },
    )

    @Test fun `a 200 HTML page falls back to the mirror`() = runTest {
        agency.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(html).build())
        mirror.enqueue(zipResponse(feed, "\"m1\""))
        val store = store()
        assertTrue(store.sync(setOf("A"), emptySet()))
        assertArrayEquals(feed, store.zipFile.readBytes())
        assertEquals(mirror.url("/latest.zip").toString(), store.zipSource())
        assertEquals(listOf(mirror.url("/latest.zip").toString(), "\"m1\""), File(store.zipFile.parentFile, "gtfs.etag").readLines())
        assertEquals(1, store.schedule.value!!.schedule.departures.size)
        assertFalse(File(store.zipFile.parentFile, "gtfs.zip.part").exists())
    }

    @Test fun `a body that is not a zip is rejected even when it claims to be one`() = runTest {
        agency.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/zip").body(html).build())
        mirror.enqueue(zipResponse(feed, "\"m1\""))
        val store = store()
        assertTrue(store.sync(setOf("A"), emptySet()))
        assertEquals(mirror.url("/latest.zip").toString(), store.zipSource())
    }

    @Test fun `garbage everywhere leaves the cached zip and ETag untouched and reports a failure`() = runTest {
        val cache = tmp.newFolder()
        agency.enqueue(zipResponse(feed, "\"a1\""))
        val store = store(cache)
        assertTrue(store.sync(setOf("A"), emptySet()))
        val etag = File(cache, "gtfs.etag").readText()

        // The weekly refresh: the agency answers a page, the mirror a zip that is not a GTFS feed.
        agency.enqueue(MockResponse.Builder().body(html).build())
        mirror.enqueue(zipResponse(zipBytes(mapOf("stops.txt" to "stop_id\nA\n")), "\"m2\""))
        // false is what makes GtfsSyncWorker answer Result.retry().
        assertFalse(store.sync(setOf("A"), emptySet()))
        assertArrayEquals(feed, store.zipFile.readBytes())
        assertEquals(etag, File(cache, "gtfs.etag").readText())
        agency.takeRequest()
        assertEquals("\"a1\"", agency.takeRequest().headers["If-None-Match"])
        assertFalse(File(cache, "gtfs.zip.part").exists())
        assertEquals(1, store.schedule.value!!.schedule.departures.size)
    }
}
