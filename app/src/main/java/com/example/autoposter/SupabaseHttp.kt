package com.example.autoposter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public project values only (URL + publishable anon key). The service-role key is never used in the app. */
data class SupabaseConfig(val url: String, val anonKey: String, val googleClientId: String = "") {
    val configured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()
    val base: String get() = url.trim().trimEnd('/')
}

enum class ErrorKind { NETWORK, AUTH, VALIDATION, SERVER, NOT_CONFIGURED, UNKNOWN }

class ApiException(
    val status: Int, message: String, val kind: ErrorKind, val errorCode: String? = null
) : Exception(message)

/** Thin Supabase REST client (GoTrue, PostgREST, Storage, RPC). */
class SupabaseHttp(val config: SupabaseConfig, client: OkHttpClient = Net.client) {
    private val http = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Returns the response body text. Throws [ApiException] for HTTP errors and network failures. */
    suspend fun call(
        method: String, path: String, body: String? = null, bearer: String? = null, headers: Map<String, String> = emptyMap()
    ): String {
        if (!config.configured) throw ApiException(0, "Backend is not configured", ErrorKind.NOT_CONFIGURED, "not_configured")
        val b = Request.Builder().url(config.base + path).header("apikey", config.anonKey)
            .header("Authorization", "Bearer ${bearer ?: config.anonKey}")
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (body != null) body.toRequestBody(json) else if (method == "GET" || method == "HEAD") null else "".toRequestBody(json))
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(b.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.isSuccessful) text else throw parseError(r.code, text)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                throw ApiException(0, "Network error", ErrorKind.NETWORK, "network")
            }
        }
    }

    companion object {
        fun parseError(code: Int, body: String): ApiException {
            val j = runCatching { JSONObject(body) }.getOrNull()
            val errorCode = j?.let { it.optString("error_code").ifBlank { it.optString("code").ifBlank { it.optString("error") } } }
                ?.takeIf { it.isNotBlank() }
            val msg = j?.let {
                it.optString("msg").ifBlank { it.optString("message").ifBlank { it.optString("error_description").ifBlank { it.optString("error") } } }
            }?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            val kind = when {
                code == 401 || code == 403 -> ErrorKind.AUTH
                code == 400 && errorCode in setOf("invalid_credentials", "email_not_confirmed", "otp_expired", "invalid_grant", "bad_jwt", "refresh_token_not_found", "refresh_token_already_used", "session_not_found") -> ErrorKind.AUTH
                code in 400..499 -> ErrorKind.VALIDATION
                code >= 500 -> ErrorKind.SERVER
                else -> ErrorKind.UNKNOWN
            }
            return ApiException(code, msg, kind, errorCode)
        }
    }
}
