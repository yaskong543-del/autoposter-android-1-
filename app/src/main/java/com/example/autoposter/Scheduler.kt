package com.example.autoposter

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * "run" starts the queue now; timers wake it for the next scheduled item.
 * Timers alternate between slots a/b so a worker never cancels itself when it plans the next wake-up.
 * Android decides the exact wake-up time (battery saver, Doze), so scheduled posts can run late.
 */
object Scheduler {
    private val names = listOf("queue-timer-u", "queue-timer-a", "queue-timer-b")

    private fun constraints(ctx: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (Settings.wifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    fun kick(ctx: Context) {
        if (Settings.paused(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "queue-run", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PostWorker>().setConstraints(constraints(ctx)).setInputData(workDataOf("slot" to "run"))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        )
        scheduleNext(ctx, "u")
    }

    fun scheduleNext(ctx: Context, slot: String) {
        if (Settings.paused(ctx)) return
        val next = PostStore.nextScheduled() ?: return
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(0)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "queue-timer-$slot", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PostWorker>().setConstraints(constraints(ctx))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(workDataOf("slot" to slot)).build()
        )
    }

    fun pause(ctx: Context) {
        Settings.setPaused(ctx, true)
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork("queue-run"); names.forEach { wm.cancelUniqueWork(it) }
    }

    fun resume(ctx: Context) { Settings.setPaused(ctx, false); kick(ctx) }
}
