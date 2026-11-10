package com.example.autoposter

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class UploadException(msg: String, val retryable: Boolean = false, val authExpired: Boolean = false) : Exception(msg)

interface PlatformUploader {
    /** Blocking. Returns the platform's id for the new post. Throws [UploadException] or [IOException]. */
    fun upload(acc: Account, file: File, text: String, onProgress: (Float) -> Unit, cancelled: () -> Boolean): String
}

fun uploaderFor(p: Platform): PlatformUploader = when (p) {
    Platform.YOUTUBE -> YouTubeUploader()
    Platform.INSTAGRAM -> InstagramUploader()
    Platform.TIKTOK -> TikTokUploader()
}

internal object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).build()

    fun fail(what: String, code: Int, body: String?): Nothing {
        val snippet = body?.replace("\n", " ")?.take(220) ?: ""
        throw UploadException(
            "$what: HTTP $code $snippet".trim(),
            retryable = code == 408 || code == 429 || code >= 500,
            authExpired = code == 401
        )
    }
    fun json(r: Response, what: String): JSONObject {
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            // Meta Graph errors carry code 190 when the token is invalid or expired.
            val code190 = runCatching { JSONObject(body).getJSONObject("error").optInt("code") == 190 }.getOrDefault(false)
            if (code190) throw UploadException("$what: token expired or revoked", authExpired = true)
            fail(what, r.code, body)
        }
        return runCatching { JSONObject(body) }.getOrElse { JSONObject() }
    }
}

/** Streams a byte range of a file and reports progress. */
internal class FileBody(
    private val file: File, private val type: String, private val start: Long = 0L,
    private val len: Long = file.length() - start,
    private val onBytes: (Long) -> Unit = {}, private val cancelled: () -> Boolean = { false }
) : RequestBody() {
    override fun contentType() = type.toMediaType()
    override fun contentLength() = len
    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024); var left = len
            while (left > 0) {
                if (cancelled()) throw IOException("Upload stopped")
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                sink.write(buf, 0, n); left -= n; onBytes(n.toLong())
            }
        }
    }
}

