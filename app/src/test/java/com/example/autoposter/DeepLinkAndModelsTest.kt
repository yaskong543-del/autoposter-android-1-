package com.example.autoposter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkAndModelsTest {
    @Test fun parsesRecoveryLink() {
        val d = DeepLinkParser.parse("autoposter://auth/callback#access_token=AT&refresh_token=RT&expires_in=1800&token_type=bearer&type=recovery")
        assertEquals(DeepLink.Tokens("AT", "RT", 1800, "recovery"), d)
    }

    @Test fun parsesErrorLink() {
        val d = DeepLinkParser.parse("autoposter://auth/callback#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid")
        assertEquals(DeepLink.Failure("otp_expired", "Email link is invalid"), d)
    }

    @Test fun ignoresOtherLinks() {
        assertEquals(DeepLink.None, DeepLinkParser.parse("https://example.com/#access_token=a&refresh_token=b"))
        assertEquals(DeepLink.None, DeepLinkParser.parse("autoposter://auth/callback#access_token=a"))
        assertEquals(DeepLink.None, DeepLinkParser.parse(null))
    }

    private val body = """{"access_token":"at","refresh_token":"rt","expires_in":3600,"user":{"id":"u1","email":"a@b.co","email_confirmed_at":"2026-01-01T00:00:00Z","app_metadata":{"provider":"google"},"user_metadata":{"name":"Ann"}}}"""

    @Test fun parsesGoTrueSession() {
        val s = AuthSession.fromGoTrue(body, 1000L)!!
        assertEquals("at", s.accessToken); assertEquals(1000L + 3_600_000L, s.expiresAtMs)
        assertEquals("u1", s.user.id); assertEquals("Ann", s.user.displayName); assertTrue(s.user.emailConfirmed); assertEquals("google", s.user.provider)
    }

    @Test fun signUpWithoutSessionReturnsNull() {
        assertNull(AuthSession.fromGoTrue("""{"id":"u1","email":"a@b.co","confirmation_sent_at":"x"}""", 0L))
    }

    @Test fun unconfirmedUser() {
        assertFalse(AuthSession.parseUser(JSONObject("""{"id":"u1","email":"a@b.co"}""")).emailConfirmed)
    }

    @Test fun storedJsonRoundTrips() {
        val s = AuthSession.fromGoTrue(body, 5L)!!
        assertEquals(s, AuthSession.fromStoredJson(s.toJson()))
        assertNull(AuthSession.fromStoredJson("not json"))
    }

    @Test fun expiryWindow() {
        val s = AuthSession("a", "r", 10_000L, AuthUser("u", "e", null, true, null))
        assertTrue(s.expiresWithin(5_000, 6_000)); assertFalse(s.expiresWithin(1_000, 6_000))
    }

    @Test fun postEntityRoundTrip() {
        val item = PostItem(campaignId = "c", accountId = "a", accountLabel = "Main", platform = Platform.TIKTOK, filePath = "/x.mp4", name = "x",
            fingerprint = "fp", text = "hi", scheduledAt = 42L, status = Status.NEEDS_AUTH, progress = 0.5f, attempts = 2, remoteId = "r",
            error = "e", sizeBytes = 9L, views = 3L)
        assertEquals(item, item.toEntity().toItem())
        assertNull(item.toEntity().copy(status = "BOGUS").toItem())
    }

    @Test fun draftTextRotatesCaptionsAndTags() {
        val d = Draft()
        d.baseCaption = "Hi | Yo"; d.baseTags = "a b"
        assertEquals("Hi\n\n#a #b", d.textFor(Platform.YOUTUBE, 0))
        assertEquals("Yo\n\n#a #b", d.textFor(Platform.YOUTUBE, 1))
        assertEquals(listOf("#x", "#y"), parseTags("x, #y x"))
    }

    @Test fun accountHealth() {
        val now = 1_000_000_000L
        fun acc(exp: Long, refresh: String? = null, client: String? = null) =
            Account(platform = Platform.INSTAGRAM, label = "l", accessToken = "t", expiresAt = exp, refreshToken = refresh, clientId = client)
        assertEquals(AccountHealth.NEEDS_RECONNECT, AccountHealthCheck.of(acc(now + 1), now, true))
        assertEquals(AccountHealth.UNKNOWN, AccountHealthCheck.of(acc(0), now, false))
        assertEquals(AccountHealth.EXPIRED, AccountHealthCheck.of(acc(now - 1), now, false))
        assertEquals(AccountHealth.EXPIRING, AccountHealthCheck.of(acc(now + 3_600_000L), now, false))
        assertEquals(AccountHealth.OK, AccountHealthCheck.of(acc(now + 3 * AccountHealthCheck.WARN_WINDOW_MS), now, false))
        val yt = Account(platform = Platform.YOUTUBE, label = "y", accessToken = "t", refreshToken = "r", clientId = "c", expiresAt = now - 5)
        assertEquals(AccountHealth.OK, AccountHealthCheck.of(yt, now, false))
        assertTrue(AccountHealth.EXPIRED.needsAttention); assertFalse(AccountHealth.OK.needsAttention)
    }

    @Test fun errorParsingAndMessages() {
        val a = SupabaseHttp.parseError(400, """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
        assertEquals(ErrorKind.AUTH, a.kind); assertEquals("invalid_credentials", a.errorCode)
        assertEquals(R.string.err_invalid_credentials, messageFor(a).res)
        val w = SupabaseHttp.parseError(422, """{"code":422,"error_code":"weak_password","msg":"too weak"}""")
        assertEquals(ErrorKind.VALIDATION, w.kind); assertEquals(R.string.err_weak_password, messageFor(w).res)
        assertEquals(ErrorKind.SERVER, SupabaseHttp.parseError(502, "<html>").kind)
        assertEquals("42501", SupabaseHttp.parseError(403, """{"code":"42501","message":"denied"}""").errorCode)
        assertEquals(R.string.err_network, messageFor(ApiException(0, "x", ErrorKind.NETWORK)).res)
        assertNotNull(messageFor(RuntimeException()))
    }
}
