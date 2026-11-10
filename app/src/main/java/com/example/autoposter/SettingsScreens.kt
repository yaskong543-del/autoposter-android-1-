package com.example.autoposter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

object Route {
    const val HOME = "home"; const val CREATE = "create"; const val CALENDAR = "calendar"; const val QUEUE = "queue"; const val INSIGHTS = "insights"
    const val ACCOUNTS = "accounts"; const val MEDIA = "media"; const val SETTINGS = "settings"; const val SUBSCRIPTION = "subscription"
    const val PROFILE = "profile"; const val APPEARANCE = "appearance"; const val LANGUAGE = "language"; const val NOTIFICATIONS = "notifications"
    const val SECURITY = "security"; const val DEVICES = "devices"; const val PRIVACY = "privacy"; const val HELP = "help"
    const val COMPOSER = "composer"; const val CAMPAIGN = "campaign"; const val BRANDS = "brands"
    val tabs = listOf(HOME, CREATE, CALENDAR, QUEUE, INSIGHTS)
}

@Composable
private fun msg(m: UiMessage): String = if (m.detail != null) stringResource(m.res, m.detail) else stringResource(m.res)

@Composable
private fun MessageBanner(vm: SettingsViewModel) {
    val m by vm.message.collectAsStateWithLifecycle()
    m?.let { Banner(msg(it), warning = it.res.let { r -> r != R.string.info_saved && r != R.string.info_device_removed && r != R.string.info_reset_sent }, action = stringResource(R.string.action_ok), onAction = vm::dismissMessage) }
}

@Composable
private fun displayName(vm: SettingsViewModel, profile: ProfileEntity?): String =
    profile?.displayName?.takeIf { it.isNotBlank() } ?: vm.session?.user?.displayName ?: vm.session?.user?.email?.substringBefore('@') ?: stringResource(R.string.profile_local_name)

@Composable
fun SettingsHomeScreen(vm: SettingsViewModel, onNavigate: (String) -> Unit) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val signedIn = vm.session != null
    PageList {
        item {
            StudioCard(Modifier.clickable { onNavigate(Route.PROFILE) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(displayName(vm, profile), 52.dp)
                    Column(Modifier.weight(1f)) {
                        Text(displayName(vm, profile), style = MaterialTheme.typography.titleMedium)
                        Text(vm.session?.user?.email ?: stringResource(R.string.profile_local_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
        item {
            StudioCard {
                SettingsRow(Icons.Filled.Person, stringResource(R.string.settings_profile), stringResource(R.string.settings_profile_sub), { onNavigate(Route.PROFILE) })
                SettingsRow(Icons.Filled.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub), { onNavigate(Route.APPEARANCE) })
                SettingsRow(Icons.Filled.Language, stringResource(R.string.settings_language), stringResource(R.string.settings_language_sub), { onNavigate(Route.LANGUAGE) })
                SettingsRow(Icons.Filled.Notifications, stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_sub), { onNavigate(Route.NOTIFICATIONS) })
            }
        }
        item {
            StudioCard {
                SettingsRow(Icons.Filled.Lock, stringResource(R.string.settings_security), stringResource(R.string.settings_security_sub), { onNavigate(Route.SECURITY) })
                SettingsRow(Icons.Filled.Devices, stringResource(R.string.settings_devices), stringResource(R.string.settings_devices_sub), { onNavigate(Route.DEVICES) })
                SettingsRow(Icons.Filled.PrivacyTip, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_sub), { onNavigate(Route.PRIVACY) })
            }
        }
        item {
            StudioCard {
                SettingsRow(Icons.Filled.WorkspacePremium, stringResource(R.string.settings_subscription), stringResource(R.string.settings_subscription_sub), { onNavigate(Route.SUBSCRIPTION) })
                SettingsRow(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.settings_help), stringResource(R.string.settings_help_sub), { onNavigate(Route.HELP) })
            }
        }
        if (signedIn) item {
            OutlinedButton({ vm.signOut() }, Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, null); Text("  " + stringResource(R.string.action_sign_out))
            }
        }
        item { Muted(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) }
    }
}

