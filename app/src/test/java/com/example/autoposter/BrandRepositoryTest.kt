package com.example.autoposter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BrandRepositoryTest {
    private class FakeBrandDao : BrandDao {
        val rows = MutableStateFlow<Map<String, BrandProfile>>(emptyMap())
        override fun observe(): Flow<List<BrandProfile>> = rows.map { m -> m.values.filter { !it.pendingDelete }.sortedBy { it.name.lowercase() } }
        override suspend fun get(id: String) = rows.value[id]
        override suspend fun all() = rows.value.values.toList()
        override suspend fun dirty() = rows.value.values.filter { it.dirty }
        override suspend fun upsert(b: BrandProfile) { rows.value = rows.value + (b.id to b) }
        override suspend fun upsertAll(list: List<BrandProfile>) { rows.value = rows.value + list.associateBy { it.id } }
        override suspend fun delete(ids: List<String>) { rows.value = rows.value - ids.toSet() }
        override suspend fun clear() { rows.value = emptyMap() }
    }

    private lateinit var server: MockWebServer
    private val dao = FakeBrandDao()
    private var now = 1_000_000L
    private lateinit var auth: AuthRepository
    private lateinit var repo: BrandRepository

    @Before fun setUp() = runTest {
        server = MockWebServer().also { it.start() }
        val storage = InMemorySessionStorage()
        val http = SupabaseHttp(SupabaseConfig(server.url("/").toString().trimEnd('/'), "anon-key"))
        auth = AuthRepository(http, storage) { now }
        storage.save(AuthSession("jwt-1", "rt", now + 3_600_000, AuthUser("u1", "a@b.co", "Ann", true, "email")).toJson())
        auth.restore()
        repo = BrandRepository(dao, http, auth) { now }
    }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private suspend fun save(name: String = "Chai Co", id: String? = null) =
        repo.save(id, name, "Small-batch tea", AiTone.MARKETING, "students", "tea, #Chai #tea", "hi", "Order today", "— Chai Co")

    @Test fun savingNormalisesAndMarksDirty() = runTest {
        val b = save()
        assertEquals("#tea #Chai", b.hashtags); assertTrue(b.dirty); assertEquals("marketing", b.tone); assertEquals("hi", b.language)
        assertEquals(b, dao.get(b.id))
    }

    @Test fun emptyNameIsRejected() = runTest {
        assertTrue(runCatching { save("   ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun editingKeepsIdAndCreatedAt() = runTest {
        val first = save(); now += 5000
        val edited = save("Chai Co 2", id = first.id)
        assertEquals(first.id, edited.id); assertEquals(first.createdAt, edited.createdAt); assertEquals(now, edited.updatedAt)
        assertEquals(1, dao.rows.value.size)
    }

    @Test fun pushSendsBrandWithUserTokenAndClearsDirty() = runTest {
        val b = save()
        server.enqueue(MockResponse().setResponseCode(201))
        repo.push()
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/rest/v1/brand_profiles?on_conflict=id", req.path)
        assertEquals("Bearer jwt-1", req.getHeader("Authorization")); assertTrue(req.getHeader("Prefer")!!.contains("merge-duplicates"))
        val row = JSONArray(req.body.readUtf8()).getJSONObject(0)
        assertEquals(b.id, row.getString("id")); assertEquals("Chai Co", row.getString("name")); assertEquals("— Chai Co", row.getString("signature"))
        assertFalse(dao.get(b.id)!!.dirty)
    }

    @Test fun offlinePushKeepsTheRowDirtyForLater() = runTest {
        val b = save(); server.shutdown()
        assertTrue(runCatching { repo.push() }.isFailure)
        assertTrue(dao.get(b.id)!!.dirty)
        assertEquals(listOf("Chai Co"), repo.brands.first().map { it.name })    // still visible offline
    }

    @Test fun aSecondEditAfterPushIsDirtyAgain() = runTest {
        val b = save(); server.enqueue(MockResponse().setResponseCode(201)); repo.push()
        assertFalse(dao.get(b.id)!!.dirty)
        now += 10; repo.save(b.id, "renamed", "", AiTone.CASUAL, "", "", "en", "", "")
        assertTrue(dao.get(b.id)!!.dirty)
    }

    @Test fun deleteHidesImmediatelyAndRemovesOnServerOnPush() = runTest {
        val b = save(); server.enqueue(MockResponse().setResponseCode(201)); repo.push(); server.takeRequest()
        repo.delete(b.id)
        assertNotNull(dao.get(b.id)); assertTrue(dao.get(b.id)!!.pendingDelete)
        server.enqueue(MockResponse().setResponseCode(204)); repo.push()
        val req = server.takeRequest()
        assertEquals("DELETE", req.method); assertEquals("/rest/v1/brand_profiles?id=in.(${b.id})", req.path)
        assertNull(dao.get(b.id))
    }

    @Test fun pullKeepsUnpushedLocalEditsAndDropsRowsDeletedElsewhere() = runTest {
        val local = save("Local only edit")                       // dirty
        val synced = dao.get(save("Synced one").id)!!.let { dao.upsert(it.copy(dirty = false)); it }
        val remote = """[{"id":"${local.id}","name":"server version","tone":"funny","language":"en","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z"},
            {"id":"r2","name":"From phone B","description":"d","tone":"minimal","target_audience":"a","default_hashtags":"#x","language":"es","default_cta":"c","signature":"s","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00.123+00:00"}]"""
        server.enqueue(MockResponse().setBody(remote))
        repo.pull()
        assertEquals("Local only edit", dao.get(local.id)!!.name)         // unpushed local edit wins
        assertEquals("From phone B", dao.get("r2")!!.name); assertEquals("es", dao.get("r2")!!.language)
        assertNull("deleted on the server by another device", dao.get(synced.id))
    }

    @Test fun signedOutDoesNothing() = runTest {
        server.enqueue(MockResponse().setResponseCode(204)); auth.signOut()     // the best-effort logout call
        val before = server.requestCount
        save(); repo.push(); repo.pull()
        assertEquals(before, server.requestCount)
    }

    @Test fun unknownToneFallsBackToCasual() { assertEquals(AiTone.CASUAL, AiTone.fromWire("angry")); assertEquals(AiTone.FUNNY, AiTone.fromWire("FUNNY")) }
}
