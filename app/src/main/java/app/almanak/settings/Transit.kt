package app.almanak.settings

import kotlinx.serialization.Serializable

/** How a realtime feed wants its key: in a request header, or as a URL parameter (the Mobility Database's two kinds). */
enum class KeyIn { Header, Query }

/**
 * A transit network: where its static GTFS lives and, optionally, its GTFS-realtime trip updates. Presets carry
 * known-good URLs; [CUSTOM_ID] is whatever the user pastes on the phone page.
 */
@Serializable
data class TransitAgency(
    val id: String,
    val name: String,
    /** Static GTFS zips, tried in order: the agency's own first, then a mirror (some agencies block VPN exit IPs). */
    val gtfsUrls: List<String>,
    /** GTFS-realtime TripUpdates; blank = timetable only. */
    val realtimeUrl: String = "",
    /** The key's header or parameter name; blank = the realtime feed needs no key. */
    val keyName: String = "",
    val keyIn: KeyIn = KeyIn.Header,
    /** Where to get a key, linked from the phone page. */
    val keyHelp: String = "",
    /** The data licence to credit in the TV's Sources, when the network asks for one. */
    val licence: String = "",
) {
    val needsKey: Boolean get() = realtimeUrl.isNotBlank() && keyName.isNotBlank()
    val configured: Boolean get() = id.isNotEmpty()

    /** Presets are always taken from the app, so a stored copy picks up URL fixes; custom URLs must be http(s). */
    fun normalized(): TransitAgency {
        if (id.isBlank()) return NONE
        PRESETS.firstOrNull { it.id == id }?.let { return it }
        fun ok(u: String) = u.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return copy(
            id = CUSTOM_ID,
            name = name.trim().ifEmpty { "GTFS" },
            gtfsUrls = gtfsUrls.mapNotNull(::ok).distinct(),
            realtimeUrl = ok(realtimeUrl).orEmpty(),
            keyName = keyName.trim(),
        )
    }

    companion object {
        const val CUSTOM_ID = "custom"
        /** No network chosen yet: nothing to download, no bus block. */
        val NONE = TransitAgency("", "", emptyList())
        private fun mirror(mdb: String) = "https://files.mobilitydatabase.org/$mdb/latest.zip"

        val STM = TransitAgency(
            "stm", "STM · Montréal",
            listOf("https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip", mirror("mdb-2126")),
            realtimeUrl = "https://api.stm.info/pub/od/gtfs-rt/ic/v2/tripUpdates",
            keyName = "apikey", keyIn = KeyIn.Header,
            keyHelp = "https://portail.developpeurs.stm.info/apihub/",
            licence = "CC BY 4.0",
        )
        val RTC = TransitAgency(
            "rtc", "RTC · Québec",
            listOf("https://cdn.rtcquebec.ca/Site_Internet/DonneesOuvertes/googletransit.zip", mirror("mdb-757")),
        )
        val OC_TRANSPO = TransitAgency(
            "octranspo", "OC Transpo · Ottawa",
            listOf("https://oct-gtfs-emasagcnfmcgeham.z01.azurefd.net/public-access/GTFSExport.zip", mirror("mdb-2154")),
            realtimeUrl = "https://nextrip-public-api.azure-api.net/octranspo/gtfs-rt-tp/beta/v1/TripUpdates",
            keyName = "apikey", keyIn = KeyIn.Query,
            keyHelp = "https://nextrip-public-api.developer.azure-api.net/",
        )
        // TTC's own URL is plain http, which Android refuses: the mirror only.
        val TTC = TransitAgency("ttc", "TTC · Toronto", listOf(mirror("mdb-732")), realtimeUrl = "https://bustime.ttc.ca/gtfsrt/trips")
        val STS = TransitAgency(
            "sts", "STS · Sherbrooke",
            listOf("https://gtfs.sts.qc.ca:8443/gtfs/client/GTFS_clients.zip", mirror("mdb-1996")),
            realtimeUrl = "https://gtfs.sts.qc.ca:8443/gtfsrt/tripUpdates.pb",
        )
        val PRESETS = listOf(STM, RTC, OC_TRANSPO, TTC, STS)
    }
}

/**
 * The realtime key for [agencyId]: the one typed on the phone, else the build-time key when it was built for this
 * network ([buildKeyAgency]), else the build-time key of the old `stm.apiKey` / `STM_API_KEY` property, which was
 * always an STM key. A build-time key is never sent to another network.
 */
fun realtimeKeyFor(agencyId: String, userKey: String, buildKey: String, buildKeyAgency: String, legacyBuildKey: String): String = when {
    userKey.isNotBlank() -> userKey
    buildKey.isNotBlank() && agencyId.isNotEmpty() && agencyId == buildKeyAgency -> buildKey
    legacyBuildKey.isNotBlank() && agencyId == TransitAgency.STM.id -> legacyBuildKey
    else -> ""
}
