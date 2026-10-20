package app.almanak.ephemeris

import android.content.res.Resources
import android.util.Log
import app.almanak.BuildConfig
import app.almanak.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** A bundled saints' calendar: French, or the Spanish santoral (see third_party/DATA.md). */
class Saints(private val load: () -> String) {
    constructor(resources: Resources, raw: Int) : this({ resources.openRawResource(raw).bufferedReader().use { it.readText() } })

    private val byDay: Map<String, List<String>> by lazy { Json.decodeFromString(load()) }

    fun on(date: LocalDate): List<String> = byDay["${date.monthValue}-${date.dayOfMonth}"].orEmpty()
}

@Serializable
data class HistoryItem(val year: Int, val text: String)

/**
 * Wikimedia "On this day" (selected events), from the Wikipedia in the UI language. One request per day and
 * language, cached on disk so the line survives a day offline.
 */
class OnThisDaySource(
    private val http: OkHttpClient,
    private val cacheFile: File,
    private val baseUrl: String = "https://api.wikimedia.org/feed/v1/wikipedia",
) {
    @Serializable
    private data class Cached(val date: String, val items: List<HistoryItem>, val lang: String = "fr")

    @Serializable
    private data class Feed(val selected: List<Entry> = emptyList()) {
        @Serializable
        data class Entry(val text: String, val year: Int? = null)
    }

    fun cached(date: LocalDate, lang: String): List<HistoryItem>? =
        runCatching { json.decodeFromString<Cached>(cacheFile.readText()) }.getOrNull()
            ?.takeIf { it.date == date.toString() && it.lang == lang }?.items

    /** [lang] is a Wikipedia language code (the app's language codes are). */
    suspend fun fetch(date: LocalDate, lang: String): List<HistoryItem>? = withContext(Dispatchers.IO) {
        cached(date, lang)?.let { return@withContext it }
        val url = "%s/%s/onthisday/selected/%02d/%02d".format(baseUrl, lang, date.monthValue, date.dayOfMonth)
        try {
            // Wikimedia asks API clients to identify themselves.
            val req = Request.Builder().url(url).header("User-Agent", "Almanak/${BuildConfig.VERSION_NAME} (Android TV ambient display)").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val items = pick(json.decodeFromString<Feed>(resp.body.string()))
                cacheFile.writeText(json.encodeToString(Cached(date.toString(), items, lang)))
                items
            }
        } catch (e: Exception) {
            Log.w("Almanak", "onthisday failed: $e")
            null
        }
    }

    private fun pick(feed: Feed): List<HistoryItem> = feed.selected
        .mapNotNull { e -> e.year?.let { HistoryItem(it, e.text.replace(IMAGE_NOTE, "").trim().replaceFirstChar(Char::uppercaseChar)) } }
        // Long entries don't read from the couch.
        .filter { it.text.length <= MAX_CHARS }
        .sortedBy { it.year }

    companion object {
        const val MAX_CHARS = 110
        /** Notes pointing at the feed's picture, which the TV doesn't show: "(pictured)", "(photo)", "(en la imagen)"… */
        private val IMAGE_NOTE = Regex("""\s*\((?:[^()]*\s)?(?:pictured|depicted|photo|miniature|illustration|image|imagen|foto)\)""", RegexOption.IGNORE_CASE)
        private val json = Json { ignoreUnknownKeys = true }
    }
}
