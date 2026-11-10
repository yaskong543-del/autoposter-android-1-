package com.example.autoposter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Calendar

private fun dayKey(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 10000 + it.get(Calendar.MONTH) * 100 + it.get(Calendar.DAY_OF_MONTH) }

/** Month grid of scheduled posts. Moved out of the queue screen in 0.6; behaviour is unchanged. */
@Composable
fun CalendarPage(items: List<PostItem>, onCreate: () -> Unit) {
    var month by remember { mutableStateOf(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    val counts = remember(items) { items.groupingBy { dayKey(it.scheduledAt) }.eachCount() }

    PageList {
        item { SectionTitle(stringResource(R.string.nav_calendar), stringResource(R.string.calendar_subtitle)) }
        if (items.isEmpty()) {
            item { EmptyState(Icons.Filled.CalendarMonth, stringResource(R.string.calendar_empty_title), stringResource(R.string.calendar_empty_body), actionLabel = stringResource(R.string.nav_create), onAction = onCreate) }
            return@PageList
        }
        item {
            StudioCard {
                val first = month.clone() as Calendar
                val offset = (first.get(Calendar.DAY_OF_WEEK) - first.firstDayOfWeek + 7) % 7
                val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
                val monthName = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(first.time)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ month = (month.clone() as Calendar).apply { add(Calendar.MONTH, -1) }; selectedDay = null }) { Text("‹") }
                    Text(monthName, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    TextButton({ month = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }; selectedDay = null }) { Text("›") }
                }
                val cells = offset + days
                for (row in 0 until (cells + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val d = row * 7 + col - offset + 1
                            Box(Modifier.weight(1f).height(46.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (d in 1..days) {
                                    val key = first.get(Calendar.YEAR) * 10000 + first.get(Calendar.MONTH) * 100 + d
                                    val n = counts[key] ?: 0
                                    val sel = selectedDay == key
                                    Column(
                                        Modifier.fillMaxSize().background(
                                            if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (n > 0) 1f else 0.4f),
                                            RoundedCornerShape(10.dp)
                                        ).clickable { selectedDay = if (sel) null else key },
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("$d", style = MaterialTheme.typography.labelMedium)
                                        if (n > 0) Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
                Muted(stringResource(if (selectedDay == null) R.string.calendar_hint else R.string.calendar_hint_selected))
            }
        }
        val shown = items.filter { selectedDay == null || dayKey(it.scheduledAt) == selectedDay }.sortedBy { it.scheduledAt }
        items(shown.take(100), key = { it.id }) { i -> StudioCard { ItemHeader(i) } }
        if (shown.size > 100) item { Muted(stringResource(R.string.calendar_more, shown.size - 100)) }
    }
}
