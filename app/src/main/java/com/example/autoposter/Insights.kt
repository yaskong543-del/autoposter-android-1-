package com.example.autoposter

import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar

object Insights {
    /** Published posts per day for the last [days] days, oldest first. */
    fun publishedPerDay(items: List<PostItem>, days: Int = 14): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (back in days - 1 downTo 0) {
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -back)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val n = items.count { it.status == Status.DONE && it.finishedAt >= start.timeInMillis && it.finishedAt < end.timeInMillis }
            out += "${start.get(Calendar.DAY_OF_MONTH)}/${start.get(Calendar.MONTH) + 1}" to n
        }
        return out
    }

    /**
     * Pulls view/like/comment counts for published YouTube videos (official videos.list endpoint).
     * Needs a token whose scope allows reading video statistics (e.g. youtube.readonly); upload-only tokens get a 403.
     * TikTok and Instagram insights need extra scopes (video.list / instagram_manage_insights) and are not wired yet.
     */
    fun refreshYouTube(store: AccountStore): String {
        val all = PostStore.items.value.filter { it.platform == Platform.YOUTUBE && it.status == Status.DONE && it.remoteId != null }
        if (all.isEmpty()) return "No published YouTube videos yet."
        var updated = 0; var error: String? = null
        for ((accId, list) in all.groupBy { it.accountId }) {
            val acc = store.get(accId)?.let { TokenRefresher.ensureFresh(store, it) } ?: continue
            for (chunk in list.chunked(50)) {
                val ids = chunk.joinToString(",") { it.remoteId!! }
                runCatching {
                    Net.client.newCall(
                        Request.Builder().url("https://www.googleapis.com/youtube/v3/videos?part=statistics&id=$ids")
                            .header("Authorization", "Bearer ${acc.accessToken}").build()
                    ).execute().use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) { error = "YouTube said HTTP ${r.code}. The token may lack a read scope."; return@use }
                        val arr = JSONObject(body).optJSONArray("items") ?: return@use
                        for (i in 0 until arr.length()) {
                            val v = arr.getJSONObject(i); val st = v.optJSONObject("statistics") ?: continue
                            val vid = v.getString("id")
                            chunk.firstOrNull { it.remoteId == vid }?.let { item ->
                                PostStore.update(item.id) {
                                    it.copy(views = st.optString("viewCount", "-1").toLong(), likes = st.optString("likeCount", "-1").toLong(),
                                        comments = st.optString("commentCount", "-1").toLong())
                                }
                                updated++
                            }
                        }
                    }
                }.onFailure { error = it.message }
            }
        }
        return if (updated > 0) "Updated $updated videos." else (error ?: "Nothing to update.")
    }
}