@Composable
fun DeleteAccountDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val word = stringResource(R.string.delete_word)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.delete_body))
                OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.delete_type, word)) })
            }
        },
        confirmButton = {
            TextButton(onConfirm, enabled = !busy && typed.trim().equals(word, ignoreCase = true)) {
                Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
fun ProfileScreen(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val load by vm.profileLoad.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val local by vm.localMode.collectAsStateWithLifecycle()
    var name by remember(profile?.displayName) { mutableStateOf(profile?.displayName ?: vm.session?.user?.displayName ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val session = vm.session
    LaunchedEffect(session?.user?.id) { vm.refreshProfile() }

    PageList {
        item { MessageBanner(vm) }
        if (session == null) {
            item {
                EmptyState(Icons.Filled.Person, stringResource(R.string.profile_local_mode), stringResource(R.string.profile_local_body),
                    actionLabel = if (local) stringResource(R.string.auth_sign_in) else null, onAction = if (local) ({ vm.exitLocalMode() }) else null)
            }
        } else {
            val l = load
            if (profile == null && l is LoadState.Loading) { item { SkeletonCard(3) } }
            else if (profile == null && l is LoadState.Failed) {
                item { ErrorState(msg(l.message), stringResource(R.string.action_retry), vm::refreshProfile, title = stringResource(R.string.profile_load_failed)) }
            } else {
                item {
                    StudioCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Avatar(displayName(vm, profile), 56.dp)
                            Column {
                                Text(displayName(vm, profile), style = MaterialTheme.typography.titleLarge)
                                Muted(session.user.email)
                            }
                        }
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.auth_name)) })
                        Button({ vm.saveName(name) }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.action_save)) }
                        if (l is LoadState.Failed) Muted(stringResource(R.string.profile_offline_note))
                    }
                }
                item {
                    StudioCard {
                        InfoLine(stringResource(R.string.profile_plan), (profile?.plan ?: "free").replaceFirstChar { it.uppercase() })
                        InfoLine(stringResource(R.string.profile_sign_in_method), (session.user.provider ?: "email").replaceFirstChar { it.uppercase() })
                        InfoLine(stringResource(R.string.profile_email_verified), stringResource(if (session.user.emailConfirmed) R.string.yes else R.string.no))
                    }
                }
                item {
                    OutlinedButton({ vm.signOut() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_sign_out)) }
                }
                item {
                    OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_title)) }
                }
            }
        }
    }
    if (confirmDelete) DeleteAccountDialog(busy, { vm.deleteAccount() }, { confirmDelete = false })
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Muted(subtitle)
        }
    }
}

@Composable
fun AppearanceScreen(vm: SettingsViewModel) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    PageList {
        item {
            StudioCard {
                ChoiceRow(mode == ThemeMode.SYSTEM, stringResource(R.string.theme_system), stringResource(R.string.theme_system_sub)) { vm.setTheme(ThemeMode.SYSTEM) }
                ChoiceRow(mode == ThemeMode.LIGHT, stringResource(R.string.theme_light)) { vm.setTheme(ThemeMode.LIGHT) }
                ChoiceRow(mode == ThemeMode.DARK, stringResource(R.string.theme_dark)) { vm.setTheme(ThemeMode.DARK) }
            }
        }
        item {
            StudioCard {
                Text(stringResource(R.string.theme_preview), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(stringResource(R.string.status_scheduled), MaterialTheme.colorScheme.primary)
                    Pill(stringResource(R.string.status_published), MaterialTheme.colorScheme.secondary)
                    Pill(stringResource(R.string.status_attention), MaterialTheme.colorScheme.tertiary)
                }
                Button({}, Modifier.fillMaxWidth()) { Text(stringResource(R.string.theme_sample_button)) }
            }
        }
    }
}

@Composable
fun LanguageScreen(vm: SettingsViewModel) {
    val current = remember { LanguageManager.current() }
    PageList {
        item {
            StudioCard {
                ChoiceRow(current == "system", stringResource(R.string.language_system)) { vm.setLanguage("system") }
                ChoiceRow(current == "en", "English") { vm.setLanguage("en") }
                ChoiceRow(current == "hi", "हिन्दी") { vm.setLanguage("hi") }
            }
        }
        item { Muted(stringResource(R.string.language_note)) }
    }
}

