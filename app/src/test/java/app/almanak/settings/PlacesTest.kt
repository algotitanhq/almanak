package app.almanak.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class PlacesTest {
    /** The bundled table itself (tests run from the module directory). */
    private val places = Places(Places.parse(File("src/main/res/raw/tzdata_zone.tab").readText()))

    @Test fun `zone tab coordinates, with and without seconds`() {
        val (lat, lon) = Places.coordinates("+4852+00220")!!
        assertEquals(48.8667, lat, 1e-3)
        assertEquals(2.3333, lon, 1e-3)
        val (lat2, lon2) = Places.coordinates("+404251-0740023")!!
        assertEquals(40.7142, lat2, 1e-3)
        assertEquals(-74.0064, lon2, 1e-3)
        val (lat3, _) = Places.coordinates("-3436-05827")!!
        assertEquals(-34.6, lat3, 1e-3)
    }

    @Test fun `the device zone gives its city`() {
        val paris = places.approximate(listOf("Europe/Paris"), "FR", Locale.FRANCE)
        assertEquals("Paris", paris.name)
        assertEquals(48.87, paris.lat, 0.01)
        assertEquals(2.33, paris.lon, 0.01)
        val vancouver = places.approximate(listOf("America/Vancouver"), "CA", Locale.CANADA)
        assertEquals("Vancouver", vancouver.name)
        assertEquals(49.27, vancouver.lat, 0.01)
        assertEquals(-123.12, vancouver.lon, 0.01)
        assertEquals("Buenos Aires", places.approximate(listOf("America/Argentina/Buenos_Aires"), "", Locale.US).name)
        // A link with its own row (zone.tab, unlike zone1970.tab): Amsterdam, not Brussels.
        assertEquals("Amsterdam", places.approximate(listOf("Europe/Amsterdam"), "NL", Locale.US).name)
    }

    @Test fun `an old alias falls back to its canonical zone, UTC to the region, then Greenwich`() {
        assertEquals("Kolkata", places.approximate(listOf("Asia/Calcutta", "Asia/Kolkata"), "IN", Locale.US).name)
        assertEquals("Bogota", places.approximate(listOf("UTC"), "CO", Locale.US).name)
        assertEquals(Places.GREENWICH, places.approximate(listOf("Etc/UTC"), "", Locale.US))
    }

    @Test fun `the platform's localized zone name is used when there is one`() {
        val named = Places(Places.parse(File("src/main/res/raw/tzdata_zone.tab").readText())) { id, l ->
            if (id == "Europe/London" && l.language == "fr") "Londres" else null
        }
        assertEquals("Londres", named.approximate(listOf("Europe/London"), "GB", Locale.FRANCE).name)
        assertEquals("London", named.approximate(listOf("Europe/London"), "GB", Locale.UK).name)
    }

    @Test fun `TV presets are the nearest zone cities, nearest first`() {
        val paris = places.approximate(listOf("Europe/Paris"), "FR", Locale.FRANCE)
        val near = places.nearby(paris, 6, Locale.FRANCE)
        assertEquals(6, near.size)
        assertFalse(near.any { it.name == "Paris" })
        assertTrue("Brussels" in near.map { it.name })
        val d = near.map { Places.distanceKm(paris.lat, paris.lon, it.lat, it.lon) }
        assertEquals(d.sorted(), d)
        assertTrue(d.last() < 1000)
        val vancouver = places.approximate(listOf("America/Vancouver"), "CA", Locale.CANADA)
    }
}
