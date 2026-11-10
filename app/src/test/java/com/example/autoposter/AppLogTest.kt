package com.example.autoposter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test fun masksBearerAndKeyValueSecrets() {
        val out = AppLog.redact("Authorization: Bearer abc.def password=hunter2 refresh_token=zzz")
        assertFalse(out.contains("abc.def")); assertFalse(out.contains("hunter2")); assertFalse(out.contains("zzz"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test fun masksJwtEmailAndLongTokens() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        assertEquals("token <jwt>", AppLog.redact("token $jwt"))
        assertEquals("user <email> signed in", AppLog.redact("user john.doe@example.com signed in"))
        assertEquals("key <token>", AppLog.redact("key 0123456789abcdef0123456789abcdef0123"))
    }

    @Test fun leavesOrdinaryTextAlone() {
        assertEquals("uploading video.mp4 to main", AppLog.redact("uploading video.mp4 to main"))
    }

    @Test fun errorLogsExceptionTypeButNotSecrets() {
        var line = ""
        AppLog.sink = { _, _, m -> line = m }
        AppLog.e("T", "failed", IllegalStateException("password=abc123"))
        assertTrue(line.contains("IllegalStateException")); assertFalse(line.contains("abc123"))
    }
}
