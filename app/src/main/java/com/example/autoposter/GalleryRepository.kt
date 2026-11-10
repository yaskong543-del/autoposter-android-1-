package com.example.autoposter

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Calendar

class GalleryRepository(private val ctx: Context) {
    /** Newest videos first, optionally filtered by folder (case-insensitive) and by date. Needs the media permission. */
    fun find(n: Int, folder: String? = null, sinceDays: Int? = null): List<Uri> {
        val sel = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (folder != null) { sel += "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} LIKE ?"; args += folder }
        if (sinceDays != null) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -sinceDays)
            }
            sel += "${MediaStore.Video.Media.DATE_ADDED} >= ?"; args += (cal.timeInMillis / 1000).toString()
        }
        val out = mutableListOf<Uri>()
        val q = ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            sel.joinToString(" AND ").ifEmpty { null }, args.toTypedArray().ifEmpty { null },
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        ) ?: return out
        q.use { while (it.moveToNext() && out.size < n) out += ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.getLong(0)) }
        return out
    }

    /** Gallery albums (folders) with their video counts, biggest first. Needs the media permission. */
    fun albums(): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME), null, null, null
        )?.use { while (it.moveToNext()) { val n = it.getString(0) ?: continue; counts[n] = (counts[n] ?: 0) + 1 } }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
