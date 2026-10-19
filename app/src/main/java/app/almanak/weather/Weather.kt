package app.almanak.weather

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

/** Unit-agnostic snapshot (always metric); conversion happens at display time. Also the on-disk cache format. */
@Serializable
data class Weather(
    val fetchedAtMillis: Long,
    /** Where this forecast is for, so a cache from another city is never shown. */
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val now: Now,
    val hours: List<Hour>,
    val days: List<Day>,
) {
    @Serializable
    data class Now(val tempC: Double, val feelsC: Double, val code: Int, val isDay: Boolean, val windKmh: Double, val humidity: Int)

    @Serializable
    data class Hour(val time: String, val tempC: Double, val code: Int, val precipProb: Int, val isDay: Boolean) {
        val localTime: LocalDateTime get() = LocalDateTime.parse(time)
    }

    @Serializable
    data class Day(val date: String, val code: Int, val maxC: Double, val minC: Double, val precipProb: Int, val sunrise: String, val sunset: String) {
        val localDate: LocalDate get() = LocalDate.parse(date)
    }

    /** Caches written before coordinates were recorded (0, 0) were always for the default city. */
    fun isFor(lat: Double, lon: Double) = (this.lat == 0.0 && this.lon == 0.0) ||
        (kotlin.math.abs(this.lat - lat) < 0.05 && kotlin.math.abs(this.lon - lon) < 0.05)

    /** Hours strictly after the current hour, so the strip never repeats "now". */
    fun upcomingHours(now: LocalDateTime, count: Int): List<Hour> =
        hours.filter { it.localTime.isAfter(now) }.take(count)
}

/** WMO weather interpretation codes collapsed into what we can draw. */
enum class Sky {
    Clear, MostlyClear, PartlyCloudy, Overcast, Fog, Drizzle, Rain, FreezingRain, Snow, Thunder;

    val wet: Boolean get() = this in setOf(Drizzle, Rain, FreezingRain, Snow, Thunder)

    companion object {
        fun of(wmo: Int): Sky = when (wmo) {
            0 -> Clear
            1 -> MostlyClear
            2 -> PartlyCloudy
            3 -> Overcast
            45, 48 -> Fog
            51, 53, 55 -> Drizzle
            56, 57, 66, 67 -> FreezingRain
            61, 63, 65, 80, 81, 82 -> Rain
            71, 73, 75, 77, 85, 86 -> Snow
            95, 96, 99 -> Thunder
            else -> Overcast
        }
    }
}

enum class TempUnit { Celsius, Fahrenheit }

fun Double.toUnit(unit: TempUnit): Int = when (unit) {
    TempUnit.Celsius -> Math.round(this).toInt()
    TempUnit.Fahrenheit -> Math.round(this * 9 / 5 + 32).toInt()
}

/** "11°" — the unit letter is implied by the setting, as on any good wall thermometer. */
fun Double.degrees(unit: TempUnit): String = "${toUnit(unit)}°"
