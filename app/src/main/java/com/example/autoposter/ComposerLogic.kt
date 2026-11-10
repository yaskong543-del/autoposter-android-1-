package com.example.autoposter

/** The text of one post as it will actually be sent to one platform, and whether it had to be cut. */
data class PlatformText(val platform: Platform, val text: String, val rawLength: Int, val limit: Int, val tags: List<String>) {
    val overLimit: Boolean get() = rawLength > limit
}

/** Builds platform-specific text from the shared caption, per-platform overrides, hashtags and the brand signature. Pure. */
data class ComposerContent(
    val baseCaption: String = "",
    val platformCaptions: Map<Platform, String> = emptyMap(),
    val hashtags: String = "",
    val platformHashtags: Map<Platform, String> = emptyMap(),
    val signature: String = ""
) {
    fun textFor(p: Platform): PlatformText {
        val caption = (platformCaptions[p]?.takeIf { it.isNotBlank() } ?: baseCaption).trim()
        val tags = parseTags(platformHashtags[p]?.takeIf { it.isNotBlank() } ?: hashtags).take(p.tagLimit)
        val raw = listOf(caption, signature.trim(), tags.joinToString(" ")).filter { it.isNotBlank() }.joinToString("\n\n")
        return PlatformText(p, raw.take(p.captionLimit), raw.length, p.captionLimit, tags)
    }

    /** Same as [textFor] but with a different caption, used for per-media captions in a campaign. */
    fun withCaption(caption: String) = copy(baseCaption = caption, platformCaptions = emptyMap())
}

object ComposerRules {
    fun problems(mediaCount: Int, accountCount: Int, content: ComposerContent, platforms: Collection<Platform>, hasVideo: Boolean): List<ComposerProblem> =
        buildList {
            if (mediaCount == 0) add(ComposerProblem.NO_MEDIA)
            if (mediaCount > 0 && !hasVideo) add(ComposerProblem.ONLY_IMAGES)
            if (accountCount == 0) add(ComposerProblem.NO_ACCOUNT)
            if (content.baseCaption.isBlank() && platforms.all { content.platformCaptions[it].isNullOrBlank() }) add(ComposerProblem.NO_CAPTION)
            if (platforms.any { content.textFor(it).overLimit }) add(ComposerProblem.OVER_LIMIT)
        }

    /** Hashtags not already in [current], so a suggestion chip never adds a duplicate. */
    fun newTags(suggested: List<String>, current: String): List<String> {
        val have = parseTags(current).map { it.lowercase() }.toSet()
        return suggested.map { if (it.startsWith("#")) it else "#$it" }.filter { it.lowercase() !in have }.distinctBy { it.lowercase() }
    }

    fun appendTags(current: String, add: List<String>): String = (parseTags(current) + newTags(add, current)).joinToString(" ")
}

enum class ComposerProblem { NO_MEDIA, ONLY_IMAGES, NO_ACCOUNT, NO_CAPTION, OVER_LIMIT }

object SelectionMerge {
    /** Keeps the order the person already had for items that stay selected, then appends new picks in library order. */
    fun merge(current: List<String>, chosen: Set<String>, libraryOrder: List<String>): List<String> {
        val keep = current.filter { it in chosen }
        return keep + libraryOrder.filter { it in chosen && it !in keep }
    }
}
