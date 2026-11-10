package com.example.autoposter

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the `ai-gateway` Edge Function with the signed-in user's JWT. There is NO AI provider key in this app:
 * the gateway holds it, checks the user, enforces the quota and rate limit, and only then calls the model.
 */
class AiGatewayClient(
    private val baseUrl: String,
    private val anonKey: String,
    private val token: suspend () -> String?,
    client: OkHttpClient = Net.client
) {
    private val http = client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    val configured: Boolean get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    private suspend fun post(body: String): Triple<Int, String, String?> {
        if (!configured) throw AiException(AiErrorKind.NOT_CONFIGURED, "Backend is not configured")
        val jwt = token() ?: throw AiException(AiErrorKind.SIGNED_OUT, "Sign in to use AI")
        val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/functions/v1/ai-gateway")
            .header("apikey", anonKey).header("Authorization", "Bearer $jwt")
            .post(body.toRequestBody(json)).build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(req).execute().use { r -> Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After")) }
            } catch (e: IOException) {
                throw AiException(AiErrorKind.OFFLINE, "No connection. AI needs the internet.")
            }
        }
    }

    suspend fun generate(r: AiRequest): AiResult {
        val (code, body, retry) = post(r.toJson())
        if (code !in 200..299) throw AiWire.parseError(code, body, retry)
        return runCatching { AiWire.parseResult(body) }.getOrElse { throw AiException(AiErrorKind.SERVER, "Unreadable AI response") }
    }

    suspend fun usage(): AiUsage {
        val (code, body, retry) = post("""{"action":"usage"}""")
        if (code !in 200..299) throw AiWire.parseError(code, body, retry)
        return AiWire.parseUsage(org.json.JSONObject(body).optJSONObject("usage")) ?: throw AiException(AiErrorKind.SERVER, "Unreadable usage response")
    }
}

/** Last usage the server told us, kept so the remaining-token display works offline. Display only; never used to authorise. */
@Entity(tableName = "ai_usage_cache")
data class AiUsageEntity(
    @PrimaryKey val id: Int = 1, val plan: String, val tokenLimit: Long, val used: Long, val reserved: Long, val remaining: Long,
    val periodStart: String, val resetsAt: String, val requestsPerMinute: Int, val fetchedAt: Long
)

@Dao
interface AiUsageDao {
    @Query("SELECT * FROM ai_usage_cache WHERE id = 1") fun observe(): Flow<AiUsageEntity?>
    @Query("SELECT * FROM ai_usage_cache WHERE id = 1") suspend fun get(): AiUsageEntity?
    @Upsert suspend fun upsert(e: AiUsageEntity)
    @Query("DELETE FROM ai_usage_cache") suspend fun clear()
}

fun AiUsageEntity.toUsage() = AiUsage(plan, tokenLimit, used, reserved, remaining, periodStart, resetsAt, requestsPerMinute)

class AiRepository(
    private val client: AiGatewayClient, private val dao: AiUsageDao, private val clock: () -> Long = System::currentTimeMillis
) {
    val usage: Flow<AiUsage?> = dao.observe().map { it?.toUsage() }
    val available: Boolean get() = client.configured

    private suspend fun remember(u: AiUsage?) {
        if (u != null) dao.upsert(AiUsageEntity(1, u.plan, u.limit, u.used, u.reserved, u.remaining, u.periodStart, u.resetsAt, u.requestsPerMinute, clock()))
    }

    /** Every call goes to the server: it alone decides whether the request is allowed and what it costs. */
    suspend fun generate(r: AiRequest): AiResult {
        try {
            val res = client.generate(r)
            remember(res.usage)
            return res
        } catch (e: AiException) {
            remember(e.usage)
            throw e
        }
    }

    suspend fun refreshUsage(): AiUsage? = try { client.usage().also { remember(it) } } catch (e: AiException) { remember(e.usage); null }

    suspend fun clearLocal() = dao.clear()
}
