package com.example.autoposter

import org.json.JSONObject
import java.util.UUID

enum class Platform(val label: String, val symbol: String, val captionLimit: Int, val tagLimit: Int, val tagSuggest: Int) {
    YOUTUBE("YouTube", "▶", 5000, 15, 8),
    INSTAGRAM("Instagram", "◎", 2200, 30, 10),
    TIKTOK("TikTok", "♪", 2200, 20, 5);

    /** Visibility values each platform's API accepts. Instagram has none. */
    val visibilityOptions: List<String>
        get() = when (this) {
            YOUTUBE -> listOf("private", "unlisted", "public")
            TIKTOK -> listOf("SELF_ONLY", "FOLLOWER_OF_CREATOR", "MUTUAL_FOLLOW_FRIENDS", "PUBLIC_TO_EVERYONE")
            INSTAGRAM -> emptyList()
        }
    val defaultVisibility: String get() = visibilityOptions.firstOrNull() ?: ""
}

enum class Status(val label: String) {
    QUEUED("Scheduled"), UPLOADING("Uploading"), DONE("Published"),
    FAILED("Failed"), NEEDS_AUTH("Needs sign-in"), CANCELED("Canceled");

    val active: Boolean get() = this == QUEUED || this == UPLOADING
    val retryable: Boolean get() = this == FAILED || this == NEEDS_AUTH || this == CANCELED
}

private fun JSONObject.strOrNull(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val platform: Platform,
    val label: String,
    val accessToken: String,
    val refreshToken: String? = null,   // YouTube: lets the app renew the access token
    val clientId: String? = null,       // YouTube: OAuth client id used for the refresh
    val expiresAt: Long = 0L,           // epoch ms, 0 = unknown
    val igUserId: String? = null,       // Instagram: professional account user id
    val visibility: String = platform.defaultVisibility
) {
    fun toJson(): String = JSONObject()
        .put("id", id).put("platform", platform.name).put("label", label)
        .put("accessToken", accessToken).put("refreshToken", refreshToken).put("clientId", clientId)
        .put("expiresAt", expiresAt).put("igUserId", igUserId).put("visibility", visibility).toString()

    companion object {
        fun fromJson(s: String): Account? = runCatching {
            val o = JSONObject(s)
            val p = Platform.valueOf(o.getString("platform"))
            Account(
                o.getString("id"), p, o.getString("label"), o.getString("accessToken"),
                o.strOrNull("refreshToken"), o.strOrNull("clientId"), o.optLong("expiresAt", 0L),
                o.strOrNull("igUserId"), o.optString("visibility", p.defaultVisibility)
            )
        }.getOrNull()
    }
}

/** One video going to one account. A campaign is many of these. */
data class PostItem(
    val id: String = UUID.randomUUID().toString(),
    val campaignId: String,
    val accountId: String,
    val accountLabel: String,
    val platform: Platform,
    val filePath: String,
    val name: String,
    val fingerprint: String?,
    val text: String,
    val scheduledAt: Long,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val attempts: Int = 0,
    val remoteId: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L,
    val sizeBytes: Long = 0L,
    val views: Long = -1L,
    val likes: Long = -1L,
    val comments: Long = -1L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("campaignId", campaignId).put("accountId", accountId).put("accountLabel", accountLabel)
        .put("platform", platform.name).put("filePath", filePath).put("name", name).put("fingerprint", fingerprint)
        .put("text", text).put("scheduledAt", scheduledAt).put("status", status.name).put("progress", progress.toDouble())
        .put("attempts", attempts).put("remoteId", remoteId).put("error", error).put("createdAt", createdAt)
        .put("finishedAt", finishedAt).put("sizeBytes", sizeBytes).put("views", views).put("likes", likes).put("comments", comments)

    companion object {
        fun fromJson(o: JSONObject): PostItem = PostItem(
            id = o.getString("id"), campaignId = o.getString("campaignId"), accountId = o.getString("accountId"),
            accountLabel = o.optString("accountLabel", "account"), platform = Platform.valueOf(o.getString("platform")),
            filePath = o.getString("filePath"), name = o.optString("name", "video"), fingerprint = o.strOrNull("fingerprint"),
            text = o.optString("text", ""), scheduledAt = o.getLong("scheduledAt"),
            status = Status.valueOf(o.getString("status")), progress = o.optDouble("progress", 0.0).toFloat(),
            attempts = o.optInt("attempts", 0), remoteId = o.strOrNull("remoteId"), error = o.strOrNull("error"),
            createdAt = o.optLong("createdAt", 0L), finishedAt = o.optLong("finishedAt", 0L), sizeBytes = o.optLong("sizeBytes", 0L),
            views = o.optLong("views", -1L), likes = o.optLong("likes", -1L), comments = o.optLong("comments", -1L)
        )
    }
}
