package com.example.autoposter

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

// ---- Brand voice -----------------------------------------------------------------------------------------------------

/** A saved brand voice. Room-first; [dirty] rows are pushed to public.brand_profiles by [BrandRepository]. */
@Entity(tableName = "brand_profiles")
data class BrandProfile(
    @PrimaryKey val id: String, val name: String, val description: String, val tone: String, val audience: String,
    val hashtags: String, val language: String, val cta: String, val signature: String,
    val createdAt: Long, val updatedAt: Long, val dirty: Boolean, val pendingDelete: Boolean
) {
    val toneEnum: AiTone get() = AiTone.fromWire(tone)

    /** What the gateway needs. The signature is NOT sent: it is appended locally after generation, so it costs no tokens. */
    fun toAiJson(): JSONObject = JSONObject()
        .put("name", name).put("description", description).put("tone", tone).put("audience", audience)
        .put("hashtags", hashtags).put("language", language).put("cta", cta)
}

@Dao
interface BrandDao {
    @Query("SELECT * FROM brand_profiles WHERE pendingDelete = 0 ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<BrandProfile>>
    @Query("SELECT * FROM brand_profiles WHERE id = :id") suspend fun get(id: String): BrandProfile?
    @Query("SELECT * FROM brand_profiles") suspend fun all(): List<BrandProfile>
    @Query("SELECT * FROM brand_profiles WHERE dirty = 1") suspend fun dirty(): List<BrandProfile>
    @Upsert suspend fun upsert(b: BrandProfile)
    @Upsert suspend fun upsertAll(list: List<BrandProfile>)
    @Query("DELETE FROM brand_profiles WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Query("DELETE FROM brand_profiles") suspend fun clear()
}

// ---- Media library ---------------------------------------------------------------------------------------------------

enum class MediaKind { VIDEO, IMAGE }

/** One imported file. The bytes live in app storage ([path]) so the library keeps working offline and after the picker permission is gone. */
@Entity(tableName = "media_items", indices = [Index("fingerprint"), Index("folderId"), Index("addedAt")])
data class MediaItemEntity(
    @PrimaryKey val id: String, val kind: String, val name: String, val mime: String, val path: String,
    val sizeBytes: Long, val durationMs: Long, val width: Int, val height: Int, val takenAt: Long, val addedAt: Long,
    val fingerprint: String?, val folderId: String?, val tags: String
) {
    val kindEnum: MediaKind get() = if (kind == MediaKind.IMAGE.name) MediaKind.IMAGE else MediaKind.VIDEO
    val tagList: List<String> get() = Tags.split(tags)
}

@Entity(tableName = "media_folders")
data class MediaFolderEntity(@PrimaryKey val id: String, val name: String, val createdAt: Long)

object Tags {
    /** Lower-case, no '#', no commas or spaces, max 30 chars, de-duplicated, order kept. */
    fun normalize(raw: String): List<String> = raw.split(Regex("[,\\s]+"))
        .map { it.trim().trimStart('#').lowercase().replace(Regex("[^\\p{L}\\p{N}_-]"), "").take(30) }
        .filter { it.isNotEmpty() }.distinct()
    fun split(stored: String): List<String> = stored.split(',').filter { it.isNotBlank() }
    fun join(tags: List<String>): String = tags.distinct().joinToString(",")
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items ORDER BY addedAt DESC") fun observe(): Flow<List<MediaItemEntity>>
    @Query("SELECT * FROM media_items") suspend fun all(): List<MediaItemEntity>
    @Query("SELECT * FROM media_items WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<MediaItemEntity>
    @Query("SELECT * FROM media_items WHERE fingerprint = :fp LIMIT 1") suspend fun byFingerprint(fp: String): MediaItemEntity?
    @Upsert suspend fun upsertAll(list: List<MediaItemEntity>)
    @Query("UPDATE media_items SET tags = :tags WHERE id = :id") suspend fun setTags(id: String, tags: String)
    @Query("UPDATE media_items SET folderId = :folderId WHERE id IN (:ids)") suspend fun setFolder(ids: List<String>, folderId: String?)
    @Query("DELETE FROM media_items WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM media_items") suspend fun totalBytes(): Long
    @Query("UPDATE media_items SET folderId = NULL WHERE folderId = :folderId") suspend fun clearFolder(folderId: String)
}

@Dao
interface MediaFolderDao {
    @Query("SELECT * FROM media_folders ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<MediaFolderEntity>>
    @Upsert suspend fun upsert(f: MediaFolderEntity)
    @Query("DELETE FROM media_folders WHERE id = :id") suspend fun delete(id: String)
}

// ---- Composer drafts -------------------------------------------------------------------------------------------------

/** A post being prepared in the composer. Lives only on this device until it is queued (then it is deleted). [plannedAt] 0 = not scheduled. */
@Entity(tableName = "composer_drafts", indices = [Index("updatedAt"), Index("plannedAt")])
data class ComposerDraftEntity(
    @PrimaryKey val id: String, val title: String, val baseCaption: String, val platformCaptions: String, val hashtags: String,
    val mediaIds: String, val accountIds: String, val brandId: String?, val tone: String, val language: String,
    val plannedAt: Long, val zoneId: String, val createdAt: Long, val updatedAt: Long
) {
    val mediaIdList: List<String> get() = JsonLists.strings(mediaIds)
    val accountIdList: List<String> get() = JsonLists.strings(accountIds)
    val captionMap: Map<Platform, String> get() = JsonLists.platformMap(platformCaptions)
}

object JsonLists {
    fun encode(list: Collection<String>): String = JSONArray(list.toList()).toString()
    fun strings(s: String): List<String> = runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    fun encodeMap(m: Map<Platform, String>): String = JSONObject().also { o -> m.forEach { (p, t) -> if (t.isNotBlank()) o.put(p.name, t) } }.toString()
    fun platformMap(s: String): Map<Platform, String> = runCatching {
        val o = JSONObject(s)
        o.keys().asSequence().mapNotNull { k -> runCatching { Platform.valueOf(k) }.getOrNull()?.let { it to o.getString(k) } }.toMap()
    }.getOrDefault(emptyMap())
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM composer_drafts ORDER BY updatedAt DESC") fun observe(): Flow<List<ComposerDraftEntity>>
    @Query("SELECT * FROM composer_drafts WHERE id = :id") suspend fun get(id: String): ComposerDraftEntity?
    @Upsert suspend fun upsert(d: ComposerDraftEntity)
    @Query("DELETE FROM composer_drafts WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM composer_drafts") suspend fun count(): Int
}
