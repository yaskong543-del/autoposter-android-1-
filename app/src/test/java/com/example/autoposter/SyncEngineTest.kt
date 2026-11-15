package com.example.autoposter

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemOutbox : OutboxStore {
    val ops = mutableListOf<OutboxOp>(); private var seq = 0L
    override fun all() = ops.toList(); override fun add(op: OutboxOp) { ops.add(op) }
    override fun remove(opIds: Set<String>) { ops.removeAll { it.opId in opIds } }
    override fun nextSeq() = ++seq; override fun clear() { ops.clear() }
}
class MemState : SyncStateStore {
    override var cursor = 0L; val recs = mutableMapOf<String, Pair<Long, JSONObject>>()
    override fun get(entity: String, id: String) = recs["$entity|$id"]
    override fun put(entity: String, id: String, rev: Long, fields: JSONObject) { recs["$entity|$id"] = rev to fields }
    override fun remove(entity: String, id: String) { recs.remove("$entity|$id") }
    override fun ids(entity: String) = recs.keys.filter { it.startsWith("$entity|") }.map { it.removePrefix("$entity|") }.toSet()
    override fun clear() { recs.clear(); cursor = 0 }
}
class RecordingApplier : EntityApplier { val applied = mutableListOf<SyncChange>(); override suspend fun apply(changes: List<SyncChange>) { applied += changes } }
class ScriptedTransport(private val replies: MutableList<() -> SyncReply>) : SyncTransport {
    val sent = mutableListOf<Triple<String, Long, List<OutboxOp>>>()
    override suspend fun sync(deviceId: String, trigger: String, cursor: Long, ops: List<OutboxOp>, limit: Int): SyncReply { sent += Triple(trigger, cursor, ops); return replies.removeAt(0)() }
}
private fun change(id: String, rev: Long, seq: Long, text: String = "x", deleted: Boolean = false, entity: String = "draft") = SyncChange(entity, id, rev, deleted, JSONObject().put("text", text), seq)

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun collapseKeepsNewestStateAndOldestBase() {
        fun op(seq: Long, baseRev: Long, text: String) = OutboxOp("o$seq", "draft", "d1", seq, baseRev, JSONObject().put("b", baseRev), JSONObject().put("text", text), false, seq)
        val out = OutboxLogic.collapse(listOf(op(2, 4, "two"), op(1, 3, "one"), OutboxOp("z", "post", "d1", 3, 0, JSONObject(), JSONObject(), false, 3)))
        assertEquals(2, out.size)                                          // same id in different entities stays separate
        val d = out.first { it.entity == "draft" }
        assertEquals("two", d.fields.getString("text")); assertEquals(3L, d.baseRev); assertEquals("o2", d.opId)
    }

    @Test fun pushThenPullAppliesChangesAndAdvancesCursor() = runBlocking {
        val outbox = MemOutbox(); val state = MemState(); val applier = RecordingApplier()
        val t = ScriptedTransport(mutableListOf({ SyncReply(listOf(OpResult("will-be-replaced", "applied", 1, 0)), listOf(change("d1", 1, 7, "mine"), change("d2", 3, 8, "theirs")), 8, false) }))
        val engine = SyncEngine(t, outbox, state, applier, "dev")
        engine.record("draft", "d1", JSONObject().put("text", "mine"), false)
        // answer with the real opId
        val realId = outbox.all().single().opId
        val t2 = ScriptedTransport(mutableListOf({ SyncReply(listOf(OpResult(realId, "applied", 1, 0)), listOf(change("d1", 1, 7, "mine"), change("d2", 3, 8, "theirs")), 8, false) }))
        val out = SyncEngine(t2, outbox, state, applier, "dev").run("open")
        assertEquals(SyncStatus.OK, out.status); assertEquals(1, out.pushed); assertEquals(2, out.pulled); assertEquals(0, out.pendingOps)
        assertEquals(8L, state.cursor); assertEquals(3L, state.get("draft", "d2")!!.first)
        assertEquals(listOf("d1", "d2"), applier.applied.map { it.id })
        assertEquals("open", t2.sent.single().first); assertEquals(1, t2.sent.single().third.size)
    }

    @Test fun retryResultsKeepTheOpAndPendingRecordsAreNotOverwritten() = runBlocking {
        val outbox = MemOutbox(); val state = MemState(); val applier = RecordingApplier()
        val e0 = SyncEngine(ScriptedTransport(mutableListOf()), outbox, state, applier, "dev")
        e0.record("draft", "d1", JSONObject().put("text", "local edit"), false)
        val opId = outbox.all().single().opId
        val t = ScriptedTransport(mutableListOf({ SyncReply(listOf(OpResult(opId, "retry", null, 0)), listOf(change("d1", 5, 9, "server version"), change("d3", 1, 10)), 10, false) }))
        val out = SyncEngine(t, outbox, state, applier, "dev").run("manual")
        assertEquals(1, out.pendingOps); assertEquals(0, out.pushed)
        assertEquals(listOf("d3"), applier.applied.map { it.id })          // d1 has an unsent local edit: the server's copy is not applied over it
        assertEquals(null, state.get("draft", "d1"))                      // base stays old so the server can three-way merge later
    }

    @Test fun supersededOpsAreRemovedWhenTheSurvivorIsAccepted() = runBlocking {
        val outbox = MemOutbox(); val state = MemState()
        val e = SyncEngine(ScriptedTransport(mutableListOf()), outbox, state, RecordingApplier(), "dev")
        e.record("draft", "d1", JSONObject().put("text", "1"), false); e.record("draft", "d1", JSONObject().put("text", "2"), false)
        val survivor = OutboxLogic.collapse(outbox.all()).single()
        val t = ScriptedTransport(mutableListOf({ SyncReply(listOf(OpResult(survivor.opId, "applied", 1, 1)), emptyList(), 1, false) }))
        val out = SyncEngine(t, outbox, state, RecordingApplier(), "dev").run("open")
        assertEquals(0, out.pendingOps); assertEquals(1, out.conflicts); assertEquals(1, t.sent.single().third.size)
    }

    @Test fun tombstonesRemoveLocalStateAndPaginationContinues() = runBlocking {
        val outbox = MemOutbox(); val state = MemState(); val applier = RecordingApplier()
        state.put("draft", "gone", 2, JSONObject())
        val t = ScriptedTransport(mutableListOf(
            { SyncReply(emptyList(), listOf(change("gone", 3, 4, deleted = true)), 4, true) },
            { SyncReply(emptyList(), listOf(change("n", 1, 5)), 5, false) }))
        val out = SyncEngine(t, outbox, state, applier, "dev").run("open")
        assertEquals(2, t.sent.size); assertTrue(t.sent[1].third.isEmpty()); assertEquals(4L, t.sent[1].second)
        assertEquals(null, state.get("draft", "gone")); assertEquals(5L, state.cursor); assertEquals(2, out.pulled)
    }

    @Test fun serverRefusalsMapToStatuses() = runBlocking {
        fun status(code: String, kind: ErrorKind): SyncStatus {
            val t = object : SyncTransport { override suspend fun sync(deviceId: String, trigger: String, cursor: Long, ops: List<OutboxOp>, limit: Int): SyncReply = throw ApiException(403, "x", kind, code) }
            return runBlocking { SyncEngine(t, MemOutbox(), MemState(), RecordingApplier(), "dev").run("auto").status }
        }
        assertEquals(SyncStatus.AUTO_SYNC_NOT_ALLOWED, status("plan_limit_autosync", ErrorKind.AUTH))
        assertEquals(SyncStatus.DEVICE_REVOKED, status("device_revoked", ErrorKind.AUTH))
        assertEquals(SyncStatus.DEVICE_SUSPENDED, status("device_suspended", ErrorKind.AUTH))
        assertEquals(SyncStatus.OFFLINE, status("network", ErrorKind.NETWORK))
        assertEquals(SyncStatus.ERROR, status("boom", ErrorKind.SERVER))
    }

    @Test fun durableStoresSurviveProcessRestart() {
        val of = tmp.newFile("outbox.json"); val sf = tmp.newFile("state.json"); of.delete(); sf.delete()
        val outbox = FileOutboxStore(of); val state = FileSyncStateStore(sf)
        val e = SyncEngine(ScriptedTransport(mutableListOf()), outbox, state, RecordingApplier(), "dev")
        state.put("draft", "d1", 4, JSONObject().put("text", "base")); state.cursor = 42
        e.record("draft", "d1", JSONObject().put("text", "edit"), false); e.record("draft", "d2", JSONObject(), true)
        // "restart": brand-new instances read the same files
        val outbox2 = FileOutboxStore(of); val state2 = FileSyncStateStore(sf)
        val ops = outbox2.all()
        assertEquals(2, ops.size); assertEquals(4L, ops.first { it.id == "d1" }.baseRev); assertEquals("base", ops.first { it.id == "d1" }.baseFields.getString("text"))
        assertTrue(ops.first { it.id == "d2" }.deleted); assertEquals(setOf(1L, 2L), ops.map { it.seq }.toSet())
        assertEquals(42L, state2.cursor); assertEquals(setOf("d1"), state2.ids("draft"))
        outbox2.remove(setOf(ops[0].opId)); assertEquals(1, FileOutboxStore(of).all().size)
        assertEquals(3L, outbox2.nextSeq())
        outbox2.clear(); state2.clear(); assertEquals(0, FileOutboxStore(of).all().size); assertEquals(0L, FileSyncStateStore(sf).cursor)
    }

    @Test fun draftCodecRoundTripAndChangeDetection() {
        val d = ComposerDraftEntity("id1", "T", "cap", "{\"YOUTUBE\":\"y\"}", "#a", "[\"m1\"]", "[\"a1\"]", null, "casual", "en", 5L, "UTC", 1L, 2L)
        val f = DraftCodec.toFields(d)
        assertEquals(d, DraftCodec.fromFields("id1", f))
        assertTrue(DraftCodec.same(f, DraftCodec.toFields(d)))
        assertTrue(!DraftCodec.same(f, DraftCodec.toFields(d.copy(baseCaption = "changed"))))
    }

    @Test fun postResultsOnlyTouchTerminalServerOwnedStates() = runBlocking {
        val updates = mutableListOf<String>()
        val a = PostResultApplier { id, st, rid, err, fin -> updates += "$id:$st:$rid:$err:$fin" }
        fun post(id: String, status: String) = SyncChange("post", id, 1, false, JSONObject().put("status", status).put("remote_id", "R").put("finished_at", "2026-11-12T10:00:00Z"), 1)
        a.apply(listOf(post("p1", "DONE"), post("p2", "UPLOADING"), post("p3", "BOGUS"), SyncChange("draft", "d", 1, false, JSONObject(), 2)))
        assertEquals(1, updates.size); assertTrue(updates[0].startsWith("p1:DONE:R:null:"))
    }
}
