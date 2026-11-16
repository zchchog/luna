package tv.luna.player.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import tv.luna.player.IptvApp

/** On launch: re-syncs catalogues older than a day, then rematches their EPG links. */
class CatalogueSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as IptvApp).container
        var failed = false
        var synced = false
        for (p in c.db.providerDao().all()) {
            runCatching { c.providerRepository.syncIfStale(p.id) }
                .onSuccess { if (it != null) { c.epgMatcher.rematch(p.id); synced = true } }
                .onFailure { failed = true; Log.w(TAG, "Catalogue sync failed for provider ${p.id}", it) }
        }
        if (synced && c.settings.settings.first().epgUpdateOnPlaylistChange) EpgRefreshWorker.runNow(applicationContext, automatic = true)
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        const val TAG = "IptvSync"

        fun runOnLaunch(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "catalogue-sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CatalogueSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    // Not the instant the app opens: a launch (or several quick ones) shouldn't hit the provider with a burst of requests.
                    .setInitialDelay(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
