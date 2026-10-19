package app.almanak.transit

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.almanak.AlmanakApp
import java.util.concurrent.TimeUnit

/** Background cache of the static schedule — the only work that runs while nothing is on screen. */
class GtfsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as AlmanakApp).graph
        val (stops, routes) = graph.transitSelection()
        return if (graph.gtfs.sync(stops, routes)) Result.success() else Result.retry()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Weekly refresh (ETag makes an unchanged feed a 304), plus an immediate run when [now] is true. */
        fun schedule(context: Context, now: Boolean) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "gtfs-weekly", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GtfsSyncWorker>(7, TimeUnit.DAYS).setConstraints(network).build(),
            )
            if (now) {
                wm.enqueueUniqueWork(
                    "gtfs-now", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<GtfsSyncWorker>().setConstraints(network).build(),
                )
            }
        }
    }
}
