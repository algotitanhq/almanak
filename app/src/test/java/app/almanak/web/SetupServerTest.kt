package app.almanak.web

import app.almanak.settings.City
import app.almanak.settings.GuestWifi
import app.almanak.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SetupServerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `parses a PUT with query, headers and body`() {
        val body = """{"theme":"Paper"}"""
        val raw = "PUT /api/settings?t=abc%20d&x=1 HTTP/1.1\r\nHost: tv\r\nX-Almanak-Token: tok\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val r = SetupServer.parse(raw.byteInputStream())!!
        assertEquals("PUT", r.method)
        assertEquals("/api/settings", r.path)
        assertEquals("abc d", r.query["t"])
        assertEquals("tok", r.headers["x-almanak-token"])
        assertEquals(body, r.body)
    }

    @Test fun `rejects garbage and oversized bodies`() {
        assertNull(SetupServer.parse("\r\n".byteInputStream()))
        assertNull(SetupServer.parse("GET / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".byteInputStream()))
    }

    @Test fun `stop search by code and by name, skipping stations`() {
        val zip = tmp.newFile("g.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("stops.txt"))
            z.write("stop_id,stop_code,stop_name,location_type\nSTATION_1,,Station Maple,1\n10001,10001,Maple / Elm,0\n10003,10003,Maple / Oak,0\n99,99,\"Rue, avec virgule\",\n".toByteArray())
            z.closeEntry()
        }
        assertEquals(listOf("10001"), searchStops(zip, "10001").map { it.id })
        assertEquals(listOf("10001", "10003"), searchStops(zip, "maple").map { it.id })
        assertEquals("Rue, avec virgule", searchStops(zip, "virgule").single().name)
        assertEquals(emptyList<SetupApi.Stop>(), searchStops(zip, "b"))
    }

    @Test fun `secrets never reach the page, and an empty field keeps them`() {
        val current = Settings(realtimeKey = "stm-secret", guestWifi = GuestWifi("Invités", "wifi-secret"))
        val page = SetupApi.redacted(current)
        assertFalse("stm-secret" in page)
        assertFalse("wifi-secret" in page)
        assertTrue("\"guestWifiPasswordSet\":true" in page)
        // No city chosen: the page shows the time zone's place, and only then.
        assertTrue("\"approxCity\":{\"name\":\"Paris\"" in SetupApi.redacted(current, City("Paris", 48.87, 2.33)))
        assertFalse("approxCity" in page)
        // The page sends back what it got (empty secrets): both are kept.
        val sent = current.copy(realtimeKey = "", guestWifi = current.guestWifi.copy(password = ""))
        assertEquals(current, SetupApi.merge(current, sent))
        // A save from the phone is the end of the first-run welcome.
        assertTrue(SetupApi.merge(current.copy(onboarded = false), sent.copy(onboarded = false)).onboarded)
        // A new password replaces the old one.
        val changed = sent.copy(guestWifi = sent.guestWifi.copy(password = "new"))
        assertEquals("new", SetupApi.merge(current, changed).guestWifi.password)
    }
}
