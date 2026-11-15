package com.example.autoposter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Two-way sync client. Local changes are recorded in a durable outbox (survives process death/reboot), collapsed per record,
 * pushed with idempotent op ids, and the server's merged result comes back through the pull. The server decides conflicts
 * (supabase/functions/_shared/sync_core.ts); this client never invents a resolution.
 */
data class SyncChange(val entity: String, val id: String, val rev: Long, val deleted: Boolean, val fields: JSONObject, val serverSeq: Long)
data class OutboxOp(
    val opId: String, val entity: String, val id: String, val seq: Long, val baseRev: Long, val baseFields: JSONObject,
    val fields: JSONObject, val deleted: Boolean, val clientUpdatedAtMs: Long
)
data class OpResult(val opId: String, val status: String, val rev: Long?, val conflicts: Int)
data class SyncReply(val results: List<OpResult>, val changes: List<SyncChange>, val cursor: Long, val hasMore: Boolean)

enum class SyncStatus { OK, AUTO_SYNC_NOT_ALLOWED, DEVICE_REVOKED, DEVICE_SUSPENDED, OFFLINE, ERROR }
data class SyncOutcome(val status: SyncStatus, val pushed: Int, val pulled: Int, val conflicts: Int, val pendingOps: Int)

interface OutboxStore {
    fun all(): List<OutboxOp>
    fun add(op: OutboxOp)
    fun remove(opIds: Set<String>)
    fun nextSeq(): Long
    fun clear()
}

/** What this device last knew of each synced record (its "base" for three-way merges) and how far it has pulled. */
interface SyncStateStore {
    var cursor: Long
    fun get(entity: String, id: String): Pair<Long, JSONObject>?
    fun put(entity: String, id: String, rev: Long, fields: JSONObject)
    fun remove(entity: String, id: String)
    fun ids(entity: String): Set<String>
    fun clear()
}

interface SyncTransport { suspend fun sync(deviceId: String, trigger: String, cursor: Long, ops: List<OutboxOp>, limit: Int): SyncReply }
interface EntityApplier { suspend fun apply(changes: List<SyncChange>) }

object OutboxLogic {
    /** One op per (entity, id): the newest state, but the OLDEST base so the server's three-way merge sees the true common ancestor. */
    fun collapse(ops: List<OutboxOp>): List<OutboxOp> {
        val latest = LinkedHashMap<String, OutboxOp>()
        for (o in ops.sortedBy { it.seq }) {
            val k = o.entity + "|" + o.id
            val prev = latest[k]
            latest[k] = if (prev == null) o else o.copy(baseRev = prev.baseRev, baseFields = prev.baseFields)
        }
        return latest.values.sortedBy { it.seq }
    }
}

class SyncEngine(
    private val transport: SyncTransport, private val outbox: OutboxStore, private val state: SyncStateStore,
    private val applier: EntityApplier, private val deviceId: String, private val clock: () -> Long = System::currentTimeMillis
) {
    /** Records a local create/update/delete. The base is what this device last saw from the server for that record. */
    fun record(entity: String, id: String, fields: JSONObject, deleted: Boolean) {
        val base = state.get(entity, id)
        outbox.add(OutboxOp(UUID.randomUUID().toString(), entity, id, outbox.nextSeq(), base?.first ?: 0L, base?.second ?: JSONObject(), fields, deleted, clock()))
    }

    suspend fun run(trigger: String): SyncOutcome {
        var pushed = 0; var pulled = 0; var conflicts = 0
        try {
            var first = true
            while (true) {
                val all = outbox.all()
                val survivors = if (first) OutboxLogic.collapse(all).take(100) else emptyList()
                first = false
                val reply = transport.sync(deviceId, trigger, state.cursor, survivors, 200)
                val done = reply.results.filter { it.status == "applied" || it.status == "noop" || it.status == "rejected" }
                for (r in done) {
                    if (r.status != "rejected") pushed++
                    conflicts += r.conflicts
                    val s = survivors.firstOrNull { it.opId == r.opId } ?: continue
                    // The surviving op stands for every older op on the same record.
                    outbox.remove(all.filter { it.entity == s.entity && it.id == s.id && it.seq <= s.seq }.map { it.opId }.toSet())
                }
                // Never overwrite a record that still has an unsent local edit: the edit will be merged by the server using its older base.
                val pending = outbox.all().map { it.entity + "|" + it.id }.toSet()
                val apply = reply.changes.filter { (it.entity + "|" + it.id) !in pending }
                if (apply.isNotEmpty()) applier.apply(apply)
                for (c in apply) if (c.deleted) state.remove(c.entity, c.id) else state.put(c.entity, c.id, c.rev, c.fields)
                pulled += reply.changes.size
                state.cursor = reply.cursor
                if (!reply.hasMore) break
            }
            return SyncOutcome(SyncStatus.OK, pushed, pulled, conflicts, outbox.all().size)
        } catch (e: ApiException) {
            val st = when {
                e.errorCode == "plan_limit_autosync" -> SyncStatus.AUTO_SYNC_NOT_ALLOWED
                e.errorCode == "device_revoked" -> SyncStatus.DEVICE_REVOKED
                e.errorCode == "device_suspended" -> SyncStatus.DEVICE_SUSPENDED
                e.kind == ErrorKind.NETWORK -> SyncStatus.OFFLINE
                else -> SyncStatus.ERROR
            }
            return SyncOutcome(st, pushed, pulled, conflicts, outbox.all().size)
        }
    }
}

