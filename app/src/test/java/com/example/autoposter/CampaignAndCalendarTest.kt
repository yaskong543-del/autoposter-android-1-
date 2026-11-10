package com.example.autoposter

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

class CampaignPlannerTest {
    private val min = 60_000L
    private val now = 1_800_000_000_000L
    private val a1 = Fx.account("a1", Platform.YOUTUBE, "Main YT"); private val a2 = Fx.account("a2", Platform.INSTAGRAM, "Reels")
    private fun m(i: Int, kind: MediaKind = MediaKind.VIDEO) = CampaignMedia("m$i", "clip$i.mp4", kind, "/lib/m$i", 1000, "fp$i")
    private fun spec(media: List<CampaignMedia>, accounts: List<Account> = listOf(a1, a2), mode: Mode = Mode.ROTATE, start: Long = now + 10 * min, gap: Int = 30, stagger: Int = 0,
                     captions: Map<String, String> = emptyMap(), startFor: Map<String, Long> = emptyMap(), over: Map<String, Long> = emptyMap()) =
        CampaignSpec(media, accounts, mode, ComposerContent("Shared", hashtags = "#x"), captions, start, gap, stagger, startFor, over, seed = 7)

    @Test fun rotateAssignsOneClipPerAccountInTurn() {
        val p = CampaignPlanner.plan(spec(listOf(m(1), m(2), m(3))), now)
        assertEquals(listOf("a1", "a2", "a1"), p.map { it.account.id })
    }

    @Test fun sequentialAndSameClipProduceTheFullMatrix() {
        assertEquals(listOf("a1", "a1", "a2", "a2"), CampaignPlanner.plan(spec(listOf(m(1), m(2)), mode = Mode.SEQUENTIAL), now).map { it.account.id })
        assertEquals(4, CampaignPlanner.plan(spec(listOf(m(1), m(2)), mode = Mode.SAME_CLIP), now).size)
    }

    @Test fun randomUsesEveryClipOnceAndIsRepeatableForTheSameSeed() {
        val s = spec((1..6).map { m(it) }, mode = Mode.RANDOM)
        val one = CampaignPlanner.plan(s, now).map { it.media.id }; val two = CampaignPlanner.plan(s, now).map { it.media.id }
        assertEquals(one, two); assertEquals((1..6).map { "m$it" }.toSet(), one.toSet())
    }

    @Test fun intervalsApplyPerAccount() {
        val p = CampaignPlanner.plan(spec((1..4).map { m(it) }, gap = 30), now)
        val a1Times = p.filter { it.account.id == "a1" }.map { it.scheduledAt }
        assertEquals(listOf(now + 10 * min, now + 40 * min), a1Times)
    }

    @Test fun staggerAndPerAccountStartAndPerPostOverride() {
        val p = CampaignPlanner.plan(spec(listOf(m(1), m(2)), stagger = 5, gap = 0), now)
        assertEquals(now + 10 * min, p[0].scheduledAt); assertEquals(now + 15 * min, p[1].scheduledAt)
        val q = CampaignPlanner.plan(spec(listOf(m(1), m(2)), startFor = mapOf("a2" to now + 500 * min), over = mapOf(CampaignPlanner.key("m1", "a1") to now + 99 * min)), now)
        assertEquals(now + 99 * min, q[0].scheduledAt); assertEquals(now + 500 * min, q[1].scheduledAt)
    }

    @Test fun individualCaptionsOverrideTheSharedOne() {
        val p = CampaignPlanner.plan(spec(listOf(m(1), m(2)), captions = mapOf("m2" to "Only for clip 2")), now)
        assertTrue(p[0].text.startsWith("Shared")); assertTrue(p[1].text.startsWith("Only for clip 2")); assertTrue(p[1].text.endsWith("#x"))
    }

    @Test fun imagesAreBlockedNotFaked() {
        val p = CampaignPlanner.plan(spec(listOf(m(1), m(2, MediaKind.IMAGE))), now)
        assertTrue(PlanWarning.IMAGE_NOT_SUPPORTED in p[1].warnings); assertTrue(p[1].blocked); assertFalse(p[0].blocked)
        val items = CampaignPlanner.toPostItems(p, "camp", { it.path }, now)
        assertEquals(1, items.size); assertEquals("/lib/m1", items.single().filePath)
        assertEquals(CampaignPlanner.Summary(2, 1, 1, 0, p[0].scheduledAt, p[0].scheduledAt, mapOf("Main YT" to 1)), CampaignPlanner.summarize(p))
    }

