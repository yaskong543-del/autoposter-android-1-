package com.example.autoposter

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
private fun kindColor(k: CalendarKind): Color = when (k) {
    CalendarKind.DRAFT -> MaterialTheme.colorScheme.tertiary
    CalendarKind.SCHEDULED -> statusColor(Status.QUEUED)
    CalendarKind.PUBLISHED -> statusColor(Status.DONE)
    CalendarKind.FAILED -> statusColor(Status.FAILED)
    CalendarKind.OTHER -> statusColor(Status.CANCELED)
}

private fun kindLabel(k: CalendarKind) = when (k) {
    CalendarKind.DRAFT -> R.string.v7_cal_drafts; CalendarKind.SCHEDULED -> R.string.v7_cal_scheduled
    CalendarKind.PUBLISHED -> R.string.v7_cal_published; CalendarKind.FAILED -> R.string.v7_cal_failed; CalendarKind.OTHER -> R.string.v7_cal_other
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(
    posts: List<PostItem>, drafts: List<ComposerDraftEntity>, accounts: List<Account>, zoneId: String,
    onZone: (String) -> Unit, onCreate: () -> Unit, onOpenDraft: (String) -> Unit
) {
    val ctx = LocalContext.current
    val container = ctx.container
    val scope = rememberCoroutineScope()
    val zone: ZoneId = CalendarLogic.zoneOrDefault(zoneId.ifBlank { null })
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    var view by remember { mutableStateOf(CalendarView.MONTH) }
    var anchor by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    var filter by remember { mutableStateOf(CalendarFilter()) }
    var zonePicker by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<CalendarEntry?>(null) }

    val all = remember(posts, drafts) { CalendarLogic.entries(posts, drafts) }
    val shown = remember(all, filter) { CalendarLogic.filter(all, filter) }
    val counts = remember(shown, zone) { CalendarLogic.countsByDay(shown, zone) }
    val unscheduledDrafts = drafts.filter { it.plannedAt <= 0 && (filter.kinds.isEmpty() || CalendarKind.DRAFT in filter.kinds) && filter.platforms.isEmpty() && filter.accountIds.isEmpty() }

    if (zonePicker) ZonePickerDialog(zoneId, { zonePicker = false }) { onZone(it); zonePicker = false }
    moving?.let { e ->
        DateTimeDialog(e.atMs, zone, { moving = null }) { ms ->
            val now = System.currentTimeMillis()
            if (e.fromDraft) {
                scope.launch { container.db.drafts().get(e.id)?.let { container.db.drafts().upsert(it.copy(plannedAt = ms, zoneId = zoneId, updatedAt = now)) } }
            } else {
                val cur = PostStore.get(e.id)
                if (cur != null) when (val r = Reschedule.post(cur, ms, now)) {
                    is RescheduleResult.Ok -> { PostStore.update(cur.id) { r.item }; Scheduler.kick(ctx) }
                    RescheduleResult.InPast -> Toast.makeText(ctx, ctx.getString(R.string.v7_resched_past), Toast.LENGTH_LONG).show()
                    RescheduleResult.NotAllowed -> Toast.makeText(ctx, ctx.getString(R.string.v7_resched_no), Toast.LENGTH_LONG).show()
                }
            }
            moving = null
        }
    }

    val days: List<LocalDate> = when (view) {
        CalendarView.MONTH -> emptyList()
        CalendarView.WEEK -> CalendarLogic.weekDays(anchor, firstDay)
        CalendarView.DAY -> listOf(anchor)
    }
    fun step(dir: Long) { anchor = when (view) { CalendarView.MONTH -> anchor.plusMonths(dir); CalendarView.WEEK -> anchor.plusWeeks(dir); CalendarView.DAY -> anchor.plusDays(dir) } }

    PageList {
        item { SectionTitle(stringResource(R.string.nav_calendar), stringResource(R.string.calendar_subtitle)) }
        item {
            StudioCard {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalendarView.entries.forEach { v ->
                        FilterChip(view == v, { view = v }, { Text(stringResource(when (v) { CalendarView.MONTH -> R.string.v7_view_month; CalendarView.WEEK -> R.string.v7_view_week; CalendarView.DAY -> R.string.v7_view_day })) })
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton({ anchor = LocalDate.now(zone) }) { Text(stringResource(R.string.v7_today)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ step(-1) }) { Text("‹") }
                    val title = when (view) {
                        CalendarView.MONTH -> YearMonth.from(anchor).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                        CalendarView.WEEK -> days.first().format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) + " – " + days.last().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
                        CalendarView.DAY -> anchor.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.getDefault()))
                    }
                    Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    TextButton({ step(1) }) { Text("›") }
                }
                TextButton({ zonePicker = true }) { Text(stringResource(R.string.v7_showing_in, CalendarLogic.zoneLabel(zone, System.currentTimeMillis()))) }
            }
        }
        item {
            StudioCard {
                Text(stringResource(R.string.v7_filters), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalendarKind.entries.forEach { k ->
                        FilterChip(k in filter.kinds, { filter = filter.copy(kinds = if (k in filter.kinds) filter.kinds - k else filter.kinds + k) }, { Text(stringResource(kindLabel(k))) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Platform.entries.forEach { p -> FilterChip(p in filter.platforms, { filter = filter.copy(platforms = if (p in filter.platforms) filter.platforms - p else filter.platforms + p) }, { Text(p.label) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    accounts.forEach { a -> FilterChip(a.id in filter.accountIds, { filter = filter.copy(accountIds = if (a.id in filter.accountIds) filter.accountIds - a.id else filter.accountIds + a.id) }, { Text(a.label, maxLines = 1) }) }
                }
                if (filter != CalendarFilter()) TextButton({ filter = CalendarFilter() }) { Text(stringResource(R.string.v7_clear)) }
            }
        }

        if (all.isEmpty() && drafts.isEmpty()) {
            item { EmptyState(Icons.Filled.CalendarMonth, stringResource(R.string.calendar_empty_title), stringResource(R.string.calendar_empty_body), actionLabel = stringResource(R.string.nav_create), onAction = onCreate) }
            return@PageList
        }

        if (view == CalendarView.MONTH) {
            item {
                StudioCard {
                    val grid = CalendarLogic.monthGrid(YearMonth.from(anchor), firstDay)
                    Row(Modifier.fillMaxWidth()) {
                        (0..6).forEach { i -> Text(firstDay.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
                    }
                    grid.forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { d ->
                                Box(Modifier.weight(1f).height(48.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                                    if (d != null) {
                                        val n = counts[d] ?: 0
                                        val sel = d == anchor
                                        Column(
                                            Modifier.fillMaxSize().background(
                                                if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (n > 0) 1f else 0.4f), RoundedCornerShape(10.dp)
                                            ).clickable { anchor = d },
                                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                                        ) {
                                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelMedium, fontWeight = if (d == LocalDate.now(zone)) FontWeight.Black else FontWeight.Normal)
                                            if (n > 0) Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Text(anchor.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())), style = MaterialTheme.typography.titleSmall) }
            entryRows(CalendarLogic.entriesOn(shown, anchor, zone), zone, onOpenDraft) { moving = it }
        } else {
            days.forEach { d ->
                item(key = "h-$d") { Text(d.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())) + "  ·  ${counts[d] ?: 0}", style = MaterialTheme.typography.titleSmall) }
                entryRows(CalendarLogic.entriesOn(shown, d, zone), zone, onOpenDraft) { moving = it }
            }
        }
        if (unscheduledDrafts.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.v7_unscheduled_drafts)) }
            items(unscheduledDrafts, key = { "ud-" + it.id }) { d ->
                StudioCard(Modifier.clickable { onOpenDraft(d.id) }) {
                    Text(d.title.ifBlank { stringResource(R.string.v7_untitled) }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Muted(d.baseCaption.take(80))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.entryRows(
    list: List<CalendarEntry>, zone: ZoneId, onOpenDraft: (String) -> Unit, onMove: (CalendarEntry) -> Unit
) {
    if (list.isEmpty()) { item { Muted("—") }; return }
    items(list, key = { (if (it.fromDraft) "d-" else "p-") + it.id }) { e ->
        StudioCard(if (e.fromDraft) Modifier.clickable { onOpenDraft(e.id) } else Modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.platform != null) { PlatformLogo(e.platform, 28.dp); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Muted(java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(e.atMs).atZone(zone)) + (e.accountLabel?.let { " · $it" } ?: ""))
                }
                Pill(e.status?.label ?: stringResource(kindLabel(e.kind)), kindColor(e.kind))
            }
            if (e.canReschedule) TextButton({ onMove(e) }) { Text(stringResource(R.string.v7_reschedule)) }
        }
    }
}
