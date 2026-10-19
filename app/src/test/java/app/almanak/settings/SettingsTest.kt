package app.almanak.settings

import app.almanak.ui.theme.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.Locale
import org.junit.Test

class SettingsTest {
    @Test fun `defaults round-trip through JSON`() {
        val s = Settings()
        assertEquals(s, SettingsStore.fromJson(SettingsStore.toJson(s)))
    }

    @Test fun `normalization repairs impossible layouts and junk input`() {
        val messy = Settings(
            language = "de",
            stops = listOf(" 10001", "10001", "", "1", "2", "3", "4", "5", "6"),
            routes = listOf(" 160 ", ""),
            modules = listOf(ModuleSetting(Module.Bus, zone = Zone.UnderClock)), // bus can't sit under the date
        ).normalized()
        assertEquals("en", messy.language)
        assertEquals(listOf("10001", "1", "2", "3", "4", "5"), messy.stops)
        assertEquals(listOf("160"), messy.routes)
        assertEquals(Zone.Right, messy.module(Module.Bus).zone)
        // Bus first (as given), then the missing ones in default order.
        assertEquals(listOf(Module.Bus, Module.Almanac, Module.Weather), messy.modules.map { it.module })
    }

    @Test fun `older or partial files keep working`() {
        val s = SettingsStore.fromStoredJson("""{"theme":"Paper","someFutureField":42}""")
        assertEquals(ThemeId.Paper, s.theme)
        assertEquals(Settings().stops, s.stops)
    }

    /** settings.json exactly as main @ 20ced4b writes it (encodeDefaults): Montréal and the STM spelled out. */
    private val schema1File = """{"theme":"Nocturne","language":"fr","unit":"Celsius",
        "city":{"name":"Montréal","lat":45.5019,"lon":-73.5674},"stops":["10001","10003"],"routes":["160"],
        "wallpaper":"Sky","wallpaperUrl":"",
        "modules":[{"module":"Almanac","enabled":true,"zone":"UnderClock","size":"Medium"},{"module":"Bus","enabled":true,"zone":"Right","size":"Medium"},{"module":"Weather","enabled":true,"zone":"Bottom","size":"Medium"}],
        "agency":{"id":"stm","name":"STM · Montréal","gtfsUrls":["https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip","https://files.mobilitydatabase.org/mdb-2126/latest.zip"],"realtimeUrl":"https://api.stm.info/pub/od/gtfs-rt/ic/v2/tripUpdates","keyName":"apikey","keyIn":"Header","keyHelp":"https://portail.developpeurs.stm.info/apihub/"},
        "realtimeKey":"k","guestWifi":{"ssid":"","password":"","security":"WPA","hidden":false},"onboarded":true}"""

    @Test fun `an upgraded install keeps Montreal and the STM`() {
        val s = SettingsStore.fromStoredJson(schema1File)
        assertEquals(City("Montréal", 45.5019, -73.5674), s.city)
        assertEquals(TransitAgency.STM, s.agency)
        assertEquals(listOf("10001", "10003"), s.stops)
        assertEquals("k", s.realtimeKey)
        assertEquals(true, s.onboarded)
        // Once rewritten it carries the schema, and still says Montréal.
        val again = SettingsStore.fromStoredJson(SettingsStore.toJson(s))
        assertEquals(s, again)
        assertEquals(Settings.SCHEMA, again.schema)
    }

    @Test fun `a schema 1 file without city or network gets the old defaults back`() {
        val s = SettingsStore.fromStoredJson("""{"theme":"Paper","stops":["10001"]}""")
        assertEquals("Montréal", s.city?.name)
        assertEquals(TransitAgency.STM, s.agency)
    }

    @Test fun `the oldest files (no network, no onboarded, stmApiKey) keep Montreal, the STM and the key`() {
        // The key set of the file on storagebits's Shield, written by the 2026-10-13 build (values made up).
        val s = SettingsStore.fromStoredJson("""{"theme":"Nocturne","language":"es","unit":"Celsius",
            "city":{"name":"Montréal","lat":45.5019,"lon":-73.5674},"stops":["1","2"],"routes":[],"wallpaper":"Sky",
            "wallpaperUrl":"","modules":[],"guestWifi":{"ssid":"","password":"","security":"WPA","hidden":false},"stmApiKey":"k"}""")
        assertEquals("Montréal", s.city?.name)
        assertEquals(TransitAgency.STM, s.agency)
        assertEquals("k", s.realtimeKey)
        assertEquals(true, s.onboarded)
    }

    @Test fun `a fresh install has no city and no network`() {
        assertNull(SettingsSerializer.defaultValue.city)
        assertEquals(TransitAgency.NONE, SettingsSerializer.defaultValue.agency)
        // After the first save (TV settings visited, nothing chosen) it stays that way.
        val saved = SettingsStore.fromStoredJson(SettingsStore.toJson(SettingsSerializer.defaultValue.copy(onboarded = true)))
        assertNull(saved.city)
        assertEquals(TransitAgency.NONE, saved.agency)
        assertTrue("Montr" !in SettingsStore.toJson(Settings()))
        assertTrue("stm" !in SettingsStore.toJson(Settings()).lowercase())
    }

    @Test fun `the language follows the device region`() {
        assertEquals(Locale.CANADA_FRENCH, Settings.localeFor("fr", "CA"))
        assertEquals(Locale.FRANCE, Settings.localeFor("fr", "FR"))
        assertEquals(Locale("fr", "BE"), Settings.localeFor("fr", "BE"))
        // No French data for the US: the language's home, not a made-up fr-US.
        assertEquals(Locale.FRANCE, Settings.localeFor("fr", "US"))
        assertEquals(Locale.FRANCE, Settings.localeFor("fr", ""))
        assertEquals(Locale("es", "MX"), Settings.localeFor("es", "MX"))
        assertEquals(Locale("es", "ES"), Settings.localeFor("es", "FR"))
        assertEquals(Locale.US, Settings.localeFor("en", ""))
        assertEquals(Locale.UK, Settings.localeFor("en", "GB"))
    }

    @Test fun `guest wifi payload escapes what the WIFI format reserves`() {
        assertEquals("WIFI:T:WPA;S:Chez nous;P:abc123;;", GuestWifi("Chez nous", "abc123").qrPayload())
        assertEquals("""WIFI:T:WPA;S:a\;b\,c;P:p\:q\"r\\s;H:true;;""", GuestWifi("a;b,c", "p:q\"r\\s", hidden = true).qrPayload())
        // An open network carries no password, even a leftover one.
        assertEquals("WIFI:T:nopass;S:Invités;;", GuestWifi("Invités", "old", WifiSecurity.None).qrPayload())
    }

    @Test fun `guest wifi without a name is dropped, password included`() {
        assertEquals(GuestWifi(), Settings(guestWifi = GuestWifi(" ", "secret")).normalized().guestWifi)
    }

    @Test fun `a fresh install starts unconfigured, an existing file counts as set up`() {
        assertEquals(false, SettingsSerializer.defaultValue.onboarded)
        assertEquals(emptyList<String>(), SettingsSerializer.defaultValue.stops)
        assertEquals(true, SettingsStore.fromStoredJson("""{"theme":"Paper","stops":["10001"]}""").onboarded)
    }
}
