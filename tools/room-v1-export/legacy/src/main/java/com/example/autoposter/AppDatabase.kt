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


@Database(
    entities = [PostEntity::class, ProfileEntity::class, DeviceEntity::class, AccountMetaEntity::class],
    version = 1, exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun posts(): PostDao
    abstract fun profiles(): ProfileDao
    abstract fun devices(): DeviceDao
    abstract fun accountMeta(): AccountMetaDao
}
