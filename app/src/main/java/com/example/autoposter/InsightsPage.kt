package com.example.autoposter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun InsightsPage(items: List<PostItem>, statsNote: String?, refreshing: Boolean, onRefresh: () -> Unit) {
    val done = items.count { it.status == Status.DONE }
    val failed = items.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH }
    val finished = done + failed
    val rate = if (finished == 0) "–" else "${done * 100 / finished}%"
    val perDay = Insights.publishedPerDay(items)
    val yt = items.filter { it.platform == Platform.YOUTUBE && it.views >= 0 }

    PageList {
        item { SectionTitle(stringResource(R.string.nav_insights), stringResource(R.string.insights_subtitle)) }
        if (items.isEmpty()) {
            item { EmptyState(Icons.Filled.Insights, stringResource(R.string.insights_empty_title), stringResource(R.string.insights_empty_body)) }
            return@PageList
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Published", done.toString(), Modifier.weight(1f))
                StatCard("Success rate", rate, Modifier.weight(1f))
                StatCard("Pending", items.count { it.status.active }.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            }
        }
        item {
            StudioCard {
                Text("Published, last 14 days", fontWeight = FontWeight.Bold)
                BarChart(perDay.map { it.second }, perDay.map { it.first }, MaterialTheme.colorScheme.secondary)
            }
        }
        item {
            StudioCard {
                Text("By platform", fontWeight = FontWeight.Bold)
                Platform.values().forEach { p ->
                    val mine = items.filter { it.platform == p }
                    val d = mine.count { it.status == Status.DONE }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.symbol} ${p.label}", Modifier.width(110.dp), color = platformColor(p))
                        LinearProgressIndicator(progress = { if (mine.isEmpty()) 0f else d.toFloat() / mine.size }, Modifier.weight(1f), color = platformColor(p))
                        Text("  $d/${mine.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        val byAccount = items.groupBy { it.accountId }
        if (byAccount.isNotEmpty()) item {
            StudioCard {
                Text("By account", fontWeight = FontWeight.Bold)
                byAccount.values.sortedByDescending { g -> g.count { it.status == Status.DONE } }.forEach { g ->
                    Row {
                        Text("${g.first().platform.symbol} ${g.first().accountLabel}", Modifier.weight(1f))
                        Muted("${g.count { it.status == Status.DONE }} live · ${g.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH }} failed")
                    }
                }
            }
        }
        item {
            StudioCard {
                Text("YouTube performance", fontWeight = FontWeight.Bold)
                if (yt.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Views", fmtCount(yt.sumOf { it.views }), Modifier.weight(1f))
                        StatCard("Likes", fmtCount(yt.sumOf { it.likes.coerceAtLeast(0) }), Modifier.weight(1f))
                        StatCard("Comments", fmtCount(yt.sumOf { it.comments.coerceAtLeast(0) }), Modifier.weight(1f))
                    }
                    Text("Top videos", fontWeight = FontWeight.SemiBold)
                    yt.sortedByDescending { it.views }.take(5).forEach { v ->
                        Row { Text(v.name, Modifier.weight(1f), maxLines = 1); Muted("${fmtCount(v.views)} views") }
                    }
                }
                if (refreshing && yt.isEmpty()) {
                    Skeleton(Modifier.fillMaxWidth().height(18.dp)); Skeleton(Modifier.fillMaxWidth().height(18.dp))
                }
                Button(onRefresh, Modifier.fillMaxWidth(), enabled = !refreshing) { Text(if (refreshing) "Refreshing…" else "Refresh YouTube stats") }
                statsNote?.let { Muted(it) }
                Muted("Stats come from YouTube's official API and need a token with read access. TikTok and Instagram metrics need extra permissions and aren't connected yet; the numbers above for those platforms are your own publishing counts.")
            }
        }
    }
}