@Composable
fun NotificationsScreen() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Settings.notifyResults(ctx)) }
    val systemOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    PageList {
        if (!systemOn) item {
            Banner(stringResource(R.string.notif_blocked), action = stringResource(R.string.notif_open_settings), onAction = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        item {
            StudioCard {
                SwitchRow(stringResource(R.string.notif_results), stringResource(R.string.notif_results_sub), on) { on = it; Settings.setNotifyResults(ctx, it) }
                Muted(stringResource(R.string.notif_progress_note))
            }
        }
    }
}

@Composable
fun SecurityScreen(vm: SettingsViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    val signedIn = vm.session != null
    PageList {
        item { MessageBanner(vm) }
        if (signedIn) item {
            StudioCard {
                Text(stringResource(R.string.security_password), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.security_password_sub))
                OutlinedButton({ vm.sendPasswordResetToSelf() }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.security_send_reset)) }
            }
        }
        if (signedIn) item {
            StudioCard {
                Text(stringResource(R.string.security_signout_all), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.security_signout_all_sub))
                OutlinedButton({ confirmAll = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.security_signout_all)) }
            }
        }
        item {
            StudioCard {
                Text(stringResource(R.string.security_on_device), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.security_on_device_sub))
            }
        }
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false }, title = { Text(stringResource(R.string.security_signout_all)) },
        text = { Text(stringResource(R.string.security_signout_all_confirm)) },
        confirmButton = { TextButton({ confirmAll = false; vm.signOut(true) }) { Text(stringResource(R.string.action_sign_out)) } },
        dismissButton = { TextButton({ confirmAll = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
fun DevicesScreen(vm: SettingsViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val load by vm.devicesLoad.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LaunchedEffect(vm.session?.user?.id) { vm.refreshDevices() }
    PageList {
        item { MessageBanner(vm) }
        if (vm.session == null) {
            item { EmptyState(Icons.Filled.DevicesOther, stringResource(R.string.devices_title), stringResource(R.string.devices_signed_out)) }
            return@PageList
        }
        val l = load
        if (devices.isEmpty() && l is LoadState.Loading) { items(2) { SkeletonCard(2) } }
        else if (devices.isEmpty() && l is LoadState.Failed) {
            item { ErrorState(msg(l.message), stringResource(R.string.action_retry), vm::refreshDevices) }
        } else if (devices.isEmpty()) {
            item { EmptyState(Icons.Filled.DevicesOther, stringResource(R.string.devices_title), stringResource(R.string.devices_empty)) }
        } else {
            if (l is LoadState.Failed) item { Banner(stringResource(R.string.devices_stale), warning = true) }
            items(devices, key = { it.installId }) { d ->
                StudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Devices, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleSmall)
                            Muted("${d.osVersion} · v${d.appVersion}")
                            Muted(stringResource(R.string.devices_last_seen, fmt.format(Date(d.lastSeenAt))))
                        }
                        if (d.isThisDevice) Pill(stringResource(R.string.devices_this), MaterialTheme.colorScheme.secondary)
                    }
                    if (!d.isThisDevice) TextButton({ vm.removeDevice(d.installId) }, enabled = !busy) {
                        Text(stringResource(R.string.devices_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item { Muted(stringResource(R.string.devices_note)) }
        }
    }
}

@Composable
fun PrivacyScreen(vm: SettingsViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    PageList {
        item { MessageBanner(vm) }
        item {
            StudioCard {
                Text(stringResource(R.string.privacy_phone_title), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.privacy_phone_body))
                Text(stringResource(R.string.privacy_cloud_title), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.privacy_cloud_body))
            }
        }
        item { CloudCard(signedIn = vm.session != null) }
        if (vm.session != null) item {
            StudioCard {
                Text(stringResource(R.string.privacy_delete_title), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.privacy_delete_body))
                OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_title)) }
            }
        }
    }
    if (confirmDelete) DeleteAccountDialog(busy, { vm.deleteAccount() }, { confirmDelete = false })
}

fun buildDiagnostics(ctx: Context, items: List<PostItem>, tier: Tier): String {
    val counts = items.groupingBy { it.status.name }.eachCount().entries.joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "empty" }
    val text = buildString {
        appendLine("AutoPoster ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Engine tier: $tier · language: ${LanguageManager.current()}")
        appendLine("Queue: $counts")
        appendLine("Backend configured: ${BuildConfig.SUPABASE_URL.isNotBlank()}")
        appendLine("--- recent events ---")
        PostLog.lines.takeLast(40).forEach { appendLine(it) }
    }
    return AppLog.redact(text)
}

@Composable
fun HelpScreen(items: List<PostItem>, tier: Tier) {
    val ctx = LocalContext.current
    val faqs = listOf(R.string.faq_q1 to R.string.faq_a1, R.string.faq_q2 to R.string.faq_a2, R.string.faq_q3 to R.string.faq_a3, R.string.faq_q4 to R.string.faq_a4)
    PageList {
        items(faqs) { (q, a) ->
            var open by remember { mutableStateOf(false) }
            StudioCard(Modifier.clickable { open = !open }) {
                Text(stringResource(q), style = MaterialTheme.typography.titleSmall)
                if (open) Text(stringResource(a), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            StudioCard {
                Text(stringResource(R.string.help_contact), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.help_diagnostics_sub))
                Button({
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "AutoPoster diagnostics").putExtra(Intent.EXTRA_TEXT, buildDiagnostics(ctx, items, tier))
                    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.help_share_diagnostics)) }
                if (BuildConfig.SUPPORT_EMAIL.isNotBlank()) OutlinedButton({
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${BuildConfig.SUPPORT_EMAIL}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.help_email_support)) }
            }
        }
        item { Muted(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) }
    }
}

@Composable
fun SubscriptionScreen(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val plan = (profile?.plan ?: "free").replaceFirstChar { it.uppercase() }
    PageList {
        item {
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Muted(stringResource(R.string.sub_current))
                        Text(plan, style = MaterialTheme.typography.headlineSmall)
                    }
                    Pill(stringResource(R.string.sub_active), MaterialTheme.colorScheme.secondary)
                }
                Muted(stringResource(R.string.sub_free_body))
            }
        }
        item {
            StudioCard {
                Text(stringResource(R.string.sub_included), style = MaterialTheme.typography.titleSmall)
                listOf(R.string.sub_f1, R.string.sub_f2, R.string.sub_f3, R.string.sub_f4).forEach {
                    Text("•  " + stringResource(it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            StudioCard {
                Text(stringResource(R.string.sub_upgrades), style = MaterialTheme.typography.titleSmall)
                Muted(stringResource(R.string.sub_billing_note))
                Button({}, Modifier.fillMaxWidth(), enabled = false) { Text(stringResource(R.string.sub_upgrade_unavailable)) }
            }
        }
    }
}
