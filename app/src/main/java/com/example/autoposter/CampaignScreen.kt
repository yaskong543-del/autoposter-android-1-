package com.example.autoposter

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/** State of the bulk-campaign builder. Lives for the screen's lifetime; nothing is queued until the review step is confirmed. */
class CampaignViewModel(private val c: AppContainer) : ViewModel() {
    var step by mutableIntStateOf(0)
    val mediaIds = mutableStateListOf<String>()
    var accountIds by mutableStateOf<Set<String>>(emptySet())
    var mode by mutableStateOf(Mode.ROTATE)
    var sharedCaption by mutableStateOf("")
    var hashtags by mutableStateOf("")
    var brandId by mutableStateOf<String?>(null)
    val captions = mutableStateMapOf<String, String>()
    var startAt by mutableLongStateOf(0L)
    var intervalMinutes by mutableIntStateOf(30)
    var staggerMinutes by mutableIntStateOf(0)
    val accountStart = mutableStateMapOf<String, Long>()
    val overrides = mutableStateMapOf<String, Long>()
    var zoneId by mutableStateOf("")
    var seed by mutableLongStateOf(System.nanoTime())
    var busy by mutableStateOf<String?>(null)
    var aiBusyId by mutableStateOf<String?>(null)
    var aiError by mutableStateOf<AiException?>(null)

    val zone: ZoneId get() = CalendarLogic.zoneOrDefault(zoneId.ifBlank { null })

    fun spec(library: List<MediaItemEntity>, accounts: List<Account>, brand: BrandProfile?, now: Long): CampaignSpec {
        val byId = library.associateBy { it.id }
        return CampaignSpec(
            media = mediaIds.mapNotNull { byId[it] }.map { CampaignMedia(it.id, it.name, it.kindEnum, it.path, it.sizeBytes, it.fingerprint) },
            accounts = accounts.filter { it.id in accountIds }, mode = mode,
            content = ComposerContent(sharedCaption, emptyMap(), hashtags, emptyMap(), brand?.signature.orEmpty()),
            captionByMedia = captions.toMap(), startAt = if (startAt > 0) startAt else now, intervalMinutes = intervalMinutes,
            accountStaggerMinutes = staggerMinutes, accountStart = accountStart.toMap(), timeOverride = overrides.toMap(), seed = seed
        )
    }

    /** One AI caption for one file (costs tokens from the same monthly allowance). */
    fun suggest(media: MediaItemEntity, brand: BrandProfile?, tone: AiTone, language: String) {
        if (aiBusyId != null) return
        aiBusyId = media.id; aiError = null
        viewModelScope.launch {
            try {
                val r = c.ai.generate(AiRequest(AiTask.CAPTION, tone, language, listOf(sharedCaption, media.name.substringBeforeLast('.')).first { it.isNotBlank() }, null, 1, null, brand))
                r.items.firstOrNull()?.let { captions[media.id] = it }
            } catch (e: AiException) { aiError = e } finally { aiBusyId = null }
        }
    }

