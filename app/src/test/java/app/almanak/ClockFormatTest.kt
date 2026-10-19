package app.almanak

import app.almanak.clock.ClockFormat
import app.almanak.clock.millisToNextMinute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ClockFormatTest {
    private val t = ZonedDateTime.of(2026, 10, 8, 20, 5, 42, 250_000_000, ZoneId.of("America/Toronto"))

    @Test fun `24h has no meridiem and no leading zero`() {
        val c = ClockFormat.time(t, use24h = true, Locale.US)
        assertEquals("20:05", c.time)
        assertNull(c.meridiem)
    }

    @Test fun `12h splits meridiem`() {
        val c = ClockFormat.time(t, use24h = false, Locale.US)
        assertEquals("8:05", c.time)
        assertEquals("PM", c.meridiem)
    }

    @Test fun `weekday is capitalised in French`() {
        assertEquals("Jeudi", ClockFormat.weekday(t, Locale.CANADA_FRENCH))
        assertEquals("Thursday", ClockFormat.weekday(t, Locale.US))
    }

    @Test fun `long date follows locale punctuation`() {
        assertEquals("Thursday, October 8", ClockFormat.longDate(t, Locale.US))
        assertEquals("Jeudi 8 octobre", ClockFormat.longDate(t, Locale.CANADA_FRENCH))
        assertEquals("Jueves, 8 de octubre", ClockFormat.longDate(t, Locale.forLanguageTag("es")))
    }

    @Test fun `next minute delay`() {
        assertEquals(17_750L, millisToNextMinute(t))
    }
}
