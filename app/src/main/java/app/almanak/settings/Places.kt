package app.almanak.settings

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One row of tzdata's `zone.tab`: a zone, its country, and the coordinate of the city it is named after. */
data class TzZone(val id: String, val country: String, val lat: Double, val lon: Double) {
    /** "America/Argentina/Buenos_Aires" → "Buenos Aires". */
    val cityName: String get() = id.substringAfterLast('/').replace('_', ' ')
}

/**
 * Where the TV probably is, before anyone has chosen a city: the time zone's own city (tzdata, public domain), so
 * the weather and the sky are roughly right anywhere at first launch without any network lookup. Also the TV's
 * city presets: the zone cities nearest to that place.
 */
class Places(
    private val zones: List<TzZone>,
    /** The zone city's name in the UI language when the platform knows it (ICU), else null for [TzZone.cityName]. */
    private val localName: (zoneId: String, Locale) -> String? = { _, _ -> null },
) {
    private val byId = zones.associateBy { it.id }

    /**
     * The first of [zoneIds] (the device zone, then its canonical form) found in the table; else the first zone of
     * the device [region]; else Greenwich ("UTC" with no region set: the meridian is the honest answer).
     */
    fun approximate(zoneIds: List<String>, region: String, locale: Locale): City {
        val zone = zoneIds.firstNotNullOfOrNull { byId[it] }
            ?: zones.firstOrNull { region.isNotEmpty() && it.country.equals(region, ignoreCase = true) }
            ?: return GREENWICH
        return city(zone, locale)
    }

    /** The [count] zone cities closest to [from], nearest first (the place itself excluded). */
    fun nearby(from: City, count: Int, locale: Locale): List<City> =
        zones.asSequence()
            .map { it to distanceKm(from.lat, from.lon, it.lat, it.lon) }
            .filter { (_, d) -> d > 1.0 }
            .sortedBy { it.second }
            .map { city(it.first, locale) }
            .filter { it.name != from.name }
            .distinctBy { it.name }
            .take(count)
            .toList()

    private fun city(z: TzZone, locale: Locale) = City(localName(z.id, locale)?.takeIf { it.isNotBlank() } ?: z.cityName, z.lat, z.lon)

    companion object {
        val GREENWICH = City("Greenwich", 51.4779, -0.0015)

        /** Parses `zone.tab` (country, ±DDMM[SS]±DDDMM[SS], zone id, comment). */
        fun parse(text: String): List<TzZone> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val cols = line.split('\t')
                if (cols.size < 3) return@mapNotNull null
                val (lat, lon) = coordinates(cols[1]) ?: return@mapNotNull null
                TzZone(cols[2].trim(), cols[0].trim(), lat, lon)
            }.toList()

        /** "+4852+00220" → (48.8667, 2.3333); "-3436-05827" and the seconds form "+404251-0740023" too. */
        internal fun coordinates(s: String): Pair<Double, Double>? {
            val m = Regex("""([+-]\d{4,6})([+-]\d{5,7})""").matchEntire(s.trim()) ?: return null
            fun dms(v: String, degDigits: Int): Double {
                val sign = if (v[0] == '-') -1 else 1
                val d = v.substring(1)
                val deg = d.substring(0, degDigits).toInt()
                val min = d.substring(degDigits, degDigits + 2).toInt()
                val sec = d.substring(degDigits + 2).ifEmpty { "0" }.toInt()
                return sign * (deg + min / 60.0 + sec / 3600.0)
            }
            return dms(m.groupValues[1], 2) to dms(m.groupValues[2], 3)
        }

        internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = Math.toRadians(1.0)
            val h = sin((lat2 - lat1) * r / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(h))
        }
    }
}
