package com.example.autoposter

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/** One AI suggestion list shown under the caption editor. */
data class AiSuggestions(val task: AiTask, val items: List<String>, val hashtags: List<String>, val title: String?, val description: String?, val platform: Platform?)

sealed interface PublishOutcome {
    data class Queued(val count: Int, val skipped: Int) : PublishOutcome
    data class Blocked(val reason: ComposerProblem) : PublishOutcome
    data object NoSpace : PublishOutcome
    data class Failed(val message: String) : PublishOutcome
}

class ComposerViewModel(private val c: AppContainer) : ViewModel() {
    var draftId by mutableStateOf<String?>(null); private set
    var title by mutableStateOf("")
    var topic by mutableStateOf("")                       // what the video is about: input for AI generation
    var caption by mutableStateOf("")
    val platformCaptions = mutableStateMapOf<Platform, String>()
    var hashtags by mutableStateOf("")
    val mediaIds = mutableStateListOf<String>()
    var accountIds by mutableStateOf<Set<String>>(emptySet())
    var brandId by mutableStateOf<String?>(null)
    var tone by mutableStateOf(AiTone.CASUAL)
    var language by mutableStateOf("en")
    var plannedAt by mutableStateOf(0L)
    var zoneId by mutableStateOf("")                      // "" = device zone
    var message by mutableStateOf<String?>(null)

