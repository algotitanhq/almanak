package app.almanak.web

import android.annotation.SuppressLint
import android.content.res.Resources
import app.almanak.R
import app.almanak.settings.City
import app.almanak.settings.DevicePlaces
import app.almanak.settings.Settings
import app.almanak.settings.SettingsStore
import app.almanak.settings.TransitAgency
import app.almanak.transit.parseCsvLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipFile

/** Routes for the phone setup page. The TV observes the same settings, so a save shows up live on screen. */
class SetupApi(
    private val store: SettingsStore,
    private val http: OkHttpClient,
    /** The cached schedule zip, or null while it isn't the current network's (just switched, still downloading). */
    private val gtfsZip: () -> File?,
    private val resources: Resources,
    /** The approximate place before a city is chosen, and the TV's city choices around it. */
    val places: DevicePlaces,
) {
    @Serializable data class Stop(val id: String, val name: String)
    @Serializable data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    // R.font TTFs are packaged as plain files, so openRawResource serves them as-is (no copy in res/raw).
    @SuppressLint("ResourceType")
    suspend fun handle(r: HttpRequest): HttpResponse = when {
        r.method == "GET" && r.path == "/" -> page()
        r.method == "GET" && r.path == "/api/settings" -> HttpResponse.json(redacted(store.settings.value, approxPlace(store.settings.value)))
        r.method == "PUT" && r.path == "/api/settings" -> save(r.body)
        r.method == "GET" && r.path == "/api/agencies" -> HttpResponse.json(json.encodeToString(TransitAgency.PRESETS))
        r.method == "GET" && r.path == "/api/stops" -> HttpResponse.json(json.encodeToString(searchStops(r.query["q"].orEmpty())))
        r.method == "GET" && r.path == "/api/places" -> HttpResponse.json(json.encodeToString(searchPlaces(r.query["q"].orEmpty())))
        r.method == "GET" && r.path == "/fonts/serif.ttf" ->
            HttpResponse(200, "font/ttf", resources.openRawResource(R.font.instrument_serif_italic).use { it.readBytes() }, mapOf("Cache-Control" to "max-age=86400"))
        else -> HttpResponse.text(404, "not found")
    }

    private fun page() = HttpResponse(200, "text/html; charset=utf-8", resources.openRawResource(R.raw.setup).use { it.readBytes() })

    private suspend fun save(body: String): HttpResponse {
        val incoming = runCatching { SettingsStore.fromJson(body) }.getOrNull() ?: return HttpResponse.text(400, "invalid settings")
        // An empty key or password field means "unchanged", not "delete": the page never receives them.
        val saved = store.update { current -> merge(current, incoming) }
        return HttpResponse.json(redacted(saved, approxPlace(saved)))
    }

    private fun approxPlace(s: Settings): City? = if (s.city == null) places.here(s.locale) else null

    private fun searchStops(q: String): List<Stop> = gtfsZip()?.let { searchStops(it, q) }.orEmpty()

    /** City search through Open-Meteo's geocoder (no key), with place names in the UI language. */
    private fun searchPlaces(q: String): List<Place> {
        if (q.trim().length < 2) return emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", q.trim()).addQueryParameter("count", "8").addQueryParameter("language", store.settings.value.language).build()
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                json.decodeFromString<Geo>(resp.body.string()).results.orEmpty().map {
                    Place(it.name, listOfNotNull(it.admin1, it.country).joinToString(", "), it.latitude, it.longitude)
                }
            }
        }.getOrDefault(emptyList())
    }

    @Serializable private data class Geo(val results: List<GeoResult>? = null)
    @Serializable private data class GeoResult(val name: String, val latitude: Double, val longitude: Double, val country: String? = null, val admin1: String? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * The realtime key and the Wi-Fi password never go back to the browser; the page only learns whether one is
         * set. [approx]: the time zone's place, shown while no city is chosen.
         */
        internal fun redacted(s: Settings, approx: City? = null): String {
            val obj = json.parseToJsonElement(SettingsStore.toJson(s.copy(realtimeKey = "", guestWifi = s.guestWifi.copy(password = ""))))
                .let { it as kotlinx.serialization.json.JsonObject }
            return kotlinx.serialization.json.JsonObject(obj + mapOf(
                "realtimeKeySet" to kotlinx.serialization.json.JsonPrimitive(s.realtimeKey.isNotBlank()),
                "guestWifiPasswordSet" to kotlinx.serialization.json.JsonPrimitive(s.guestWifi.password.isNotEmpty()),
            ) + listOfNotNull(approx?.let { "approxCity" to json.encodeToJsonElement(it) })).toString()
        }

        internal fun merge(current: Settings, incoming: Settings): Settings = incoming.copy(
            onboarded = true,
            // Another network: its stop codes and key mean nothing for the new one.
            realtimeKey = incoming.realtimeKey.ifBlank { if (incoming.agency.id == current.agency.id) current.realtimeKey else "" },
            guestWifi = incoming.guestWifi.let { it.copy(password = it.password.ifEmpty { current.guestWifi.password }) },
        )
    }
}

/** Stop search by code or name in an agency feed's stops.txt (no network). Platforms/stations are skipped. */
internal fun searchStops(zip: File, q: String, limit: Int = 20): List<SetupApi.Stop> {
    val query = q.trim()
    val needle = query.lowercase()
    if (needle.length < 2 || !zip.exists()) return emptyList()
    return ZipFile(zip).use { z ->
        val entry = z.getEntry("stops.txt") ?: return emptyList()
        z.getInputStream(entry).bufferedReader().useLines { lines ->
            val it = lines.iterator()
            if (!it.hasNext()) return@useLines emptyList()
            val header = parseCsvLine(it.next().removePrefix("\uFEFF")).map(String::trim)
            val id = header.indexOf("stop_id")
            val code = header.indexOf("stop_code")
            val name = header.indexOf("stop_name")
            val type = header.indexOf("location_type")
            it.asSequence().map(::parseCsvLine)
                .filter { row -> type < 0 || row.getOrNull(type).isNullOrBlank() || row[type] == "0" }
                .filter { row -> row.getOrNull(id) == query || row.getOrNull(code) == query || row.getOrNull(name)?.lowercase()?.contains(needle) == true }
                .take(limit)
                .map { row -> SetupApi.Stop(row[id], row.getOrNull(name).orEmpty()) }
                .toList()
        }
    }
}
