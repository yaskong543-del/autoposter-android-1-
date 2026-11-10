package com.example.autoposter

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Integration test of the Supabase Auth client against a local fake server (real HTTP, real JSON). */
class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var storage: InMemorySessionStorage
    private var now = 1_000_000L

    @Before fun setUp() { server = MockWebServer().also { it.start() }; storage = InMemorySessionStorage() }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun repo(url: String = server.url("/").toString().trimEnd('/')) =
        AuthRepository(SupabaseHttp(SupabaseConfig(url, "anon-key")), storage) { now }

    private fun session(token: String = "at1", refresh: String = "rt1", expiresIn: Int = 3600) =
        """{"access_token":"$token","refresh_token":"$refresh","expires_in":$expiresIn,"user":{"id":"u1","email":"a@b.co","email_confirmed_at":"2026-01-01T00:00:00Z","app_metadata":{"provider":"email"},"user_metadata":{"full_name":"Ann"}}}"""

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private fun saved(expiresAt: Long) = storage.save(AuthSession("old", "rt-old", expiresAt, AuthUser("u1", "a@b.co", "Ann", true, "email")).toJson())

    @Test fun signInStoresSessionAndSendsApiKey() = runTest {
        server.enqueue(ok(session()))
        val r = repo(); r.signIn("a@b.co", "pw12345678")
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path); assertEquals("anon-key", req.getHeader("apikey"))
        assertTrue(r.state.value is AuthState.SignedIn); assertNotNull(storage.load())
    }

    @Test fun wrongPasswordIsAnAuthError() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""))
        val e = runCatching { repo().signIn("a@b.co", "bad") }.exceptionOrNull() as ApiException
        assertEquals(ErrorKind.AUTH, e.kind); assertEquals("invalid_credentials", e.errorCode); assertNull(storage.load())
    }

    @Test fun signUpNeedingVerificationHasNoSession() = runTest {
        server.enqueue(ok("""{"id":"u1","email":"a@b.co","confirmation_sent_at":"2026-01-01T00:00:00Z"}"""))
        val r = repo()
        assertEquals(SignUpResult.NeedsVerification("a@b.co"), r.signUp("a@b.co", "pw12345678", "Ann"))
        assertNull(storage.load()); assertTrue(server.takeRequest().path!!.startsWith("/auth/v1/signup"))
    }

    @Test fun signUpWithAutoConfirmSignsIn() = runTest {
        server.enqueue(ok(session()))
        val r = repo()
        assertEquals(SignUpResult.SignedIn, r.signUp("a@b.co", "pw12345678", "Ann")); assertTrue(r.state.value is AuthState.SignedIn)
    }

    @Test fun restoreWithoutSessionIsSignedOut() = runTest {
        val r = repo(); r.restore(); assertEquals(AuthState.SignedOut, r.state.value)
    }

    @Test fun restoreValidSessionMakesNoNetworkCall() = runTest {
        saved(now + 3_600_000L)
        val r = repo(); r.restore()
        assertTrue(r.state.value is AuthState.SignedIn); assertEquals(0, server.requestCount)
    }

    @Test fun restoreExpiredSessionRefreshesIt() = runTest {
        saved(now - 1)
        server.enqueue(ok(session("new-at", "new-rt")))
        val r = repo(); r.restore()
        assertEquals("new-at", (r.state.value as AuthState.SignedIn).session.accessToken)
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", req.path); assertTrue(req.body.readUtf8().contains("rt-old"))
    }

    @Test fun rejectedRefreshTokenSignsOut() = runTest {
        saved(now - 1)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid Refresh Token"}"""))
        val r = repo(); r.restore()
        assertEquals(AuthState.SignedOut, r.state.value); assertNull(storage.load())
    }

    @Test fun offlineRestoreKeepsPersonSignedIn() = runTest {
        saved(now - 1)
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val r = repo(url); r.restore()
        assertTrue(r.state.value is AuthState.SignedIn); assertNotNull(storage.load())
    }

    @Test fun accessTokenRefreshesNearExpiry() = runTest {
        saved(now + 30_000)
        server.enqueue(ok(session("fresh", "rt2")))
        val r = repo(); r.restore()
        assertEquals("fresh", r.accessToken())
    }

    @Test fun signOutClearsLocalStateAndCallsGlobalLogout() = runTest {
        server.enqueue(ok(session())); server.enqueue(MockResponse().setResponseCode(204))
        val r = repo(); r.signIn("a@b.co", "pw12345678"); server.takeRequest()
        r.signOut(everywhere = true)
        assertEquals(AuthState.SignedOut, r.state.value); assertNull(storage.load())
        assertEquals("/auth/v1/logout?scope=global", server.takeRequest().path)
    }

    @Test fun deleteAccountCleansStorageThenCallsRpc() = runTest {
        server.enqueue(ok(session()))
        server.enqueue(ok("[]")); server.enqueue(ok("[]")); server.enqueue(MockResponse().setResponseCode(204))
        val r = repo(); r.signIn("a@b.co", "pw12345678"); server.takeRequest()
        r.deleteAccount()
        assertEquals("/storage/v1/object/list/avatars", server.takeRequest().path)
        assertEquals("/storage/v1/object/list/exports", server.takeRequest().path)
        assertEquals("/rest/v1/rpc/delete_my_account", server.takeRequest().path)
        assertEquals(AuthState.SignedOut, r.state.value)
    }

    @Test fun recoveryLinkOpensRecoveryState() = runTest {
        server.enqueue(ok("""{"id":"u1","email":"a@b.co","email_confirmed_at":"2026-01-01T00:00:00Z"}"""))
        val r = repo()
        assertNull(r.handleDeepLink("autoposter://auth/callback#access_token=AT&refresh_token=RT&expires_in=3600&type=recovery"))
        assertTrue(r.state.value is AuthState.Recovery)
    }

    @Test fun expiredLinkReportsAnError() = runTest {
        val msg = repo().handleDeepLink("autoposter://auth/callback#error=access_denied&error_code=otp_expired&error_description=expired")
        assertEquals("expired", msg)
    }

    @Test fun unconfiguredBackendFailsClearly() = runTest {
        val e = runCatching { AuthRepository(SupabaseHttp(SupabaseConfig("", "")), storage).signIn("a@b.co", "x") }.exceptionOrNull() as ApiException
        assertEquals(ErrorKind.NOT_CONFIGURED, e.kind)
    }
}
