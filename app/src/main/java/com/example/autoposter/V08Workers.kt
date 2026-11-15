package com.example.autoposter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---- small persisted helpers -----------------------------------------------------------------------------------------------

object V08Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("v08", Context.MODE_PRIVATE)
    fun cellularUploads(ctx: Context) = p(ctx).getBoolean("cellularUploads", false)
    fun setCellularUploads(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("cellularUploads", v).apply()
    fun notifyKind(ctx: Context, kind: String): Boolean = p(ctx).getBoolean("notify_$kind", true)
    fun setNotifyKind(ctx: Context, kind: String, v: Boolean) = p(ctx).edit().putBoolean("notify_$kind", v).apply()
    fun deviceId(ctx: Context): String? = p(ctx).getString("deviceId", null)
    fun setDeviceId(ctx: Context, v: String?) = p(ctx).edit().putString("deviceId", v).apply()
    fun shownIds(ctx: Context): Set<String> = (p(ctx).getString("shown", "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()
    fun addShown(ctx: Context, ids: Collection<String>) {
        val merged = ((p(ctx).getString("shown", "") ?: "").split(',').filter { it.isNotEmpty() } + ids).distinct().takeLast(300)
        p(ctx).edit().putString("shown", merged.joinToString(",")).apply()
    }
    fun lastLocalAlertDay(ctx: Context, key: String): Long = p(ctx).getLong("alert_$key", -1L)
    fun setLastLocalAlertDay(ctx: Context, key: String, day: Long) = p(ctx).edit().putLong("alert_$key", day).apply()
    fun clear(ctx: Context) = p(ctx).edit().clear().apply()
}

class EntitlementCache(private val ctx: Context) {
    private fun p() = ctx.getSharedPreferences("v08", Context.MODE_PRIVATE)
    /** Last server answer, or the strictest plan when none is known. It never grants more than the server did. */
    fun get(): Entitlements = runCatching { Entitlements.fromJson(JSONObject(p().getString("entitlements", "") ?: "")) }.getOrDefault(Entitlements.FREE)
    fun save(e: Entitlements) = p().edit().putString("entitlements", e.toJson()).apply()
    fun clear() = p().edit().remove("entitlements").apply()
}

data class UploadEntry(
    val key: String, val userId: String, val postId: String, val accountId: String, val localPath: String, val mimeType: String,
    val runAtMs: Long, val tz: String, val payloadJson: String, val createdAt: Long
)

/** Durable list of uploads that still have to reach the cloud. WorkManager runs them; this list lets RetryWorker revive lost work. */
class UploadQueueStore(private val file: File) {
    private fun load(): MutableList<UploadEntry> = synchronized(this) {
        if (!file.exists()) return mutableListOf()
        runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                UploadEntry(it.getString("key"), it.getString("userId"), it.getString("postId"), it.getString("accountId"), it.getString("localPath"), it.getString("mimeType"),
                    it.getLong("runAtMs"), it.getString("tz"), it.getString("payloadJson"), it.getLong("createdAt"))
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }
    private fun save(l: List<UploadEntry>) = synchronized(this) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("key", it.key).put("userId", it.userId).put("postId", it.postId).put("accountId", it.accountId)
            .put("localPath", it.localPath).put("mimeType", it.mimeType).put("runAtMs", it.runAtMs).put("tz", it.tz).put("payloadJson", it.payloadJson).put("createdAt", it.createdAt)) }
        AtomicFile.write(file, a.toString())
    }
    fun all(): List<UploadEntry> = load()
    fun get(key: String): UploadEntry? = load().firstOrNull { it.key == key }
    fun put(e: UploadEntry) = synchronized(this) { save(load().filter { it.key != e.key } + e) }
    fun remove(key: String) = synchronized(this) { save(load().filter { it.key != key }) }
    fun clear() = synchronized(this) { file.delete(); Unit }
}

