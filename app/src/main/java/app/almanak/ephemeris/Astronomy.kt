package app.almanak.ephemeris

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

data class SunTimes(val sunrise: ZonedDateTime?, val sunset: ZonedDateTime?) {
    val daylight: Duration? get() = if (sunrise != null && sunset != null) Duration.between(sunrise, sunset) else null
}

/** Sunrise equation (as on Wikipedia "Sunrise equation", ~1 min accuracy at mid latitudes). Offline, no API. */
object Sun {
    private const val J2000 = 2451545.0

    fun times(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes {
        // Julian date at 0h UT of [date]: n is then the day count since J2000 (noon would round one day ahead).
        val jd0 = date.toEpochDay() + 2440587.5
        val n = ceil(jd0 - J2000 + 0.0008)
        val jStar = n - lon / 360.0
        val m = norm(357.5291 + 0.98560028 * jStar)
        val c = 1.9148 * sinD(m) + 0.0200 * sinD(2 * m) + 0.0003 * sinD(3 * m)
        val lambda = norm(m + c + 180 + 102.9372)
        val transit = J2000 + jStar + 0.0053 * sinD(m) - 0.0069 * sinD(2 * lambda)
        val decl = asin(sinD(lambda) * sinD(23.4397))
        val cosW = (sinD(-0.833) - sinD(lat) * sin(decl)) / (cosD(lat) * cos(decl))
        if (cosW < -1 || cosW > 1) return SunTimes(null, null) // polar day / night
        val w = acos(cosW) * 180 / PI
        return SunTimes(jdToZoned(transit - w / 360, zone), jdToZoned(transit + w / 360, zone))
    }

    private fun jdToZoned(jd: Double, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(((jd - 2440587.5) * 86_400_000).toLong()).atZone(zone)
}

enum class MoonPhaseName { New, WaxingCrescent, FirstQuarter, WaxingGibbous, Full, WaningGibbous, LastQuarter, WaningCrescent }

/** [phase] in [0, 1): 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
data class MoonPhase(val phase: Double, val illumination: Double, val nextFull: LocalDate, val nextNew: LocalDate) {
    val waxing: Boolean get() = phase < 0.5
    /** Principal phases are named only within ~1 day of the exact instant; otherwise the in-between names. */
    val name: MoonPhaseName get() {
        val day = 1.0 / 29.53
        return when {
            phase < day || phase > 1 - day -> MoonPhaseName.New
            abs(phase - 0.25) < day -> MoonPhaseName.FirstQuarter
            abs(phase - 0.5) < day -> MoonPhaseName.Full
            abs(phase - 0.75) < day -> MoonPhaseName.LastQuarter
            phase < 0.25 -> MoonPhaseName.WaxingCrescent
            phase < 0.5 -> MoonPhaseName.WaxingGibbous
            phase < 0.75 -> MoonPhaseName.WaningGibbous
            else -> MoonPhaseName.WaningCrescent
        }
    }
}

/**
 * True new/full moon instants after Meeus, "Astronomical Algorithms" ch. 49 (main periodic terms, ~1 min).
 * The phase fraction is interpolated between the surrounding true new moons, so dates and glyph agree.
 */
object Moon {
    private const val SYNODIC = 29.530588861
    private const val K0_JDE = 2451550.09766

    fun phase(at: Instant, zone: ZoneId): MoonPhase {
        val jd = at.toEpochMilli() / 86_400_000.0 + 2440587.5
        val kApprox = floor((jd - K0_JDE) / SYNODIC)
        val news = (-1..2).map { trueJde(kApprox + it, full = false) }
        val prevNew = news.last { it <= jd }
        val nextNew = news.first { it > jd }
        val nextFull = (-1..2).map { trueJde(kApprox + it + 0.5, full = true) }.first { it > jd }
        val p = (jd - prevNew) / (nextNew - prevNew)
        return MoonPhase(p, (1 - cos(2 * PI * p)) / 2, jdDate(nextFull, zone), jdDate(nextNew, zone))
    }

    /** JDE of the new (k integer) or full (k + 0.5) moon number [k] counted from 2000-01-06. */
    internal fun trueJde(k: Double, full: Boolean): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val jde = K0_JDE + SYNODIC * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t2 * t2
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        val m = 2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3
        val mp = 201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t2 * t2
        val f = 160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t2 * t2
        val om = 124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3
        val c = if (full) {
            -0.40614 * sinD(mp) + 0.17302 * e * sinD(m) + 0.01614 * sinD(2 * mp) + 0.01043 * sinD(2 * f) +
                0.00734 * e * sinD(mp - m) - 0.00515 * e * sinD(mp + m) + 0.00209 * e * e * sinD(2 * m)
        } else {
            -0.40720 * sinD(mp) + 0.17241 * e * sinD(m) + 0.01608 * sinD(2 * mp) + 0.01039 * sinD(2 * f) +
                0.00739 * e * sinD(mp - m) - 0.00514 * e * sinD(mp + m) + 0.00208 * e * e * sinD(2 * m)
        }
        val common = -0.00111 * sinD(mp - 2 * f) - 0.00057 * sinD(mp + 2 * f) + 0.00056 * e * sinD(2 * mp + m) -
            0.00042 * sinD(3 * mp) + 0.00042 * e * sinD(m + 2 * f) + 0.00038 * e * sinD(m - 2 * f) -
            0.00024 * e * sinD(2 * mp - m) - 0.00017 * sinD(om)
        return jde + c + common
    }

    internal fun jdInstant(jd: Double): Instant = Instant.ofEpochMilli(((jd - 2440587.5) * 86_400_000).toLong())
    private fun jdDate(jd: Double, zone: ZoneId) = jdInstant(jd).atZone(zone).toLocalDate()
}

private fun norm(d: Double) = ((d % 360) + 360) % 360
private fun sinD(d: Double) = sin(d * PI / 180)
private fun cosD(d: Double) = cos(d * PI / 180)