/** YouTube Data API v3 resumable upload. Token scope: youtube.upload. */
class YouTubeUploader : PlatformUploader {
    override fun upload(acc: Account, file: File, text: String, onProgress: (Float) -> Unit, cancelled: () -> Boolean): String {
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.replace(Regex("[<>]"), "")?.take(100)
            ?: file.nameWithoutExtension.take(100)
        val meta = JSONObject()
            .put("snippet", JSONObject().put("title", title).put("description", text.replace(Regex("[<>]"), "")).put("categoryId", "22"))
            .put("status", JSONObject().put("privacyStatus", acc.visibility.ifBlank { "private" }).put("selfDeclaredMadeForKids", false))
        val init = Request.Builder()
            .url("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
            .header("Authorization", "Bearer ${acc.accessToken}")
            .header("X-Upload-Content-Length", file.length().toString())
            .header("X-Upload-Content-Type", "video/*")
            .post(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
        val location = Net.client.newCall(init).execute().use {
            if (!it.isSuccessful) Net.fail("YouTube init", it.code, it.body?.string())
            it.header("Location") ?: throw UploadException("YouTube returned no upload URL")
        }
        var sent = 0L; val total = file.length().coerceAtLeast(1)
        val body = FileBody(file, "video/*", onBytes = { sent += it; onProgress((sent.toFloat() / total).coerceAtMost(0.99f)) }, cancelled = cancelled)
        Net.client.newCall(Request.Builder().url(location).put(body).build()).execute().use {
            return Net.json(it, "YouTube upload").optString("id", "ok")
        }
    }
}

/**
 * TikTok Content Posting API (FILE_UPLOAD). Needs an approved app with the video.publish (direct post)
 * or video.upload (draft inbox) scope. Unaudited apps can only post privately (SELF_ONLY).
 */
class TikTokUploader : PlatformUploader {
    override fun upload(acc: Account, file: File, text: String, onProgress: (Float) -> Unit, cancelled: () -> Boolean): String {
        val size = file.length()
        val mb = 1024L * 1024L
        val chunk: Long; val count: Int
        if (size <= 64 * mb) { chunk = size; count = 1 } else { chunk = 32 * mb; count = (size / chunk).toInt() }
        val init = JSONObject()
            .put("post_info", JSONObject().put("title", text.take(2200)).put("privacy_level", acc.visibility.ifBlank { "SELF_ONLY" }))
            .put("source_info", JSONObject().put("source", "FILE_UPLOAD").put("video_size", size).put("chunk_size", chunk).put("total_chunk_count", count))
        val initResp = Net.client.newCall(
            Request.Builder().url("https://open.tiktokapis.com/v2/post/publish/video/init/")
                .header("Authorization", "Bearer ${acc.accessToken}")
                .post(init.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
        ).execute().use { tiktok(it, "TikTok init") }
        val data = initResp.optJSONObject("data") ?: throw UploadException("TikTok init returned no data")
        val publishId = data.getString("publish_id"); val uploadUrl = data.getString("upload_url")

        var sent = 0L
        for (i in 0 until count) {
            val start = i * chunk
            val len = if (i == count - 1) size - start else chunk
            val body = FileBody(file, "video/mp4", start, len, { sent += it; onProgress((sent.toFloat() / size).coerceAtMost(0.97f)) }, cancelled)
            Net.client.newCall(
                Request.Builder().url(uploadUrl)
                    .header("Content-Range", "bytes $start-${start + len - 1}/$size").put(body).build()
            ).execute().use { if (!it.isSuccessful) Net.fail("TikTok upload", it.code, it.body?.string()) }
        }
        // Processing is asynchronous: poll for a short while, then trust the publish id.
        repeat(24) {
            if (cancelled()) throw IOException("Upload stopped")
            Thread.sleep(5000)
            val st = Net.client.newCall(
                Request.Builder().url("https://open.tiktokapis.com/v2/post/publish/status/fetch/")
                    .header("Authorization", "Bearer ${acc.accessToken}")
                    .post(JSONObject().put("publish_id", publishId).toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
            ).execute().use { tiktok(it, "TikTok status") }
            when (st.optJSONObject("data")?.optString("status")) {
                "PUBLISH_COMPLETE", "SEND_TO_USER_INBOX" -> return publishId
                "FAILED" -> throw UploadException("TikTok rejected the video: ${st.optJSONObject("data")?.optString("fail_reason")}")
            }
        }
        return publishId
    }

    private fun tiktok(r: Response, what: String): JSONObject {
        val body = r.body?.string().orEmpty()
        val j = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        val code = j.optJSONObject("error")?.optString("code") ?: ""
        if (code == "access_token_invalid" || r.code == 401) throw UploadException("$what: token invalid or expired", authExpired = true)
        if (!r.isSuccessful || (code.isNotEmpty() && code != "ok")) {
            val msg = j.optJSONObject("error")?.optString("message").orEmpty()
            throw UploadException("$what: ${code.ifEmpty { "HTTP ${r.code}" }} $msg".trim(), retryable = r.code >= 500 || r.code == 429)
        }
        return j
    }
}

/** Instagram Reels via the Graph API resumable upload flow. Needs a professional account and instagram_content_publish. */
class InstagramUploader : PlatformUploader {
    private val api = "https://graph.facebook.com/v21.0"

    override fun upload(acc: Account, file: File, text: String, onProgress: (Float) -> Unit, cancelled: () -> Boolean): String {
        val ig = acc.igUserId ?: throw UploadException("Instagram user ID is missing for '${acc.label}'")
        val create = Net.client.newCall(
            Request.Builder().url("$api/$ig/media").post(
                FormBody.Builder().add("media_type", "REELS").add("upload_type", "resumable")
                    .add("caption", text.take(2200)).add("access_token", acc.accessToken).build()
            ).build()
        ).execute().use { Net.json(it, "Instagram create") }
        val container = create.getString("id"); val uploadUri = create.getString("uri")

        var sent = 0L; val size = file.length().coerceAtLeast(1)
        val body = FileBody(file, "application/octet-stream", onBytes = { sent += it; onProgress((sent.toFloat() / size).coerceAtMost(0.9f)) }, cancelled = cancelled)
        Net.client.newCall(
            Request.Builder().url(uploadUri).header("Authorization", "OAuth ${acc.accessToken}")
                .header("offset", "0").header("file_size", file.length().toString()).post(body).build()
        ).execute().use { Net.json(it, "Instagram upload") }

        var finished = false
        repeat(36) {
            if (finished) return@repeat
            if (cancelled()) throw IOException("Upload stopped")
            Thread.sleep(5000)
            val st = Net.client.newCall(
                Request.Builder().url("$api/$container?fields=status_code&access_token=${acc.accessToken}").build()
            ).execute().use { Net.json(it, "Instagram status") }
            when (st.optString("status_code")) {
                "FINISHED" -> finished = true
                "ERROR", "EXPIRED" -> throw UploadException("Instagram could not process the video (${st.optString("status_code")})")
            }
        }
        if (!finished) throw UploadException("Instagram is still processing the video", retryable = true)
        onProgress(0.97f)
        val pub = Net.client.newCall(
            Request.Builder().url("$api/$ig/media_publish").post(
                FormBody.Builder().add("creation_id", container).add("access_token", acc.accessToken).build()
            ).build()
        ).execute().use { Net.json(it, "Instagram publish") }
        return pub.optString("id", container)
    }
}