object NetworkProbe {
    fun kind(ctx: Context): NetKind {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetKind.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
            else -> NetKind.CELLULAR   // unknown transports are treated like the costlier case
        }
    }
}

object LocalNotifier {
    fun permitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channelFor(kind: String): Pair<String, String> = when (kind) {
        "publish_success", "publish_failure" -> "alerts" to "Post results"
        "token_expiring", "account_error" -> "accounts" to "Account problems"
        "sync_problem" -> "sync" to "Sync problems"
        else -> "reminders" to "Reminders"
    }

    /** Honors the Android permission and the user's per-kind preference. The same id replaces (never duplicates) a notification. */
    fun show(ctx: Context, id: Int, kind: String, title: String, text: String): Boolean {
        if (!permitted(ctx) || !V08Settings.notifyKind(ctx, kind)) return false
        if ((kind == "publish_success" || kind == "publish_failure") && !Settings.notifyResults(ctx)) return false
        return runCatching {
            val (ch, name) = channelFor(kind)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(ch, name, NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(id, NotificationCompat.Builder(ctx, ch).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(text).setAutoCancel(true).build())
            true
        }.getOrDefault(false)
    }
}

// ---- scheduling ------------------------------------------------------------------------------------------------------------

object V08Scheduler {
    fun uploadName(key: String) = "v08-upload-" + key.take(40)
    const val NOTIFY = "v08-notify"; const val RETRY = "v08-retry"; const val AUTOSYNC = "v08-autosync"; const val SYNC_NOW = "v08-sync-now"
    const val MAX_UPLOAD_RUNS = 8

    /** Queue a video for cloud publishing. Idempotent: the key is stable per (user, post, account), re-adding replaces the same entry. */
    fun enqueueUpload(ctx: Context, entry: UploadEntry, ent: Entitlements) {
        ctx.container.uploadQueue.put(entry)
        enqueueUploadWork(ctx, entry.key, ent)
    }

    fun enqueueUploadWork(ctx: Context, key: String, ent: Entitlements) {
        val net = UploadPolicy.networkType(ent, V08Settings.cellularUploads(ctx))
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Backoff.BASE_MS, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(uploadName(key), ExistingWorkPolicy.KEEP, req)
    }

    fun cancelUpload(ctx: Context, key: String) {
        ctx.container.uploadQueue.remove(key)
        WorkManager.getInstance(ctx).cancelUniqueWork(uploadName(key))
    }

    fun syncNow(ctx: Context, trigger: String) {
        val req = OneTimeWorkRequestBuilder<CloudSyncWorker>().setInputData(workDataOf(CloudSyncWorker.TRIGGER to trigger))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Backoff.BASE_MS, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** Called after sign-in and at app start while signed in. Periodic work survives reboot (WorkManager persists it). */
    fun onSignedIn(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val any = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(NOTIFY, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).setConstraints(any).build())
        wm.enqueueUniquePeriodicWork(RETRY, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RetryWorker>(30, TimeUnit.MINUTES).build())
        applyPlan(ctx, ctx.container.entitlements.get())
        syncNow(ctx, "open")
    }

