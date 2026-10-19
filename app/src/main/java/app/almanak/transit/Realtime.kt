package app.almanak.transit

import android.util.Log
import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.almanak.settings.KeyIn
import app.almanak.settings.TransitAgency
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

/** The realtime facts we use, for the trips we care about only. */
data class RtSnapshot(val fetchedAtMillis: Long, val trips: Map<String, RtTrip>)

data class RtTrip(val canceled: Boolean, val updates: List<RtStopUpdate>)

data class RtStopUpdate(val stopSequence: Int?, val stopId: String?, val timeEpochSec: Long?, val delaySec: Int?, val skipped: Boolean)

sealed interface RtResult {
    data class Ok(val snapshot: RtSnapshot) : RtResult
    data object NoFeed : RtResult
    data object NoKey : RtResult
    data object Unauthorized : RtResult
    data class Failed(val reason: String) : RtResult
}

class RealtimeSource(
    private val http: OkHttpClient,
    private val agency: () -> TransitAgency,
    private val apiKey: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetch(tripIds: Set<String>): RtResult = withContext(Dispatchers.IO) {
        val a = agency()
        if (a.realtimeUrl.isBlank()) return@withContext RtResult.NoFeed
        val key = apiKey()
        if (a.needsKey && key.isBlank()) return@withContext RtResult.NoKey
        try {
            val req = Request.Builder().apply {
                when {
                    !a.needsKey -> url(a.realtimeUrl)
                    a.keyIn == KeyIn.Header -> url(a.realtimeUrl).header(a.keyName, key)
                    else -> url(a.realtimeUrl.toHttpUrl().newBuilder().addQueryParameter(a.keyName, key).build())
                }
            }.build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> RtResult.Unauthorized
                    // Some feeds answer 400 "Invalid API Key" rather than 401.
                    resp.code == 400 && resp.peekBody(256).string().contains("API Key", ignoreCase = true) -> RtResult.Unauthorized
                    !resp.isSuccessful -> RtResult.Failed("HTTP ${resp.code}")
                    else -> RtResult.Ok(parse(resp.body.byteStream(), tripIds, clock()))
                }
            }
        } catch (e: Exception) {
            Log.w("Almanak", "GTFS-RT fetch failed: $e")
            RtResult.Failed(e.javaClass.simpleName)
        }
    }

    companion object {
        fun parse(input: InputStream, tripIds: Set<String>, now: Long): RtSnapshot {
            val feed = GtfsRealtime.FeedMessage.parseFrom(input)
            val trips = HashMap<String, RtTrip>()
            for (entity in feed.entityList) {
                if (!entity.hasTripUpdate()) continue
                val tu = entity.tripUpdate
                val id = tu.trip.tripId
                if (id !in tripIds) continue
                val canceled = tu.trip.scheduleRelationship == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
                val updates = tu.stopTimeUpdateList.map { u ->
                    val ev = when {
                        u.hasDeparture() -> u.departure
                        u.hasArrival() -> u.arrival
                        else -> null
                    }
                    RtStopUpdate(
                        stopSequence = if (u.hasStopSequence()) u.stopSequence else null,
                        stopId = if (u.hasStopId()) u.stopId else null,
                        timeEpochSec = ev?.takeIf { it.hasTime() }?.time,
                        delaySec = ev?.takeIf { it.hasDelay() }?.delay,
                        skipped = u.scheduleRelationship == GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED,
                    )
                }
                trips[id] = RtTrip(canceled, updates)
            }
            return RtSnapshot(now, trips)
        }
    }
}