    @Test fun warnsAboutPastTimesExpiringAccountsDuplicatesAndLongCaptions() {
        val past = CampaignPlanner.plan(spec(listOf(m(1)), start = now - 3_600_000), now)
        assertTrue(PlanWarning.TIME_IN_PAST in past.single().warnings)
        assertEquals(now, CampaignPlanner.toPostItems(past, "c", { it.path }, now).single().scheduledAt)   // never queued in the past

        val expiring = Fx.account("a3", Platform.TIKTOK, "TT", expiresAt = now + 3_600_000)
        assertTrue(PlanWarning.ACCOUNT_NEEDS_ATTENTION in CampaignPlanner.plan(spec(listOf(m(1)), listOf(expiring)), now).single().warnings)

        val hist = listOf(Fx.post("h", Status.DONE, now, acc = "a1", fp = "fp1"))
        val dup = CampaignPlanner.plan(spec(listOf(m(1))), now, hist)
        assertTrue(dup.single().blocked)
        assertFalse(CampaignPlanner.plan(spec(listOf(m(1))), now, listOf(Fx.post("h", Status.FAILED, now, acc = "a1", fp = "fp1"))).single().blocked)

        val long = CampaignPlanner.plan(spec(listOf(m(1)), captions = mapOf("m1" to "y".repeat(6000))), now)
        assertTrue(PlanWarning.OVER_CAPTION_LIMIT in long.single().warnings)
    }

    @Test fun nothingPlannedWithoutMediaOrAccounts() {
        assertTrue(CampaignPlanner.plan(spec(emptyList()), now).isEmpty()); assertTrue(CampaignPlanner.plan(spec(listOf(m(1)), emptyList()), now).isEmpty())
    }
}

class CalendarLogicTest {
    private val ny = ZoneId.of("America/New_York"); private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test fun statusMapsToCalendarKinds() {
        assertEquals(CalendarKind.SCHEDULED, CalendarLogic.kindOf(Status.QUEUED)); assertEquals(CalendarKind.SCHEDULED, CalendarLogic.kindOf(Status.UPLOADING))
        assertEquals(CalendarKind.PUBLISHED, CalendarLogic.kindOf(Status.DONE)); assertEquals(CalendarKind.FAILED, CalendarLogic.kindOf(Status.NEEDS_AUTH))
        assertEquals(CalendarKind.OTHER, CalendarLogic.kindOf(Status.CANCELED))
    }

    @Test fun entriesCombinePostsAndDatedDraftsOnly() {
        val e = CalendarLogic.entries(
            listOf(Fx.post("p1", Status.QUEUED, 2000), Fx.post("p2", Status.DONE, 500, finished = 3000)),
            listOf(Fx.draft("d1", planned = 1000), Fx.draft("d2", planned = 0))
        )
        assertEquals(listOf("d1", "p1", "p2"), e.map { it.id })
        assertEquals(3000L, e.first { it.id == "p2" }.atMs)       // published posts sit on the day they finished
        assertEquals(CalendarKind.DRAFT, e.first().kind)
    }

    @Test fun filtersByKindPlatformAndAccount() {
        val e = CalendarLogic.entries(listOf(Fx.post("p1", Status.QUEUED, 1, acc = "a1"), Fx.post("p2", Status.FAILED, 2, acc = "a2", platform = Platform.TIKTOK), Fx.post("p3", Status.DONE, 3, acc = "a1")), listOf(Fx.draft("d", 4)))
        assertEquals(listOf("p2"), CalendarLogic.filter(e, CalendarFilter(kinds = setOf(CalendarKind.FAILED))).map { it.id })
        assertEquals(listOf("p2"), CalendarLogic.filter(e, CalendarFilter(platforms = setOf(Platform.TIKTOK))).map { it.id })
        assertEquals(listOf("p1", "p3"), CalendarLogic.filter(e, CalendarFilter(accountIds = setOf("a1"))).map { it.id }.sorted())
        assertEquals(4, CalendarLogic.filter(e, CalendarFilter()).size)
    }

