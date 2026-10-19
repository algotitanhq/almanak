package app.almanak.wallpaper

import app.almanak.ephemeris.Sun
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SkyPhaseTest {
    private val zone = ZoneId.of("America/Toronto")
    private val sun = Sun.times(LocalDate.of(2026, 10, 8), 45.5, -73.57, zone) // ~07:01 / 18:21

    private fun at(h: Int, m: Int) = skyPhase(ZonedDateTime.of(2026, 10, 8, h, m, 0, 0, zone), sun)

    @Test fun `phases follow the real sun with a 45 min margin`() {
        assertEquals(SkyPhase.Night, at(5, 0))
        assertEquals(SkyPhase.Dawn, at(6, 30))
        assertEquals(SkyPhase.Dawn, at(7, 30))
        assertEquals(SkyPhase.Day, at(12, 0))
        assertEquals(SkyPhase.Dusk, at(18, 0))
        assertEquals(SkyPhase.Dusk, at(18, 50))
        assertEquals(SkyPhase.Night, at(21, 30))
    }
}
