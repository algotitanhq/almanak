package app.almanak.transit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Owns the static schedule: conditional download of the agency zip (ETag), extraction for the configured
 * stops, and the on-disk JSON that survives reboots and offline periods.
 */
class GtfsStore(
    private val http: OkHttpClient,
    private val filesDir: File,
    private val cacheDir: File,
    /** The current network's zips, tried in order: the agency's own first, a mirror when it blocks us (VPN exit IPs). */
    private val feedUrls: () -> List<String>,
) {
    private val lock = Mutex()
    private val scheduleFile get() = File(filesDir, "schedule.json")
    /** The last downloaded agency zip (may be absent); also used for stop search on the phone page. */
    val zipFile get() = File(cacheDir, "gtfs.zip")
    private val etagFile get() = File(cacheDir, "gtfs.etag")

    private val _schedule = MutableStateFlow(load())
    val schedule: StateFlow<Cached?> = _schedule.asStateFlow()

    /** The extracted schedule plus the stop selection it was extracted for. */
    @Serializable
    data class Cached(val key: String, val schedule: StopSchedule)

    fun needsSync(stopIds: Set<String>, routeIds: Set<String>, today: java.time.LocalDate): Boolean {
        val c = _schedule.value ?: return true
        return c.key != key(feedUrls(), stopIds, routeIds) || today.isAfter(c.schedule.feedEnd)
    }

    /** Downloads (if changed) and re-extracts (if needed). Returns false on failure; the old cache stays. */
    suspend fun sync(stopIds: Set<String>, routeIds: Set<String>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { syncLocked(stopIds, routeIds) }
    }

    private fun syncLocked(stopIds: Set<String>, routeIds: Set<String>): Boolean {
        return try {
            val urls = feedUrls()
            // No network chosen yet: nothing to fetch, and nothing to retry.
            if (urls.isEmpty()) return true
            // Another network's zip is useless: never extract from it, even if the new download fails.
            if (zipSource() !in urls) zipFile.delete()
            val downloaded = download(urls)
            val key = key(urls, stopIds, routeIds)
            if (downloaded || _schedule.value?.key != key) {
                val started = System.currentTimeMillis()
                val extracted = GtfsExtractor.extract(zipFile, stopIds, routeIds)
                val cached = Cached(key, extracted)
                scheduleFile.writeText(json.encodeToString(cached))
                _schedule.value = cached
                Log.i(TAG, "GTFS extracted ${extracted.departures.size} departures for $key in ${System.currentTimeMillis() - started} ms (feed ${extracted.feedVersion})")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "GTFS sync failed: $e")
            false
        }
    }

    /** True when a new zip was written. Falls through the URL list on failure. */
    /** The URL the cached zip came from (kept beside its ETag), or null. */
    fun zipSource(): String? = etagFile.takeIf { it.exists() && zipFile.exists() }?.readLines()?.firstOrNull()

    private fun download(urls: List<String>): Boolean {
        var last: Exception? = null
        for (url in urls) {
            try {
                return downloadFrom(url)
            } catch (e: IOException) {
                Log.w(TAG, "GTFS download from $url failed: ${e.message}")
                last = e
            }
        }
        throw last ?: IOException("no GTFS url")
    }

    private fun downloadFrom(url: String): Boolean {
        // The ETag is only meaningful for the URL that issued it: stored as "url\netag".
        val etag = etagFile.takeIf { it.exists() && zipFile.exists() }?.readLines()
            ?.takeIf { it.size == 2 && it[0] == url }?.get(1)
        val req = Request.Builder().url(url).apply { if (etag != null) header("If-None-Match", etag) }.build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 304) return false
            if (!resp.isSuccessful) throw IOException("GTFS HTTP ${resp.code}")
            val tmp = File(cacheDir, "gtfs.zip.part")
            tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out, 1 shl 16) }
            if (!tmp.renameTo(zipFile)) throw IOException("rename failed")
            // The URL is kept even without an ETag: it says which network the zip belongs to.
            etagFile.writeText(url + (resp.header("ETag")?.let { "\n$it" } ?: ""))
            Log.i(TAG, "GTFS downloaded ${zipFile.length() / 1024} KiB")
            return true
        }
    }

    private fun load(): Cached? = runCatching { json.decodeFromString<Cached>(scheduleFile.readText()) }.getOrNull()

    companion object {
        private const val TAG = "Almanak"
        private val json = Json { ignoreUnknownKeys = true }
        fun key(urls: List<String>, stopIds: Set<String>, routeIds: Set<String>) =
            urls.firstOrNull().orEmpty() + "|" + stopIds.sorted().joinToString(",") + "|" + routeIds.sorted().joinToString(",")
    }
}
