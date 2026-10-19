package app.almanak.settings

import android.content.res.Resources
import android.icu.text.TimeZoneNames
import android.icu.util.ULocale
import app.almanak.R
import java.util.Locale
import java.util.TimeZone

/** [Places] for this TV: its time zone and region, zone names from ICU in the UI language. */
class DevicePlaces(resources: Resources) {
    private val places by lazy {
        Places(Places.parse(resources.openRawResource(R.raw.tzdata_zone).use { it.readBytes().decodeToString() })) { id, locale ->
            runCatching { TimeZoneNames.getInstance(ULocale.forLocale(locale)).getExemplarLocationName(id) }.getOrNull()
        }
    }

    /** Recomputed on each call: the zone can change while the app runs. */
    fun here(locale: Locale): City {
        val id = TimeZone.getDefault().id
        val canonical = runCatching { android.icu.util.TimeZone.getCanonicalID(id) }.getOrNull()
        return places.approximate(listOfNotNull(id, canonical), Locale.getDefault().country, locale)
    }

    /** The TV's city choices: the zone cities around [from]. */
    fun nearby(from: City, locale: Locale, count: Int = 6): List<City> = places.nearby(from, count, locale)
}