class HttpSyncTransport(private val api: CloudApi) : SyncTransport {
    override suspend fun sync(deviceId: String, trigger: String, cursor: Long, ops: List<OutboxOp>, limit: Int): SyncReply {
        val body = JSONObject().put("deviceId", deviceId).put("trigger", trigger).put("cursor", cursor).put("limit", limit).put("ops", JSONArray().also { arr ->
            ops.forEach { o ->
                arr.put(JSONObject().put("entity", o.entity).put("id", o.id).put("opId", o.opId).put("baseRev", o.baseRev).put("baseFields", o.baseFields)
                    .put("fields", o.fields).put("deleted", o.deleted).put("clientUpdatedAtMs", o.clientUpdatedAtMs).put("deviceId", deviceId))
            }
        })
        val j = api.sync(body)
        val results = j.getJSONArray("results").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map {
            OpResult(it.getString("opId"), it.getString("status"), if (it.has("rev")) it.optLong("rev") else null, it.optJSONArray("conflicts")?.length() ?: 0)
        }
        val changes = j.getJSONArray("changes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map {
            SyncChange(it.getString("entity"), it.getString("id"), it.getLong("rev"), it.optBoolean("deleted"), it.optJSONObject("fields") ?: JSONObject(), it.getLong("serverSeq"))
        }
        return SyncReply(results, changes, j.getLong("cursor"), j.optBoolean("hasMore"))
    }
}

// ---- durable stores (plain files, atomic replace) -------------------------------------------------------------------------

internal object AtomicFile {
    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

class FileOutboxStore(private val file: File) : OutboxStore {
    private fun load(): MutableList<OutboxOp> = synchronized(this) {
        if (!file.exists()) return mutableListOf()
        runCatching {
            val o = JSONObject(file.readText()); val a = o.getJSONArray("ops")
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                OutboxOp(it.getString("opId"), it.getString("entity"), it.getString("id"), it.getLong("seq"), it.getLong("baseRev"), it.getJSONObject("baseFields"),
                    it.getJSONObject("fields"), it.getBoolean("deleted"), it.getLong("at"))
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }
    private fun save(ops: List<OutboxOp>, seq: Long) = synchronized(this) {
        val a = JSONArray(); ops.forEach { a.put(JSONObject().put("opId", it.opId).put("entity", it.entity).put("id", it.id).put("seq", it.seq).put("baseRev", it.baseRev)
            .put("baseFields", it.baseFields).put("fields", it.fields).put("deleted", it.deleted).put("at", it.clientUpdatedAtMs)) }
        AtomicFile.write(file, JSONObject().put("seq", seq).put("ops", a).toString())
    }
    private fun seq(): Long = synchronized(this) { if (!file.exists()) 0L else runCatching { JSONObject(file.readText()).optLong("seq", 0L) }.getOrDefault(0L) }
    override fun all(): List<OutboxOp> = load()
    override fun add(op: OutboxOp) = synchronized(this) { val l = load(); l.add(op); save(l, maxOf(seq(), op.seq)) }
    override fun remove(opIds: Set<String>) = synchronized(this) { save(load().filter { it.opId !in opIds }, seq()) }
    override fun nextSeq(): Long = synchronized(this) { val n = maxOf(seq(), load().maxOfOrNull { it.seq } ?: 0L) + 1; save(load(), n); n }
    override fun clear() = synchronized(this) { file.delete(); Unit }
}

class FileSyncStateStore(private val file: File) : SyncStateStore {
    private fun root(): JSONObject = synchronized(this) { if (!file.exists()) JSONObject() else runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) }
    private fun save(o: JSONObject) = synchronized(this) { AtomicFile.write(file, o.toString()) }
    override var cursor: Long
        get() = root().optLong("cursor", 0L)
        set(v) = synchronized(this) { save(root().put("cursor", v)) }
    override fun get(entity: String, id: String): Pair<Long, JSONObject>? {
        val r = root().optJSONObject("records")?.optJSONObject("$entity|$id") ?: return null
        return r.getLong("rev") to r.getJSONObject("fields")
    }
    override fun put(entity: String, id: String, rev: Long, fields: JSONObject) = synchronized(this) {
        val o = root(); val recs = o.optJSONObject("records") ?: JSONObject()
        recs.put("$entity|$id", JSONObject().put("rev", rev).put("fields", fields)); save(o.put("records", recs))
    }
    override fun remove(entity: String, id: String) = synchronized(this) {
        val o = root(); o.optJSONObject("records")?.remove("$entity|$id"); save(o)
    }
    override fun ids(entity: String): Set<String> = root().optJSONObject("records")?.keys()?.asSequence()?.filter { it.startsWith("$entity|") }?.map { it.removePrefix("$entity|") }?.toSet() ?: emptySet()
    override fun clear() = synchronized(this) { file.delete(); Unit }
}

// ---- drafts and post results ------------------------------------------------------------------------------------------------

object DraftCodec {
    fun toFields(d: ComposerDraftEntity): JSONObject = JSONObject().put("title", d.title).put("baseCaption", d.baseCaption).put("platformCaptions", d.platformCaptions)
        .put("hashtags", d.hashtags).put("mediaIds", d.mediaIds).put("accountIds", d.accountIds).put("brandId", d.brandId ?: JSONObject.NULL).put("tone", d.tone)
        .put("language", d.language).put("plannedAt", d.plannedAt).put("zoneId", d.zoneId).put("createdAt", d.createdAt).put("updatedAt", d.updatedAt)

