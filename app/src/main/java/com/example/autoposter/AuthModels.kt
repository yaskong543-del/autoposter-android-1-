package com.example.autoposter

import org.json.JSONObject
import java.net.URLDecoder

data class AuthUser(
    val id: String, val email: String, val displayName: String?, val emailConfirmed: Boolean, val provider: String?
)

data class AuthSession(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val user: AuthUser) {
    fun expiresWithin(ms: Long, now: Long) = expiresAtMs - now <= ms

    fun toJson(): String = JSONObject()
        .put("access_token", accessToken).put("refresh_token", refreshToken).put("expires_at_ms", expiresAtMs)
        .put("user", JSONObject().put("id", user.id).put("email", user.email).put("name", user.displayName)
            .put("confirmed", user.emailConfirmed).put("provider", user.provider)).toString()

    companion object {
        fun fromStoredJson(s: String): AuthSession? = runCatching {
            val o = JSONObject(s); val u = o.getJSONObject("user")
            AuthSession(
                o.getString("access_token"), o.getString("refresh_token"), o.getLong("expires_at_ms"),
                AuthUser(u.getString("id"), u.optString("email", ""), u.optString("name").takeIf { it.isNotBlank() && it != "null" },
                    u.optBoolean("confirmed", false), u.optString("provider").takeIf { it.isNotBlank() && it != "null" })
            )
        }.getOrNull()

        /** Parses a GoTrue token/verify response. Returns null when the body has no session. */
        fun fromGoTrue(body: String, now: Long): AuthSession? = runCatching {
            val o = JSONObject(body)
            if (!o.has("access_token")) return null
            val user = parseUser(o.getJSONObject("user"))
            AuthSession(o.getString("access_token"), o.optString("refresh_token", ""),
                now + o.optLong("expires_in", 3600) * 1000, user)
        }.getOrNull()

        fun parseUser(u: JSONObject): AuthUser {
            val meta = u.optJSONObject("user_metadata")
            val name = meta?.optString("full_name")?.takeIf { it.isNotBlank() } ?: meta?.optString("name")?.takeIf { it.isNotBlank() }
            return AuthUser(
                u.getString("id"), u.optString("email", ""), name,
                !u.isNull("email_confirmed_at") && u.optString("email_confirmed_at").isNotBlank() || !u.isNull("confirmed_at") && u.optString("confirmed_at").isNotBlank(),
                u.optJSONObject("app_metadata")?.optString("provider")?.takeIf { it.isNotBlank() }
            )
        }
    }
}

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val session: AuthSession) : AuthState
    /** Opened from a password-reset link: the person must choose a new password before continuing. */
    data class Recovery(val session: AuthSession) : AuthState
}

sealed interface DeepLink {
    data object None : DeepLink
    data class Tokens(val accessToken: String, val refreshToken: String, val expiresInSec: Long, val type: String) : DeepLink
    data class Failure(val code: String, val description: String) : DeepLink
}

/** Parses autoposter://auth/callback#access_token=...&type=recovery without needing android.net.Uri. */
object DeepLinkParser {
    fun parse(link: String?): DeepLink {
        if (link.isNullOrBlank() || !link.startsWith("autoposter://auth")) return DeepLink.None
        val raw = listOf(link.substringAfter('#', ""), link.substringAfter('?', "").substringBefore('#'))
            .filter { it.isNotBlank() }.joinToString("&")
        val p = raw.split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
        }
        p["error"]?.let { return DeepLink.Failure(p["error_code"] ?: it, p["error_description"] ?: it) }
        val at = p["access_token"]; val rt = p["refresh_token"]
        if (at.isNullOrBlank() || rt.isNullOrBlank()) return DeepLink.None
        return DeepLink.Tokens(at, rt, p["expires_in"]?.toLongOrNull() ?: 3600, p["type"] ?: "")
    }
}
