package com.example.autoposter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class VideoRef(val uri: Uri, val name: String, val size: Long, val fingerprint: String?)

val mediaPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasMediaPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, mediaPermission) == PackageManager.PERMISSION_GRANTED

object Media {
    fun describe(ctx: Context, uri: Uri): VideoRef {
        var name = "video"; var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: name; size = it.getLong(1) }
            }
        }
        return VideoRef(uri, name, size, fingerprint(ctx, uri, size))
    }

    /** Cheap duplicate fingerprint: file size + SHA-256 of the first 1 MB. */
    internal fun fingerprint(ctx: Context, uri: Uri, size: Long): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(1 shl 20); var read = 0
            while (read < buf.size) { val n = ins.read(buf, read, buf.size - read); if (n < 0) break; read += n }
            md.update(buf, 0, read)
        } ?: return null
        "$size:" + md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }.getOrNull()
}

/** Videos are copied into app storage when queued, so scheduled posts still work after the picker permission is gone. */
object Outbox {
    private fun dir(ctx: Context) = File(ctx.filesDir, "outbox").also { it.mkdirs() }
    fun copyIn(ctx: Context, uri: Uri): File {
        val f = File(dir(ctx), UUID.randomUUID().toString() + ".mp4")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        } catch (e: Exception) { f.delete(); throw e }
        return f
    }
    fun delete(paths: List<String>) = paths.forEach { runCatching { File(it).delete() } }
    fun freeBytes(ctx: Context) = android.os.StatFs(ctx.filesDir.path).availableBytes
}
