package com.example.autoposter

/**
 * On-device caption + hashtag helper. It is template- and keyword-based (no network, works on LITE devices).
 * MID/HIGH tiers can later swap in a local LLM behind the same functions.
 */
object CaptionAI {
    private val stop = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "my", "our", "your", "is", "are",
        "this", "that", "it", "at", "from", "by", "new", "video", "videos", "post", "about", "how", "i", "we", "you", "was", "be")

    private val categories: Map<String, List<String>> = mapOf(
        "travel" to listOf("#travel", "#explore", "#wanderlust", "#traveltok"),
        "food" to listOf("#food", "#foodie", "#recipe", "#delicious"),
        "cook" to listOf("#cooking", "#homemade", "#recipe"),
        "fitness" to listOf("#fitness", "#workout", "#gym", "#health"),
        "gym" to listOf("#gym", "#fitness", "#training"),
        "music" to listOf("#music", "#newmusic", "#song"),
        "dance" to listOf("#dance", "#dancer", "#choreography"),
        "tech" to listOf("#tech", "#technology", "#gadgets"),
        "game" to listOf("#gaming", "#gamer", "#gameplay"),
        "fashion" to listOf("#fashion", "#style", "#ootd"),
        "beauty" to listOf("#beauty", "#makeup", "#skincare"),
        "comedy" to listOf("#comedy", "#funny", "#humor"),
        "car" to listOf("#cars", "#carlife", "#automotive"),
        "pet" to listOf("#pets", "#petlife", "#cute"),
        "dog" to listOf("#dogs", "#doglife", "#puppy"),
        "cat" to listOf("#cats", "#catlife", "#kitten"),
        "business" to listOf("#business", "#entrepreneur", "#marketing"),
        "art" to listOf("#art", "#artist", "#creative"),
        "nature" to listOf("#nature", "#outdoors", "#scenery"),
        "sport" to listOf("#sports", "#athlete", "#highlights"),
        "summer" to listOf("#summer", "#summervibes", "#sunshine"),
        "diy" to listOf("#diy", "#howto", "#tips")
    )

    private val platformTags = mapOf(
        Platform.YOUTUBE to listOf("#shorts"),
        Platform.INSTAGRAM to listOf("#reels", "#instagood"),
        Platform.TIKTOK to listOf("#fyp")
    )

    fun keywords(text: String): List<String> = text.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 && it !in stop }.distinct()

    fun hashtags(topic: String, platform: Platform): List<String> {
        val kws = keywords(topic).take(4)
        val out = LinkedHashSet<String>()
        kws.forEach { out += "#$it" }
        if (kws.size >= 2) out += "#${kws[0]}${kws[1]}"
        kws.forEach { k -> categories.filterKeys { k.startsWith(it) }.values.forEach { out += it } }
        platformTags[platform]?.let { out += it }
        return out.take(platform.tagSuggest)
    }

    fun captions(topic: String, platform: Platform, tone: String): List<String> {
        val t = topic.trim().ifBlank { "this" }
        val short = platform == Platform.TIKTOK
        return when (tone) {
            "Professional" -> listOf(
                "Here's what we learned about $t.",
                "$t, explained in under a minute.",
                "A practical look at $t. Save this for later.",
                "$t: the key points, no fluff."
            )
            "Inspiring" -> listOf(
                "Small steps. Big change. $t.",
                "Start before you feel ready: $t.",
                "$t is a reminder that progress counts.",
                "Keep going. $t is worth it."
            )
            else -> listOf(
                "POV: $t 👀",
                "Wait for it… $t 😅",
                "Tell me you love $t without telling me 😎",
                "This is your sign to try $t ✨"
            )
        }.map { base ->
            when (platform) {
                Platform.YOUTUBE -> "$base Subscribe for more."
                Platform.INSTAGRAM -> "$base\nSave it and send it to a friend."
                Platform.TIKTOK -> base
            }
        }.map { if (short) it.take(150) else it }
    }
}
