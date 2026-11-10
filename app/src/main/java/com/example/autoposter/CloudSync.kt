package com.example.autoposter

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Filled in with your own Supabase project. The publishable key is designed to be public; row-level security protects the data. */
object CloudDefaults {
    val URL: String = BuildConfig.SUPABASE_URL
    val KEY: String = BuildConfig.SUPABASE_ANON_KEY
}

data class CloudConfig(val url: String, val key: String, val email: String, val password: String)

/**
 * Optional backup of queue metadata (clip names, captions, status, stats) to YOUR Supabase project,
 * so the web dashboard can show it. Account tokens and video files never leave the phone.
 */
object CloudSync {
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, "cloud_v1", MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("enabled", v).apply() }

    fun saved(ctx: Context): CloudConfig {
        val p = prefs(ctx)
        return CloudConfig(
            p.getString("url", CloudDefaults.URL).orEmpty(), p.getString("key", CloudDefaults.KEY).orEmpty(),
            p.getString("email", "").orEmpty(), p.getString("password", "").orEmpty()
        )
    }

    fun save(ctx: Context, c: CloudConfig) {
        prefs(ctx).edit().putString("url", c.url.trim()).putString("key", c.key.trim())
            .putString("email", c.email.trim()).putString("password", c.password).apply()
    }

    private fun ready(c: CloudConfig) = c.url.isNotBlank() && c.key.isNotBlank() && c.email.isNotBlank() && c.password.length >= 6
    private fun base(c: CloudConfig) = c.url.trim().trimEnd('/')

    private class Session(val token: String, val userId: String)

    private fun errorText(body: String, code: Int): String =
        runCatching { JSONObject(body).let { it.optString("msg").ifBlank { it.optString("message") }.ifBlank { it.optString("error_description") } } }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

    private fun signIn(c: CloudConfig): Session {
        val req = Request.Builder().url("${base(c)}/auth/v1/token?grant_type=password").header("apikey", c.key)
            .post(JSONObject().put("email", c.email.trim()).put("password", c.password).toString().toRequestBody(json)).build()
        Net.client.newCall(req).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Sign-in failed: ${errorText(b, r.code)}")
            val j = JSONObject(b)
            return Session(j.getString("access_token"), j.getJSONObject("user").getString("id"))
        }
    }

    /** Creates the cloud login. Supabase may email a confirmation link first. */
    fun signUp(c: CloudConfig): String {
        if (!ready(c)) return "Fill in URL, key, email and a password of 6+ characters."
        val req = Request.Builder().url("${base(c)}/auth/v1/signup").header("apikey", c.key)
            .post(JSONObject().put("email", c.email.trim()).put("password", c.password).toString().toRequestBody(json)).build()
        Net.client.newCall(req).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) return "Couldn't create account: ${errorText(b, r.code)}"
            return if (JSONObject(b).has("access_token")) "Account created. You can sync now."
            else "Account created. Open the confirmation email, then tap Sign in & sync."
        }
    }

    private fun iso(ms: Long): Any = if (ms <= 0) JSONObject.NULL else
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun row(i: PostItem, owner: String) = JSONObject()
        .put("owner", owner).put("id", i.id).put("campaign_id", i.campaignId).put("account_label", i.accountLabel)
        .put("platform", i.platform.name).put("name", i.name).put("text", i.text).put("scheduled_at", iso(i.scheduledAt))
        .put("status", i.status.name).put("progress", i.progress.toDouble()).put("attempts", i.attempts)
        .put("remote_id", i.remoteId ?: JSONObject.NULL).put("error", i.error ?: JSONObject.NULL)
        .put("created_at", iso(i.createdAt)).put("finished_at", iso(i.finishedAt)).put("size_bytes", i.sizeBytes)
        .put("views", i.views).put("likes", i.likes).put("comments", i.comments)

    /** Blocking. Returns a short message for the UI. Deleting a row on the phone keeps it in the cloud as history. */
    fun push(ctx: Context): String {
        val items = PostStore.items.value
        val auth = ctx.container.auth
        val appSession = auth.currentSession
        val cfg = ctx.container.config
        // Signed in to AutoPoster: use that session. Otherwise fall back to the separate credentials from v0.5.
        val c: CloudConfig; val s: Session
        if (appSession != null && cfg.configured) {
            val token = kotlinx.coroutines.runBlocking { auth.accessToken() } ?: return "Session expired. Sign in again."
            c = CloudConfig(cfg.url, cfg.anonKey, "", ""); s = Session(token, appSession.user.id)
        } else {
            c = saved(ctx)
            if (!ready(c)) return "Cloud sync isn't set up yet."
            if (items.isEmpty()) return "Nothing to sync yet."
            s = signIn(c)
        }
        if (items.isEmpty()) return "Nothing to sync yet."
        for (chunk in items.chunked(200)) {
            val arr = JSONArray(); chunk.forEach { arr.put(row(it, s.userId)) }
            val req = Request.Builder().url("${base(c)}/rest/v1/post_items?on_conflict=owner,id")
                .header("apikey", c.key).header("Authorization", "Bearer ${s.token}")
                .header("Prefer", "resolution=merge-duplicates,return=minimal").post(arr.toString().toRequestBody(json)).build()
            Net.client.newCall(req).execute().use {
                if (!it.isSuccessful) throw IOException("Sync failed: ${errorText(it.body?.string().orEmpty(), it.code)}")
            }
        }
        return "Synced ${items.size} items at ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}."
    }

    fun pushIfEnabled(ctx: Context) { if (enabled(ctx)) runCatching { push(ctx) } }
}
