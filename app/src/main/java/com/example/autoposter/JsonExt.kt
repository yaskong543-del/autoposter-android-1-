package com.example.autoposter

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** String value or null for missing / JSON-null / blank fields. */
fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

object Iso {
    fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    fun orNull(ms: Long): Any = if (ms <= 0) JSONObject.NULL else utc(ms)
    fun parse(s: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s!!.substringBefore('.').substringBefore('+').removeSuffix("Z"))!!.time
    }.getOrDefault(0L)
}
