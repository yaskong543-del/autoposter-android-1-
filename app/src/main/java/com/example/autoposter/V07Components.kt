package com.example.autoposter

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

object ThumbMemory { val cache = android.util.LruCache<String, ImageBitmap>(160) }

/** Square preview of a library item: video frame or image, duration badge, selection tick. */
@Composable
fun MediaThumb(item: MediaItemEntity, modifier: Modifier = Modifier, selected: Boolean = false, flagged: Boolean = false) {
    val repo = LocalContext.current.container.library
    val bmp by produceState<ImageBitmap?>(ThumbMemory.cache.get(item.id), item.id) {
        if (value == null) value = repo.thumbnail(item)?.asImageBitmap()?.also { ThumbMemory.cache.put(item.id, it) }
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
    ) {
        bmp?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (item.kindEnum == MediaKind.VIDEO) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.align(Alignment.Center).size(28.dp), tint = Color.White.copy(alpha = 0.85f))
            val d = MediaFormat.duration(item.durationMs)
            if (d.isNotEmpty()) Text(d, Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        if (flagged) Text(stringResource(R.string.v7_dup_badge), Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0xCCB3261E), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Date step, then time step. The result is a UTC instant for that wall-clock time in [zone]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(initialMs: Long, zone: ZoneId, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val base = if (initialMs > 0) initialMs else System.currentTimeMillis() + 3_600_000L
    val z = Instant.ofEpochMilli(base).atZone(zone)
    var step by remember { mutableIntStateOf(0) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = z.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val timeState = rememberTimePickerState(z.hour, z.minute, true)
    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton({ step = 1 }, enabled = dateState.selectedDateMillis != null) { Text(stringResource(R.string.v7_next)) } },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } }
        ) { DatePicker(dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton({
                    val day = Instant.ofEpochMilli(dateState.selectedDateMillis ?: base).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(CalendarLogic.toInstantMs(day, LocalTime.of(timeState.hour, timeState.minute), zone))
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { TimePicker(timeState); Muted(CalendarLogic.zoneLabel(zone, base)) } }
        )
    }
}

@Composable
fun ZonePickerDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc") }.sorted() }
    val shown = all.filter { q.isBlank() || it.contains(q.trim(), true) }
    AlertDialog(
        onDismissRequest = onDismiss, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.v7_time_zone)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.v7_search)) })
                TextButton({ onPick("") }) { Text(stringResource(R.string.v7_zone_device) + " · " + ZoneId.systemDefault().id) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown.take(200), key = { it }) { id ->
                        Text(id, Modifier.fillMaxWidth().clickableRow { onPick(id) }.padding(vertical = 10.dp),
                            fontWeight = if (id == current) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    )
}

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

/** Compact dropdown used for tone, language, brand. */
@Composable
fun <T> PickerField(label: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, maxLines = 1) }
            Text("▾")
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem({ Text(l) }, { open = false; onSelect(v) }) }
        }
    }
}

@Composable
fun toneLabel(t: AiTone): String = stringResource(when (t) {
    AiTone.PROFESSIONAL -> R.string.v7_tone_professional; AiTone.FUNNY -> R.string.v7_tone_funny; AiTone.CASUAL -> R.string.v7_tone_casual
    AiTone.EDUCATIONAL -> R.string.v7_tone_educational; AiTone.MARKETING -> R.string.v7_tone_marketing
    AiTone.INSPIRATIONAL -> R.string.v7_tone_inspirational; AiTone.MINIMAL -> R.string.v7_tone_minimal
})

/** "4,120 of 5,000 AI tokens left · resets 1 Dec". Shows the server's last answer; works offline from the cached copy. */
@Composable
fun TokenMeter(usage: AiUsage?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (usage == null) { Muted(stringResource(R.string.v7_tokens_unknown)); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.v7_tokens_left, "%,d".format(usage.remaining), "%,d".format(usage.limit)), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Pill(stringResource(if (usage.isPremium) R.string.v7_plan_premium else R.string.v7_plan_free), MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { usage.fractionUsed }, Modifier.fillMaxWidth())
        val reset = Iso.parse(usage.resetsAt)
        if (reset > 0) Muted(stringResource(R.string.v7_tokens_reset, java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(reset))))
    }
}

@Composable
fun aiErrorText(e: AiException): String = when (e.kind) {
    AiErrorKind.OFFLINE -> stringResource(R.string.v7_err_offline)
    AiErrorKind.SIGNED_OUT -> stringResource(R.string.v7_err_signed_out)
    AiErrorKind.QUOTA -> stringResource(R.string.v7_err_quota)
    AiErrorKind.RATE_LIMITED -> stringResource(R.string.v7_err_rate, e.retryAfterSec.coerceAtLeast(1))
    AiErrorKind.TOO_LARGE -> stringResource(R.string.v7_err_large)
    AiErrorKind.REFUSED -> stringResource(R.string.v7_err_refused)
    AiErrorKind.NOT_CONFIGURED -> stringResource(R.string.v7_err_not_configured)
    AiErrorKind.INVALID -> stringResource(R.string.v7_err_invalid)
    AiErrorKind.SERVER, AiErrorKind.UNKNOWN -> stringResource(R.string.v7_err_server)
}
