package com.example.autoposter

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "posts", indices = [Index("status"), Index("scheduledAt"), Index("accountId")])
data class PostEntity(
    @PrimaryKey val id: String, val campaignId: String, val accountId: String, val accountLabel: String,
    val platform: String, val filePath: String, val name: String, val fingerprint: String?, val text: String,
    val scheduledAt: Long, val status: String, val progress: Float, val attempts: Int, val remoteId: String?,
    val error: String?, val createdAt: Long, val finishedAt: Long, val sizeBytes: Long,
    val views: Long, val likes: Long, val comments: Long
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val userId: String, val displayName: String?, val email: String, val avatarPath: String?,
    val language: String, val plan: String, val updatedAtMs: Long, val dirty: Boolean = false
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val installId: String, val name: String, val platform: String, val appVersion: String,
    val osVersion: String, val lastSeenAt: Long, val createdAt: Long, val isThisDevice: Boolean, val synced: Boolean
)

@Entity(tableName = "account_meta")
data class AccountMetaEntity(
    @PrimaryKey val id: String, val platform: String, val label: String, val status: String, val expiresAt: Long,
    val externalId: String?, val createdAt: Long, val updatedAt: Long, val synced: Boolean, val pendingDelete: Boolean = false
)

fun PostItem.toEntity() = PostEntity(
    id, campaignId, accountId, accountLabel, platform.name, filePath, name, fingerprint, text, scheduledAt, status.name,
    progress, attempts, remoteId, error, createdAt, finishedAt, sizeBytes, views, likes, comments
)

fun PostEntity.toItem(): PostItem? {
    val p = runCatching { Platform.valueOf(platform) }.getOrNull() ?: return null
    val s = runCatching { Status.valueOf(status) }.getOrNull() ?: return null
    return PostItem(id, campaignId, accountId, accountLabel, p, filePath, name, fingerprint, text, scheduledAt, s,
        progress, attempts, remoteId, error, createdAt, finishedAt, sizeBytes, views, likes, comments)
}

@Dao
abstract class PostDao {
    @Query("SELECT * FROM posts") abstract suspend fun all(): List<PostEntity>
    @Query("SELECT COUNT(*) FROM posts") abstract suspend fun count(): Int
    @Upsert abstract suspend fun upsertAll(list: List<PostEntity>)
    @Query("DELETE FROM posts WHERE id IN (:ids)") abstract suspend fun deleteByIds(ids: List<String>)

    /** Writes only what changed, atomically. */
    @Transaction
    open suspend fun sync(changed: List<PostEntity>, removedIds: List<String>) {
        removedIds.chunked(500).forEach { deleteByIds(it) }
        changed.chunked(500).forEach { upsertAll(it) }
    }
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE userId = :id") fun observe(id: String): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE userId = :id") suspend fun get(id: String): ProfileEntity?
    @Query("SELECT * FROM profile WHERE dirty = 1") suspend fun dirty(): List<ProfileEntity>
    @Upsert suspend fun upsert(p: ProfileEntity)
    @Query("DELETE FROM profile") suspend fun clear()
}

@Dao
abstract class DeviceDao {
    @Query("SELECT * FROM devices ORDER BY isThisDevice DESC, lastSeenAt DESC") abstract fun observe(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM devices WHERE synced = 0") abstract suspend fun unsynced(): List<DeviceEntity>
    @Query("SELECT * FROM devices WHERE installId = :id") abstract suspend fun find(id: String): DeviceEntity?
    @Upsert abstract suspend fun upsert(d: DeviceEntity)
    @Upsert abstract suspend fun upsertAll(list: List<DeviceEntity>)
    @Query("DELETE FROM devices WHERE installId = :id") abstract suspend fun delete(id: String)
    @Query("DELETE FROM devices WHERE isThisDevice = 0") abstract suspend fun deleteOthers()
    @Query("DELETE FROM devices") abstract suspend fun clear()

    /** Replaces the list of other devices with the server's copy; this device's row is kept. */
    @Transaction
    open suspend fun replaceOthers(list: List<DeviceEntity>) { deleteOthers(); upsertAll(list) }
}

@Dao
interface AccountMetaDao {
    @Query("SELECT * FROM account_meta") suspend fun all(): List<AccountMetaEntity>
    @Query("SELECT * FROM account_meta WHERE synced = 0 AND pendingDelete = 0") suspend fun unsynced(): List<AccountMetaEntity>
    @Query("SELECT * FROM account_meta WHERE pendingDelete = 1") suspend fun pendingDeletes(): List<AccountMetaEntity>
    @Upsert suspend fun upsertAll(list: List<AccountMetaEntity>)
    @Query("DELETE FROM account_meta WHERE id IN (:ids)") suspend fun delete(ids: List<String>)
    @Query("DELETE FROM account_meta") suspend fun clear()
}

/** Room schema 1 -> 2 (v0.7). Additive only: nothing in posts / profile / devices / account_meta is touched. Every statement is repeatable. */
object AppMigrations {
    val V2_STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `brand_profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `tone` TEXT NOT NULL, `audience` TEXT NOT NULL, `hashtags` TEXT NOT NULL, `language` TEXT NOT NULL, `cta` TEXT NOT NULL, `signature` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, `pendingDelete` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `media_items` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `takenAt` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `fingerprint` TEXT, `folderId` TEXT, `tags` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_media_items_fingerprint` ON `media_items` (`fingerprint`)",
        "CREATE INDEX IF NOT EXISTS `index_media_items_folderId` ON `media_items` (`folderId`)",
        "CREATE INDEX IF NOT EXISTS `index_media_items_addedAt` ON `media_items` (`addedAt`)",
        "CREATE TABLE IF NOT EXISTS `media_folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `composer_drafts` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `baseCaption` TEXT NOT NULL, `platformCaptions` TEXT NOT NULL, `hashtags` TEXT NOT NULL, `mediaIds` TEXT NOT NULL, `accountIds` TEXT NOT NULL, `brandId` TEXT, `tone` TEXT NOT NULL, `language` TEXT NOT NULL, `plannedAt` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_composer_drafts_updatedAt` ON `composer_drafts` (`updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_composer_drafts_plannedAt` ON `composer_drafts` (`plannedAt`)",
        "CREATE TABLE IF NOT EXISTS `ai_usage_cache` (`id` INTEGER NOT NULL, `plan` TEXT NOT NULL, `tokenLimit` INTEGER NOT NULL, `used` INTEGER NOT NULL, `reserved` INTEGER NOT NULL, `remaining` INTEGER NOT NULL, `periodStart` TEXT NOT NULL, `resetsAt` TEXT NOT NULL, `requestsPerMinute` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) { V2_STATEMENTS.forEach { db.execSQL(it) } }
    }
}

@Database(
    entities = [
        PostEntity::class, ProfileEntity::class, DeviceEntity::class, AccountMetaEntity::class,
        BrandProfile::class, MediaItemEntity::class, MediaFolderEntity::class, ComposerDraftEntity::class, AiUsageEntity::class
    ],
    version = 2, exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun posts(): PostDao
    abstract fun profiles(): ProfileDao
    abstract fun devices(): DeviceDao
    abstract fun accountMeta(): AccountMetaDao
    abstract fun brands(): BrandDao
    abstract fun media(): MediaDao
    abstract fun mediaFolders(): MediaFolderDao
    abstract fun drafts(): DraftDao
    abstract fun aiUsage(): AiUsageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            // No destructive fallback: the publishing queue lives here. Future versions must ship a Migration.
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "autoposter.db")
                .addMigrations(AppMigrations.MIGRATION_1_2).build().also { instance = it }
        }
    }
}
