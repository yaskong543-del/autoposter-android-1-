package com.example.autoposter

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class CloudApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CloudApi
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        api = CloudApi(SupabaseHttp(SupabaseConfig(server.url("/").toString().trimEnd('/'), "anon-key")), { "user-jwt" })
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun enqueueJobSendsAuthenticatedRpcAndParsesScalar() = runBlocking {
        server.enqueue(MockResponse().setBody("\"3f2b8f0e-0000-4000-8000-000000000001\""))
        val id = api.enqueueJob("post-1", "acct-1", 1_800_000_000_000L, "Asia/Kolkata", JSONObject().put("caption", "hi"), "u1/post-1.mp4", "k".repeat(32))
        assertEquals("3f2b8f0e-0000-4000-8000-000000000001", id)
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/rest/v1/rpc/enqueue_publish_job", r.path)
        assertEquals("Bearer user-jwt", r.getHeader("Authorization")); assertEquals("anon-key", r.getHeader("apikey"))
        val b = JSONObject(r.body.readUtf8())
        assertEquals("post-1", b.getString("p_post_id")); assertEquals("Asia/Kolkata", b.getString("p_tz")); assertEquals("u1/post-1.mp4", b.getString("p_media_path"))
        assertTrue(b.getString("p_run_at").endsWith("Z")); assertEquals("hi", b.getJSONObject("p_payload").getString("caption"))
    }

    @Test fun planLimitErrorsSurfaceAsCodes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"PL001","message":"PLAN_LIMIT_PROFILES: your free plan allows 2 connected profiles"}"""))
        try { api.enqueueJob("p", "a", 0, "UTC", JSONObject(), "u/p.mp4", "k".repeat(32)); fail("expected ApiException") }
        catch (e: ApiException) { assertEquals("PL001", e.errorCode); assertEquals(ErrorKind.VALIDATION, e.kind); assertTrue(PlanErrors.message(e.errorCode)!!.isNotBlank()) }
    }

    @Test fun noSessionMeansNoRequest() = runBlocking {
        val anon = CloudApi(SupabaseHttp(SupabaseConfig(server.url("/").toString().trimEnd('/'), "k")), { null })
        try { anon.entitlements(); fail("expected ApiException") } catch (e: ApiException) { assertEquals(ErrorKind.AUTH, e.kind) }
        assertEquals(0, server.requestCount)
    }

    @Test fun entitlementsRoundTrip() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"plan":"free","max_profiles":2,"profiles_used":1,"max_devices":1,"devices_used":1,"ai_tokens_monthly":5000,"auto_sync":false,"cellular_uploads":false,"advanced_automation":false,"max_pending_jobs":10}"""))
        assertEquals(Entitlements.FREE, api.entitlements())
    }

    @Test fun notificationsAndSafeMarkDelivered() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"3f2b8f0e-0000-4000-8000-000000000001","kind":"publish_success","title":"Post published","body":"b","ref":"j1","created_at":"2026-11-12T10:00:00+00:00","delivered_at":null}]"""))
        val list = api.notifications()
        assertEquals(1, list.size); assertEquals(0L, list[0].deliveredAt); assertTrue(list[0].createdAt > 0)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        api.markDelivered(listOf("3f2b8f0e-0000-4000-8000-000000000001", "1) or (1=1", "x"), 1_800_000_000_000L)
        val r = server.takeRequest()
        assertEquals("PATCH", r.method); assertEquals("/rest/v1/notifications?id=in.(3f2b8f0e-0000-4000-8000-000000000001)", r.path)   // injection attempt filtered out
        api.markDelivered(listOf("nope"), 0); assertEquals(1, server.requestCount - 1)                                                    // nothing sent for invalid ids
    }

    @Test fun syncEndpointErrorCodesReachTheEngine() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"device_revoked"}"""))
        val t = HttpSyncTransport(api)
        try { t.sync("dev", "open", 0, emptyList(), 200); fail("expected ApiException") } catch (e: ApiException) { assertEquals("device_revoked", e.errorCode) }
        assertEquals("/functions/v1/sync", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"ok":true,"results":[{"opId":"o1","status":"applied","rev":2,"conflicts":[{"id":"d"}]}],"changes":[{"entity":"draft","id":"d","rev":2,"deleted":false,"fields":{"text":"x"},"serverSeq":9}],"cursor":9,"hasMore":false}"""))
        val reply = t.sync("dev", "open", 0, listOf(OutboxOp("o1", "draft", "d", 1, 1, JSONObject(), JSONObject().put("text", "x"), false, 5)), 200)
        assertEquals(1, reply.results[0].conflicts); assertEquals(9L, reply.changes[0].serverSeq); assertEquals(9L, reply.cursor)
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("dev", sent.getString("deviceId")); assertEquals("o1", sent.getJSONArray("ops").getJSONObject(0).getString("opId"))
    }

    @Test fun uploadStreamsFileWithProgressToPrivateBucket() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Key":"publish-media/u1/p.mp4"}"""))
        val f = tmp.newFile("clip.mp4"); f.writeBytes(ByteArray(300_000) { 1 })
        val progress = mutableListOf<Float>()
        api.uploadMedia("u1/p 1.mp4", f, "video/mp4", { progress += it }, { false })
        val r = server.takeRequest()
        assertEquals("PUT", r.method); assertEquals("/storage/v1/object/publish-media/u1/p%201.mp4", r.path)
        assertEquals("true", r.getHeader("x-upsert")); assertEquals("Bearer user-jwt", r.getHeader("Authorization")); assertEquals(300_000L, r.bodySize)
        assertTrue(progress.isNotEmpty() && progress.last() == 1f)
    }

    @Test fun uploadErrorsAreClassified() = runBlocking {
        val f = tmp.newFile("a.mp4"); f.writeBytes(ByteArray(10))
        server.enqueue(MockResponse().setResponseCode(503))
        try { api.uploadMedia("u/a.mp4", f, "video/mp4", {}, { false }); fail() } catch (e: UploadHttpException) { assertTrue(e.retryable) }
        server.enqueue(MockResponse().setResponseCode(413))
        try { api.uploadMedia("u/a.mp4", f, "video/mp4", {}, { false }); fail() } catch (e: UploadHttpException) { assertFalse(e.retryable); assertEquals(413, e.status) }
        // cancellation stops the stream before completion
        server.enqueue(MockResponse().setBody("{}"))
        try { api.uploadMedia("u/a.mp4", f, "video/mp4", {}, { true }); fail("cancel should abort") } catch (e: IOException) { /* cancelled or connection aborted */ }
    }

    @Test fun oauthStartAndDisconnect() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"url":"https://accounts.google.com/o/oauth2/v2/auth?x=1"}"""))
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?x=1", api.oauthStart("YOUTUBE"))
        assertEquals("/functions/v1/oauth/start", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(501).setBody("""{"error":"not_configured"}"""))
        try { api.oauthStart("TIKTOK"); fail() } catch (e: ApiException) { assertEquals("not_configured", e.errorCode) }
    }
}
