package com.cocakova.kouros.run

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cocakova.kouros.app
import java.util.concurrent.TimeUnit

/**
 * The fallback when no foreground service may run: asks each server about the phone's active
 * runs, records the finished ones, and reschedules itself while any remain.
 */
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val active = app.db.runs().activeList()
        for (serverId in active.map { it.serverId }.distinct()) {
            app.sessions.byId(serverId)?.let { app.runs.reconcile(it) }
        }
        if (app.db.runs().activeList().isNotEmpty()) schedule(applicationContext, delayMinutes = 2)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, delayMinutes: Long = 1) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reconcile",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReconcileWorker>()
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
