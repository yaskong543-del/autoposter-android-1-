package com.example.autoposter

import androidx.work.NetworkType
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.math.pow

/** Pure rules for the v0.8 cloud engine. The SERVER is authoritative; these mirror it for UX and for deciding when to even try. */

enum class NetKind { NONE, WIFI, CELLULAR }

data class Entitlements(
    val plan: String, val maxProfiles: Int?, val maxDevices: Int, val aiTokensMonthly: Int,
    val autoSync: Boolean, val cellularUploads: Boolean, val advancedAutomation: Boolean, val maxPendingJobs: Int?
) {
    val premium: Boolean get() = plan != "free"

    fun toJson(): String = JSONObject().put("plan", plan).put("max_profiles", maxProfiles ?: JSONObject.NULL).put("max_devices", maxDevices)
        .put("ai_tokens_monthly", aiTokensMonthly).put("auto_sync", autoSync).put("cellular_uploads", cellularUploads)
        .put("advanced_automation", advancedAutomation).put("max_pending_jobs", maxPendingJobs ?: JSONObject.NULL).toString()

    companion object {
        /** Offline fallback (strictest plan). It is only ever used to RESTRICT behaviour while the real values are unknown. */
        val FREE = Entitlements("free", 2, 1, 5_000, false, false, false, 10)

        fun fromJson(o: JSONObject): Entitlements {
            val plan = o.optString("plan", "free")
            val freePlan = plan == "free"
            return Entitlements(
                plan = plan,
                maxProfiles = when {
                    !o.has("max_profiles") -> if (freePlan) 2 else null
                    o.isNull("max_profiles") -> null
                    else -> o.getInt("max_profiles")
                },
                maxDevices = o.optInt("max_devices", 1),
                aiTokensMonthly = o.optInt("ai_tokens_monthly", 5_000),
                autoSync = o.optBoolean("auto_sync", false),
                cellularUploads = o.optBoolean("cellular_uploads", false),
                advancedAutomation = o.optBoolean("advanced_automation", false),
                maxPendingJobs = when {
                    !o.has("max_pending_jobs") -> if (freePlan) 10 else null
                    o.isNull("max_pending_jobs") -> null
                    else -> o.getInt("max_pending_jobs")
                }
            )
        }
    }
}

enum class UploadReason { OK, OFFLINE, WIFI_ONLY_PLAN, CELLULAR_DISABLED }
data class UploadDecision(val allowed: Boolean, val reason: UploadReason)

object UploadPolicy {
    /** FREE: Wi-Fi only. PREMIUM: cellular only when the user also switched it on. */
    fun decide(e: Entitlements, userAllowsCellular: Boolean, net: NetKind): UploadDecision = when {
        net == NetKind.NONE -> UploadDecision(false, UploadReason.OFFLINE)
        net == NetKind.WIFI -> UploadDecision(true, UploadReason.OK)
        !e.cellularUploads -> UploadDecision(false, UploadReason.WIFI_ONLY_PLAN)
        !userAllowsCellular -> UploadDecision(false, UploadReason.CELLULAR_DISABLED)
        else -> UploadDecision(true, UploadReason.OK)
    }

    /** WorkManager constraint matching [decide]: UNMETERED = Wi-Fi style networks, CONNECTED = any network. */
    fun networkType(e: Entitlements, userAllowsCellular: Boolean): NetworkType =
        if (e.cellularUploads && userAllowsCellular) NetworkType.CONNECTED else NetworkType.UNMETERED
}

object Backoff {
    const val BASE_MS = 30_000L
    const val MAX_MS = 6 * 3_600_000L
    /** Exponential with "equal jitter" (half fixed, half random), the same formula the server worker uses. [rnd] in [0,1). */
    fun delayMs(attempt: Int, rnd: Double): Long {
        val n = attempt.coerceAtLeast(1)
        val cap = min(MAX_MS.toDouble(), BASE_MS * 2.0.pow(n - 1)).toLong()
        return (cap / 2 + (rnd.coerceIn(0.0, 0.999999) * (cap / 2)).toLong())
    }
}

object Idempotency {
    /** One intended publication = (owner, post, account). Must equal supabase/functions/_shared/jobs.ts idempotencyKey(). */
    fun key(owner: String, postId: String, accountId: String): String =
        MessageDigest.getInstance("SHA-256").digest("v1|$owner|$postId|$accountId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Safe object name inside the user's own storage folder (the server also checks the folder). */
    fun storagePath(userId: String, postId: String, ext: String): String {
        val safeExt = ext.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
        val safeId = postId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifEmpty { "post" }
        return "$userId/$safeId.$safeExt"
    }
}

object ScheduleTime {
    /**
     * Exact UTC instant for a wall-clock time in [zone]. Spring-forward gap: moved forward by the gap length (02:30 -> 03:30).
     * Fall-back overlap: the EARLIER of the two instants. This is what is sent to the server as `run_at`.
     */
    fun toUtcMs(local: LocalDateTime, zone: ZoneId): Long = ZonedDateTime.ofLocal(local, zone, null).toInstant().toEpochMilli()
}

// ---- notifications ----------------------------------------------------------------------------------------------------------

data class ServerNotification(val id: String, val kind: String, val title: String, val body: String, val ref: String?, val createdAt: Long, val deliveredAt: Long)
data class NotificationPlan(val toShow: List<ServerNotification>, val toMarkDelivered: List<String>)

object NotificationPlanner {
    const val MAX_AGE_MS = 24 * 3_600_000L
    const val MAX_PER_RUN = 5

    /**
     * Decides what to show. Server rows are already de-duplicated per (owner, dedupe_key); [shownIds] guards against showing the
     * same server row twice when marking it delivered failed. Stale rows are marked delivered without being shown.
     * Without the notification permission nothing is shown or marked, so it is retried once the user grants it.
     */
    fun plan(incoming: List<ServerNotification>, shownIds: Set<String>, kindEnabled: (String) -> Boolean, permissionGranted: Boolean, nowMs: Long): NotificationPlan {
        if (!permissionGranted) return NotificationPlan(emptyList(), emptyList())
        val show = ArrayList<ServerNotification>(); val mark = ArrayList<String>()
        for (n in incoming.sortedBy { it.createdAt }) {
            if (n.deliveredAt > 0) continue
            if (n.id in shownIds) { mark += n.id; continue }
            if (nowMs - n.createdAt > MAX_AGE_MS) { mark += n.id; continue }
            if (!kindEnabled(n.kind)) continue
            if (show.size >= MAX_PER_RUN) break
            show += n; mark += n.id
        }
        return NotificationPlan(show, mark)
    }

    /** Local-only problems (e.g. repeated sync failures) use a per-day key so they never spam. */
    fun localKey(kind: String, ref: String, dayEpoch: Long): String = "$kind:$ref:$dayEpoch"
}
