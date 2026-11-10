package com.example.autoposter

data class PostCommand(
    val count: Int,
    val captions: List<String>,      // rotated across videos; empty = none
    val sound: String?,
    val accounts: List<String>,      // empty = first saved account, ["*"] = all accounts
    val folder: String? = null,      // gallery folder name, e.g. "Camera"
    val sinceDays: Int? = null,      // 0 = today, 1 = since yesterday
    val gapSeconds: Int = 60,
    val platforms: Set<Platform> = emptySet(),   // "on youtube / tiktok / instagram"
    val hashtags: List<String> = emptyList(),
    val startAt: Long? = null                    // "at 6pm", "tomorrow at 9", "in 2 hours"
)

interface CommandEngine { suspend fun parse(text: String): PostCommand? }

/**
 * Understands phrases like:
 *  post 3 videos caption "Summer vibes" sound Song X account main
 *  upload all videos from Camera folder today on accounts main, second every 5 minutes
 *  post five videos captions Hi | Hello | Hey on all accounts
 */
class RuleEngine : CommandEngine {
    private val I = RegexOption.IGNORE_CASE
    private val stop = """(?=\s+(?:sound|music|song|caption|captions|hashtags?|on account|on accounts|to account|to accounts|on all|on (?:youtube|instagram|tiktok)|account|accounts|from|every|today|yesterday|tomorrow|at\s+\d+(?::\d+)?\s*(?:am|pm)?|in\s+\d+\s+(?:minute|hour|day))\b|$)"""
    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    private fun grab(t: String, key: String): String? {
        Regex("""\b$key\s*[:=]?\s*["“]([^"”]+)["”]""", I).find(t)?.let { return it.groupValues[1].trim() }
        return Regex("""\b$key\s*[:=]?\s*(.+?)$stop""", I).find(t)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    override suspend fun parse(text: String): PostCommand? {
        val t = text.trim()
        if (!Regex("""\b(post|upload)\b""", I).containsMatchIn(t)) return null

        val m = Regex("""\b(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:latest\s+|new\s+|last\s+)?videos?\b""", I).find(t)
        val count = m?.groupValues?.get(1)?.let { it.toIntOrNull() ?: words[it.lowercase()] }
            ?: if (Regex("""\ball\s+(?:my\s+|the\s+)?videos?\b""", I).containsMatchIn(t)) 100 else 1

        val captions = grab(t, "captions?")?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val sound = grab(t, "(?:sound|music|song)")

        val accounts = if (Regex("""\ball\s+accounts\b""", I).containsMatchIn(t)) listOf("*")
        else grab(t, "accounts?")?.split(Regex("""\s*,\s*|\s+and\s+"""))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        var folder = Regex("""\bfrom\s+(?:my\s+|the\s+)?(?:folder\s+)?(.+?)(?:\s+folder)?$stop""", I)
            .find(t)?.groupValues?.get(1)?.trim()
        if (folder != null && folder.lowercase() in setOf("gallery", "phone", "my gallery", "my phone")) folder = null

        val since = when {
            Regex("""\btoday\b""", I).containsMatchIn(t) -> 0
            Regex("""\byesterday\b""", I).containsMatchIn(t) -> 1
            else -> null
        }
        val gap = Regex("""every\s+(\d+)\s*(second|sec|minute|min)s?""", I).find(t)?.let {
            val n = it.groupValues[1].toInt()
            if (it.groupValues[2].lowercase().startsWith("min")) n * 60 else n
        } ?: 60

        val tags = Regex("""#\w+""").findAll(t).map { it.value }.toList().distinct()
        val platforms = buildSet {
            if (Regex("""\b(youtube|shorts)\b""", I).containsMatchIn(t)) add(Platform.YOUTUBE)
            if (Regex("""\b(instagram|reels?)\b""", I).containsMatchIn(t)) add(Platform.INSTAGRAM)
            if (Regex("""\btiktok\b""", I).containsMatchIn(t)) add(Platform.TIKTOK)
        }
        val cleanCaps = captions.map { c -> c.replace(Regex("""\s*#\w+"""), "").trim() }.filter { it.isNotEmpty() }

        return PostCommand(count.coerceIn(1, 100), cleanCaps, sound, accounts, folder, since, gap.coerceIn(10, 3600),
            platforms, tags, parseStart(t))
    }

    private fun parseStart(t: String): Long? {
        Regex("""\bin\s+(\d+)\s*(minute|min|hour|hr|day)s?\b""", I).find(t)?.let {
            val n = it.groupValues[1].toLong()
            val unit = it.groupValues[2].lowercase()
            val ms = when { unit.startsWith("min") -> 60_000L; unit.startsWith("h") -> 3_600_000L; else -> 86_400_000L }
            return System.currentTimeMillis() + n * ms
        }
        val tomorrow = Regex("""\btomorrow\b""", I).containsMatchIn(t)
        val m = Regex("""\bat\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""", I).find(t)
        if (m == null && !tomorrow) return null
        var h = m?.groupValues?.get(1)?.toInt() ?: 9
        val min = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val ap = m?.groupValues?.get(3)?.lowercase().orEmpty()
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h.coerceIn(0, 23)); set(java.util.Calendar.MINUTE, min.coerceIn(0, 59))
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            if (tomorrow) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}

/** For MID/HIGH tiers: plug a local model in here; it falls back to rules if the model fails. */
class LlmEngine(private val fallback: CommandEngine = RuleEngine()) : CommandEngine {
    override suspend fun parse(text: String): PostCommand? {
        // The on-device model is not bundled yet, so this always uses the rule engine.
        return fallback.parse(text)
    }
}

fun engineFor(tier: Tier): CommandEngine = if (tier == Tier.LITE) RuleEngine() else LlmEngine()
