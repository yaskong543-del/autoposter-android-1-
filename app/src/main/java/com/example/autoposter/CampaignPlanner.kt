package com.example.autoposter

import java.util.Random

/** One file in a campaign. Videos can be published; images can be prepared but no uploader accepts them yet. */
data class CampaignMedia(val id: String, val name: String, val kind: MediaKind, val path: String, val sizeBytes: Long, val fingerprint: String?)

enum class PlanWarning { IMAGE_NOT_SUPPORTED, ACCOUNT_NEEDS_ATTENTION, OVER_CAPTION_LIMIT, TIME_IN_PAST, ALREADY_QUEUED, EMPTY_CAPTION }

data class PlannedPost(
    val key: String, val media: CampaignMedia, val account: Account, val text: String, val scheduledAt: Long,
    val warnings: Set<PlanWarning>
) {
    /** Blocking problems: these posts are left out when the campaign is queued. */
    val blocked: Boolean get() = PlanWarning.IMAGE_NOT_SUPPORTED in warnings || PlanWarning.ALREADY_QUEUED in warnings
}

data class CampaignSpec(
    val media: List<CampaignMedia>,
    val accounts: List<Account>,
    val mode: Mode = Mode.ROTATE,
    val content: ComposerContent = ComposerContent(),
    val captionByMedia: Map<String, String> = emptyMap(),   // individual caption per file; blank = use the shared one
    val startAt: Long,                                       // epoch ms (UTC instant)
    val intervalMinutes: Int = 30,                           // gap between posts on the same account
    val accountStaggerMinutes: Int = 0,                      // extra delay per account index so accounts don't all post at once
    val accountStart: Map<String, Long> = emptyMap(),        // accountId -> its own first post time
    val timeOverride: Map<String, Long> = emptyMap(),        // "mediaId|accountId" -> an exact time for that one post
    val seed: Long = 1L
)

object CampaignPlanner {
    fun key(mediaId: String, accountId: String) = "$mediaId|$accountId"

    fun pairs(spec: CampaignSpec): List<Pair<Account, CampaignMedia>> {
        val a = spec.accounts; val m = spec.media
        if (a.isEmpty() || m.isEmpty()) return emptyList()
        return when (spec.mode) {
            Mode.ROTATE -> m.mapIndexed { i, v -> a[i % a.size] to v }
            Mode.RANDOM -> m.shuffled(Random(spec.seed)).mapIndexed { i, v -> a[i % a.size] to v }
            Mode.SEQUENTIAL -> a.flatMap { acc -> m.map { v -> acc to v } }
            Mode.SAME_CLIP -> m.flatMap { v -> a.map { acc -> acc to v } }
        }
    }

    fun plan(spec: CampaignSpec, now: Long, history: List<PostItem> = emptyList(), needsAuth: Set<String> = emptySet()): List<PlannedPost> {
        val perAccount = HashMap<String, Int>()
        val live = setOf(Status.QUEUED, Status.UPLOADING, Status.DONE)
        val interval = spec.intervalMinutes.coerceAtLeast(0) * 60_000L
        return pairs(spec).map { (acc, med) ->
            val n = perAccount.getOrDefault(acc.id, 0); perAccount[acc.id] = n + 1
            val idx = spec.accounts.indexOfFirst { it.id == acc.id }.coerceAtLeast(0)
            val base = spec.accountStart[acc.id]?.takeIf { it > 0 } ?: (spec.startAt + idx * spec.accountStaggerMinutes.coerceAtLeast(0) * 60_000L)
            val at = spec.timeOverride[key(med.id, acc.id)] ?: (base + n * interval)

            val caption = spec.captionByMedia[med.id]?.takeIf { it.isNotBlank() }
            val pt = (if (caption != null) spec.content.withCaption(caption) else spec.content).textFor(acc.platform)
            val w = buildSet {
                if (med.kind == MediaKind.IMAGE) add(PlanWarning.IMAGE_NOT_SUPPORTED)
                if (AccountHealthCheck.of(acc, now, acc.id in needsAuth).needsAttention) add(PlanWarning.ACCOUNT_NEEDS_ATTENTION)
                if (pt.overLimit) add(PlanWarning.OVER_CAPTION_LIMIT)
                if (pt.text.isBlank()) add(PlanWarning.EMPTY_CAPTION)
                if (at < now - 60_000L) add(PlanWarning.TIME_IN_PAST)
                if (med.fingerprint != null && history.any { it.accountId == acc.id && it.fingerprint == med.fingerprint && it.status in live }) add(PlanWarning.ALREADY_QUEUED)
            }
            PlannedPost(key(med.id, acc.id), med, acc, pt.text, at, w)
        }
    }

    data class Summary(val total: Int, val publishable: Int, val blocked: Int, val withWarnings: Int, val firstAt: Long?, val lastAt: Long?, val perAccount: Map<String, Int>)

    fun summarize(posts: List<PlannedPost>): Summary {
        val ok = posts.filter { !it.blocked }
        return Summary(
            posts.size, ok.size, posts.size - ok.size, ok.count { it.warnings.isNotEmpty() },
            ok.minOfOrNull { it.scheduledAt }, ok.maxOfOrNull { it.scheduledAt },
            ok.groupingBy { it.account.label }.eachCount()
        )
    }

    /** Turns the publishable part of a plan into queue rows. Pure: file copying happens in [CampaignQueue]. */
    fun toPostItems(posts: List<PlannedPost>, campaignId: String, queuedPath: (CampaignMedia) -> String, now: Long): List<PostItem> =
        posts.filter { !it.blocked }.map { p ->
            PostItem(
                campaignId = campaignId, accountId = p.account.id, accountLabel = p.account.label, platform = p.account.platform,
                filePath = queuedPath(p.media), name = p.media.name, fingerprint = p.media.fingerprint, text = p.text,
                scheduledAt = maxOf(p.scheduledAt, now), sizeBytes = p.media.sizeBytes.coerceAtLeast(0), createdAt = now
            )
        }
}

/** What each platform needs before a post really goes live. Shown in the campaign review so nothing looks more automatic than it is. */
object PlatformReadiness {
    fun note(p: Platform): String = when (p) {
        Platform.YOUTUBE -> "Uploads use the YouTube Data API. Until your API project passes Google's audit, uploaded videos are locked to private."
        Platform.INSTAGRAM -> "Reels are published through the Instagram Graph API and need a professional account and Meta app review."
        Platform.TIKTOK -> "Posting uses the TikTok Content Posting API; until TikTok approves your app, posts can only be private."
    }
}