    /** Automatic sync exists only for plans that have it; removing the plan removes the periodic work (data is untouched). */
    fun applyPlan(ctx: Context, ent: Entitlements) {
        val wm = WorkManager.getInstance(ctx)
        if (ent.autoSync) {
            val req = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES).setInputData(workDataOf(CloudSyncWorker.TRIGGER to "auto"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork(AUTOSYNC, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else wm.cancelUniqueWork(AUTOSYNC)
    }

    fun onSignedOut(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        listOf(NOTIFY, RETRY, AUTOSYNC, SYNC_NOW).forEach { wm.cancelUniqueWork(it) }
        ctx.container.uploadQueue.all().forEach { wm.cancelUniqueWork(uploadName(it.key)) }
        ctx.container.uploadQueue.clear(); ctx.container.syncOutbox.clear(); ctx.container.syncState.clear()
        ctx.container.entitlements.clear(); V08Settings.setDeviceId(ctx, null)
    }
}

// ---- workers ---------------------------------------------------------------------------------------------------------------

/** Uploads one video to private cloud storage (progress, cancellation, backoff), then creates the idempotent server job. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object { const val KEY = "key"; const val PROGRESS = "progress" }

    private fun fail(entry: UploadEntry, text: String): Result {
        applicationContext.container.uploadQueue.remove(entry.key)
        LocalNotifier.show(applicationContext, entry.key.hashCode(), "publish_failure", "Upload failed", text)
        return Result.failure(workDataOf("error" to text))
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val key = inputData.getString(KEY) ?: return Result.failure()
        val c = ctx.container
        val entry = c.uploadQueue.get(key) ?: return Result.success()               // cancelled or already done
        if (runAttemptCount >= V08Scheduler.MAX_UPLOAD_RUNS) return fail(entry, "Could not upload after several attempts.")
        val ent = c.entitlements.get()
        // Defense in depth: the WorkManager constraint already gates the network, this re-checks the plan rule at run time.
        if (!UploadPolicy.decide(ent, V08Settings.cellularUploads(ctx), NetworkProbe.kind(ctx)).allowed) return Result.retry()
        val file = File(entry.localPath)
        if (!file.exists()) return fail(entry, "The video file is no longer on this device.")
        runCatching { setForeground(foreground(0)) }                                // optional: some OEMs refuse; the upload still runs
        return try {
            val path = Idempotency.storagePath(entry.userId, entry.postId, file.extension)
            var last = -1
            c.cloudApi.uploadMedia(path, file, entry.mimeType, { f ->
                val pct = (f * 100).toInt()
                if (pct != last && pct % 5 == 0) { last = pct; kotlinx.coroutines.runBlocking { setProgressAsync(workDataOf(PROGRESS to pct)); runCatching { setForeground(foreground(pct)) } } }
            }, { isStopped })
            c.cloudApi.enqueueJob(entry.postId, entry.accountId, entry.runAtMs, entry.tz, JSONObject(entry.payloadJson), path, entry.key)
            c.uploadQueue.remove(key)
            Result.success()
        } catch (e: ApiException) {
            val plan = PlanErrors.message(e.errorCode)
            when {
                plan != null -> fail(entry, plan)
                e.kind == ErrorKind.NETWORK || e.kind == ErrorKind.SERVER || e.kind == ErrorKind.AUTH || e.status == 429 -> Result.retry()
                else -> fail(entry, "The server rejected this post.")
            }
        } catch (e: UploadHttpException) {
            if (e.retryable || e.status == 401) Result.retry() else fail(entry, if (e.status == 413) "The video is larger than the cloud storage limit." else "Cloud storage rejected the upload.")
        } catch (e: IOException) {
            Result.retry()                                                           // includes our own "cancelled" when the work was stopped
        }
    }

    private fun foreground(pct: Int): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("uploads", "Uploads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(ctx, "uploads").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Uploading video")
            .setContentText("$pct%").setProgress(100, pct, false).setOngoing(true).setOnlyAlertOnce(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7801, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(7801, n)
    }
}

/** Revives uploads whose WorkManager work vanished (cleared app data of WorkManager, cancelled by mistake). Cheap and idempotent. */
class RetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext; val c = ctx.container
        val ent = c.entitlements.get()
        val wm = WorkManager.getInstance(ctx)
        for (e in c.uploadQueue.all()) {
            val infos = withContext(Dispatchers.IO) { wm.getWorkInfosForUniqueWork(V08Scheduler.uploadName(e.key)).get() }
            if (infos.none { !it.state.isFinished }) V08Scheduler.enqueueUploadWork(ctx, e.key, ent)
        }
        return Result.success()
    }
}

/** Fetches server notifications, shows each once (respecting permission and preferences), and records delivery. */
class NotificationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext; val c = ctx.container
        if (c.auth.currentSession == null) return Result.success()
        val now = System.currentTimeMillis()
        val list = try { c.cloudApi.notifications() } catch (e: ApiException) {
            return if (e.kind == ErrorKind.NETWORK || e.kind == ErrorKind.SERVER) Result.retry() else Result.success()
        }
        val plan = NotificationPlanner.plan(list, V08Settings.shownIds(ctx), { V08Settings.notifyKind(ctx, it) }, LocalNotifier.permitted(ctx), now)
        val shown = plan.toShow.filter { LocalNotifier.show(ctx, it.id.hashCode(), it.kind, it.title, it.body) }.map { it.id }
        V08Settings.addShown(ctx, shown)
        val ack = plan.toMarkDelivered.filter { id -> id in shown || plan.toShow.none { it.id == id } }
        runCatching { c.cloudApi.markDelivered(ack, now) }
        return Result.success()
    }
}

