package com.example.autoposter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The Android side of the AI protocol, run against a fake gateway over real HTTP. */
class AiGatewayTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer().also { it.start() } }
    @After fun down() { runCatching { server.shutdown() } }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun client(token: String? = "user-jwt") = AiGatewayClient(base(), "anon-key", { token })

    private val usageJson = """{"plan":"free","limit":5000,"used":270,"reserved":0,"remaining":4730,"periodStart":"2026-11-01","resetsAt":"2026-12-01T00:00:00.000Z","requestsPerMinute":6}"""
    private val okBody = """{"result":{"items":["Sun, sand, chai"],"hashtags":["#goa"]},"tokens":{"charged":270},"usage":$usageJson}"""

    private class FakeDao : AiUsageDao {
        val row = MutableStateFlow<AiUsageEntity?>(null)
        override fun observe() = row
        override suspend fun get() = row.value
        override suspend fun upsert(e: AiUsageEntity) { row.value = e }
        override suspend fun clear() { row.value = null }
    }

    @Test fun requestCarriesUserJwtAndAnonKeyAndNothingElseSecret() = runTest {
        server.enqueue(MockResponse().setBody(okBody))
        val res = client().generate(AiRequest(AiTask.CAPTION, AiTone.FUNNY, "en", "trip to Goa", Platform.INSTAGRAM, 2))
        val req = server.takeRequest()
        assertEquals("/functions/v1/ai-gateway", req.path)
        assertEquals("Bearer user-jwt", req.getHeader("Authorization"))
        assertEquals("anon-key", req.getHeader("apikey"))
        assertNull(req.getHeader("x-api-key"))
        val j = JSONObject(req.body.readUtf8())
        assertEquals("caption", j.getString("task")); assertEquals("funny", j.getString("tone")); assertEquals("instagram", j.getString("platform"))
        assertEquals(2, j.getInt("count"))
        assertEquals(listOf("Sun, sand, chai"), res.items); assertEquals(270, res.tokensCharged); assertEquals(4730L, res.usage!!.remaining)
    }

    @Test fun brandVoiceIsSentButSignatureStaysOnDevice() = runTest {
        server.enqueue(MockResponse().setBody(okBody))
        val brand = BrandProfile("b1", "Chai Co", "Small-batch tea", "marketing", "students", "#tea #chai", "en", "Order today", "— SECRET SIGNATURE", 1, 1, false, false)
        client().generate(AiRequest(AiTask.CAPTION, input = "new blend", brand = brand))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("Chai Co")); assertTrue(body.contains("Order today"))
        assertFalse("signature must not be sent (it costs tokens and is appended locally)", body.contains("SECRET SIGNATURE"))
    }

    @Test fun translateSendsTargetLanguage() = runTest {
        server.enqueue(MockResponse().setBody(okBody))
        client().generate(AiRequest(AiTask.TRANSLATE, input = "hello", targetLanguage = "hi"))
        assertEquals("hi", JSONObject(server.takeRequest().body.readUtf8()).getString("targetLanguage"))
    }

    @Test fun quotaExceededMapsToQuotaWithUsage() = runTest {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":"quota_exceeded","message":"used up","usage":$usageJson}"""))
        val e = runCatching { client().generate(AiRequest(AiTask.CAPTION, input = "x")) }.exceptionOrNull() as AiException
        assertEquals(AiErrorKind.QUOTA, e.kind); assertEquals(5000L, e.usage!!.limit)
    }

    @Test fun rateLimitReadsRetryAfter() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "42").setBody("""{"error":"rate_limited","message":"slow down"}"""))
        val e = runCatching { client().generate(AiRequest(AiTask.CAPTION, input = "x")) }.exceptionOrNull() as AiException
        assertEquals(AiErrorKind.RATE_LIMITED, e.kind); assertEquals(42, e.retryAfterSec)
    }

    @Test fun otherErrorsMap() {
        assertEquals(AiErrorKind.SIGNED_OUT, AiWire.parseError(401, "{}", null).kind)
        assertEquals(AiErrorKind.TOO_LARGE, AiWire.parseError(413, "{}", null).kind)
        assertEquals(AiErrorKind.REFUSED, AiWire.parseError(422, "{}", null).kind)
        assertEquals(AiErrorKind.INVALID, AiWire.parseError(400, "{}", null).kind)
        assertEquals(AiErrorKind.NOT_CONFIGURED, AiWire.parseError(503, """{"error":"ai_not_configured"}""", null).kind)
        assertEquals(AiErrorKind.SERVER, AiWire.parseError(502, "not json", null).kind)
    }

    @Test fun signedOutNeverCallsTheServer() = runTest {
        val e = runCatching { client(token = null).generate(AiRequest(AiTask.CAPTION, input = "x")) }.exceptionOrNull() as AiException
        assertEquals(AiErrorKind.SIGNED_OUT, e.kind); assertEquals(0, server.requestCount)
    }

    @Test fun notConfiguredNeverCallsTheServer() = runTest {
        val e = runCatching { AiGatewayClient("", "", { "jwt" }).generate(AiRequest(AiTask.CAPTION, input = "x")) }.exceptionOrNull() as AiException
        assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind)
    }

    @Test fun networkFailureIsOffline() = runTest {
        val dead = AiGatewayClient("http://127.0.0.1:1", "anon", { "jwt" })
        val e = runCatching { dead.generate(AiRequest(AiTask.CAPTION, input = "x")) }.exceptionOrNull() as AiException
        assertEquals(AiErrorKind.OFFLINE, e.kind)
    }

    @Test fun repositoryCachesUsageAfterSuccessAndAfterQuotaError() = runTest {
        val dao = FakeDao(); val repo = AiRepository(client(), dao) { 123L }
        server.enqueue(MockResponse().setBody(okBody))
        repo.generate(AiRequest(AiTask.CAPTION, input = "x"))
        assertEquals(4730L, dao.row.value!!.remaining); assertEquals(123L, dao.row.value!!.fetchedAt)
        val used = """{"plan":"free","limit":5000,"used":5000,"reserved":0,"remaining":0,"periodStart":"2026-11-01","resetsAt":"2026-12-01T00:00:00.000Z","requestsPerMinute":6}"""
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":"quota_exceeded","message":"x","usage":$used}"""))
        runCatching { repo.generate(AiRequest(AiTask.CAPTION, input = "x")) }
        assertEquals(0L, dao.row.value!!.remaining)
        val shown = dao.observe().map { it?.toUsage() }
        assertNotNull(shown)
    }

    @Test fun usageFractionAndPlan() {
        val u = AiWire.parseUsage(JSONObject(usageJson))!!
        assertFalse(u.isPremium); assertEquals(0.054f, u.fractionUsed, 0.001f)
        assertTrue(u.copy(plan = "pro").isPremium)
    }

    @Test fun noProviderKeyOrProviderUrlInAppSources() {
        // The app must only ever talk to our gateway. Scan the Kotlin sources that ship in the APK.
        val dir = java.io.File("src/main/java")
        val bad = dir.walkTopDown().filter { it.extension == "kt" }.filter {
            val t = it.readText(); t.contains("api.anthropic.com") || t.contains("x-api-key") || t.contains("ANTHROPIC_API_KEY") || t.contains("sk-ant")
        }.map { it.name }.toList()
        assertTrue("provider references found in: $bad", bad.isEmpty())
    }
}
