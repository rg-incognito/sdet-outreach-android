package com.rohitgande.sdetoutreach.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "scrape_and_outreach_periodic"

// ~2h interval matches the phone's existing Termux cron cadence (see TERMUX_SETUP.md).
// Doze/App Standby can delay (not skip) a run by tens of minutes — that's fine, the
// pipeline is idempotent/resumable by construction (Tracker-sheet dedup).
fun schedulePeriodicRun(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request = PeriodicWorkRequestBuilder<ScrapeAndOutreachWorker>(2, TimeUnit.HOURS)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

fun cancelPeriodicRun(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
}

suspend fun isPeriodicRunScheduled(context: Context): Boolean {
    val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
    return infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
}
