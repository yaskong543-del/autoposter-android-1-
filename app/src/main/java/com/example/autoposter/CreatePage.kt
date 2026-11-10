package com.example.autoposter

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.util.Calendar

fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = if (initial > 0) initial else System.currentTimeMillis() }
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            onPicked(Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePage(
    draft: Draft, accounts: List<Account>, items: List<PostItem>, busy: String?,
    onPick: () -> Unit, onOpenAlbums: () -> Unit, albums: List<Pair<String, Int>>?, onCloseAlbums: () -> Unit,
    onFolder: (String, Int) -> Unit, onQueue: () -> Unit
) {
    val ctx = LocalContext.current
    var aiDialog by remember { mutableStateOf(false) }
    var scopeSel by remember { mutableStateOf<Platform?>(null) }

    val selected = accounts.filter { it.id in draft.accountIds }
    val platforms = selected.map { it.platform }.distinct()
    if (scopeSel != null && scopeSel !in platforms) scopeSel = null

    val (plan, skipped) = planUploads(draft, items, selected)
    val dupFps = run {
        val counts = draft.videos.mapNotNull { it.fingerprint }.groupingBy { it }.eachCount()
        val live = setOf(Status.QUEUED, Status.UPLOADING, Status.DONE)
        draft.videos.mapNotNull { it.fingerprint }.filter { fp ->
            (counts[fp] ?: 0) > 1 || items.any { it.fingerprint == fp && it.accountId in draft.accountIds && it.status in live }
        }.toSet()
    }

    val problems = buildList {
        if (draft.videos.isEmpty()) add("Add at least one video.")
        if (selected.isEmpty()) add("Choose at least one account.")
        selected.map { it.platform }.distinct().forEach { p ->
            val longest = draft.videos.indices.maxOfOrNull { draft.rawTextFor(p, it).length } ?: draft.rawTextFor(p, 0).length
            if (longest > p.captionLimit) add("${p.label} caption is $longest characters (limit ${p.captionLimit}).")
            if (parseTags(draft.overrideTags[p]?.takeIf { it.isNotBlank() } ?: draft.baseTags).size > p.tagLimit)
                add("${p.label} allows at most ${p.tagLimit} hashtags.")
        }
        selected.filter { it.platform == Platform.INSTAGRAM && it.igUserId.isNullOrBlank() }
            .forEach { add("'${it.label}' needs its Instagram user ID (edit it on Accounts).") }
        if (plan.isEmpty() && draft.videos.isNotEmpty() && selected.isNotEmpty()) add("Everything selected is a duplicate of something already queued or published.")
    }

    PageList {
        item { SectionTitle("New campaign", "Pick videos, destinations, captions and timing") }

        // 1 — videos
        item {
            StudioCard {
                Text("1 · Clips", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onPick, Modifier.weight(1f)) { Text("＋ Pick from gallery") }
                    OutlinedButton(onOpenAlbums, Modifier.weight(1f)) { Text("Use an album") }
                }
                Muted("Pick clips by hand (up to 100 at a time, repeat to add more) or use a whole gallery album, up to 500 clips.")
                if (draft.videos.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${draft.videos.size} videos", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                        if (dupFps.isNotEmpty()) Pill("${draft.videos.count { it.fingerprint in dupFps }} duplicates", statusColor(Status.NEEDS_AUTH))
                        TextButton({ draft.videos = emptyList() }) { Text("Clear") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.skipDuplicates, { draft.skipDuplicates = it })
                        Column { Text("Skip duplicates"); Muted("Same size + start of file as a video already in this list, queued or published to that account") }
                    }
                }
            }
        }
        items(draft.videos.take(60), key = { it.uri.toString() }) { v ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Muted(fmtSize(v.size) + if (v.fingerprint in dupFps) " · duplicate" else "")
                    }
                    TextButton({ draft.videos = draft.videos.filter { it.uri != v.uri } }) { Text("✕") }
                }
            }
        }
        if (draft.videos.size > 60) item { Muted("…and ${draft.videos.size - 60} more (all will be used).") }

        // 2 — destinations
        item {
            StudioCard {
                Text("2 · Accounts", fontWeight = FontWeight.Bold)
                if (accounts.isEmpty()) Muted("No accounts yet. Add some in Accounts (menu, top left).")
                Platform.values().forEach { p ->
                    val list = accounts.filter { it.platform == p }
                    if (list.isNotEmpty()) {
                        Text("${p.symbol} ${p.label}", color = platformColor(p), fontWeight = FontWeight.SemiBold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { a ->
                                FilterChip(
                                    selected = a.id in draft.accountIds,
                                    onClick = { draft.accountIds = if (a.id in draft.accountIds) draft.accountIds - a.id else draft.accountIds + a.id },
                                    label = { Text(a.label) }
                                )
                            }
                        }
                    }
                }
                if (accounts.isNotEmpty()) Row {
                    TextButton({ draft.accountIds = accounts.map { it.id }.toSet() }) { Text("Select all") }
                    TextButton({ draft.accountIds = emptySet() }) { Text("None") }
                }
            }
        }

        // 3 — how to post
        item {
            StudioCard {
                Text("3 · How to post", fontWeight = FontWeight.Bold)
                Mode.values().forEach { m ->
                    Surface(
                        onClick = { draft.mode = m },
                        shape = RoundedCornerShape(14.dp),
                        color = if (draft.mode == m) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(m.label, fontWeight = FontWeight.SemiBold)
                            Muted(m.help)
                        }
                    }
                }
                if (draft.mode == Mode.RANDOM) OutlinedButton({ draft.seed = System.nanoTime() }) { Text("🔀 Reshuffle") }
                Muted("Order preview")
                if (plan.isEmpty()) Muted("Add clips and choose accounts to see the order.")
                plan.take(6).forEachIndexed { i, (a, v) -> Muted("${i + 1}. ${v.name} → ${a.label}") }
                if (plan.size > 6) Muted("…and ${plan.size - 6} more")
            }
        }

        // 4 — captions
        item {
            StudioCard {
                Text("4 · Captions & hashtags", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(scopeSel == null, { scopeSel = null }, { Text("All platforms") })
                    platforms.forEach { p -> FilterChip(scopeSel == p, { scopeSel = p }, { Text(p.label) }) }
                }
                val sc = scopeSel
                val cap = if (sc == null) draft.baseCaption else draft.overrideCaption[sc].orEmpty()
                val tags = if (sc == null) draft.baseTags else draft.overrideTags[sc].orEmpty()
                OutlinedTextField(
                    cap, { if (sc == null) draft.baseCaption = it else draft.overrideCaption[sc] = it },
                    Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(16.dp),
                    label = { Text(if (sc == null) "Caption (use | for variants rotated across videos)" else "${sc.label} caption (overrides the shared one)") },
                    supportingText = { Text(if (sc != null) "${cap.length}/${sc.captionLimit}" else "${cap.length} characters") }
                )
                OutlinedTextField(
                    tags, { if (sc == null) draft.baseTags = it else draft.overrideTags[sc] = it },
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    label = { Text(if (sc == null) "Hashtags" else "${sc.label} hashtags") },
                    supportingText = { Text("${parseTags(tags).size}${sc?.let { "/${it.tagLimit}" } ?: ""} tags") }
                )
                if (sc != null && (cap.isNotEmpty() || tags.isNotEmpty())) TextButton({ draft.overrideCaption.remove(sc); draft.overrideTags.remove(sc) }) { Text("Use shared text for ${sc.label}") }
                OutlinedButton({ aiDialog = true }, Modifier.fillMaxWidth()) { Text("✨ Caption & hashtag ideas") }
                OutlinedTextField(
                    draft.sound, { draft.sound = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    label = { Text("Sound credit (optional)") },
                    supportingText = { Text("Added to the caption as text. Platform APIs can't attach catalog music for you.") }
                )
            }
        }

        // 5 — schedule
        item {
            StudioCard {
                Text("5 · Schedule per account", fontWeight = FontWeight.Bold)
                if (selected.isEmpty()) Muted("Choose accounts first.")
                selected.forEach { a ->
                    val start = draft.starts[a.id] ?: 0L
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${a.platform.symbol} ${a.label}", fontWeight = FontWeight.SemiBold, color = platformColor(a.platform))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton({ pickDateTime(ctx, start) { draft.starts[a.id] = it } }, Modifier.weight(1f)) {
                                Text(if (start > 0) fmtTime(start) else "Start now")
                            }
                            if (start > 0) TextButton({ draft.starts.remove(a.id) }) { Text("Now") }
                            OutlinedTextField(
                                (draft.gaps[a.id] ?: 0).toString(), { draft.gaps[a.id] = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 },
                                Modifier.width(112.dp), singleLine = true, label = { Text("Gap (min)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
                if (selected.size > 1) {
                    val first = selected.first()
                    TextButton({
                        selected.drop(1).forEach { a ->
                            draft.starts[a.id] = draft.starts[first.id] ?: 0L; draft.gaps[a.id] = draft.gaps[first.id] ?: 0
                            if ((draft.starts[a.id] ?: 0L) == 0L) draft.starts.remove(a.id)
                        }
                    }) { Text("Copy ${first.label}'s timing to all") }
                }
                if (selected.isNotEmpty()) OutlinedButton({
                    val seen = HashMap<Platform, Int>()
                    selected.forEach { a ->
                        val k = seen.getOrDefault(a.platform, 0); seen[a.platform] = k + 1
                        val hrs = BestTime.hours(a.platform, items)
                        draft.starts[a.id] = BestTime.nextAt(hrs[k % hrs.size], k / hrs.size)
                        if ((draft.gaps[a.id] ?: 0) == 0) draft.gaps[a.id] = 180
                    }
                }, Modifier.fillMaxWidth()) { Text("✨ Suggest start times") }
                Muted("Gap = minutes between this account's posts (0 = back to back). Each account runs on its own clock.")
            }
        }

        // review
        item {
            StudioCard {
                Text("Review", fontWeight = FontWeight.Bold)
                Text("${draft.videos.size} clips · ${selected.size} accounts → ${plan.size} posts", style = MaterialTheme.typography.titleMedium)
                if (skipped > 0) Muted("$skipped skipped as duplicates")
                problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Muted("Videos are copied into app storage when queued (needs free space), then removed after they're published.")
                Button(onQueue, Modifier.fillMaxWidth(), enabled = problems.isEmpty() && busy == null) {
                    Text(if (busy != null) busy else "Queue ${plan.size} posts")
                }
            }
        }
    }

    if (albums != null) {
        AlertDialog(
            onDismissRequest = onCloseAlbums,
            title = { Text("Choose an album") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (albums.isEmpty()) Muted("No video albums found on this phone.")
                    albums.forEach { (name, n) ->
                        OutlinedButton({ onFolder(name, 500) }, Modifier.fillMaxWidth()) { Text("$name · $n clips") }
                    }
                }
            },
            confirmButton = { TextButton(onCloseAlbums) { Text("Cancel") } }
        )
    }
    if (aiDialog) AiDialog(
        scope = scopeSel ?: platforms.firstOrNull() ?: Platform.INSTAGRAM, shared = scopeSel == null,
        seed = draft.baseCaption.substringBefore("|").take(60),
        onCaption = { c -> scopeSel.let { s -> if (s == null) draft.baseCaption = c else draft.overrideCaption[s] = c } },
        onTags = { t -> scopeSel.let { s -> if (s == null) draft.baseTags = t else draft.overrideTags[s] = t } },
        onDismiss = { aiDialog = false }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiDialog(scope: Platform, shared: Boolean, seed: String, onCaption: (String) -> Unit, onTags: (String) -> Unit, onDismiss: () -> Unit) {
    var topic by remember { mutableStateOf(seed) }
    var tone by remember { mutableStateOf("Fun") }
    var target by remember { mutableStateOf(scope) }
    val caps = remember(topic, tone, target) { CaptionAI.captions(topic, target, tone) }
    val tags = remember(topic, target) { CaptionAI.hashtags(topic, target) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Caption & hashtag ideas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(topic, { topic = it }, singleLine = true, label = { Text("What is the video about?") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Fun", "Professional", "Inspiring").forEach { FilterChip(tone == it, { tone = it }, { Text(it) }) }
                }
                if (shared) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Platform.values().forEach { FilterChip(target == it, { target = it }, { Text(it.label) }) }
                }
                Muted("Tap an idea to use it. Rule-based, on-device suggestions; edit freely.")
                caps.forEach { c -> OutlinedButton({ onCaption(c) }, Modifier.fillMaxWidth()) { Text(c, maxLines = 3) } }
                Text(tags.joinToString(" "), color = MaterialTheme.colorScheme.secondary)
                OutlinedButton({ onTags(tags.joinToString(" ")) }, Modifier.fillMaxWidth()) { Text("Use these hashtags") }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } }
    )
}
