package com.example.autoposter

import org.json.JSONArray
import org.json.JSONObject

/** What the AI gateway can do. [wire] is the value the backend (supabase/functions/ai-gateway) expects. */
enum class AiTask(val wire: String) {
    CAPTION("caption"), VARIATIONS("variations"), HASHTAGS("hashtags"), HOOKS("hooks"), CTA("cta"), REWRITE("rewrite"),
    SHORTEN("shorten"), GRAMMAR("grammar"), TRANSLATE("translate"), PLATFORM_CAPTION("platform_caption"),
    YOUTUBE_META("youtube_meta"), VIDEO_DESCRIPTION("video_description")
}

enum class AiTone {
    PROFESSIONAL, FUNNY, CASUAL, EDUCATIONAL, MARKETING, INSPIRATIONAL, MINIMAL;
    val wire: String get() = name.lowercase()
    companion object { fun fromWire(s: String?): AiTone = entries.firstOrNull { it.wire == s?.lowercase() } ?: CASUAL }
}

data class AiLanguage(val code: String, val label: String)

object AiLanguages {
    val all = listOf(
        AiLanguage("en", "English"), AiLanguage("hi", "हिन्दी"), AiLanguage("es", "Español"), AiLanguage("fr", "Français"),
        AiLanguage("de", "Deutsch"), AiLanguage("pt", "Português"), AiLanguage("it", "Italiano"), AiLanguage("ar", "العربية"),
        AiLanguage("bn", "বাংলা"), AiLanguage("ta", "தமிழ்"), AiLanguage("te", "తెలుగు"), AiLanguage("mr", "मराठी"),
        AiLanguage("ur", "اردو"), AiLanguage("id", "Bahasa Indonesia"), AiLanguage("ru", "Русский"), AiLanguage("ja", "日本語"),
        AiLanguage("ko", "한국어"), AiLanguage("tr", "Türkçe")
    )
    fun label(code: String) = all.firstOrNull { it.code == code }?.label ?: code
}

data class AiRequest(
    val task: AiTask,
    val tone: AiTone = AiTone.CASUAL,
    val language: String = "en",
    val input: String = "",
    val platform: Platform? = null,
    val count: Int = 1,
    val targetLanguage: String? = null,
    val brand: BrandProfile? = null,
    val maxChars: Int? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("action", "generate"); put("task", task.wire); put("tone", tone.wire); put("language", language)
        put("input", input.take(4000)); put("count", count.coerceIn(1, 5))
        platform?.let { put("platform", it.name.lowercase()) }
        targetLanguage?.let { put("targetLanguage", it) }
        maxChars?.let { put("maxChars", it) }
        brand?.let { put("brand", it.toAiJson()) }
    }.toString()
}

/** Token allowance as the backend reports it. The backend is the only authority; this is for display. */
data class AiUsage(
    val plan: String, val limit: Long, val used: Long, val reserved: Long, val remaining: Long,
    val periodStart: String, val resetsAt: String, val requestsPerMinute: Int
) {
    val isPremium: Boolean get() = plan == "pro" || plan == "agency"
    val fractionUsed: Float get() = if (limit <= 0) 1f else ((used + reserved).toFloat() / limit).coerceIn(0f, 1f)
}

data class AiResult(
    val items: List<String>, val hashtags: List<String>, val title: String?, val description: String?,
    val tokensCharged: Int, val usage: AiUsage?
)

enum class AiErrorKind { OFFLINE, SIGNED_OUT, QUOTA, RATE_LIMITED, TOO_LARGE, INVALID, REFUSED, NOT_CONFIGURED, SERVER, UNKNOWN }

class AiException(
    val kind: AiErrorKind, message: String, val retryAfterSec: Int = 0, val usage: AiUsage? = null
) : Exception(message)

/** JSON <-> model mapping for the gateway protocol. Pure, so it is unit-tested without a server. */
object AiWire {
    fun parseUsage(o: JSONObject?): AiUsage? {
        if (o == null) return null
        return AiUsage(
            plan = o.optString("plan", "free"), limit = o.optLong("limit"), used = o.optLong("used"),
            reserved = o.optLong("reserved"), remaining = o.optLong("remaining"),
            periodStart = o.optString("periodStart"), resetsAt = o.optString("resetsAt"),
            requestsPerMinute = o.optInt("requestsPerMinute")
        )
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    fun parseResult(body: String): AiResult {
        val j = JSONObject(body)
        val r = j.getJSONObject("result")
        return AiResult(
            items = strings(r.optJSONArray("items")), hashtags = strings(r.optJSONArray("hashtags")),
            title = r.str("title"), description = r.str("description"),
            tokensCharged = j.optJSONObject("tokens")?.optInt("charged") ?: 0, usage = parseUsage(j.optJSONObject("usage"))
        )
    }

    fun parseError(status: Int, body: String, retryAfterHeader: String?): AiException {
        val j = runCatching { JSONObject(body) }.getOrNull()
        val code = j?.optString("error").orEmpty()
        val msg = j?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP $status"
        val usage = parseUsage(j?.optJSONObject("usage"))
        val retry = j?.optInt("retryAfter", 0)?.takeIf { it > 0 } ?: retryAfterHeader?.toIntOrNull() ?: 0
        val kind = when {
            status == 401 || code == "unauthorized" -> AiErrorKind.SIGNED_OUT
            status == 402 || code == "quota_exceeded" -> AiErrorKind.QUOTA
            status == 429 || code == "rate_limited" -> AiErrorKind.RATE_LIMITED
            status == 413 || code == "too_large" -> AiErrorKind.TOO_LARGE
            status == 422 || code == "refused" -> AiErrorKind.REFUSED
            status == 400 || code == "invalid_request" -> AiErrorKind.INVALID
            code == "ai_not_configured" -> AiErrorKind.NOT_CONFIGURED
            status >= 500 -> AiErrorKind.SERVER
            else -> AiErrorKind.UNKNOWN
        }
        return AiException(kind, msg, retry, usage)
    }
}