    var aiBusy by mutableStateOf(false)
    var aiError by mutableStateOf<AiException?>(null)
    var suggestions by mutableStateOf<AiSuggestions?>(null)
    var publishing by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)

    val zone: ZoneId get() = CalendarLogic.zoneOrDefault(zoneId.ifBlank { null })

    fun content(brand: BrandProfile?): ComposerContent =
        ComposerContent(caption, platformCaptions.toMap(), hashtags, emptyMap(), brand?.signature.orEmpty())

    fun touch() { dirty = true }

    // ---- drafts -------------------------------------------------------------------------------------------------
    fun startNew(zoneFromPrefs: String) {
        draftId = null; title = ""; topic = ""; caption = ""; platformCaptions.clear(); hashtags = ""; mediaIds.clear()
        accountIds = emptySet(); brandId = null; tone = AiTone.CASUAL; language = "en"; plannedAt = 0L; zoneId = zoneFromPrefs
        suggestions = null; aiError = null; dirty = false
    }

    suspend fun load(id: String, fallbackZone: String) {
        val d = c.db.drafts().get(id) ?: return startNew(fallbackZone)
        draftId = d.id; title = d.title; topic = ""; caption = d.baseCaption
        platformCaptions.clear(); platformCaptions.putAll(d.captionMap)
        hashtags = d.hashtags; mediaIds.clear(); mediaIds.addAll(d.mediaIdList)
        accountIds = d.accountIdList.toSet(); brandId = d.brandId; tone = AiTone.fromWire(d.tone); language = d.language
        plannedAt = d.plannedAt; zoneId = d.zoneId; suggestions = null; aiError = null; dirty = false
    }

    fun toEntity(now: Long = System.currentTimeMillis()): ComposerDraftEntity {
        val first = caption.lineSequence().firstOrNull { it.isNotBlank() }?.take(50).orEmpty()
        return ComposerDraftEntity(
            id = draftId ?: UUID.randomUUID().toString(), title = title.ifBlank { first }, baseCaption = caption,
            platformCaptions = JsonLists.encodeMap(platformCaptions), hashtags = hashtags, mediaIds = JsonLists.encode(mediaIds),
            accountIds = JsonLists.encode(accountIds), brandId = brandId, tone = tone.wire, language = language,
            plannedAt = plannedAt, zoneId = zoneId, createdAt = now, updatedAt = now
        )
    }

    fun saveDraft(onDone: () -> Unit = {}) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val e = toEntity(now)
        val old = draftId?.let { c.db.drafts().get(it) }
        c.db.drafts().upsert(e.copy(createdAt = old?.createdAt ?: now))
        draftId = e.id; dirty = false
        onDone()
    }

    /** Saves a copy under a new id and switches to it. */
    fun duplicate(onDone: () -> Unit = {}) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val e = toEntity(now).copy(id = UUID.randomUUID().toString(), title = (title.ifBlank { caption.take(40) } + " (copy)").trim(), createdAt = now, plannedAt = 0L)
        c.db.drafts().upsert(e)
        draftId = e.id; title = e.title; plannedAt = 0L; dirty = false
        onDone()
    }

    fun deleteDraft(onDone: () -> Unit = {}) = viewModelScope.launch { draftId?.let { c.db.drafts().delete(it) }; draftId = null; onDone() }

    // ---- AI -----------------------------------------------------------------------------------------------------
    fun ai(task: AiTask, brand: BrandProfile?, platform: Platform?, mediaNames: List<String>, target: String? = null) {
        if (aiBusy) return
        val textTasks = setOf(AiTask.REWRITE, AiTask.SHORTEN, AiTask.GRAMMAR, AiTask.TRANSLATE)
        val source = if (task in textTasks) (platform?.let { platformCaptions[it] }?.takeIf { it.isNotBlank() } ?: caption)
            else listOf(topic, caption, mediaNames.joinToString(", ")).firstOrNull { it.isNotBlank() }.orEmpty()
        aiBusy = true; aiError = null
        viewModelScope.launch {
            try {
                val r = c.ai.generate(AiRequest(
                    task = task, tone = tone, language = language, input = source, platform = platform,
                    count = when (task) { AiTask.VARIATIONS -> 3; AiTask.HOOKS, AiTask.CTA, AiTask.REWRITE -> 3; else -> 1 },
                    targetLanguage = target, brand = brand,
                    maxChars = if (task == AiTask.SHORTEN) 150 else null
                ))
                suggestions = AiSuggestions(task, r.items, r.hashtags, r.title, r.description, platform)
            } catch (e: AiException) { aiError = e } finally { aiBusy = false }
        }
    }

    fun useCaption(text: String, platform: Platform?) {
        if (platform == null) caption = text else platformCaptions[platform] = text
        dirty = true
    }
    fun appendToCaption(text: String, atStart: Boolean) { caption = if (atStart) "$text\n$caption".trim() else "$caption\n$text".trim(); dirty = true }
    fun addTags(tags: List<String>) { hashtags = ComposerRules.appendTags(hashtags, tags); dirty = true }

    // ---- publishing ---------------------------------------------------------------------------------------------
    /** Builds the plan for this composer state. [startAt] 0 = now. */
    fun plan(library: List<MediaItemEntity>, accounts: List<Account>, brand: BrandProfile?, startAt: Long, history: List<PostItem>, now: Long): List<PlannedPost> {
        val byId = library.associateBy { it.id }
        val media = mediaIds.mapNotNull { byId[it] }.map { CampaignMedia(it.id, it.name, it.kindEnum, it.path, it.sizeBytes, it.fingerprint) }
        val spec = CampaignSpec(
            media = media, accounts = accounts.filter { it.id in accountIds }, mode = Mode.SAME_CLIP,
            content = content(brand), startAt = if (startAt > 0) startAt else now, intervalMinutes = 0
        )
        return CampaignPlanner.plan(spec, now, history, AccountHealthCheck.needsAuthIds(history))
    }

    fun publish(library: List<MediaItemEntity>, accounts: List<Account>, brand: BrandProfile?, scheduleAt: Long, history: List<PostItem>, onDone: (PublishOutcome) -> Unit) {
        val now = System.currentTimeMillis()
        val chosen = accounts.filter { it.id in accountIds }
        val problems = ComposerRules.problems(
            mediaIds.size, chosen.size, content(brand), chosen.map { it.platform }.distinct(),
            hasVideo = library.any { it.id in mediaIds && it.kindEnum == MediaKind.VIDEO }
        )
        problems.firstOrNull { it != ComposerProblem.OVER_LIMIT }?.let { onDone(PublishOutcome.Blocked(it)); return }
        val planned = plan(library, accounts, brand, scheduleAt, history, now)
        val skipped = planned.count { it.blocked }
        publishing = "…"
        viewModelScope.launch {
            val out = CampaignQueue.enqueue(c.app, planned, now) { i, n -> publishing = "$i/$n" }
            publishing = null
            when (out) {
                is CampaignQueue.Outcome.Queued -> { deleteDraft(); onDone(PublishOutcome.Queued(out.count, skipped)) }
                is CampaignQueue.Outcome.NotEnoughSpace -> onDone(PublishOutcome.NoSpace)
                is CampaignQueue.Outcome.Failed -> onDone(PublishOutcome.Failed(out.message))
                CampaignQueue.Outcome.NothingToQueue -> onDone(PublishOutcome.Blocked(ComposerProblem.ONLY_IMAGES))
            }
        }
    }

    /** Videos prepared in the library as a Create-tab selection, so the v0.5 bulk flow can use them too. */
    suspend fun asVideoRefs(ids: List<String>): List<VideoRef> = withContext(Dispatchers.IO) {
        c.library.byIds(ids).filter { it.kindEnum == MediaKind.VIDEO }
            .map { VideoRef(Uri.fromFile(java.io.File(it.path)), it.name, it.sizeBytes, it.fingerprint) }
    }
}
