package app.almanak.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import app.almanak.ui.theme.ThemeId
import app.almanak.wallpaper.WallpaperMode
import app.almanak.weather.TempUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** Optional blocks around the clock (the clock and date are always shown). */
enum class Module { Weather, Bus, Almanac }

/** Where a module sits in the grid: right column, bottom band, or the line under the date. */
enum class Zone { Right, Bottom, UnderClock }

enum class ModuleSize(val scale: Float) { Small(0.85f), Medium(1f), Large(1.15f) }

@Serializable
data class ModuleSetting(val module: Module, val enabled: Boolean = true, val zone: Zone, val size: ModuleSize = ModuleSize.Medium)

@Serializable
data class City(val name: String, val lat: Double, val lon: Double)

enum class WifiSecurity { WPA, WEP, None }

/** The guest network, shown as a join QR when someone presses an arrow on the remote. Blank [ssid] = not set up. */
@Serializable
data class GuestWifi(
    val ssid: String = "",
    val password: String = "",
    val security: WifiSecurity = WifiSecurity.WPA,
    val hidden: Boolean = false,
) {
    val configured: Boolean get() = ssid.isNotBlank()

    /** The `WIFI:` payload phone cameras join from (ZXing's format: `\ ; , : "` are backslash-escaped). */
    fun qrPayload(): String = buildString {
        append("WIFI:T:").append(if (security == WifiSecurity.None) "nopass" else security.name)
        append(";S:").append(escape(ssid))
        if (security != WifiSecurity.None) append(";P:").append(escape(password))
        if (hidden) append(";H:true")
        append(";;")
    }

    private fun escape(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
}

/** Everything the user can change. One JSON document in DataStore; unknown fields are ignored on read. */
@Serializable
data class Settings(
    val theme: ThemeId = ThemeId.Nocturne,
    val language: String = "en",
    val unit: TempUnit = TempUnit.Celsius,
    /** Null until a city is chosen: the place is then approximated from the TV's time zone ([Places]). */
    val city: City? = null,
    /** Stop codes, in display order. None until set on the phone: the bus block stays hidden. */
    val stops: List<String> = emptyList(),
    /** Optional route filter; empty = every route serving the stops. */
    val routes: List<String> = emptyList(),
    val wallpaper: WallpaperMode = WallpaperMode.Sky,
    val wallpaperUrl: String = "",
    val modules: List<ModuleSetting> = DEFAULT_MODULES,
    /** [TransitAgency.NONE] until a network is chosen on the phone. */
    val agency: TransitAgency = TransitAgency.NONE,
    /** The realtime feed's key, entered on the phone page (overrides the build-time key). Was "stmApiKey". */
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("stmApiKey")
    val realtimeKey: String = "",
    val guestWifi: GuestWifi = GuestWifi(),
    /**
     * False only on a fresh install, until the first save from the phone or the first visit to the TV settings: the
     * screen then shows the "scan to set up" card. A file without the field predates it, so counts as set up.
     */
    val onboarded: Boolean = true,
    /** Absent from files written before neutral defaults: see [SettingsSerializer.decodeStored]. */
    val schema: Int = SCHEMA,
) {
    /** The UI language with the TV's region for dates and numbers. */
    val locale: Locale get() = localeFor(language, Locale.getDefault().country)

    fun module(m: Module): ModuleSetting = modules.firstOrNull { it.module == m } ?: DEFAULT_MODULES.first { it.module == m }

    /** Repairs anything a hand-edited or older file could contain. */
    fun normalized(): Settings = copy(
        language = language.takeIf { it in LANGUAGES } ?: "en",
        agency = agency.normalized(),
        stops = stops.map(String::trim).filter { it.isNotEmpty() }.distinct().take(MAX_STOPS),
        routes = routes.map(String::trim).filter { it.isNotEmpty() }.distinct(),
        // No network name, nothing to keep (not even a password).
        guestWifi = guestWifi.takeIf { it.configured } ?: GuestWifi(),
        // Keep the user's order (it decides stacking within a zone); drop duplicates; append anything missing.
        modules = (modules.distinctBy { it.module } + DEFAULT_MODULES.filter { d -> modules.none { it.module == d.module } })
            .map { s -> if (s.zone in ALLOWED_ZONES.getValue(s.module)) s else s.copy(zone = ALLOWED_ZONES.getValue(s.module).first()) },
    )

    companion object {
        const val MAX_STOPS = 6
        /** UI languages, in the order the TV settings cycle through them. */
        val LANGUAGES = listOf("en", "fr", "es")
        const val SCHEMA = 2
        val DEFAULT_MODULES = listOf(
            ModuleSetting(Module.Almanac, zone = Zone.UnderClock),
            ModuleSetting(Module.Bus, zone = Zone.Right),
            ModuleSetting(Module.Weather, zone = Zone.Bottom),
        )
        /** Each module has a layout for these zones only; the first one is its default. */
        val ALLOWED_ZONES = mapOf(
            Module.Almanac to listOf(Zone.UnderClock, Zone.Bottom),
            Module.Bus to listOf(Zone.Right, Zone.Bottom),
            Module.Weather to listOf(Zone.Bottom, Zone.Right),
        )
        private val HOME_REGION = mapOf("en" to "US", "fr" to "FR", "es" to "ES")
        private val AVAILABLE = Locale.getAvailableLocales().toSet()

        /**
         * [language] with the device [region] when the platform has data for that pair (fr-CA, fr-BE, es-MX, en-GB…),
         * else the language's home region (fr-FR, es-ES, en-US): French on a US TV is fr-FR, not a made-up fr-US.
         */
        fun localeFor(language: String, region: String): Locale {
            val lang = language.takeIf { it in LANGUAGES } ?: "en"
            val local = Locale(lang, region.uppercase())
            return if (region.isNotEmpty() && local in AVAILABLE) local else Locale(lang, HOME_REGION.getValue(lang))
        }
    }
}

internal object SettingsSerializer : Serializer<Settings> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    /** No file yet: a fresh install, in the TV's language when the app has it. */
    override val defaultValue = Settings(
        onboarded = false,
        language = Locale.getDefault().language.takeIf { it in Settings.LANGUAGES } ?: "en",
    )
    override suspend fun readFrom(input: InputStream): Settings =
        runCatching { decodeStored(input.readBytes().decodeToString()) }
            .onFailure { Log.w("Almanak", "settings unreadable, using defaults: $it") }
            .getOrDefault(defaultValue)

    override suspend fun writeTo(t: Settings, output: OutputStream) = output.write(json.encodeToString(t).toByteArray())

    /**
     * A file on disk. One written before neutral defaults (no "schema") was saved with Montréal and the STM as
     * defaults: a field it lacks gets that old default back, so an upgrade never moves anyone's TV.
     */
    fun decodeStored(text: String): Settings {
        val obj = json.parseToJsonElement(text).jsonObject
        val migrated = if ("schema" in obj) obj else JsonObject(obj + buildMap {
            if ("city" !in obj) put("city", json.encodeToJsonElement(LegacyDefaults.CITY))
            if ("agency" !in obj) put("agency", json.encodeToJsonElement(TransitAgency.STM))
        })
        return json.decodeFromJsonElement<Settings>(migrated).normalized()
    }

    fun encode(s: Settings) = json.encodeToString(s)
    fun decode(text: String) = json.decodeFromString<Settings>(text).normalized()
}

/** The defaults of schema 1, for migrating its files only. */
internal object LegacyDefaults {
    val CITY = City("Montréal", 45.5019, -73.5674)
}

class SettingsStore(file: File, scope: CoroutineScope) {
    private val store: DataStore<Settings> = DataStoreFactory.create(SettingsSerializer, produceFile = { file }, scope = scope)

    /**
     * Current settings, always available synchronously. The first read blocks once at app start: it's a
     * sub-kilobyte file, and the alternative is a frame of wrong defaults (wrong city, wrong stops).
     */
    val settings: StateFlow<Settings> = store.data.stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first() })

    suspend fun update(transform: (Settings) -> Settings): Settings = store.updateData { transform(it).normalized() }

    companion object {
        fun toJson(s: Settings) = SettingsSerializer.encode(s)
        /** From the phone page: a whole document as the page received it, so no migration. */
        fun fromJson(text: String) = SettingsSerializer.decode(text)
        /** From disk, migrating older files. */
        fun fromStoredJson(text: String) = SettingsSerializer.decodeStored(text)
    }
}
