package com.shilapi.xcertplay.update

import android.content.Context
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

internal object BackgroundUpdatePolicy {
    fun connectionBusy(hasSession: Boolean, active: Boolean): Boolean = hasSession && !active
}

/** WorkManager can still instantiate requests persisted by an older version. */
internal class BackgroundUpdateWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}

internal object BackgroundUpdateScheduler {
    private const val PERIODIC_WORK = "diplay-periodic-update-check"
    private const val OPEN_WORK = "diplay-open-update-check"

    fun cancelLegacy(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(PERIODIC_WORK)
        manager.cancelUniqueWork(OPEN_WORK)
    }
}
