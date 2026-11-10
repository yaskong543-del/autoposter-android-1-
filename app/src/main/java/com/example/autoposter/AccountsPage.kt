package com.example.autoposter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private class DialogSpec(val existing: Account?, val platform: Platform, val reconnect: Boolean)

@Composable
private fun healthColor(h: AccountHealth) = when (h) {
    AccountHealth.OK -> MaterialTheme.colorScheme.secondary
    AccountHealth.EXPIRING -> MaterialTheme.colorScheme.tertiary
    AccountHealth.EXPIRED, AccountHealth.NEEDS_RECONNECT -> MaterialTheme.colorScheme.error
    AccountHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun healthLabel(h: AccountHealth) = when (h) {
    AccountHealth.OK -> R.string.acct_status_connected
    AccountHealth.EXPIRING -> R.string.acct_status_expiring
    AccountHealth.EXPIRED -> R.string.acct_status_expired
    AccountHealth.NEEDS_RECONNECT -> R.string.acct_status_reconnect
    AccountHealth.UNKNOWN -> R.string.acct_status_unknown
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsPage(
    accounts: List<Account>, activeId: String?, items: List<PostItem>,
    onSave: (Account) -> Unit, onRename: (String, String) -> Unit, onRemove: (String) -> Unit, onActive: (String) -> Unit
) {
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var renaming by remember { mutableStateOf<Account?>(null) }
    var removing by remember { mutableStateOf<Account?>(null) }
    val now = System.currentTimeMillis()
    val needsAuth = AccountHealthCheck.needsAuthIds(items)
    val health = accounts.associate { it.id to AccountHealthCheck.of(it, now, it.id in needsAuth) }
    val attention = accounts.filter { health[it.id]?.needsAttention == true }

    PageList {
        item { SectionTitle(stringResource(R.string.acct_title), stringResource(R.string.acct_subtitle)) }
        if (attention.isNotEmpty()) item {
            Banner(stringResource(R.string.acct_attention, attention.joinToString { it.label }), warning = true,
                action = stringResource(R.string.acct_reconnect), onAction = { dialog = DialogSpec(attention.first(), attention.first().platform, true) })
        }
        item {
            StudioCard {
                Text(stringResource(R.string.acct_connect), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Platform.values().forEach { p ->
                        OutlinedButton({ dialog = DialogSpec(null, p, false) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            PlatformLogo(p, 24.dp); Text("  " + p.label)
                        }
                    }
                }
            }
        }
        if (accounts.isEmpty()) item { EmptyState(Icons.Filled.ManageAccounts, stringResource(R.string.acct_empty_title), stringResource(R.string.acct_empty_body)) }
        Platform.values().forEach { p ->
            val list = accounts.filter { it.platform == p }
            if (list.isNotEmpty()) {
                item(key = "h-${p.name}") { Text("${p.label} · ${list.size}", style = MaterialTheme.typography.titleSmall, color = platformColor(p)) }
                items(list, key = { it.id }) { a ->
                    val mine = items.filter { it.accountId == a.id }
                    val h = health.getValue(a.id)
                    StudioCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PlatformLogo(a.platform, 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = MaterialTheme.typography.titleMedium)
                                Muted(a.platform.label + if (a.id == activeId) " · " + stringResource(R.string.acct_active) else "")
                            }
                            Pill(stringResource(healthLabel(h)), healthColor(h))
                        }
                        Muted(
                            when {
                                TokenRefresher.refreshable(a) -> stringResource(R.string.acct_auto_renews)
                                a.expiresAt > 0 -> stringResource(R.string.acct_expires, fmtTime(a.expiresAt))
                                else -> stringResource(R.string.acct_expiry_unknown)
                            } + " · " + stringResource(R.string.acct_counts, mine.count { it.status == Status.DONE }, mine.count { it.status == Status.QUEUED })
                        )
                        if (a.platform.visibilityOptions.isNotEmpty()) Muted(stringResource(R.string.acct_posts_as, a.visibility))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (h.needsAttention) Button({ dialog = DialogSpec(a, a.platform, true) }) { Text(stringResource(R.string.acct_reconnect)) }
                            else OutlinedButton({ dialog = DialogSpec(a, a.platform, true) }) { Text(stringResource(R.string.acct_reconnect)) }
                            if (a.id != activeId) FilledTonalButton({ onActive(a.id) }) { Text(stringResource(R.string.acct_switch)) }
                            OutlinedButton({ dialog = DialogSpec(a, a.platform, false) }) { Text(stringResource(R.string.action_edit)) }
                            OutlinedButton({ renaming = a }) { Text(stringResource(R.string.action_rename)) }
                            OutlinedButton({ removing = a }) { Text(stringResource(R.string.acct_disconnect), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        item { Banner(stringResource(R.string.acct_dev_note), warning = false) }
    }

    dialog?.let { d -> AccountDialog(d, onDismiss = { dialog = null }, onSave = { onSave(it); dialog = null }) }
    renaming?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.label) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.acct_rename_title)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.acct_label)) }) },
            confirmButton = { TextButton({ if (name.isNotBlank()) onRename(a.id, name.trim()); renaming = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton({ renaming = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    removing?.let { a ->
        val pending = items.count { it.accountId == a.id && it.status.active }
        AlertDialog(
            onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.acct_disconnect_title, a.label)) },
            text = { Text(stringResource(if (pending > 0) R.string.acct_disconnect_pending else R.string.acct_disconnect_body, pending)) },
            confirmButton = { TextButton({ onRemove(a.id); removing = null }) { Text(stringResource(R.string.acct_disconnect), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.acct_keep)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDialog(spec: DialogSpec, onDismiss: () -> Unit, onSave: (Account) -> Unit) {
    val existing = spec.existing
    val platform = spec.platform
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var token by remember { mutableStateOf(if (spec.reconnect) "" else existing?.accessToken ?: "") }
    var refresh by remember { mutableStateOf(if (spec.reconnect) "" else existing?.refreshToken ?: "") }
    var clientId by remember { mutableStateOf(existing?.clientId ?: "") }
    var igId by remember { mutableStateOf(existing?.igUserId ?: "") }
    var hours by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf(existing?.visibility ?: platform.defaultVisibility) }
    var show by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when {
                existing == null -> stringResource(R.string.acct_add_title, platform.label)
                spec.reconnect -> stringResource(R.string.acct_reconnect_title, existing.label)
                else -> stringResource(R.string.acct_edit_title, existing.label)
            })
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(stringResource(R.string.acct_dialog_note))
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.acct_label_hint)) })
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.acct_token)) },
                    visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(show, { show = it }); Text(stringResource(R.string.acct_show_token)) }
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.acct_valid_hours)) })
                if (platform == Platform.YOUTUBE) {
                    OutlinedTextField(refresh, { refresh = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.acct_refresh_token)) },
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.acct_client_id)) })
                }
                if (platform == Platform.INSTAGRAM)
                    OutlinedTextField(igId, { igId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.acct_ig_id)) })
                if (platform.visibilityOptions.isNotEmpty()) {
                    Muted(stringResource(R.string.acct_visibility))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        platform.visibilityOptions.forEach { v -> FilterChip(visibility == v, { visibility = v }, { Text(v.lowercase().replace('_', ' ')) }) }
                    }
                }
                Muted(when (platform) {
                    Platform.YOUTUBE -> stringResource(R.string.acct_scope_youtube)
                    Platform.INSTAGRAM -> stringResource(R.string.acct_scope_instagram)
                    Platform.TIKTOK -> stringResource(R.string.acct_scope_tiktok)
                })
            }
        },
        confirmButton = {
            TextButton({
                if (label.isNotBlank() && token.isNotBlank()) {
                    val h = hours.toLongOrNull() ?: 0L
                    val newToken = token.trim()
                    val expires = when {
                        h > 0 -> System.currentTimeMillis() + h * 3_600_000L
                        existing != null && existing.accessToken == newToken -> existing.expiresAt
                        else -> 0L
                    }
                    onSave(
                        (existing ?: Account(platform = platform, label = label, accessToken = newToken)).copy(
                            label = label.trim(), accessToken = newToken, visibility = visibility,
                            refreshToken = refresh.trim().ifBlank { if (spec.reconnect) existing?.refreshToken else null }, clientId = clientId.trim().ifBlank { null },
                            igUserId = igId.trim().ifBlank { null }, expiresAt = expires
                        )
                    )
                }
            }) { Text(stringResource(R.string.acct_save_secure)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