/** Registers the device, refreshes entitlements, pushes the outbox, pulls remote changes. Trigger "auto" is Premium-only (server-enforced). */
class CloudSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object { const val TRIGGER = "trigger" }

    override suspend fun doWork(): Result {
        val ctx = applicationContext; val c = ctx.container
        val session = c.auth.currentSession ?: return Result.success()
        val trigger = inputData.getString(TRIGGER) ?: "open"
        try {
            val ent = c.cloudApi.entitlements(); c.entitlements.save(ent); V08Scheduler.applyPlan(ctx, ent)
            if (trigger == "auto" && !ent.autoSync) return Result.success()
            var deviceId = V08Settings.deviceId(ctx)
            if (deviceId == null) {
                deviceId = c.cloudApi.registerDevice(c.prefs.installId(), "${Build.MANUFACTURER} ${Build.MODEL}".trim(), BuildConfig.VERSION_NAME, Build.VERSION.RELEASE)
                V08Settings.setDeviceId(ctx, deviceId)
            }
            PostStore.init(ctx)
            val engine = c.syncEngine(deviceId)
            val dao = c.db.drafts()
            val outboxIds = c.syncOutbox.all().map { it.id }.toSet()
            DraftChangeScanner.scan(dao, c.syncState, engine, dao.observe().first().map { it.id }, outboxIds)
            val out = engine.run(trigger)
            return when (out.status) {
                SyncStatus.OK -> Result.success()
                SyncStatus.AUTO_SYNC_NOT_ALLOWED -> { WorkManager.getInstance(ctx).cancelUniqueWork(V08Scheduler.AUTOSYNC); Result.success() }
                SyncStatus.DEVICE_REVOKED -> { c.auth.signOut(false); V08Scheduler.onSignedOut(ctx); Result.success() }   // the server removed this device: end the local session too
                SyncStatus.DEVICE_SUSPENDED -> { problem(ctx, session.user.id, "This device is over your plan's device limit, so it is not syncing."); Result.success() }
                SyncStatus.OFFLINE -> Result.retry()
                SyncStatus.ERROR -> { if (runAttemptCount >= 3) problem(ctx, session.user.id, "Sync keeps failing. We will keep trying."); Result.retry() }
            }
        } catch (e: ApiException) {
            if (e.errorCode == "PL003") { c.auth.signOut(false); V08Scheduler.onSignedOut(ctx); return Result.success() }
            PlanErrors.message(e.errorCode)?.let { problem(ctx, session.user.id, it); return Result.success() }
            return if (runAttemptCount >= 5) Result.success() else Result.retry()
        }
    }

    /** At most one sync-problem notification per day. */
    private fun problem(ctx: Context, userId: String, text: String) {
        val day = System.currentTimeMillis() / 86_400_000L
        val key = NotificationPlanner.localKey("sync_problem", userId.take(8), day)
        if (V08Settings.lastLocalAlertDay(ctx, "sync_problem") == day) return
        if (LocalNotifier.show(ctx, key.hashCode(), "sync_problem", "Sync problem", text)) V08Settings.setLastLocalAlertDay(ctx, "sync_problem", day)
    }
}
