package com.example.autoposter

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerScreen(
    vm: ComposerViewModel, accounts: List<Account>, library: List<MediaItemEntity>, brands: List<BrandProfile>,
    usage: AiUsage?, aiAvailable: Boolean, history: List<PostItem>,
    onImport: () -> Unit, onBrands: () -> Unit, onFinished: () -> Unit
) {
    val ctx = LocalContext.current
    val brand = brands.firstOrNull { it.id == vm.brandId }
    val chosen = accounts.filter { it.id in vm.accountIds }
    val platforms = chosen.map { it.platform }.distinct()
    val media = vm.mediaIds.mapNotNull { id -> library.firstOrNull { it.id == id } }
    val content = vm.content(brand)
    var picker by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var zonePicker by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf<Platform?>(null) }     // which caption the AI works on: null = shared
    var translateTo by remember { mutableStateOf("hi") }
    if (scope != null && scope !in platforms) scope = null

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    val problems = ComposerRules.problems(vm.mediaIds.size, chosen.size, content, platforms, media.any { it.kindEnum == MediaKind.VIDEO })
    val nameOf = { p: ComposerProblem ->
        ctx.getString(when (p) {
            ComposerProblem.NO_MEDIA -> R.string.v7_problem_media; ComposerProblem.ONLY_IMAGES -> R.string.v7_problem_images
            ComposerProblem.NO_ACCOUNT -> R.string.v7_problem_account; ComposerProblem.NO_CAPTION -> R.string.v7_problem_caption
            ComposerProblem.OVER_LIMIT -> R.string.v7_problem_limit
        })
    }

    if (picker) MediaPickerDialog(library, vm.mediaIds.toSet(), onDismiss = { picker = false }, onImport = onImport) { ids ->
        // keep the order the person already had, append new picks
        val merged = SelectionMerge.merge(vm.mediaIds.toList(), ids, library.map { it.id })
        vm.mediaIds.clear(); vm.mediaIds.addAll(merged); vm.touch(); picker = false
    }
    if (datePicker) DateTimeDialog(vm.plannedAt, vm.zone, { datePicker = false }) { vm.plannedAt = it; vm.touch(); datePicker = false }
    if (zonePicker) ZonePickerDialog(vm.zoneId, { zonePicker = false }) { vm.zoneId = it; vm.touch(); zonePicker = false }

    PageList {
        item { SectionTitle(stringResource(R.string.v7_composer_title), stringResource(R.string.v7_composer_sub)) }

        // 1. media
        item {
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.v7_media), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    FilledTonalButton({ picker = true }) { Text(stringResource(R.string.v7_add_media)) }
                }
                if (media.isEmpty()) Muted(stringResource(R.string.v7_media_empty))
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(media, key = { it.id }) { m ->
                        Box { MediaThumb(m, Modifier.size(92.dp))
                            IconButton({ vm.mediaIds.remove(m.id); vm.touch() }, Modifier.align(Alignment.TopEnd).size(28.dp)) { Icon(Icons.Filled.Close, stringResource(R.string.action_remove)) } }
                    }
                }
                if (media.any { it.kindEnum == MediaKind.IMAGE }) Muted(stringResource(R.string.v7_images_note))
            }
        }

        // 2. voice
        item {
            StudioCard {
                Text(stringResource(R.string.v7_voice), style = MaterialTheme.typography.titleSmall)
                PickerField(stringResource(R.string.v7_brand), brand?.name ?: stringResource(R.string.v7_brand_none),
                    listOf<Pair<String?, String>>(null to stringResource(R.string.v7_brand_none)) + brands.map { it.id to it.name },
                    { id -> vm.brandId = id; brands.firstOrNull { it.id == id }?.let { vm.tone = it.toneEnum; vm.language = it.language }; vm.touch() })
                TextButton(onBrands) { Text(stringResource(R.string.v7_manage_brands)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerField(stringResource(R.string.v7_tone), toneLabel(vm.tone), AiTone.entries.map { it to toneLabel(it) }, { vm.tone = it; vm.touch() }, Modifier.weight(1f))
                    PickerField(stringResource(R.string.v7_language), AiLanguages.label(vm.language), AiLanguages.all.map { it.code to it.label }, { vm.language = it; vm.touch() }, Modifier.weight(1f))
                }
            }
        }

        // 3. caption + AI
        item {
            StudioCard {
                OutlinedTextField(vm.topic, { vm.topic = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_topic)) }, minLines = 1, maxLines = 3, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(vm.caption, { vm.caption = it; vm.touch() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_caption)) },
                    minLines = 4, maxLines = 10, shape = RoundedCornerShape(14.dp), supportingText = { Text("${vm.caption.length}") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Text("  " + stringResource(R.string.v7_ai_assistant), style = MaterialTheme.typography.titleSmall)
                }
                TokenMeter(usage)
                if (!aiAvailable) Banner(stringResource(R.string.v7_ai_unavailable), warning = true)
                if (platforms.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(scope == null, { scope = null }, { Text(stringResource(R.string.v7_scope_shared)) })
                    platforms.forEach { p -> FilterChip(scope == p, { scope = p }, { Text(p.label) }) }
                }
                val names = media.map { it.name }
                val platformFor = { t: AiTask ->
                    when (t) { AiTask.YOUTUBE_META -> Platform.YOUTUBE; AiTask.PLATFORM_CAPTION -> scope ?: platforms.firstOrNull(); else -> scope }
                }
                val go = { t: AiTask -> vm.ai(t, brand, platformFor(t), names, if (t == AiTask.TRANSLATE) translateTo else null) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        AiTask.CAPTION to R.string.v7_ai_caption, AiTask.VARIATIONS to R.string.v7_ai_variations, AiTask.HOOKS to R.string.v7_ai_hooks,
                        AiTask.CTA to R.string.v7_ai_cta, AiTask.HASHTAGS to R.string.v7_ai_hashtags, AiTask.REWRITE to R.string.v7_ai_rewrite,
                        AiTask.SHORTEN to R.string.v7_ai_shorten, AiTask.GRAMMAR to R.string.v7_ai_grammar, AiTask.PLATFORM_CAPTION to R.string.v7_ai_platform,
                        AiTask.VIDEO_DESCRIPTION to R.string.v7_ai_description, AiTask.YOUTUBE_META to R.string.v7_ai_youtube
                    ).forEach { (t, label) ->
                        AssistChip({ go(t) }, { Text(stringResource(label)) }, enabled = aiAvailable && !vm.aiBusy &&
                            (t != AiTask.PLATFORM_CAPTION || scope != null || platforms.isNotEmpty()))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PickerField(stringResource(R.string.v7_translate_to), AiLanguages.label(translateTo), AiLanguages.all.map { it.code to it.label }, { translateTo = it }, Modifier.weight(1f))
                    FilledTonalButton({ go(AiTask.TRANSLATE) }, enabled = aiAvailable && !vm.aiBusy && vm.caption.isNotBlank()) { Text(stringResource(R.string.v7_ai_translate)) }
                }
                if (vm.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.aiError?.let { Banner(aiErrorText(it), warning = true) }
            }
        }
        vm.suggestions?.let { s -> item { SuggestionsCard(vm, s, scope) } }

        // 4. hashtags
        item {
            StudioCard {
                OutlinedTextField(vm.hashtags, { vm.hashtags = it; vm.touch() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.v7_hashtags)) }, minLines = 2, shape = RoundedCornerShape(14.dp))
                val local = remember(vm.topic, vm.caption, media.size) {
                    val seed = listOf(vm.topic, vm.caption, media.joinToString(" ") { it.name.substringBeforeLast('.') }).joinToString(" ")
                    CaptionAI.hashtags(seed, platforms.firstOrNull() ?: Platform.INSTAGRAM)
                }
                val fromBrand = parseTags(brand?.hashtags.orEmpty())
                val sugg = ComposerRules.newTags(fromBrand + local + (vm.suggestions?.hashtags.orEmpty()), vm.hashtags)
                if (sugg.isNotEmpty()) {
                    Muted(stringResource(R.string.v7_tag_suggestions))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { sugg.take(14).forEach { t -> SuggestionChip({ vm.addTags(listOf(t)) }, { Text(t) }) } }
                }
            }
        }

        // 5. platforms + accounts
        item {
            StudioCard {
                Text(stringResource(R.string.v7_where), style = MaterialTheme.typography.titleSmall)
                if (accounts.isEmpty()) Muted(stringResource(R.string.v7_no_accounts))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Platform.entries.filter { p -> accounts.any { it.platform == p } }.forEach { p ->
                        val ids = accounts.filter { it.platform == p }.map { it.id }.toSet()
                        val all = ids.all { it in vm.accountIds }
                        FilterChip(all, { vm.accountIds = if (all) vm.accountIds - ids else vm.accountIds + ids; vm.touch() }, { Text(p.label) })
                    }
                }
                Platform.entries.forEach { p ->
                    accounts.filter { it.platform == p }.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(a.id in vm.accountIds, { on -> vm.accountIds = if (on) vm.accountIds + a.id else vm.accountIds - a.id; vm.touch() })
                            PlatformLogo(p, 24.dp); Spacer(Modifier.width(8.dp)); Text(a.label, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 6. per-platform captions + previews
        if (platforms.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.v7_previews), stringResource(R.string.v7_previews_sub)) }
            items(platforms, key = { "pv-" + it.name }) { p ->
                val t = content.textFor(p)
                StudioCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformLogo(p, 28.dp); Spacer(Modifier.width(8.dp))
                        Text(p.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Pill("${t.rawLength}/${t.limit}", if (t.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                    }
                    OutlinedTextField(vm.platformCaptions[p].orEmpty(), { vm.platformCaptions[p] = it; vm.touch() }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.v7_platform_caption_hint, p.label)) }, minLines = 2, shape = RoundedCornerShape(14.dp))
                    PlatformPreview(p, t.text, chosen.filter { it.platform == p }.joinToString { it.label })
                    if (t.overLimit) Text(stringResource(R.string.v7_will_cut, t.limit), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 7. schedule + actions
        item {
            StudioCard {
                Text(stringResource(R.string.v7_when), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ datePicker = true }, Modifier.weight(1f)) {
                        Text(if (vm.plannedAt > 0) SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone(vm.zone) }.format(Date(vm.plannedAt)) else stringResource(R.string.v7_pick_time))
                    }
                    if (vm.plannedAt > 0) TextButton({ vm.plannedAt = 0L; vm.touch() }) { Text(stringResource(R.string.v7_clear)) }
                }
                TextButton({ zonePicker = true }) { Text(CalendarLogic.zoneLabel(vm.zone, if (vm.plannedAt > 0) vm.plannedAt else System.currentTimeMillis())) }
                Muted(stringResource(R.string.v7_zone_note))
                problems.forEach { Text("• " + nameOf(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                platforms.forEach { Muted(PlatformReadiness.note(it)) }
                val handle = { o: PublishOutcome ->
                    when (o) {
                        is PublishOutcome.Queued -> { toast(ctx.getString(R.string.v7_queued, o.count) + if (o.skipped > 0) " " + ctx.getString(R.string.v7_skipped, o.skipped) else ""); onFinished() }
                        is PublishOutcome.Blocked -> toast(nameOf(o.reason))
                        PublishOutcome.NoSpace -> toast(ctx.getString(R.string.v7_no_space))
                        is PublishOutcome.Failed -> toast(ctx.getString(R.string.v7_publish_failed, o.message))
                    }
                }
                val blocking = problems.any { it != ComposerProblem.OVER_LIMIT }
                Button({ vm.publish(library, accounts, brand, vm.plannedAt, history, handle) }, Modifier.fillMaxWidth(), enabled = !blocking && vm.publishing == null) {
                    Text(if (vm.publishing != null) stringResource(R.string.v7_preparing, vm.publishing!!) else if (vm.plannedAt > 0) stringResource(R.string.v7_schedule) else stringResource(R.string.v7_publish_now))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.saveDraft { toast(ctx.getString(R.string.v7_draft_saved)) } }, Modifier.weight(1f)) { Text(stringResource(R.string.v7_save_draft)) }
                    OutlinedButton({ vm.duplicate { toast(ctx.getString(R.string.v7_duplicated)) } }, Modifier.weight(1f)) { Text(stringResource(R.string.v7_duplicate)) }
                }
                if (vm.draftId != null) TextButton({ vm.deleteDraft { onFinished() } }) { Text(stringResource(R.string.v7_delete_draft), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsCard(vm: ComposerViewModel, s: AiSuggestions, scope: Platform?) {
    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.v7_suggestions), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton({ vm.suggestions = null }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
        }
        val target = s.platform ?: scope
        if (s.title != null || s.description != null) {
            Text(s.title.orEmpty(), fontWeight = FontWeight.Bold)
            Text(s.description.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            Button({ vm.useCaption(listOfNotNull(s.title, s.description).joinToString("\n\n"), Platform.YOUTUBE) }) { Text(stringResource(R.string.v7_use_youtube)) }
        }
        s.items.forEach { text ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (s.task) {
                            AiTask.HOOKS -> TextButton({ vm.appendToCaption(text, true) }) { Text(stringResource(R.string.v7_put_start)) }
                            AiTask.CTA -> TextButton({ vm.appendToCaption(text, false) }) { Text(stringResource(R.string.v7_put_end)) }
                            else -> TextButton({ vm.useCaption(text, target) }) { Text(stringResource(R.string.v7_use_caption)) }
                        }
                        TextButton({ vm.appendToCaption(text, false) }) { Text(stringResource(R.string.v7_append)) }
                    }
                }
            }
        }
        if (s.hashtags.isNotEmpty()) {
            Muted(stringResource(R.string.v7_tag_suggestions))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { s.hashtags.forEach { t -> SuggestionChip({ vm.addTags(listOf(t)) }, { Text(t) }) } }
            TextButton({ vm.addTags(s.hashtags) }) { Text(stringResource(R.string.v7_add_all)) }
        }
        if (s.items.isEmpty() && s.hashtags.isEmpty() && s.title == null) Muted(stringResource(R.string.v7_nothing_back))
    }
}

/** A simple stand-in for how the text will look on each platform (layout is approximate, not the real app). */
@Composable
fun PlatformPreview(p: Platform, text: String, account: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (p) {
                Platform.YOUTUBE -> {
                    val title = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(100)
                    Text(title.ifBlank { "—" }, fontWeight = FontWeight.Bold, maxLines = 2)
                    Muted(account)
                    Text(text.removePrefix(title).trim(), style = MaterialTheme.typography.bodySmall, maxLines = 6)
                }
                Platform.INSTAGRAM -> {
                    Text(account.ifBlank { "account" }, fontWeight = FontWeight.Bold)
                    Text(text.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, maxLines = if (text.length > 125) 3 else 8)
                    if (text.length > 125) Muted("… more")
                }
                Platform.TIKTOK -> {
                    Text("@" + account.ifBlank { "account" }, fontWeight = FontWeight.Bold)
                    Text(text.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
            }
        }
    }
}
