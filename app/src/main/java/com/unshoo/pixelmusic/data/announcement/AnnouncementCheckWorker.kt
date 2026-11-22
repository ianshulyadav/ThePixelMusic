package com.unshoo.pixelmusic.data.announcement

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Checks www.pixelmusic.me for a new announcement a few times a day and posts a notification.
 * No push service is needed: one small, CDN-cached request per run.
 */
@HiltWorker
class AnnouncementCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AnnouncementsRepository,
    private val notifier: AnnouncementNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val force = inputData.getBoolean(KEY_FORCE, false)
        repository.takeAnnouncementToNotify(force)?.let(notifier::show)
        Result.success()
    } catch (e: Exception) {
        Timber.w(e, "Announcement check failed")
        Result.success() // try again at the next scheduled run
    }

    companion object {
        private const val WORK_NAME = "pixelmusic_announcement_check"
        private const val INTERVAL_HOURS = 6L
        private const val KEY_FORCE = "force"

        /** Runs the check once right now and always notifies about the newest announcement (testing). */
        fun runNowForTesting(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<AnnouncementCheckWorker>()
                .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnnouncementCheckWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
                1, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
