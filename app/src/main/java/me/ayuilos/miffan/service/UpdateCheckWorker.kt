package me.ayuilos.miffan.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.ayuilos.miffan.utils.UpdateChecker
import me.ayuilos.miffan.utils.UpdateDownloadState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** Checks for a new version once a day while Miffan is closed, so the reminder does not wait for the next launch. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val checker: UpdateChecker by inject()

    override suspend fun doWork(): Result {
        checker.checkIfDueNow()
        // A Wi-Fi download started by the check finishes within this job's lifetime.
        withTimeoutOrNull(9.minutes) {
            checker.downloadState.first {
                it !is UpdateDownloadState.Downloading && it !is UpdateDownloadState.Verifying
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "app_update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
