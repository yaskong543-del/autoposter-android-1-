package com.example.autoposter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

data class CloudJob(
    val id: String, val postId: String, val platform: String, val status: String, val runAt: Long, val attempts: Int, val maxAttempts: Int,
    val remoteUrl: String?, val errorCode: String?, val errorMessage: String?
)
data class CloudAccount(val id: String, val platform: String, val displayName: String, val status: String, val tokenExpiresAt: Long, val blockedUntil: Long, val errorCode: String?)

/** Maps the server's plan-limit SQLSTATEs / codes to messages for the user. Anything else is not a plan problem. */
object PlanErrors {
    fun message(code: String?): String? = when (code) {
        "PL001" -> "Your plan's limit of connected profiles is reached. Remove one or upgrade."
        "PL002" -> "Your plan's limit of registered devices is reached. Remove a device or upgrade."
        "PL003" -> "This device was removed from your account. Sign in again to add it back."
        "PL005" -> "This account needs to be reconnected before it can publish."
        "PL007" -> "Your plan's limit of scheduled cloud posts is reached."
        "PL008" -> "This post may already be live. Check the platform before retrying."
        "plan_limit_autosync" -> "Automatic sync is a Premium feature. Sync manually or upgrade."
        "device_revoked" -> "This device was removed from your account."
        "device_suspended" -> "This device is over your plan's device limit."
        else -> null
    }
}

class UploadHttpException(val status: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = status == 408 || status == 429 || status >= 500
}

/** Everything the app asks of the v0.8 backend. All calls carry the signed-in user's JWT; no service-role key exists in the app. */
class CloudApi(private val http: SupabaseHttp, private val token: suspend () -> String?) {
    private suspend fun bearer(): String = token() ?: throw ApiException(401, "Not signed in", ErrorKind.AUTH, "no_session")
    private suspend fun rpc(fn: String, body: JSONObject): String = http.call("POST", "/rest/v1/rpc/$fn", body.toString(), bearer())

    private fun scalar(text: String): String = runCatching { JSONArray("[$text]").getString(0) }.getOrDefault(text.trim().trim('"'))

    suspend fun entitlements(): Entitlements = Entitlements.fromJson(JSONObject(rpc("my_entitlements", JSONObject())))

    suspend fun registerDevice(installId: String, name: String, appVersion: String, osVersion: String): String =
        scalar(rpc("register_device", JSONObject().put("p_install_id", installId).put("p_name", name).put("p_platform", "android")
            .put("p_app_version", appVersion).put("p_os_version", osVersion)))

    suspend fun revokeDevice(deviceId: String): Boolean = scalar(rpc("revoke_device", JSONObject().put("p_device", deviceId))).toBoolean()

    suspend fun enqueueJob(postId: String, accountId: String, runAtMs: Long, tz: String, payload: JSONObject, mediaPath: String, idempotencyKey: String): String =
        scalar(rpc("enqueue_publish_job", JSONObject().put("p_post_id", postId).put("p_account_id", accountId).put("p_run_at", Iso.utc(runAtMs))
            .put("p_tz", tz).put("p_payload", payload).put("p_media_path", mediaPath).put("p_idem", idempotencyKey)))

    /** "cancelled" | "cancel_requested" | "too_late" | "already_final" */
    suspend fun cancelJob(jobId: String): String = scalar(rpc("cancel_publish_job", JSONObject().put("p_job", jobId)))
    suspend fun retryJob(jobId: String, runAtMs: Long): Boolean = scalar(rpc("retry_publish_job", JSONObject().put("p_job", jobId).put("p_run_at", Iso.utc(runAtMs)))).toBoolean()

