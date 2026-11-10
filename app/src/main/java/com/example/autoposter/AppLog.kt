package com.example.autoposter

import android.util.Log

/** Logging that never writes secrets: tokens, JWTs, passwords, API keys and e-mail addresses are masked. */
object AppLog {
    private val kv = Regex("(?i)(access_token|refresh_token|id_token|apikey|api_key|authorization|password|secret|client_secret)(\"?\\s*[:=]\\s*\"?)(Bearer\\s+)?[^\\s\",&}]+")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val longToken = Regex("\\b[A-Za-z0-9_\\-]{32,}\\b")

    fun redact(s: String): String = s
        .let { kv.replace(it) { m -> "${m.groupValues[1]}=<redacted>" } }
        .let { jwt.replace(it, "<jwt>") }
        .let { email.replace(it, "<email>") }
        .let { longToken.replace(it, "<token>") }

    /** Replaceable so unit tests can run without the Android logger. */
    @Volatile var sink: (Int, String, String) -> Unit = { level, tag, msg -> try { Log.println(level, tag, msg) } catch (_: RuntimeException) { } }

    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) sink(Log.DEBUG, tag, redact(msg)) }
    fun i(tag: String, msg: String) = sink(Log.INFO, tag, redact(msg))
    fun w(tag: String, msg: String) = sink(Log.WARN, tag, redact(msg))
    fun e(tag: String, msg: String, t: Throwable? = null) =
        sink(Log.ERROR, tag, redact(msg) + (t?.let { " [${it.javaClass.simpleName}: ${redact(it.message.orEmpty())}]" } ?: ""))
}
