package com.example.autoposter

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * Renews YouTube (Google) access tokens when the account has a refresh token + OAuth client id.
 * Instagram and TikTok accounts must be re-connected when their token expires; wire a real OAuth
 * sign-in (AppAuth) plus each platform's refresh endpoint here when you move past testing.
 */
object TokenRefresher {
    fun refreshable(a: Account) = a.platform == Platform.YOUTUBE && !a.refreshToken.isNullOrBlank() && !a.clientId.isNullOrBlank()

    fun ensureFresh(store: AccountStore, a: Account): Account {
        if (!refreshable(a)) return a
        val soon = System.currentTimeMillis() + 120_000
        return if (a.expiresAt == 0L || a.expiresAt < soon) forceRefresh(store, a) ?: a else a
    }

    /** Blocking. Returns the updated account, or null when the refresh failed. */
    fun forceRefresh(store: AccountStore, a: Account): Account? {
        if (!refreshable(a)) return null
        return runCatching {
            Net.client.newCall(
                Request.Builder().url("https://oauth2.googleapis.com/token").post(
                    FormBody.Builder().add("client_id", a.clientId!!).add("refresh_token", a.refreshToken!!)
                        .add("grant_type", "refresh_token").build()
                ).build()
            ).execute().use {
                if (!it.isSuccessful) return null
                val j = JSONObject(it.body!!.string())
                a.copy(accessToken = j.getString("access_token"), expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3300) * 1000)
                    .also(store::save)
            }
        }.getOrNull()
    }
}
