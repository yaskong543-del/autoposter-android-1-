package com.example.autoposter

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class CalendarKind { DRAFT, SCHEDULED, PUBLISHED, FAILED, OTHER }
enum class CalendarView { MONTH, WEEK, DAY }

data class CalendarEntry(
    val id: String, val fromDraft: Boolean, val kind: CalendarKind, val atMs: Long, val title: String,
    val platform: Platform?, val accountId: String?, val accountLabel: String?, val status: Status?, val text: String
) {
    val canReschedule: Boolean get() = fromDraft || status == Status.QUEUED || status == Status.FAILED || status == Status.NEEDS_AUTH || status == Status.CANCELED
}

data class CalendarFilter(
    val kinds: Set<CalendarKind> = emptySet(), val platforms: Set<Platform> = emptySet(), val accountIds: Set<String> = emptySet()
) {
    fun matches(e: CalendarEntry): Boolean =
        (kinds.isEmpty() || e.kind in kinds) &&
            // Drafts have no single platform or account, so they are hidden while one of those filters is on.
            (platforms.isEmpty() || (e.platform != null && e.platform in platforms)) &&
            (accountIds.isEmpty() || (e.accountId != null && e.accountId in accountIds))
}

/** Everything calendar-related that needs no Android: grids, filters, time-zone maths, rescheduling rules. */
object CalendarLogic {
    fun kindOf(s: Status): CalendarKind = when (s) {
        Status.QUEUED, Status.UPLOADING -> CalendarKind.SCHEDULED
        Status.DONE -> CalendarKind.PUBLISHED
        Status.FAILED, Status.NEEDS_AUTH -> CalendarKind.FAILED
        Status.CANCELED -> CalendarKind.OTHER
    }

    /** A published post sits on the day it finished; everything else on the day it is scheduled for. */
    fun entries(posts: List<PostItem>, drafts: List<ComposerDraftEntity>): List<CalendarEntry> {
        val p = posts.map { i ->
            val at = if (i.status == Status.DONE && i.finishedAt > 0) i.finishedAt else i.scheduledAt
            CalendarEntry(i.id, false, kindOf(i.status), at, i.name, i.platform, i.accountId, i.accountLabel, i.status, i.text)
        }
        val d = drafts.filter { it.plannedAt > 0 }.map { CalendarEntry(it.id, true, CalendarKind.DRAFT, it.plannedAt, it.title.ifBlank { "Draft" }, null, null, null, null, it.baseCaption) }
        return (p + d).sortedBy { it.atMs }
    }

    fun filter(all: List<CalendarEntry>, f: CalendarFilter) = all.filter { f.matches(it) }

    fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun countsByDay(entries: List<CalendarEntry>, zone: ZoneId): Map<LocalDate, Int> = entries.groupingBy { dayOf(it.atMs, zone) }.eachCount()

    fun entriesOn(entries: List<CalendarEntry>, day: LocalDate, zone: ZoneId) = entries.filter { dayOf(it.atMs, zone) == day }.sortedBy { it.atMs }

    /** Weeks of the month, each 7 cells; null = a day of the neighbouring month. */
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<List<LocalDate?>> {
        val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        val cells = ArrayList<LocalDate?>()
        repeat(lead) { cells += null }
        for (d in 1..month.lengthOfMonth()) cells += month.atDay(d)
        while (cells.size % 7 != 0) cells += null
        return cells.chunked(7)
    }

    fun weekDays(anchor: LocalDate, firstDay: DayOfWeek): List<LocalDate> {
        val start = anchor.with(TemporalAdjusters.previousOrSame(firstDay))
        return (0..6).map { start.plusDays(it.toLong()) }
    }

    /** Wall-clock time in [zone] -> UTC instant. A time skipped by a DST jump moves forward; an ambiguous one takes the earlier offset. */
    fun toInstantMs(date: LocalDate, time: LocalTime, zone: ZoneId): Long = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    fun timeOf(ms: Long, zone: ZoneId): LocalTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().withSecond(0).withNano(0)

    /** Same wall-clock time on another day, in the same zone (what "move to Friday" should mean across a DST change). */
    fun moveToDay(ms: Long, newDay: LocalDate, zone: ZoneId): Long = toInstantMs(newDay, timeOf(ms, zone), zone)

    fun zoneOrDefault(id: String?): ZoneId = runCatching { ZoneId.of(id ?: "") }.getOrDefault(ZoneId.systemDefault())

    fun zoneLabel(zone: ZoneId, atMs: Long): String {
        val off = zone.rules.getOffset(Instant.ofEpochMilli(atMs)).id.let { if (it == "Z") "+00:00" else it }
        return "${zone.id} (UTC$off)"
    }
}

sealed interface RescheduleResult {
    data class Ok(val item: PostItem) : RescheduleResult
    data object InPast : RescheduleResult
    data object NotAllowed : RescheduleResult
}

object Reschedule {
    /** QUEUED moves; FAILED / NEEDS_AUTH / CANCELED are re-queued at the new time (attempts reset). Uploading or published posts cannot move. */
    fun post(item: PostItem, newMs: Long, now: Long): RescheduleResult {
        if (item.status == Status.UPLOADING || item.status == Status.DONE) return RescheduleResult.NotAllowed
        if (newMs < now - 60_000L) return RescheduleResult.InPast
        return RescheduleResult.Ok(
            if (item.status == Status.QUEUED) item.copy(scheduledAt = newMs)
            else item.copy(status = Status.QUEUED, scheduledAt = newMs, attempts = 0, error = null, progress = 0f)
        )
    }
}