    fun fromFields(id: String, f: JSONObject): ComposerDraftEntity = ComposerDraftEntity(
        id = id, title = f.optString("title"), baseCaption = f.optString("baseCaption"), platformCaptions = f.optString("platformCaptions", "{}"),
        hashtags = f.optString("hashtags"), mediaIds = f.optString("mediaIds", "[]"), accountIds = f.optString("accountIds", "[]"),
        brandId = f.optStringOrNull("brandId"), tone = f.optString("tone", "casual"), language = f.optString("language", "en"),
        plannedAt = f.optLong("plannedAt", 0L), zoneId = f.optString("zoneId", "UTC"), createdAt = f.optLong("createdAt", 0L), updatedAt = f.optLong("updatedAt", 0L)
    )

    fun same(a: JSONObject, b: JSONObject): Boolean = keys(a) == keys(b) && keys(a).all { a.opt(it).toString() == b.opt(it).toString() }
    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()
}

/** Finds local draft changes (new, edited, deleted) by comparing with what the server last confirmed. No hooks into the composer are needed. */
object DraftChangeScanner {
    suspend fun scan(dao: DraftDao, state: SyncStateStore, engine: SyncEngine, localIds: List<String>, outboxIds: Set<String>) {
        for (id in localIds) {
            val d = dao.get(id) ?: continue
            val fields = DraftCodec.toFields(d)
            val known = state.get("draft", id)
            if (id in outboxIds) continue                               // already queued; collapse keeps only the newest state at push time
            if (known == null || !DraftCodec.same(known.second, fields)) engine.record("draft", id, fields, false)
        }
        for (gone in state.ids("draft") - localIds.toSet()) if (gone !in outboxIds) engine.record("draft", gone, JSONObject(), true)
    }
}

class DraftApplier(private val dao: DraftDao) : EntityApplier {
    override suspend fun apply(changes: List<SyncChange>) {
        for (c in changes) if (c.entity == "draft") { if (c.deleted) dao.delete(c.id) else dao.upsert(DraftCodec.fromFields(c.id, c.fields)) }
    }
}

/** Server-owned results (status, remote id, error) flow back onto local queue items that have the same id. Nothing else is touched. */
class PostResultApplier(private val update: (id: String, status: Status, remoteId: String?, error: String?, finishedAt: Long) -> Unit) : EntityApplier {
    override suspend fun apply(changes: List<SyncChange>) {
        for (c in changes) {
            if (c.entity != "post" || c.deleted) continue
            val st = runCatching { Status.valueOf(c.fields.optString("status")) }.getOrNull() ?: continue
            if (st == Status.DONE || st == Status.FAILED || st == Status.NEEDS_AUTH || st == Status.CANCELED)
                update(c.id, st, c.fields.optStringOrNull("remote_id"), c.fields.optStringOrNull("error"), Iso.parse(c.fields.optStringOrNull("finished_at")))
        }
    }
}

class CompositeApplier(private val parts: List<EntityApplier>) : EntityApplier {
    override suspend fun apply(changes: List<SyncChange>) { parts.forEach { it.apply(changes) } }
}
