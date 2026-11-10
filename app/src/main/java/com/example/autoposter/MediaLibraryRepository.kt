package com.example.autoposter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportProgress(val index: Int, val total: Int, val name: String, val fileFraction: Float) {
    val overall: Float get() = if (total == 0) 1f else ((index - 1) + fileFraction) / total
}
data class ImportResult(val added: Int, val duplicates: List<String>, val failed: List<String>)
data class StorageSummary(val libraryBytes: Long, val thumbBytes: Long, val queueBytes: Long, val freeBytes: Long)

/** Imports picked files into app storage with progress, reads metadata, detects duplicates, manages folders/tags/space. */
class MediaLibraryRepository(private val ctx: Context, private val db: AppDatabase) {
    private val dao get() = db.media()
    val items = dao.observe()
    val folders = db.mediaFolders().observe()

    private val dir get() = File(ctx.filesDir, "library").also { it.mkdirs() }
    private val thumbDir get() = File(ctx.cacheDir, "library_thumbs").also { it.mkdirs() }

    suspend fun import(
        uris: List<Uri>, folderId: String?, skipDuplicates: Boolean = true, onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult = withContext(Dispatchers.IO) {
        val added = ArrayList<MediaItemEntity>(); val dupes = ArrayList<String>(); val failed = ArrayList<String>()
        val seen = HashSet<String>()
        uris.forEachIndexed { i, uri ->
            var file: File? = null
            try {
                val (name, size) = describe(uri)
                onProgress(ImportProgress(i + 1, uris.size, name, 0f))
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                if (!mime.startsWith("video/") && !mime.startsWith("image/")) { failed += name; return@forEachIndexed }
                val fp = Media.fingerprint(ctx, uri, size)
                if (skipDuplicates && fp != null && (!seen.add(fp) || dao.byFingerprint(fp) != null)) { dupes += name; return@forEachIndexed }
                if (size > 0 && freeBytes() < size + MIN_HEADROOM) throw java.io.IOException("Not enough free storage")
                val id = UUID.randomUUID().toString()
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (mime.startsWith("video/")) "mp4" else "jpg"
                file = File(dir, "$id.$ext")
                ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024); var done = 0L; var n: Int
                        while (ins.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n); done += n
                            if (size > 0) onProgress(ImportProgress(i + 1, uris.size, name, (done.toFloat() / size).coerceIn(0f, 1f)))
                        }
                    }
                }
                val meta = readMeta(file, mime)
                added += MediaItemEntity(
                    id, if (mime.startsWith("video/")) MediaKind.VIDEO.name else MediaKind.IMAGE.name, name, mime, file.path,
                    file.length(), meta.durationMs, meta.width, meta.height, meta.takenAt, System.currentTimeMillis() + i,
                    fp, folderId, ""
                )
                onProgress(ImportProgress(i + 1, uris.size, name, 1f))
            } catch (e: Exception) {
                file?.delete()
                AppLog.w("Library", "import failed: ${e.javaClass.simpleName}")
                failed += (file?.name ?: uri.lastPathSegment ?: "file")
            }
        }
        if (added.isNotEmpty()) dao.upsertAll(added)
        ImportResult(added.size, dupes, failed)
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = "media"; var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: name; size = it.getLong(1) }
            }
        }
        return name to size
    }

    private class Meta(val durationMs: Long, val width: Int, val height: Int, val takenAt: Long)

    private fun readMeta(f: File, mime: String): Meta {
        if (mime.startsWith("image/")) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            return Meta(0, o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0), f.lastModified())
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            fun num(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
            var w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(); var h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180L != 0L) { val t = w; w = h; h = t }
            Meta(num(MediaMetadataRetriever.METADATA_KEY_DURATION), w, h, f.lastModified())
        } catch (e: Exception) { Meta(0, 0, 0, f.lastModified()) } finally { runCatching { r.release() } }
    }

    // ---- thumbnails (cache; regenerated when missing) ----
    fun thumbFile(id: String) = File(thumbDir, "$id.jpg")

    suspend fun thumbnail(item: MediaItemEntity): Bitmap? = withContext(Dispatchers.IO) {
        val t = thumbFile(item.id)
        if (t.exists()) BitmapFactory.decodeFile(t.path)?.let { return@withContext it }
        val bmp = makeThumb(item) ?: return@withContext null
        runCatching { t.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        bmp
    }

    private fun makeThumb(item: MediaItemEntity): Bitmap? = runCatching {
        if (item.kindEnum == MediaKind.IMAGE) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.path, o)
            var s = 1; while (o.outWidth / (s * 2) >= THUMB && o.outHeight / (s * 2) >= THUMB) s *= 2
            BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = s })?.let { scale(it) }
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(item.path)
                val at = (minOf(1_000L, item.durationMs / 2).coerceAtLeast(0)) * 1000
                r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scale(it) }
            } finally { runCatching { r.release() } }
        }
    }.getOrNull()

    private fun scale(b: Bitmap): Bitmap {
        val k = THUMB.toFloat() / maxOf(b.width, b.height)
        return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    // ---- organisation ----
    suspend fun setTags(id: String, raw: String) = dao.setTags(id, Tags.join(Tags.normalize(raw)))
    suspend fun addTags(ids: List<String>, raw: String) {
        val add = Tags.normalize(raw); if (add.isEmpty()) return
        dao.byIds(ids).forEach { dao.setTags(it.id, Tags.join(it.tagList + add)) }
    }
    suspend fun moveToFolder(ids: List<String>, folderId: String?) = dao.setFolder(ids, folderId)
    suspend fun createFolder(name: String): MediaFolderEntity? {
        val n = name.trim().take(40); if (n.isEmpty()) return null
        return MediaFolderEntity(UUID.randomUUID().toString(), n, System.currentTimeMillis()).also { db.mediaFolders().upsert(it) }
    }
    suspend fun renameFolder(f: MediaFolderEntity, name: String) { if (name.isNotBlank()) db.mediaFolders().upsert(f.copy(name = name.trim().take(40))) }
    suspend fun deleteFolder(id: String) { dao.clearFolder(id); db.mediaFolders().delete(id) }

    /** Removes the rows and their files. Composer drafts that referenced them simply open without that media. */
    suspend fun delete(ids: List<String>) = withContext(Dispatchers.IO) {
        val gone = dao.byIds(ids)
        gone.forEach { runCatching { File(it.path).delete() }; runCatching { thumbFile(it.id).delete() } }
        dao.delete(ids)
    }

    suspend fun byIds(ids: List<String>) = dao.byIds(ids)

    // ---- storage ----
    fun freeBytes(): Long = StatFs(ctx.filesDir.path).availableBytes

    suspend fun storage(): StorageSummary = withContext(Dispatchers.IO) {
        StorageSummary(dao.totalBytes(), sizeOf(thumbDir), sizeOf(File(ctx.filesDir, "outbox")), freeBytes())
    }

    suspend fun clearThumbnailCache() = withContext(Dispatchers.IO) { thumbDir.listFiles()?.forEach { runCatching { it.delete() } }; Unit }

    private fun sizeOf(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val THUMB = 320
        const val MIN_HEADROOM = 200L shl 20
    }
}

