package com.example.autoposter

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

interface SessionStorage { fun load(): String?; fun save(json: String); fun clear() }

class EncryptedSessionStorage(ctx: Context) : SessionStorage {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "auth_v1", MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    override fun load(): String? = prefs.getString("session", null)
    override fun save(json: String) { prefs.edit().putString("session", json).apply() }
    override fun clear() { prefs.edit().remove("session").apply() }
}

class InMemorySessionStorage : SessionStorage {
    private var v: String? = null
    override fun load() = v
    override fun save(json: String) { v = json }
    override fun clear() { v = null }
}

sealed interface SignUpResult { data class NeedsVerification(val email: String) : SignUpResult; data object SignedIn : SignUpResult }

/** AutoPoster login via Supabase Auth. Social-platform OAuth (YouTube, Instagram, TikTok) is separate and lives in AccountStore. */
class AuthRepository(
    private val http: SupabaseHttp,
    private val storage: SessionStorage,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state
    private val refreshLock = Mutex()
    val configured: Boolean get() = http.config.configured

    val currentSession: AuthSession? get() = when (val s = _state.value) {
        is AuthState.SignedIn -> s.session; is AuthState.Recovery -> s.session; else -> null
    }

    private fun body(vararg p: Pair<String, Any?>): String = JSONObject().also { o -> p.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }.toString()

    private fun accept(s: AuthSession, recovery: Boolean = false): AuthSession {
        storage.save(s.toJson())
        _state.value = if (recovery) AuthState.Recovery(s) else AuthState.SignedIn(s)
        return s
    }

    /** Restores the saved session. Offline-first: a network failure keeps the person signed in. */
    suspend fun restore() {
        val saved = storage.load()?.let(AuthSession::fromStoredJson)
        if (saved == null) { _state.value = AuthState.SignedOut; return }
        if (!configured) { _state.value = AuthState.SignedIn(saved); return }
        if (!saved.expiresWithin(60_000, clock())) { _state.value = AuthState.SignedIn(saved); return }
        _state.value = AuthState.SignedIn(saved)
        try { refresh(saved) } catch (e: ApiException) {
            if (e.kind == ErrorKind.AUTH || e.kind == ErrorKind.VALIDATION) { storage.clear(); _state.value = AuthState.SignedOut }
            else AppLog.w("Auth", "Session refresh deferred: ${e.kind}")
        }
    }

    private suspend fun refresh(old: AuthSession): AuthSession = refreshLock.withLock {
        val cur = currentSession
        if (cur != null && cur.refreshToken != old.refreshToken && !cur.expiresWithin(60_000, clock())) return cur
        val text = http.call("POST", "/auth/v1/token?grant_type=refresh_token", body("refresh_token" to old.refreshToken))
        val s = AuthSession.fromGoTrue(text, clock()) ?: throw ApiException(0, "Bad session response", ErrorKind.UNKNOWN)
        accept(s, recovery = _state.value is AuthState.Recovery)
    }

    /** A valid access token, refreshing first when it is close to expiry. Null when signed out or offline with an expired token. */
    suspend fun accessToken(): String? {
        val s = currentSession ?: return null
        if (!s.expiresWithin(60_000, clock())) return s.accessToken
        return try { refresh(s).accessToken } catch (e: ApiException) {
            if (e.kind == ErrorKind.AUTH) { storage.clear(); _state.value = AuthState.SignedOut }
            null
        }
    }

    suspend fun signUp(email: String, password: String, name: String): SignUpResult {
        val text = http.call("POST", "/auth/v1/signup?redirect_to=${CALLBACK_ENCODED}",
            body("email" to email.trim(), "password" to password, "data" to JSONObject().put("full_name", name.trim())))
        val s = AuthSession.fromGoTrue(text, clock())
        return if (s != null) { accept(s); SignUpResult.SignedIn } else SignUpResult.NeedsVerification(email.trim())
    }

    suspend fun signIn(email: String, password: String) {
        val text = http.call("POST", "/auth/v1/token?grant_type=password", body("email" to email.trim(), "password" to password))
        accept(AuthSession.fromGoTrue(text, clock()) ?: throw ApiException(0, "Bad session response", ErrorKind.UNKNOWN))
    }

    suspend fun signInWithGoogle(idToken: String, rawNonce: String) {
        val text = http.call("POST", "/auth/v1/token?grant_type=id_token",
            body("provider" to "google", "id_token" to idToken, "nonce" to rawNonce))
        accept(AuthSession.fromGoTrue(text, clock()) ?: throw ApiException(0, "Bad session response", ErrorKind.UNKNOWN))
    }

    suspend fun resendVerification(email: String) {
        http.call("POST", "/auth/v1/resend?redirect_to=${CALLBACK_ENCODED}", body("type" to "signup", "email" to email.trim()))
    }

    /** type: "signup" to confirm an e-mail, "recovery" for the password-reset code. */
    suspend fun verifyOtp(email: String, code: String, type: String) {
        val text = http.call("POST", "/auth/v1/verify", body("type" to type, "email" to email.trim(), "token" to code.trim()))
        val s = AuthSession.fromGoTrue(text, clock()) ?: throw ApiException(0, "Bad session response", ErrorKind.UNKNOWN)
        accept(s, recovery = type == "recovery")
    }

    suspend fun sendPasswordReset(email: String) {
        http.call("POST", "/auth/v1/recover?redirect_to=${CALLBACK_ENCODED}", body("email" to email.trim()))
    }

    /** Sets a new password for the signed-in (or recovery) session. */
    suspend fun updatePassword(newPassword: String) {
        val token = accessToken() ?: throw ApiException(401, "Not signed in", ErrorKind.AUTH, "no_session")
        http.call("PUT", "/auth/v1/user", body("password" to newPassword), bearer = token)
        val s = currentSession
        if (s != null) _state.value = AuthState.SignedIn(s)
    }

    suspend fun updateDisplayName(name: String) {
        val token = accessToken() ?: throw ApiException(401, "Not signed in", ErrorKind.AUTH, "no_session")
        val text = http.call("PUT", "/auth/v1/user", body("data" to JSONObject().put("full_name", name.trim())), bearer = token)
        val u = AuthSession.parseUser(JSONObject(text))
        currentSession?.let { accept(it.copy(user = u), recovery = _state.value is AuthState.Recovery) }
    }

    /** Local sign-out always succeeds; the server call is best effort. [everywhere] revokes every device's refresh token. */
    suspend fun signOut(everywhere: Boolean = false) {
        val token = currentSession?.accessToken
        storage.clear()
        _state.value = AuthState.SignedOut
        if (token != null) runCatching {
            http.call("POST", "/auth/v1/logout?scope=${if (everywhere) "global" else "local"}", bearer = token)
        }
    }

    /** Deletes the cloud account through the delete_my_account() database function (see migration 002). */
    suspend fun deleteAccount() {
        val token = accessToken() ?: throw ApiException(401, "Not signed in", ErrorKind.AUTH, "no_session")
        currentSession?.let { deleteStorageObjects(token, it.user.id) }
        http.call("POST", "/rest/v1/rpc/delete_my_account", "{}", bearer = token)
        storage.clear()
        _state.value = AuthState.SignedOut
    }

    /** Best effort: removes this user's files from the private buckets through the Storage API before the account goes. */
    private suspend fun deleteStorageObjects(token: String, userId: String) {
        for (bucket in listOf("avatars", "exports")) runCatching {
            val listing = org.json.JSONArray(http.call("POST", "/storage/v1/object/list/$bucket",
                JSONObject().put("prefix", userId).put("limit", 1000).put("offset", 0).toString(), bearer = token))
            val names = (0 until listing.length()).mapNotNull { listing.getJSONObject(it).str("name") }.map { "$userId/$it" }
            if (names.isNotEmpty()) http.call("DELETE", "/storage/v1/object/$bucket",
                JSONObject().put("prefixes", org.json.JSONArray(names)).toString(), bearer = token)
        }
    }

    /** Handles autoposter://auth/callback links from Supabase e-mails. Returns an error description, or null on success/ignored. */
    suspend fun handleDeepLink(link: String?): String? = when (val d = DeepLinkParser.parse(link)) {
        DeepLink.None -> null
        is DeepLink.Failure -> d.description
        is DeepLink.Tokens -> try {
            val text = http.call("GET", "/auth/v1/user", bearer = d.accessToken)
            val user = AuthSession.parseUser(JSONObject(text))
            accept(AuthSession(d.accessToken, d.refreshToken, clock() + d.expiresInSec * 1000, user), recovery = d.type == "recovery")
            null
        } catch (e: ApiException) { e.message }
    }

    companion object {
        const val CALLBACK = "autoposter://auth/callback"
        val CALLBACK_ENCODED: String = java.net.URLEncoder.encode(CALLBACK, "UTF-8")
    }
}
