package com.flowtube.app.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.database.entity.HistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDaoTest {

    private lateinit var database: FlowTubeDatabase
    private val dao get() = database.historyDao()

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FlowTubeDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        database.close()
    }

    private fun history(
        serviceId: Int = 1,
        videoId: String = "v1",
        positionMs: Long = 1000L,
        watchedTimestamp: Long = 1000L,
        title: String = "Test Video"
    ) = HistoryEntity(
        serviceId = serviceId,
        videoId = videoId,
        canonicalUrl = "https://example.com/watch?v=$videoId",
        title = title,
        channelId = "c1",
        channelName = "Channel 1",
        thumbnailUrl = "https://example.com/thumb.jpg",
        durationSeconds = 300L,
        playbackPositionMs = positionMs,
        watchedTimestamp = watchedTimestamp
    )

    @Test
    fun history_upsert_updates_position_and_watched_time() = runTest {
        dao.upsert(history(videoId = "v1", positionMs = 100, watchedTimestamp = 1000))
        val firstRecord = dao.getByKey(1, "v1")
        assertNotNull(firstRecord)
        assertEquals(100L, firstRecord?.playbackPositionMs)
        assertEquals(1000L, firstRecord?.watchedTimestamp)

        // Upsert same composite key with updated position and timestamp
        dao.upsert(history(videoId = "v1", positionMs = 200, watchedTimestamp = 2000))
        val secondRecord = dao.getByKey(1, "v1")
        assertNotNull(secondRecord)
        assertEquals(200L, secondRecord?.playbackPositionMs)
        assertEquals(2000L, secondRecord?.watchedTimestamp)

        // Count should remain 1 because it's an upsert on primary key (serviceId, videoId)
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
    }

    @Test
    fun history_composite_key_distinguishes_different_service_ids() = runTest {
        dao.upsert(history(serviceId = 1, videoId = "v1", title = "Service 1 Video"))
        dao.upsert(history(serviceId = 2, videoId = "v1", title = "Service 2 Video"))

        val itemService1 = dao.getByKey(1, "v1")
        val itemService2 = dao.getByKey(2, "v1")

        assertNotNull(itemService1)
        assertNotNull(itemService2)
        assertEquals("Service 1 Video", itemService1?.title)
        assertEquals("Service 2 Video", itemService2?.title)

        val all = dao.observeAll().first()
        assertEquals(2, all.size)
    }

    @Test
    fun delete_by_key_removes_only_targeted_entry() = runTest {
        dao.upsert(history(serviceId = 1, videoId = "v1"))
        dao.upsert(history(serviceId = 1, videoId = "v2"))

        dao.deleteByKey(1, "v1")
        assertNull(dao.getByKey(1, "v1"))
        assertNotNull(dao.getByKey(1, "v2"))

        val all = dao.observeAll().first()
        assertEquals(1, all.size)
    }

    @Test
    fun clear_all_removes_all_history() = runTest {
        dao.upsert(history(videoId = "v1"))
        dao.upsert(history(videoId = "v2"))
        dao.upsert(history(videoId = "v3"))

        assertEquals(3, dao.observeAll().first().size)

        dao.clearAll()
        assertEquals(0, dao.observeAll().first().size)
    }

    @Test
    fun history_ordered_by_watched_timestamp_descending() = runTest {
        dao.upsert(history(videoId = "v1", watchedTimestamp = 100L))
        dao.upsert(history(videoId = "v2", watchedTimestamp = 300L))
        dao.upsert(history(videoId = "v3", watchedTimestamp = 200L))

        val list = dao.observeAll().first()
        assertEquals(listOf("v2", "v3", "v1"), list.map { it.videoId })
    }
}
