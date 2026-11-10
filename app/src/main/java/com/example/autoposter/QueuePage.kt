package com.example.autoposter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.res.stringResource

private enum class QFilter(val label: String) { ALL("All"), SCHEDULED("Scheduled"), UPLOADING("Uploading"), DONE("Published"), PROBLEMS("Problems") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuePage(
    items: List<PostItem>, paused: Boolean,
    onRetry: (String) -> Unit, onRetryAll: () -> Unit, onCancel: (String) -> Unit, onCancelPending: () -> Unit,
    onClearFinished: () -> Unit, onTogglePause: () -> Unit
) {
    var filter by remember { mutableStateOf(QFilter.ALL) }

    val problems = items.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH }

    val shown = items.filter { i ->
        val f = when (filter) {
            QFilter.ALL -> true
            QFilter.SCHEDULED -> i.status == Status.QUEUED
            QFilter.UPLOADING -> i.status == Status.UPLOADING
            QFilter.DONE -> i.status == Status.DONE
            QFilter.PROBLEMS -> i.status == Status.FAILED || i.status == Status.NEEDS_AUTH
        }
        f
    }.sortedWith(compareBy<PostItem>({ if (it.status == Status.UPLOADING) 0 else 1 }, { it.scheduledAt }))

    PageList {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("Queue", "${items.count { it.status.active }} active · $problems need attention") }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onTogglePause) { Text(if (paused) "▶ Resume" else "⏸ Pause") }
                if (problems > 0) FilledTonalButton(onRetryAll) { Text("Retry $problems failed") }
                OutlinedButton(onCancelPending) { Text("Cancel pending") }
                OutlinedButton(onClearFinished) { Text("Clear finished") }
            }
        }

        // per-account progress
        val groups = items.filter { it.status != Status.CANCELED }.groupBy { it.accountId }
        if (groups.isNotEmpty()) item {
            StudioCard {
                Text("Progress by account", fontWeight = FontWeight.Bold)
                groups.values.forEach { g ->
                    val done = g.count { it.status == Status.DONE }
                    val live = g.filter { it.status == Status.UPLOADING }.sumOf { it.progress.toDouble() }.toFloat()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text("${g.first().platform.symbol} ${g.first().accountLabel}", Modifier.weight(1f), color = platformColor(g.first().platform), fontWeight = FontWeight.SemiBold)
                            Muted("$done / ${g.size}")
                        }
                        LinearProgressIndicator(progress = { ((done + live) / g.size).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(),
                            color = platformColor(g.first().platform))
                        val bad = g.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH }
                        if (bad > 0) Text("$bad need attention", color = statusColor(Status.FAILED), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QFilter.values().forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }) }
            }
        }
        if (shown.isEmpty()) item {
            EmptyState(Icons.Filled.Schedule, stringResource(R.string.queue_empty_title), stringResource(R.string.queue_empty_body))
        }
        items(shown, key = { it.id }) { i ->
            StudioCard {
                ItemHeader(i)
                if (i.status == Status.UPLOADING) LinearProgressIndicator(progress = { i.progress }, Modifier.fillMaxWidth(), color = platformColor(i.platform))
                i.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (i.status == Status.QUEUED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                if (i.attempts > 1 && i.status != Status.DONE) Muted("Attempt ${i.attempts}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (i.status.retryable) FilledTonalButton({ onRetry(i.id) }) { Text("Retry") }
                    if (i.status == Status.QUEUED || i.status == Status.UPLOADING) OutlinedButton({ onCancel(i.id) }) { Text("Cancel") }
                }
            }
        }
    }
}
