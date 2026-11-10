package com.example.autoposter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Publishes every due item. Accounts run in parallel (max 2 uploads at once), each account's items run
 * in order. Each item is retried with back-off; expired tokens are refreshed when possible.
 */
class PostWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private val running = Mutex()
        private const val MAX_ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(0, 2 * 60_000L, 10 * 60_000L)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Preparing uploads…")

    private fun info(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("posting", "Posting", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "posting")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("AutoPoster")
            .setContentText(text).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1, n)
    }

    private suspend fun banner(text: String) { try { setForeground(info(text)) } catch (_: Exception) { } }

    override suspend fun doWork(): Result {
        PostStore.init(applicationContext)
        if (Settings.paused(applicationContext)) return Result.success()
        if (!running.tryLock()) return Result.success()
        try {
            PostStore.resetStuck()
            val doneBefore = PostStore.items.value.count { it.status == Status.DONE }
            val badBefore = PostStore.items.value.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH }
            val store = AccountStore(applicationContext)
            val gate = Semaphore(2)
            while (!isStopped) {
                val due = PostStore.due(System.currentTimeMillis())
                if (due.isEmpty()) break
                coroutineScope {
                    due.groupBy { it.accountId }.values.forEach { list ->
                        launch {
                            for (item in list) {
                                if (isStopped) break
                                gate.withPermit { processOne(item.id, store) }
                            }
                        }
                    }
                }
            }
            runCatching { withContext(Dispatchers.IO) { CloudSync.pushIfEnabled(applicationContext) } }
            val now = PostStore.items.value
            val newDone = now.count { it.status == Status.DONE } - doneBefore
            val newBad = now.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH } - badBefore
            if ((newDone > 0 || newBad > 0) && !isStopped)
                Alerts.post(applicationContext, 2, if (newBad > 0) "Some posts need attention" else "Posts published",
                    "$newDone published" + if (newBad > 0) ", $newBad failed. Open the Queue tab to retry." else ".")
            runCatching { PostStore.flush() }
            val next = when (inputData.getString("slot")) { "a" -> "b"; else -> "a" }
            Scheduler.scheduleNext(applicationContext, next)
            return Result.success()
        } finally { running.unlock() }
    }

    private suspend fun processOne(id: String, store: AccountStore) {
        val item = PostStore.get(id) ?: return
        if (item.status != Status.QUEUED) return
        val acc = store.get(item.accountId)
        if (acc == null) { fail(id, "Account was removed"); return }
        val file = File(item.filePath)
        if (!file.exists()) { fail(id, "Video file is missing"); return }

        PostStore.update(id) { it.copy(status = Status.UPLOADING, progress = 0f, attempts = it.attempts + 1, error = null) }
        val active = PostStore.items.value.count { it.status == Status.UPLOADING }
        val left = PostStore.items.value.count { it.status.active }
        banner("[${acc.label}] ${item.name} · $left left ($active uploading)")
        PostLog.add("[${acc.label}] uploading ${item.name}")

        try {
            val remote = withContext(Dispatchers.IO) {
                val fresh = TokenRefresher.ensureFresh(store, acc)
                var last = 0f
                uploaderFor(item.platform).upload(fresh, file, item.text, { p ->
                    if (p - last >= 0.02f) { last = p; PostStore.update(id, save = false) { it.copy(progress = p) } }
                }, { isStopped })
            }
            PostStore.update(id) { it.copy(status = Status.DONE, progress = 1f, remoteId = remote, finishedAt = System.currentTimeMillis(), error = null) }
            PostLog.add("[${item.accountLabel}] published ${item.name} ($remote)")
            if (!PostStore.fileInUse(item.filePath, id)) Outbox.delete(listOf(item.filePath))
        } catch (e: kotlinx.coroutines.CancellationException) {
            PostStore.update(id) { it.copy(status = Status.QUEUED, progress = 0f, attempts = (it.attempts - 1).coerceAtLeast(0)) }
            throw e
        } catch (e: UploadException) {
            onFailure(id, item, store, e.message ?: "Upload failed", e.retryable, e.authExpired)
        } catch (e: IOException) {
            onFailure(id, item, store, e.message ?: "Network error", retryable = true, auth = false)
        } catch (e: Exception) {
            fail(id, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun onFailure(id: String, item: PostItem, store: AccountStore, msg: String, retryable: Boolean, auth: Boolean) {
        val now = System.currentTimeMillis()
        val cur = PostStore.get(id) ?: return
        PostLog.add("[${item.accountLabel}] ${item.name}: $msg")
        when {
            isStopped -> PostStore.update(id) { it.copy(status = Status.QUEUED, progress = 0f, attempts = (it.attempts - 1).coerceAtLeast(0), error = null) }
            auth -> {
                val acc = store.get(item.accountId)
                val renewed = acc?.let { a -> withContext(Dispatchers.IO) { TokenRefresher.forceRefresh(store, a) } }
                if (renewed != null && cur.attempts < MAX_ATTEMPTS)
                    PostStore.update(id) { it.copy(status = Status.QUEUED, progress = 0f, scheduledAt = now, error = "Token renewed, retrying") }
                else PostStore.update(id) { it.copy(status = Status.NEEDS_AUTH, progress = 0f, error = "Sign in again for '${item.accountLabel}'. $msg") }
            }
            retryable && cur.attempts < MAX_ATTEMPTS -> {
                val wait = BACKOFF_MS[cur.attempts.coerceIn(0, BACKOFF_MS.size - 1)].coerceAtLeast(60_000L)
                PostStore.update(id) { it.copy(status = Status.QUEUED, progress = 0f, scheduledAt = now + wait, error = "Retrying: $msg") }
            }
            else -> fail(id, msg)
        }
    }

    private fun fail(id: String, msg: String) =
        PostStore.update(id) { it.copy(status = Status.FAILED, progress = 0f, error = msg, finishedAt = System.currentTimeMillis()) }
}
