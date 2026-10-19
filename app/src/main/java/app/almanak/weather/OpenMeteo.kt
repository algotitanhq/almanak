package app.almanak.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Fetches Open-Meteo (no key) and keeps the last good answer on disk, so a reboot while offline still shows
 * something true-ish, with its timestamp.
 */
class WeatherSource(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val baseUrl: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun cached(lat: Double, lon: Double): Weather? =
        runCatching { json.decodeFromString<Weather>(cacheFile.readText()) }.getOrNull()?.takeIf { it.isFor(lat, lon) }

    /** Returns fresh weather, or null on any network/parse failure (the cache is left untouched). */
    suspend fun refresh(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,relative_humidity_2m")
            .addQueryParameter("hourly", "temperature_2m,weather_code,precipitation_probability,is_day")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "6")
            .addQueryParameter("forecast_hours", "26")
            .build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val weather = json.decodeFromString<OmResponse>(resp.body.string()).toWeather(clock()).copy(lat = lat, lon = lon)
                cacheFile.writeText(json.encodeToString(weather))
                weather
            }
        } catch (e: Exception) {
            Log.w(TAG, "weather refresh failed: $e")
            null
        }
    }

    companion object {
        private const val TAG = "Almanak"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal class OmResponse(val current: OmCurrent, val hourly: OmHourly, val daily: OmDaily) {
    fun toWeather(fetchedAt: Long) = Weather(
        fetchedAtMillis = fetchedAt,
        now = Weather.Now(current.temperature, current.apparent, current.code, current.isDay == 1, current.wind, current.humidity),
        hours = hourly.time.indices.map { i ->
            Weather.Hour(hourly.time[i], hourly.temperature[i], hourly.code[i], hourly.precip[i] ?: 0, hourly.isDay[i] == 1)
        },
        days = daily.time.indices.map { i ->
            Weather.Day(daily.time[i], daily.code[i], daily.max[i], daily.min[i], daily.precip[i] ?: 0, daily.sunrise[i], daily.sunset[i])
        },
    )
}

@Serializable
internal class OmCurrent(
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("apparent_temperature") val apparent: Double,
    @SerialName("weather_code") val code: Int,
    @SerialName("is_day") val isDay: Int,
    @SerialName("wind_speed_10m") val wind: Double,
    @SerialName("relative_humidity_2m") val humidity: Int,
)

@Serializable
internal class OmHourly(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double>,
    @SerialName("weather_code") val code: List<Int>,
    @SerialName("precipitation_probability") val precip: List<Int?>,
    @SerialName("is_day") val isDay: List<Int>,
)

@Serializable
internal class OmDaily(
    val time: List<String>,
    @SerialName("weather_code") val code: List<Int>,
    @SerialName("temperature_2m_max") val max: List<Double>,
    @SerialName("temperature_2m_min") val min: List<Double>,
    @SerialName("precipitation_probability_max") val precip: List<Int?>,
    val sunrise: List<String>,
    val sunset: List<String>,
)