    suspend fun listJobs(): List<CloudJob> {
        val arr = JSONArray(http.call("GET", "/rest/v1/publish_jobs?select=id,post_id,platform,status,run_at,attempts,max_attempts,remote_url,last_error_code,last_error_message&order=run_at.desc&limit=100", null, bearer()))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            CloudJob(it.getString("id"), it.getString("post_id"), it.getString("platform"), it.getString("status"), Iso.parse(it.optString("run_at")),
                it.optInt("attempts"), it.optInt("max_attempts", 5), it.optStringOrNull("remote_url"), it.optStringOrNull("last_error_code"), it.optStringOrNull("last_error_message"))
        }
    }

    suspend fun accounts(): List<CloudAccount> {
        val arr = JSONArray(http.call("GET", "/rest/v1/provider_accounts?select=id,platform,display_name,status,token_expires_at,blocked_until,last_error_code&order=created_at", null, bearer()))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            CloudAccount(it.getString("id"), it.getString("platform"), it.optString("display_name"), it.getString("status"),
                Iso.parse(it.optStringOrNull("token_expires_at")), Iso.parse(it.optStringOrNull("blocked_until")), it.optStringOrNull("last_error_code"))
        }
    }

    suspend fun notifications(): List<ServerNotification> {
        val arr = JSONArray(http.call("GET", "/rest/v1/notifications?select=id,kind,title,body,ref,created_at,delivered_at&order=created_at.desc&limit=50", null, bearer()))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            ServerNotification(it.getString("id"), it.getString("kind"), it.getString("title"), it.getString("body"), it.optStringOrNull("ref"),
                Iso.parse(it.optString("created_at")), Iso.parse(it.optStringOrNull("delivered_at")))
        }
    }

    suspend fun markDelivered(ids: List<String>, nowMs: Long) {
        val safe = ids.filter { Regex("^[0-9a-fA-F-]{36}$").matches(it) }          // ids go into a URL filter: accept UUIDs only
        if (safe.isEmpty()) return
        http.call("PATCH", "/rest/v1/notifications?id=in.(${safe.joinToString(",")})", JSONObject().put("delivered_at", Iso.utc(nowMs)).toString(), bearer(),
            mapOf("Prefer" to "return=minimal"))
    }

    /** Returns the provider authorization URL to open in a browser/Custom Tab. Errors: 501 not_configured, 400 unsupported_platform. */
    suspend fun oauthStart(platform: String): String =
        JSONObject(http.call("POST", "/functions/v1/oauth/start", JSONObject().put("platform", platform).toString(), bearer())).getString("url")

    suspend fun disconnect(accountId: String) { http.call("POST", "/functions/v1/oauth/disconnect", JSONObject().put("accountId", accountId).toString(), bearer()) }

    suspend fun sync(body: JSONObject): JSONObject = JSONObject(http.call("POST", "/functions/v1/sync", body.toString(), bearer()))

    /**
     * Uploads the video to the user's private folder in the publish-media bucket (standard upload with progress and cancellation).
     * Limit: the project's global Storage file-size limit (50 MB on the Supabase free plan). Resumable (TUS) upload is NOT implemented in v0.8.
     */
    suspend fun uploadMedia(storagePath: String, file: File, contentType: String, onProgress: (Float) -> Unit, isCancelled: () -> Boolean) {
        val jwt = bearer()
        val cfg = http.config
        val encoded = storagePath.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaTypeOrNull()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024); var sent = 0L; val total = file.length().coerceAtLeast(1L)
                    while (true) {
                        if (isCancelled()) throw IOException("cancelled")
                        val n = input.read(buf); if (n < 0) break
                        sink.write(buf, 0, n); sent += n; onProgress((sent.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        val req = Request.Builder().url("${cfg.base}/storage/v1/object/publish-media/$encoded").header("apikey", cfg.anonKey)
            .header("Authorization", "Bearer $jwt").header("x-upsert", "true").put(body).build()
        withContext(Dispatchers.IO) {
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw UploadHttpException(r.code, "storage upload failed (HTTP ${r.code})")
            }
        }
    }
}

internal fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else getString(k).takeIf { it.isNotEmpty() }