    @Test fun monthGridHasWholeWeeksAndCorrectOffsets() {
        val g = CalendarLogic.monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)      // 1 Nov 2026 is a Sunday
        assertTrue(g.all { it.size == 7 }); assertEquals(6, g[0].count { it == null }); assertEquals(LocalDate.of(2026, 11, 1), g[0][6])
        assertEquals(30, g.flatten().count { it != null })
        assertEquals(LocalDate.of(2026, 11, 1), CalendarLogic.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)[0][0])
    }

    @Test fun weekStartsOnTheChosenDay() {
        val w = CalendarLogic.weekDays(LocalDate.of(2026, 11, 11), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 9), w.first()); assertEquals(LocalDate.of(2026, 11, 15), w.last())
        assertEquals(LocalDate.of(2026, 11, 8), CalendarLogic.weekDays(LocalDate.of(2026, 11, 11), DayOfWeek.SUNDAY).first())
    }

    @Test fun theSameInstantLandsOnDifferentDaysInDifferentZones() {
        val ms = Instant.parse("2026-11-09T22:30:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 9), CalendarLogic.dayOf(ms, ny))
        assertEquals(LocalDate.of(2026, 11, 10), CalendarLogic.dayOf(ms, kolkata))
        val e = CalendarLogic.entries(listOf(Fx.post("p", Status.QUEUED, ms)), emptyList())
        assertEquals(1, CalendarLogic.countsByDay(e, kolkata)[LocalDate.of(2026, 11, 10)]); assertNull(CalendarLogic.countsByDay(e, kolkata)[LocalDate.of(2026, 11, 9)])
    }

    @Test fun wallClockToInstantAcrossDst() {
        // New York springs forward on 2026-03-08: 02:30 does not exist and moves to 03:30 EDT.
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), CalendarLogic.toInstantMs(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), ny))
        // It falls back on 2026-11-01: 01:30 happens twice; the earlier (EDT, UTC-4) one is used.
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), CalendarLogic.toInstantMs(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), ny))
        // Ordinary day: 09:00 in Kolkata is 03:30 UTC.
        assertEquals(Instant.parse("2026-11-09T03:30:00Z").toEpochMilli(), CalendarLogic.toInstantMs(LocalDate.of(2026, 11, 9), LocalTime.of(9, 0), kolkata))
    }

    @Test fun movingToAnotherDayKeepsTheWallClockTimeEvenAcrossDst() {
        val sat = CalendarLogic.toInstantMs(LocalDate.of(2026, 3, 7), LocalTime.of(9, 0), ny)      // EST, UTC-5
        val moved = CalendarLogic.moveToDay(sat, LocalDate.of(2026, 3, 9), ny)                     // EDT after the jump
        assertEquals(Instant.parse("2026-03-09T13:00:00Z").toEpochMilli(), moved)
        assertEquals(LocalTime.of(9, 0), CalendarLogic.timeOf(moved, ny))
    }

    @Test fun zoneParsingFallsBackToDevice() {
        assertEquals(kolkata, CalendarLogic.zoneOrDefault("Asia/Kolkata")); assertEquals(ZoneId.systemDefault(), CalendarLogic.zoneOrDefault("Not/AZone"))
        assertEquals(ZoneId.systemDefault(), CalendarLogic.zoneOrDefault(null)); assertTrue(CalendarLogic.zoneLabel(kolkata, 0).contains("+05:30"))
    }

    @Test fun reschedulingRules() {
        val now = 1_000_000L
        val q = Fx.post("q", Status.QUEUED, now + 10_000)
        assertEquals(now + 50_000, ((Reschedule.post(q, now + 50_000, now)) as RescheduleResult.Ok).item.scheduledAt)
        val failed = Fx.post("f", Status.FAILED, now - 5000).copy(attempts = 3, error = "boom", progress = 0.5f)
        val ok = (Reschedule.post(failed, now + 5000, now) as RescheduleResult.Ok).item
        assertEquals(Status.QUEUED, ok.status); assertEquals(0, ok.attempts); assertNull(ok.error); assertEquals(0f, ok.progress, 0f)
        assertEquals(RescheduleResult.InPast, Reschedule.post(q, now - 3_600_000, now))
        assertEquals(RescheduleResult.NotAllowed, Reschedule.post(Fx.post("d", Status.DONE, 1), now + 1000, now))
        assertEquals(RescheduleResult.NotAllowed, Reschedule.post(Fx.post("u", Status.UPLOADING, 1), now + 1000, now))
        assertTrue(CalendarEntry("x", false, CalendarKind.SCHEDULED, 1, "t", null, null, null, Status.QUEUED, "").canReschedule)
        assertFalse(CalendarEntry("x", false, CalendarKind.PUBLISHED, 1, "t", null, null, null, Status.DONE, "").canReschedule)
    }
}

class DashboardStatsTest {
    @Test fun countsUpcomingAndRecentActivity() {
        val now = 100_000L
        val posts = listOf(
            Fx.post("q1", Status.QUEUED, now + 2000), Fx.post("q2", Status.QUEUED, now + 1000), Fx.post("u", Status.UPLOADING, now),
            Fx.post("d1", Status.DONE, now - 10_000, finished = now - 5000), Fx.post("f1", Status.FAILED, now - 20_000, finished = now - 9000),
            Fx.post("n1", Status.NEEDS_AUTH, now - 30_000, acc = "a2"), Fx.post("c", Status.CANCELED, now - 40_000)
        )
        val s = DashboardStats.compute(posts, listOf(Fx.draft("x", updated = now - 1), Fx.draft("y", updated = now - 2)), listOf(Fx.account("a1"), Fx.account("a2", expiresAt = now + 1000)), now)
        assertEquals(2, s.drafts); assertEquals(3, s.scheduled); assertEquals(1, s.published); assertEquals(2, s.failed)
        assertEquals(2, s.connectedAccounts); assertEquals(1, s.accountsNeedingAttention)       // a2 was rejected by the platform
        assertEquals(listOf("q2", "q1"), s.upcoming.map { it.id })
        assertTrue(s.recent.size <= 8); assertEquals(s.recent.sortedByDescending { it.atMs }, s.recent)
        assertTrue(s.recent.any { it.kind == ActivityEntry.Kind.DRAFT }); assertTrue(s.recent.none { it.title == "c.mp4" })
    }

    @Test fun emptyStateIsAllZero() {
        val s = DashboardStats.compute(emptyList(), emptyList(), emptyList(), 1L)
        assertEquals(listOf(0, 0, 0, 0, 0), listOf(s.drafts, s.scheduled, s.published, s.failed, s.connectedAccounts)); assertTrue(s.upcoming.isEmpty() && s.recent.isEmpty())
    }
}
