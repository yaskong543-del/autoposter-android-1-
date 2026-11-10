package com.example.autoposter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun BrandScreen(brands: List<BrandProfile>) {
    val repo = LocalContext.current.container.brands
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<BrandProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<BrandProfile?>(null) }

    if (creating || editing != null) BrandEditor(editing, { creating = false; editing = null }) { id, n, d, t, a, h, l, c, s ->
        scope.launch { repo.save(id, n, d, t, a, h, l, c, s) }
        creating = false; editing = null
    }
    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.v7_brand_delete_title, b.name)) },
            text = { Text(stringResource(R.string.v7_brand_delete_body)) },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = { TextButton({ scope.launch { repo.delete(b.id) }; confirmDelete = null }) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) } }
        )
    }

    PageList {
        item { SectionTitle(stringResource(R.string.v7_brands_title), stringResource(R.string.v7_brands_sub)) }
        item { Button({ creating = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.v7_brand_new)) } }
        if (brands.isEmpty()) item { EmptyState(Icons.Filled.AutoAwesome, stringResource(R.string.v7_brands_empty_title), stringResource(R.string.v7_brands_empty_body)) }
        items(brands, key = { it.id }) { b ->
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(b.name, fontWeight = FontWeight.Bold); Muted(AiLanguages.label(b.language)) }
                    Pill(toneLabel(b.toneEnum), MaterialTheme.colorScheme.primary)
                }
                if (b.description.isNotBlank()) Text(b.description, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                if (b.hashtags.isNotBlank()) Muted(b.hashtags)
                Row { TextButton({ editing = b }) { Text(stringResource(R.string.action_edit)) }; TextButton({ confirmDelete = b }) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}

@Composable
private fun BrandEditor(
    b: BrandProfile?, onDismiss: () -> Unit,
    onSave: (String?, String, String, AiTone, String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(b?.name.orEmpty()) }
    var desc by remember { mutableStateOf(b?.description.orEmpty()) }
    var tone by remember { mutableStateOf(b?.toneEnum ?: AiTone.CASUAL) }
    var audience by remember { mutableStateOf(b?.audience.orEmpty()) }
    var tags by remember { mutableStateOf(b?.hashtags.orEmpty()) }
    var lang by remember { mutableStateOf(b?.language ?: "en") }
    var cta by remember { mutableStateOf(b?.cta.orEmpty()) }
    var sig by remember { mutableStateOf(b?.signature.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (b == null) R.string.v7_brand_new else R.string.action_edit)) },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton({ onSave(b?.id, name, desc, tone, audience, tags, lang, cta, sig) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.v7_brand_name)) })
                OutlinedTextField(desc, { desc = it.take(1000) }, Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(R.string.v7_brand_desc)) }, shape = RoundedCornerShape(14.dp))
                PickerField(stringResource(R.string.v7_tone), toneLabel(tone), AiTone.entries.map { it to toneLabel(it) }, { tone = it })
                OutlinedTextField(audience, { audience = it.take(300) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_brand_audience)) })
                OutlinedTextField(tags, { tags = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_brand_tags)) })
                PickerField(stringResource(R.string.v7_language), AiLanguages.label(lang), AiLanguages.all.map { it.code to it.label }, { lang = it })
                OutlinedTextField(cta, { cta = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_brand_cta)) })
                OutlinedTextField(sig, { sig = it.take(300) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_brand_signature)) }, supportingText = { Text(stringResource(R.string.v7_brand_signature_hint)) })
            }
        }
    )
}

/** Dashboard block on the Home tab. Pure display over [DashboardStats]. */
fun androidx.compose.foundation.lazy.LazyListScope.dashboardItems(stats: DashboardStats, usage: AiUsage?, onNavigate: (String) -> Unit) {
    item { SectionTitle(stringResource(R.string.v7_dash_title)) }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.v7_dash_drafts), stats.drafts.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                StatCard(stringResource(R.string.v7_dash_scheduled), stats.scheduled.toString(), Modifier.weight(1f), statusColor(Status.QUEUED))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.v7_dash_published), stats.published.toString(), Modifier.weight(1f), statusColor(Status.DONE))
                StatCard(stringResource(R.string.v7_dash_failed), stats.failed.toString(), Modifier.weight(1f), statusColor(Status.FAILED))
            }
        }
    }
    item {
        StudioCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.v7_dash_accounts), style = MaterialTheme.typography.titleSmall)
                    Muted(stringResource(R.string.v7_dash_accounts_line, stats.connectedAccounts, stats.accountsNeedingAttention))
                }
                TextButton({ onNavigate(Route.ACCOUNTS) }) { Text(stringResource(R.string.nav_accounts)) }
            }
        }
    }
    item { StudioCard { Text(stringResource(R.string.v7_dash_ai), style = MaterialTheme.typography.titleSmall); TokenMeter(usage) } }
    item { SectionTitle(stringResource(R.string.v7_dash_upcoming)) }
    if (stats.upcoming.isEmpty()) item { Muted(stringResource(R.string.v7_dash_none)) }
    else items(stats.upcoming, key = { "dash-up-" + it.id }) { i -> StudioCard { ItemHeader(i) } }
    item { SectionTitle(stringResource(R.string.v7_dash_recent)) }
    if (stats.recent.isEmpty()) item { Muted(stringResource(R.string.v7_dash_none)) }
    else items(stats.recent, key = { "dash-r-${it.atMs}-${it.title}-${it.kind}" }) { a ->
        StudioCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Muted((if (a.detail.isNotBlank()) a.detail + " · " else "") + fmtTime(a.atMs))
                }
                Pill(stringResource(when (a.kind) {
                    ActivityEntry.Kind.PUBLISHED -> R.string.v7_cal_published; ActivityEntry.Kind.FAILED -> R.string.v7_cal_failed
                    ActivityEntry.Kind.SCHEDULED -> R.string.v7_cal_scheduled; ActivityEntry.Kind.DRAFT -> R.string.v7_cal_drafts
                }), when (a.kind) {
                    ActivityEntry.Kind.PUBLISHED -> statusColor(Status.DONE); ActivityEntry.Kind.FAILED -> statusColor(Status.FAILED)
                    ActivityEntry.Kind.SCHEDULED -> statusColor(Status.QUEUED); ActivityEntry.Kind.DRAFT -> MaterialTheme.colorScheme.tertiary
                })
            }
        }
    }
}
