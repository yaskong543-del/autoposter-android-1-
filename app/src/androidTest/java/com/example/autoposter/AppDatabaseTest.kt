package com.example.autoposter

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }
    @After fun tearDown() { db.close() }

    private fun post(id: String, status: Status = Status.QUEUED) = PostItem(
        id = id, campaignId = "c", accountId = "a", accountLabel = "Main", platform = Platform.YOUTUBE,
        filePath = "/$id.mp4", name = id, fingerprint = null, text = "t", scheduledAt = 1L, status = status
    ).toEntity()

    @Test fun postSyncUpsertsChangesAndDeletesRemoved() = runBlocking {
        val dao = db.posts()
        dao.sync(listOf(post("1"), post("2")), emptyList())
        assertEquals(2, dao.count())
        dao.sync(listOf(post("2", Status.DONE)), listOf("1"))
        val rows = dao.all()
        assertEquals(listOf("2"), rows.map { it.id }); assertEquals("DONE", rows.single().status)
    }

    @Test fun profileDirtyFlagIsQueryable() = runBlocking {
        val dao = db.profiles()
        dao.upsert(ProfileEntity("u1", "Ann", "a@b.co", null, "en", "free", 1L, dirty = true))
        assertEquals(1, dao.dirty().size)
        dao.upsert(dao.get("u1")!!.copy(dirty = false))
        assertEquals(0, dao.dirty().size)
        dao.clear(); assertNull(dao.get("u1"))
    }

    @Test fun replaceOthersKeepsThisDevice() = runBlocking {
        val dao = db.devices()
        fun d(id: String, mine: Boolean) = DeviceEntity(id, "n", "android", "0.6", "A", 1L, 1L, mine, true)
        dao.upsert(d("me", true)); dao.upsert(d("old", false))
        dao.replaceOthers(listOf(d("new", false)))
        assertEquals(setOf("me", "new"), dao.unsyncedOrAll().toSet())
    }

    @Test fun accountMetaTracksUnsyncedAndPendingDeletes() = runBlocking {
        val dao = db.accountMeta()
        fun m(id: String, synced: Boolean, del: Boolean = false) = AccountMetaEntity(id, "YOUTUBE", "l", "CONNECTED", 0L, null, 1L, 1L, synced, del)
        dao.upsertAll(listOf(m("a", false), m("b", true), m("c", false, del = true)))
        assertEquals(listOf("a"), dao.unsynced().map { it.id })
        assertEquals(listOf("c"), dao.pendingDeletes().map { it.id })
        dao.delete(listOf("c")); assertEquals(2, dao.all().size)
    }

    private suspend fun DeviceDao.unsyncedOrAll(): List<String> =
        observe().first().map { it.installId }
}
