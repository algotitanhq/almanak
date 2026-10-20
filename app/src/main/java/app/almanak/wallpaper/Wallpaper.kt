package app.almanak.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.almanak.BuildConfig
import app.almanak.ephemeris.SunTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.pow

enum class WallpaperMode { None, Sky, Potd, Url }

enum class SkyPhase { Night, Dawn, Day, Dusk }

/**
 * How bright the image is behind each text region (0 = black, 1 = white), and how busy it is there (spread of
 * brightness): fine texture behind thin type hurts as much as brightness does.
 */
data class Luma(val clock: Float, val bus: Float, val band: Float, val busy: Float = 0f)

sealed interface Backdrop {
    data object Plain : Backdrop
    data class Sky(val phase: SkyPhase) : Backdrop
    data class Photo(val image: ImageBitmap, val luma: Luma, val credit: String?, val key: String) : Backdrop
}

fun skyPhase(now: ZonedDateTime, sun: SunTimes): SkyPhase {
    val rise = sun.sunrise ?: return SkyPhase.Night
    val set = sun.sunset ?: return SkyPhase.Night
    val margin = 45L
    return when {
        now.isAfter(rise.minusMinutes(margin)) && now.isBefore(rise.plusMinutes(margin)) -> SkyPhase.Dawn
        now.isAfter(set.minusMinutes(margin)) && now.isBefore(set.plusMinutes(margin)) -> SkyPhase.Dusk
        now.isAfter(rise) && now.isBefore(set) -> SkyPhase.Day
        else -> SkyPhase.Night
    }
}

@Serializable
data class PhotoRef(val url: String, val credit: String?)

/**
 * Remote photos: Wikimedia Commons Picture of the Day (no key, free licences, credited on screen) or any URL that
 * returns an image. Files are cached on disk; bitmaps are decoded at screen size, never full resolution.
 */
class PhotoSource(
    private val http: OkHttpClient,
    private val dir: File,
    private val screenW: Int = 1920,
    private val screenH: Int = 1080,
    private val featuredBase: String = "https://api.wikimedia.org/feed/v1/wikipedia/en/featured",
) {
    init { dir.mkdirs() }

    /** Landscape Pictures of the Day of the last [days] days, newest first. */
    suspend fun potd(today: LocalDate, days: Int = 10): List<PhotoRef> = withContext(Dispatchers.IO) {
        val listFile = File(dir, "potd-$today.json")
        runCatching { json.decodeFromString<List<PhotoRef>>(listFile.readText()) }.getOrNull()?.let { return@withContext it }
        val refs = (0 until days).mapNotNull { i -> runCatching { featuredImage(today.minusDays(i.toLong())) }.getOrNull() }
        if (refs.isNotEmpty()) {
            dir.listFiles { f -> f.name.startsWith("potd-") }?.forEach { it.delete() }
            listFile.writeText(json.encodeToString(refs))
        }
        refs
    }

    private fun featuredImage(date: LocalDate): PhotoRef? {
        val url = "%s/%d/%02d/%02d".format(featuredBase, date.year, date.monthValue, date.dayOfMonth)
        val body = get(url).use { it.body.string() }
        val image = json.decodeFromString<Featured>(body).image ?: return null
        val w = image.image?.width ?: return null
        val h = image.image.height
        if (w < h * 1.3) return null // portrait or square: crops badly on a TV
        val thumb = image.thumbnail?.source ?: return null
        val sized = Regex("/\\d+px-").replace(thumb, "/${screenW}px-")
        val credit = listOfNotNull(image.artist?.text?.trim()?.takeIf { it.isNotEmpty() }, image.license?.type, "Wikimedia Commons")
            .joinToString(" · ")
        return PhotoRef(sized, credit)
    }

    /** Downloads (or reuses, unless [fresh]) and decodes [ref]; null on failure. */
    suspend fun load(ref: PhotoRef, fresh: Boolean = false): Backdrop.Photo? = withContext(Dispatchers.IO) {
        try {
            val file = File(dir, "img-" + sha1(ref.url))
            // A custom URL may serve a different image each time (e.g. a random-photo service).
            if (fresh) file.delete()
            if (!file.exists()) {
                val tmp = File(dir, file.name + ".part")
                get(ref.url).use { resp -> tmp.outputStream().use { resp.body.byteStream().copyTo(it) } }
                tmp.renameTo(file)
                trimCache(keep = 12)
            }
            val bitmap = decodeForScreen(file) ?: return@withContext null
            Backdrop.Photo(bitmap.asImageBitmap(), luma(bitmap), ref.credit, ref.url)
        } catch (e: Exception) {
            Log.w("Almanak", "wallpaper load failed: $e")
            null
        }
    }

    private fun decodeForScreen(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun trimCache(keep: Int) {
        dir.listFiles { f -> f.name.startsWith("img-") && !f.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    }

    private fun get(url: String) = http.newCall(
        Request.Builder().url(url).header("User-Agent", "Almanak/${BuildConfig.VERSION_NAME} (Android TV ambient display)").build(),
    ).execute().also { if (!it.isSuccessful) { it.close(); throw IOException("HTTP ${it.code} for $url") } }

    @Serializable private data class Featured(val image: Image? = null)
    @Serializable private data class Image(
        val thumbnail: Src? = null, val image: Src? = null, val artist: Txt? = null, val license: Lic? = null,
    )
    @Serializable private data class Src(val source: String, val width: Int, val height: Int)
    @Serializable private data class Txt(val text: String? = null)
    @Serializable private data class Lic(val type: String? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * Perceived brightness per text region on a 48×27 thumbnail. Uses a high percentile, not the mean: one
         * bright cloud behind the clock hurts legibility even if the region is dark on average.
         */
        fun luma(bitmap: Bitmap): Luma {
            val w = 48
            val h = 27
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
            if (small !== bitmap) small.recycle()
            fun lum(c: Int): Float {
                fun ch(v: Int) = (v / 255f).toDouble().pow(2.2).toFloat()
                return 0.2126f * ch(c shr 16 and 0xFF) + 0.7152f * ch(c shr 8 and 0xFF) + 0.0722f * ch(c and 0xFF)
            }
            fun values(x0: Int, x1: Int, y0: Int, y1: Int) =
                buildList { for (y in y0 until y1) for (x in x0 until x1) add(lum(px[y * w + x]).toDouble().pow(1 / 2.2).toFloat()) }
            // Perceptual (gamma) space so 0.5 means mid-grey; a high percentile, not the mean.
            fun p85(v: List<Float>) = v.sorted().let { max(it[(it.size * 0.85).toInt().coerceAtMost(it.lastIndex)], 0f) }
            fun spread(v: List<Float>): Float {
                val m = v.average().toFloat()
                return kotlin.math.sqrt(v.sumOf { ((it - m) * (it - m)).toDouble() } / v.size).toFloat()
            }
            val clock = values(0, 24, 0, 17)
            val bus = values(34, 48, 0, 18)
            val band = values(0, 48, 19, 27)
            return Luma(p85(clock), p85(bus), p85(band), busy = maxOf(spread(clock), spread(bus), spread(band)))
        }
    }
}