/** Queue creation for composer drafts and campaigns. Copies each file once into the outbox (the same store v0.5 uses). */
object CampaignQueue {
    sealed interface Outcome {
        data class Queued(val count: Int) : Outcome
        data class NotEnoughSpace(val needBytes: Long) : Outcome
        data class Failed(val message: String) : Outcome
        data object NothingToQueue : Outcome
    }

    suspend fun enqueue(ctx: Context, posts: List<PlannedPost>, now: Long = System.currentTimeMillis(), onProgress: (Int, Int) -> Unit = { _, _ -> }): Outcome =
        withContext(Dispatchers.IO) {
            val ok = posts.filter { !it.blocked }
            if (ok.isEmpty()) return@withContext Outcome.NothingToQueue
            val unique = ok.map { it.media }.distinctBy { it.id }
            val need = unique.sumOf { it.sizeBytes.coerceAtLeast(0) }
            if (need > 0 && Outbox.freeBytes(ctx) < need + MediaLibraryRepository.MIN_HEADROOM) return@withContext Outcome.NotEnoughSpace(need)
            val copies = HashMap<String, File>()
            try {
                unique.forEachIndexed { i, m ->
                    onProgress(i + 1, unique.size)
                    copies[m.id] = Outbox.copyIn(ctx, Uri.fromFile(File(m.path)))
                }
            } catch (e: Exception) {
                Outbox.delete(copies.values.map { it.path })
                return@withContext Outcome.Failed(e.message ?: "copy failed")
            }
            val items = CampaignPlanner.toPostItems(ok, UUID.randomUUID().toString(), { copies.getValue(it.id).path }, now)
            PostStore.add(items)
            Scheduler.kick(ctx)
            Outcome.Queued(items.size)
        }
}
