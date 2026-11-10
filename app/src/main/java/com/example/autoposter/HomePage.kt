package com.example.autoposter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier.clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

@Composable
fun HomePage(
    tier: Tier, command: String, onCommand: (String) -> Unit, onSpeak: () -> Unit, onBuild: () -> Unit,
    draft: Draft, accounts: List<Account>, items: List<PostItem>,
    wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit, paused: Boolean, onTogglePause: () -> Unit,
    userName: String?, attentionAccounts: Int, onNavigate: (String) -> Unit,
    dashboard: DashboardStats? = null, aiUsage: AiUsage? = null
) = PageList {
    item {
        Box(Modifier.fillMaxWidth().background(heroBrush(), RoundedCornerShape(28.dp)).padding(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!userName.isNullOrBlank()) Text(stringResource(R.string.home_greeting, userName), color = Color(0xFFE3E4F5), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(stringResource(R.string.home_tagline), color = Color(0xFFE3E4F5), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(stringResource(R.string.metric_videos), draft.videos.size.toString())
                    Metric(stringResource(R.string.metric_accounts), accounts.size.toString())
                    Metric(stringResource(R.string.metric_queued), items.count { it.status == Status.QUEUED }.toString())
                    Metric(stringResource(R.string.metric_live), items.count { it.status == Status.DONE }.toString())
                }
            }
        }
    }
    val problems = items.count { it.status == Status.FAILED || it.status == Status.NEEDS_AUTH }
    if (attentionAccounts > 0) item {
        Banner(stringResource(R.string.home_attention_accounts, attentionAccounts), action = stringResource(R.string.nav_accounts), onAction = { onNavigate(Route.ACCOUNTS) })
    }
    if (problems > 0) item {
        Banner(stringResource(R.string.home_attention_posts, problems), action = stringResource(R.string.nav_queue), onAction = { onNavigate(Route.QUEUE) })
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Filled.ManageAccounts, stringResource(R.string.nav_accounts), Modifier.weight(1f)) { onNavigate(Route.ACCOUNTS) }
                QuickTile(Icons.Filled.VideoLibrary, stringResource(R.string.nav_media), Modifier.weight(1f)) { onNavigate(Route.MEDIA) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Filled.Settings, stringResource(R.string.nav_settings), Modifier.weight(1f)) { onNavigate(Route.SETTINGS) }
                QuickTile(Icons.Filled.WorkspacePremium, stringResource(R.string.nav_subscription), Modifier.weight(1f)) { onNavigate(Route.SUBSCRIPTION) }
            }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTile(Icons.Filled.AutoAwesome, stringResource(R.string.v7_composer_title), Modifier.weight(1f)) { onNavigate(Route.COMPOSER) }
            QuickTile(Icons.Filled.Campaign, stringResource(R.string.v7_campaign_title), Modifier.weight(1f)) { onNavigate(Route.CAMPAIGN) }
        }
    }
    if (dashboard != null) dashboardItems(dashboard, aiUsage, onNavigate)
    item { SectionTitle(stringResource(R.string.home_quick_title), stringResource(R.string.home_quick_sub)) }
    item {
        StudioCard {
            OutlinedTextField(
                command, onCommand, Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(16.dp),
                label = { Text("Describe your publishing job") },
                placeholder = { Text("Post 20 videos on tiktok and youtube caption \"Summer\" #travel tomorrow at 9am every 30 minutes") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onSpeak, Modifier.weight(1f)) { Icon(Icons.Filled.Mic, null); Text("  Speak") }
                Button(onBuild, Modifier.weight(1f)) { Text("Build campaign →") }
            }
            Muted("Understands: count, caption(s) split with |, #hashtags, sound note, account names, \"on youtube/instagram/tiktok\", \"all accounts\", folder, \"every 5 minutes\", \"at 6pm\", \"tomorrow at 9\", \"in 2 hours\".")
        }
    }
    val upcoming = items.filter { it.status == Status.QUEUED }.sortedBy { it.scheduledAt }.take(3)
    item { SectionTitle(stringResource(R.string.home_up_next), stringResource(R.string.home_up_next_sub)) }
    if (accounts.isEmpty()) item {
        EmptyState(Icons.Filled.ManageAccounts, stringResource(R.string.acct_empty_title), stringResource(R.string.acct_empty_body),
            actionLabel = stringResource(R.string.nav_accounts), onAction = { onNavigate(Route.ACCOUNTS) })
    }
    else if (upcoming.isEmpty()) item {
        EmptyState(Icons.Filled.AddCircle, stringResource(R.string.home_nothing_title), stringResource(R.string.home_nothing_body),
            actionLabel = stringResource(R.string.nav_create), onAction = { onNavigate(Route.CREATE) })
    }
    else upcoming.forEach { i -> item(key = "up-${i.id}") { StudioCard { ItemHeader(i) } } }

    item { SectionTitle(stringResource(R.string.home_delivery)) }
    item {
        StudioCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Wi‑Fi only", fontWeight = FontWeight.SemiBold); Muted("Hold uploads until you're on an unmetered network") }
                Switch(wifiOnly, onWifiOnly)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(if (paused) "Queue paused" else "Queue running", fontWeight = FontWeight.SemiBold); Muted("Pause stops all uploads; progress resets for the video in flight") }
                OutlinedButton(onTogglePause) { Text(if (paused) "Resume" else "Pause") }
            }
            Muted("Engine: $tier · Android may delay scheduled posts when battery saver is on. Keep battery optimisation off for this app.")
        }
    }
}

@Composable
fun ItemHeader(i: PostItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlatformLogo(i.platform, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(i.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Muted("${i.accountLabel} · ${fmtTime(i.scheduledAt)}")
        }
        Pill(i.status.label, statusColor(i.status))
    }
}
