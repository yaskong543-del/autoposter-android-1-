package com.example.autoposter

import androidx.work.NetworkType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class V08PolicyTest {
    private val free = Entitlements.FREE
    private val pro = Entitlements("pro", null, 5, 500_000, true, true, true, null)

    @Test fun entitlementsParseServerSnapshot() {
        val e = Entitlements.fromJson(JSONObject("""{"plan":"pro","max_profiles":null,"max_devices":5,"ai_tokens_monthly":500000,"auto_sync":true,"cellular_uploads":true,"advanced_automation":true,"max_pending_jobs":null}"""))
        assertEquals(pro, e); assertTrue(e.premium); assertNull(e.maxProfiles)
        val f = Entitlements.fromJson(JSONObject("""{"plan":"free","max_profiles":2,"max_devices":1,"ai_tokens_monthly":5000,"auto_sync":false,"cellular_uploads":false,"advanced_automation":false,"max_pending_jobs":10}"""))
        assertEquals(free, f); assertFalse(f.premium)
        assertEquals(pro, Entitlements.fromJson(JSONObject(pro.toJson())))
        assertEquals(free, Entitlements.fromJson(JSONObject("{}")).copy(maxPendingJobs = 10))   // missing fields fall back to the strictest plan
    }

    @Test fun freeIsWifiOnlyPremiumNeedsOptIn() {
        assertEquals(UploadReason.WIFI_ONLY_PLAN, UploadPolicy.decide(free, true, NetKind.CELLULAR).reason)
        assertTrue(UploadPolicy.decide(free, false, NetKind.WIFI).allowed)
        assertEquals(UploadReason.CELLULAR_DISABLED, UploadPolicy.decide(pro, false, NetKind.CELLULAR).reason)
        assertTrue(UploadPolicy.decide(pro, true, NetKind.CELLULAR).allowed)
        assertEquals(UploadReason.OFFLINE, UploadPolicy.decide(pro, true, NetKind.NONE).reason)
        assertEquals(NetworkType.UNMETERED, UploadPolicy.networkType(free, true))
        assertEquals(NetworkType.UNMETERED, UploadPolicy.networkType(pro, false))
        assertEquals(NetworkType.CONNECTED, UploadPolicy.networkType(pro, true))
    }

    @Test fun backoffGrowsIsBoundedAndJittered() {
        assertEquals(15_000L, Backoff.delayMs(1, 0.0))
        assertTrue(Backoff.delayMs(1, 0.999999) < 30_000L)
        assertTrue(Backoff.delayMs(5, 0.0) > Backoff.delayMs(2, 0.999999) - 1)
        assertTrue(Backoff.delayMs(100, 0.999999) <= Backoff.MAX_MS)
        assertEquals(Backoff.delayMs(1, 0.0), Backoff.delayMs(0, 0.0))
    }

    @Test fun idempotencyKeyMatchesServerImplementation() {
        // Vector produced by supabase/functions/_shared/jobs.ts idempotencyKey('user-1','post-1','acct-1')
        assertEquals("237aa8f2c46cfcb2dbf75c665f0ba4b39e2eb49bb3609e27b6aa50a3a83a85f6", Idempotency.key("user-1", "post-1", "acct-1"))
        assertTrue(Idempotency.key("a", "b", "c") != Idempotency.key("a", "b", "d"))
    }

    @Test fun storagePathStaysInsideUserFolderAndSanitizes() {
        assertEquals("u1/post-1.mp4", Idempotency.storagePath("u1", "post-1", "MP4"))
        assertEquals("u1/x.mp4", Idempotency.storagePath("u1", "../../x/", "!!"))
        assertTrue(Idempotency.storagePath("u1", "a/b\\c", "mov").startsWith("u1/"))
        assertFalse(Idempotency.storagePath("u1", "../evil", "mp4").contains(".."))
    }

    @Test fun scheduledTimeIsDstCorrect() {
        val ny = ZoneId.of("America/New_York")
        // Spring forward 2026-03-08: 02:30 does not exist -> 03:30 EDT (UTC-4) = 07:30Z
        assertEquals(LocalDateTime.of(2026, 3, 8, 7, 30).toInstant(ZoneOffset.UTC).toEpochMilli(), ScheduleTime.toUtcMs(LocalDateTime.of(2026, 3, 8, 2, 30), ny))
        // Fall back 2026-11-01: 01:30 happens twice -> the earlier one, EDT (UTC-4) = 05:30Z
        assertEquals(LocalDateTime.of(2026, 11, 1, 5, 30).toInstant(ZoneOffset.UTC).toEpochMilli(), ScheduleTime.toUtcMs(LocalDateTime.of(2026, 11, 1, 1, 30), ny))
        // Ordinary day in a half-hour zone
        assertEquals(LocalDateTime.of(2026, 7, 1, 6, 30).toInstant(ZoneOffset.UTC).toEpochMilli(), ScheduleTime.toUtcMs(LocalDateTime.of(2026, 7, 1, 12, 0), ZoneId.of("Asia/Kolkata")))
    }

    private fun n(id: String, kind: String = "publish_success", created: Long = 1_000, delivered: Long = 0) = ServerNotification(id, kind, "t", "b", null, created, delivered)

    @Test fun notificationPlannerRespectsPermissionPreferencesAndDedupe() {
        val now = 10_000L
        // no permission: nothing shown and nothing marked (retry later)
        assertEquals(NotificationPlan(emptyList(), emptyList()), NotificationPlanner.plan(listOf(n("a")), emptySet(), { true }, false, now))
        val p = NotificationPlanner.plan(listOf(n("a"), n("b", delivered = 5), n("c", "token_expiring"), n("d")), setOf("d"), { it != "token_expiring" }, true, now)
        assertEquals(listOf("a"), p.toShow.map { it.id })
        assertEquals(setOf("a", "d"), p.toMarkDelivered.toSet())          // "d" was shown before: only mark it; "b" already delivered; "c" disabled stays untouched
        // stale rows are acknowledged without being shown
        val stale = NotificationPlanner.plan(listOf(n("old", created = 1)), emptySet(), { true }, true, 1 + NotificationPlanner.MAX_AGE_MS + 1)
        assertTrue(stale.toShow.isEmpty()); assertEquals(listOf("old"), stale.toMarkDelivered)
        // bounded per run
        val many = (1..9).map { n("x$it", created = it.toLong()) }
        assertEquals(NotificationPlanner.MAX_PER_RUN, NotificationPlanner.plan(many, emptySet(), { true }, true, now).toShow.size)
        assertEquals("sync_problem:u:20400", NotificationPlanner.localKey("sync_problem", "u", 20400))
    }

    @Test fun planErrorMessages() {
        assertTrue(PlanErrors.message("PL001")!!.contains("profiles"))
        assertTrue(PlanErrors.message("plan_limit_autosync")!!.contains("Premium"))
        assertNull(PlanErrors.message("something_else"))
    }
}
