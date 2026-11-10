package com.example.autoposter

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Accounts of every platform. Tokens are encrypted at rest and never leave the device. */
class AccountStore(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "accounts_v2",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun all(): List<Account> = prefs.all.filterKeys { !it.startsWith("_") }.values
        .mapNotNull { (it as? String)?.let(Account::fromJson) }
        .sortedWith(compareBy({ it.platform.ordinal }, { it.label.lowercase() }))

    fun get(id: String): Account? = prefs.getString(id, null)?.let(Account::fromJson)
    fun save(a: Account) { prefs.edit().putString(a.id, a.toJson()).apply(); if (activeId() == null) setActive(a.id) }
    fun rename(id: String, label: String) { get(id)?.let { save(it.copy(label = label)) } }
    fun remove(id: String) {
        prefs.edit().remove(id).apply()
        if (activeId() == id) setActive(all().firstOrNull()?.id)
    }
    fun activeId(): String? = prefs.getString("_active", null)?.takeIf { get(it) != null }
    fun setActive(id: String?) { prefs.edit().putString("_active", id).apply() }
}

/** Small non-secret switches. */
object Settings {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun wifiOnly(ctx: Context) = p(ctx).getBoolean("wifiOnly", false)
    fun setWifiOnly(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("wifiOnly", v).apply()
    fun paused(ctx: Context) = p(ctx).getBoolean("paused", false)
    fun setPaused(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("paused", v).apply()
    /** Read synchronously by the upload worker, so it lives in SharedPreferences rather than DataStore. */
    fun notifyResults(ctx: Context) = p(ctx).getBoolean("notifyResults", true)
    fun setNotifyResults(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("notifyResults", v).apply()
}

/** Persistent queue. In-memory StateFlow is the working copy; Room is the durable copy (write-behind, flushed by the worker). */
object PostStore {
    private val _items = MutableStateFlow<List<PostItem>>(emptyList())
    val items: StateFlow<List<PostItem>> = _items
    private var dao: PostDao? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val saveLock = Mutex()
    private var saved: Map<String, PostItem> = emptyMap()

    @Synchronized
    fun init(ctx: Context) {
        if (dao != null) return
        val d = AppDatabase.get(ctx).posts()
        val loaded = runBlocking(Dispatchers.IO) {
            if (d.count() == 0) importLegacy(ctx, d)
            d.all().mapNotNull { it.toItem() }
        }
        saved = loaded.associateBy { it.id }
        _items.value = loaded
        dao = d
        scope.launch { for (u in dirty) runCatching { flush() }.onFailure { AppLog.e("PostStore", "save failed", it) } }
    }

    /** One-time move of the v0.5 queue.json file into the database. */
    private suspend fun importLegacy(ctx: Context, d: PostDao) {
        val file = File(ctx.filesDir, "queue.json")
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).map { PostItem.fromJson(arr.getJSONObject(it)) }
            d.upsertAll(list.map { it.toEntity() })
            file.renameTo(File(ctx.filesDir, "queue.json.migrated"))
        }.onFailure { AppLog.e("PostStore", "legacy import failed", it) }
    }

    /** Writes everything that changed since the last save. Safe to call from any coroutine. */
    suspend fun flush() {
        saveLock.withLock {
            val d = dao ?: return@withLock
            val snap = _items.value
            val changed = snap.filter { saved[it.id] != it }
            val removed = saved.keys - snap.map { it.id }.toSet()
            if (changed.isEmpty() && removed.isEmpty()) return@withLock
            d.sync(changed.map { it.toEntity() }, removed.toList())
            saved = snap.associateBy { it.id }
        }
    }

    private fun persist() { dirty.trySend(Unit) }

    fun add(list: List<PostItem>) { _items.update { it + list }; persist() }
    fun get(id: String): PostItem? = _items.value.firstOrNull { it.id == id }

    fun update(id: String, save: Boolean = true, f: (PostItem) -> PostItem) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it } }
        if (save) persist()
    }

    fun updateWhere(pred: (PostItem) -> Boolean, f: (PostItem) -> PostItem) {
        _items.update { l -> l.map { if (pred(it)) f(it) else it } }
        persist()
    }

    /** Removes rows and returns video files nobody references any more. */
    fun remove(pred: (PostItem) -> Boolean): List<String> {
        val gone = _items.value.filter(pred)
        _items.update { l -> l.filterNot(pred) }
        persist()
        return gone.map { it.filePath }.distinct().filter { !fileInUse(it) }
    }

    fun due(now: Long) = _items.value.filter { it.status == Status.QUEUED && it.scheduledAt <= now }.sortedBy { it.scheduledAt }
    fun nextScheduled(): Long? = _items.value.filter { it.status == Status.QUEUED }.minOfOrNull { it.scheduledAt }
    fun resetStuck() = updateWhere({ it.status == Status.UPLOADING }, { it.copy(status = Status.QUEUED, progress = 0f) })

    /** A file is still needed while any row that could (re)try it exists. */
    fun fileInUse(path: String, exceptId: String? = null) = _items.value.any {
        it.filePath == path && it.id != exceptId && it.status != Status.DONE && it.status != Status.CANCELED
    }
}