    fun queue(planned: List<PlannedPost>, onDone: (CampaignQueue.Outcome) -> Unit) {
        busy = "…"
        viewModelScope.launch {
            val out = CampaignQueue.enqueue(c.app, planned) { i, n -> busy = "$i/$n" }
            busy = null
            onDone(out)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampaignScreen(
    vm: CampaignViewModel, accounts: List<Account>, library: List<MediaItemEntity>, brands: List<BrandProfile>,
    history: List<PostItem>, aiAvailable: Boolean, onImport: () -> Unit, onQueued: () -> Unit
) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val brand = brands.firstOrNull { it.id == vm.brandId }
    var picker by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<String?>(null) }     // "start" | accountId | "mediaId|accountId"
    val planned = remember(vm.mediaIds.toList(), vm.accountIds, vm.mode, vm.sharedCaption, vm.hashtags, vm.captions.toMap(), vm.startAt, vm.intervalMinutes,
        vm.staggerMinutes, vm.accountStart.toMap(), vm.overrides.toMap(), vm.seed, library, accounts, brand, history.size) {
        CampaignPlanner.plan(vm.spec(library, accounts, brand, now), now, history, AccountHealthCheck.needsAuthIds(history))
    }
    val summary = CampaignPlanner.summarize(planned)

    if (picker) MediaPickerDialog(library, vm.mediaIds.toSet(), { picker = false }, onImport) { ids ->
        val merged = SelectionMerge.merge(vm.mediaIds.toList(), ids, library.map { it.id })
        vm.mediaIds.clear(); vm.mediaIds.addAll(merged); picker = false
    }
    timeFor?.let { key ->
        val initial = when { key == "start" -> vm.startAt; '|' in key -> vm.overrides[key] ?: 0L; else -> vm.accountStart[key] ?: 0L }
        DateTimeDialog(initial, vm.zone, { timeFor = null }) { ms ->
            when { key == "start" -> vm.startAt = ms; '|' in key -> vm.overrides[key] = ms; else -> vm.accountStart[key] = ms }
            timeFor = null
        }
    }

    PageList {
        item { SectionTitle(stringResource(R.string.v7_campaign_title), stringResource(R.string.v7_campaign_sub)) }
        item {
            TabRow(vm.step) {
                listOf(R.string.v7_step_select, R.string.v7_step_captions, R.string.v7_step_review).forEachIndexed { i, r ->
                    Tab(vm.step == i, { vm.step = i }, text = { Text(stringResource(r), maxLines = 1) })
                }
            }
        }
        when (vm.step) {
            0 -> {
                item {
                    StudioCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.v7_files_n, vm.mediaIds.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            FilledTonalButton({ picker = true }) { Text(stringResource(R.string.v7_add_media)) }
                        }
                        vm.mediaIds.mapNotNull { id -> library.firstOrNull { it.id == id } }.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MediaThumb(m, Modifier.size(44.dp)); Spacer(Modifier.width(10.dp))
                                Text(m.name, Modifier.weight(1f), maxLines = 1)
                                TextButton({ vm.mediaIds.remove(m.id) }) { Text(stringResource(R.string.action_remove)) }
                            }
                        }
                    }
                }
                item {
                    StudioCard {
                        Text(stringResource(R.string.v7_accounts_n, vm.accountIds.size), style = MaterialTheme.typography.titleSmall)
                        if (accounts.isEmpty()) Muted(stringResource(R.string.v7_no_accounts))
                        Platform.entries.forEach { p -> accounts.filter { it.platform == p }.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(a.id in vm.accountIds, { on -> vm.accountIds = if (on) vm.accountIds + a.id else vm.accountIds - a.id })
                                PlatformLogo(p, 24.dp); Spacer(Modifier.width(8.dp)); Text(a.label, maxLines = 1)
                            }
                        } }
                    }
                }
                item {
                    StudioCard {
                        Text(stringResource(R.string.v7_mode), style = MaterialTheme.typography.titleSmall)
                        Mode.entries.forEach { m ->
                            Row(verticalAlignment = Alignment.Top) {
                                RadioButton(vm.mode == m, { vm.mode = m })
                                Column { Text(m.label, fontWeight = FontWeight.SemiBold); Muted(m.help) }
                            }
                        }
                        if (vm.mode == Mode.RANDOM) OutlinedButton({ vm.seed = System.nanoTime() }) { Text(stringResource(R.string.v7_reshuffle)) }
                    }
                }
            }
            1 -> {
                item {
                    StudioCard {
                        PickerField(stringResource(R.string.v7_brand), brand?.name ?: stringResource(R.string.v7_brand_none),
                            listOf<Pair<String?, String>>(null to stringResource(R.string.v7_brand_none)) + brands.map { it.id to it.name }, { vm.brandId = it })
                        OutlinedTextField(vm.sharedCaption, { vm.sharedCaption = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_shared_caption)) }, minLines = 2, shape = RoundedCornerShape(14.dp))
                        OutlinedTextField(vm.hashtags, { vm.hashtags = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_hashtags)) }, shape = RoundedCornerShape(14.dp))
                    }
                }
                item {
                    StudioCard {
                        Text(stringResource(R.string.v7_schedule_title), style = MaterialTheme.typography.titleSmall)
                        OutlinedButton({ timeFor = "start" }, Modifier.fillMaxWidth()) {
                            Text(if (vm.startAt > 0) fmtTime(vm.startAt) else stringResource(R.string.v7_start_now))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(vm.intervalMinutes.toString(), { vm.intervalMinutes = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0 }, Modifier.weight(1f),
                                label = { Text(stringResource(R.string.v7_interval_min)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(vm.staggerMinutes.toString(), { vm.staggerMinutes = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0 }, Modifier.weight(1f),
                                label = { Text(stringResource(R.string.v7_stagger_min)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        accounts.filter { it.id in vm.accountIds }.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.label, Modifier.weight(1f), maxLines = 1)
                                TextButton({ timeFor = a.id }) { Text(vm.accountStart[a.id]?.let { fmtTime(it) } ?: stringResource(R.string.v7_account_start_auto)) }
                            }
                        }
                        Muted(stringResource(R.string.v7_zone_note) + " " + CalendarLogic.zoneLabel(vm.zone, now))
                    }
                }
                vm.mediaIds.mapNotNull { id -> library.firstOrNull { it.id == id } }.let { list ->
                    items(list, key = { "cap-" + it.id }) { m ->
                        StudioCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MediaThumb(m, Modifier.size(48.dp)); Spacer(Modifier.width(10.dp)); Text(m.name, Modifier.weight(1f), maxLines = 1)
                                TextButton({ vm.suggest(m, brand, brand?.toneEnum ?: AiTone.CASUAL, brand?.language ?: "en") }, enabled = aiAvailable && vm.aiBusyId == null) {
                                    Text(if (vm.aiBusyId == m.id) "…" else stringResource(R.string.v7_ai_short))
                                }
                            }
                            OutlinedTextField(vm.captions[m.id].orEmpty(), { vm.captions[m.id] = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_own_caption)) }, minLines = 2, shape = RoundedCornerShape(14.dp))
                        }
                    }
                }
                vm.aiError?.let { e -> item { Banner(aiErrorText(e)) } }
            }
            else -> {
                item {
                    StudioCard {
                        Text(stringResource(R.string.v7_review), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.v7_review_counts, summary.total, summary.publishable, summary.blocked))
                        if (summary.firstAt != null) Muted(stringResource(R.string.v7_review_span, fmtTime(summary.firstAt), fmtTime(summary.lastAt ?: summary.firstAt)))
                        summary.perAccount.forEach { (l, n) -> Muted("$l · $n") }
                        accounts.filter { it.id in vm.accountIds }.map { it.platform }.distinct().forEach { Muted(PlatformReadiness.note(it)) }
                        Banner(stringResource(R.string.v7_review_honest), warning = false)
                    }
                }
                items(planned, key = { it.key }) { p ->
                    StudioCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlatformLogo(p.account.platform, 26.dp); Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) { Text(p.media.name, maxLines = 1, fontWeight = FontWeight.SemiBold); Muted(p.account.label) }
                            TextButton({ timeFor = p.key }) { Text(fmtTime(p.scheduledAt)) }
                        }
                        if (p.text.isNotBlank()) Text(p.text, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        p.warnings.forEach { Text("⚠ " + stringResource(warningRes(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item {
                    Button({
                        vm.queue(planned) { o ->
                            val msg = when (o) {
                                is CampaignQueue.Outcome.Queued -> ctx.getString(R.string.v7_queued, o.count)
                                is CampaignQueue.Outcome.NotEnoughSpace -> ctx.getString(R.string.v7_no_space)
                                is CampaignQueue.Outcome.Failed -> ctx.getString(R.string.v7_publish_failed, o.message)
                                CampaignQueue.Outcome.NothingToQueue -> ctx.getString(R.string.v7_nothing_to_queue)
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            if (o is CampaignQueue.Outcome.Queued) onQueued()
                        }
                    }, Modifier.fillMaxWidth(), enabled = summary.publishable > 0 && vm.busy == null) {
                        Text(if (vm.busy != null) stringResource(R.string.v7_preparing, vm.busy!!) else stringResource(R.string.v7_queue_n, summary.publishable))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.step > 0) OutlinedButton({ vm.step -= 1 }, Modifier.weight(1f)) { Text(stringResource(R.string.action_back)) }
                if (vm.step < 2) Button({ vm.step += 1 }, Modifier.weight(1f)) { Text(stringResource(R.string.v7_next)) }
            }
        }
    }
}

fun warningRes(w: PlanWarning): Int = when (w) {
    PlanWarning.IMAGE_NOT_SUPPORTED -> R.string.v7_warn_image
    PlanWarning.ACCOUNT_NEEDS_ATTENTION -> R.string.v7_warn_account
    PlanWarning.OVER_CAPTION_LIMIT -> R.string.v7_warn_limit
    PlanWarning.TIME_IN_PAST -> R.string.v7_warn_past
    PlanWarning.ALREADY_QUEUED -> R.string.v7_warn_dupe
    PlanWarning.EMPTY_CAPTION -> R.string.v7_warn_empty
}
