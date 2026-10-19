package app.almanak.transit

import app.almanak.settings.Settings
import app.almanak.settings.SettingsStore
import app.almanak.settings.TransitAgency
import app.almanak.settings.realtimeKeyFor
import app.almanak.web.SetupApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AgencyTest {
    @Test fun `settings from before networks keep their STM key`() {
        val s = SettingsStore.fromStoredJson("""{"stops":["10001"],"stmApiKey":"k"}""")
        assertEquals("k", s.realtimeKey)
        assertEquals(TransitAgency.STM, s.agency)
    }

    @Test fun `the build-time key goes to its own network only, and stm_apiKey still means STM`() {
        // Typed on the phone: always wins.
        assertEquals("user", realtimeKeyFor("octranspo", "user", "built", "octranspo", "legacy"))
        // realtime.apiKey + realtime.keyAgency: for that network, never another one.
        assertEquals("built", realtimeKeyFor("octranspo", "", "built", "octranspo", ""))
        assertEquals("", realtimeKeyFor("ttc", "", "built", "octranspo", ""))
        assertEquals("", realtimeKeyFor("", "", "built", "", ""))
        // stm.apiKey / STM_API_KEY from older local.properties or CI: still the STM's key, and only the STM's.
        assertEquals("legacy", realtimeKeyFor("stm", "", "", "", "legacy"))
        assertEquals("", realtimeKeyFor("octranspo", "", "", "", "legacy"))
        assertEquals("", realtimeKeyFor("", "", "", "", "legacy"))
    }

    @Test fun `no network until one is chosen, and it survives a round trip`() {
        assertEquals(TransitAgency.NONE, Settings().agency)
        assertEquals(TransitAgency.NONE, TransitAgency("", "x", listOf("https://a.example/g.zip")).normalized())
        assertEquals(TransitAgency.NONE, SettingsStore.fromJson(SettingsStore.toJson(Settings())).agency)
    }

    @Test fun `presets come from the app, custom URLs are cleaned`() {
        val tampered = TransitAgency.STM.copy(gtfsUrls = listOf("https://evil.example/x.zip"))
        assertEquals(TransitAgency.STM, tampered.normalized())
        val custom = TransitAgency("custom", " ", listOf(" https://a.example/g.zip ", "file:///etc/passwd", ""), realtimeUrl = "ftp://x").normalized()
        assertEquals(listOf("https://a.example/g.zip"), custom.gtfsUrls)
        assertEquals("", custom.realtimeUrl)
        assertEquals("GTFS", custom.name)
    }

    @Test fun `a key is not carried over to another network`() {
        val current = Settings(agency = TransitAgency.STM, realtimeKey = "stm-key")
        assertEquals("stm-key", SetupApi.merge(current, current.copy(realtimeKey = "")).realtimeKey)
        assertEquals("", SetupApi.merge(current, current.copy(agency = TransitAgency.OC_TRANSPO, realtimeKey = "")).realtimeKey)
    }

    @Test fun `the key goes where the network wants it, and no feed means no request`() = runTest {
        val server = MockWebServer().apply { start() }
        repeat(2) { server.enqueue(MockResponse.Builder().body("").build()) }
        val url = server.url("/rt").toString()
        val inHeader = TransitAgency("custom", "H", emptyList(), realtimeUrl = url, keyName = "apikey")
        RealtimeSource(OkHttpClient(), { inHeader }, { "k1" }).fetch(emptySet())
        assertEquals("k1", server.takeRequest().headers["apikey"])
        val inQuery = inHeader.copy(keyIn = app.almanak.settings.KeyIn.Query)
        RealtimeSource(OkHttpClient(), { inQuery }, { "k2" }).fetch(emptySet())
        assertEquals("k2", server.takeRequest().url.queryParameter("apikey"))
        assertEquals(RtResult.NoKey, RealtimeSource(OkHttpClient(), { inHeader }, { "" }).fetch(emptySet()))
        assertEquals(RtResult.NoFeed, RealtimeSource(OkHttpClient(), { TransitAgency.RTC }, { "" }).fetch(emptySet()))
        assertEquals(2, server.requestCount)
        server.close()
    }
}
