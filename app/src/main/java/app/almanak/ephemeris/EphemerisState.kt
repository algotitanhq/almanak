package app.almanak.ephemeris

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.IsoFields

/** Everything the almanac line can say about today. Computed locally except [history]. */
data class EphemerisState(
    val date: LocalDate,
    val sun: SunTimes,
    val moon: MoonPhase,
    val saints: List<String>,
    val history: List<HistoryItem>,
) {
    val dayOfYear: Int get() = date.dayOfYear
    val daysInYear: Int get() = date.lengthOfYear()
    val isoWeek: Int get() = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    companion object {
        fun compute(now: ZonedDateTime, lat: Double, lon: Double, saints: Saints, history: List<HistoryItem>) = EphemerisState(
            date = now.toLocalDate(),
            sun = Sun.times(now.toLocalDate(), lat, lon, now.zone),
            moon = Moon.phase(now.toInstant(), now.zone),
            saints = saints.on(now.toLocalDate()),
            history = history,
        )
    }
}
