package com.flowtube.app.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.database.entity.SubscriptionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionDaoTest {

    private lateinit var database: FlowTubeDatabase
    private val dao get() = database.subscriptionDao()

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

    private fun sub(
        serviceId: Int = 1,
        channelId: String = "c1",
        name: String = "Channel 1",
        subscribedTimestamp: Long = 1000L
    ) = SubscriptionEntity(
        serviceId = serviceId,
        channelId = channelId,
        canonicalUrl = "https://example.com/channel/$channelId",
        name = name,
        avatarUrl = "https://example.com/avatar.jpg",
        subscribedTimestamp = subscribedTimestamp
    )

    @Test
    fun subscription_upsert_and_query() = runTest {
        dao.upsert(sub(channelId = "c1", name = "Original Name"))
        assertTrue(dao.isSubscribed(1, "c1"))
        assertTrue(dao.observeIsSubscribed(1, "c1").first())

        val record = dao.getByKey(1, "c1")
        assertNotNull(record)
        assertEquals("Original Name", record?.name)

        // Upsert updates fields on existing key
        dao.upsert(sub(channelId = "c1", name = "Updated Name"))
        val updatedRecord = dao.getByKey(1, "c1")
        assertEquals("Updated Name", updatedRecord?.name)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun subscription_composite_key_allows_same_channel_id_on_different_services() = runTest {
        dao.upsert(sub(serviceId = 1, channelId = "c1", name = "Service 1 Channel"))
        dao.upsert(sub(serviceId = 2, channelId = "c1", name = "Service 2 Channel"))

        assertEquals(2, dao.observeAll().first().size)
        assertTrue(dao.isSubscribed(1, "c1"))
        assertTrue(dao.isSubscribed(2, "c1"))
        assertFalse(dao.isSubscribed(3, "c1"))
    }

    @Test
    fun delete_by_key_unsubscribes() = runTest {
        dao.upsert(sub(channelId = "c1"))
        assertTrue(dao.isSubscribed(1, "c1"))

        dao.deleteByKey(1, "c1")
        assertFalse(dao.isSubscribed(1, "c1"))
        assertFalse(dao.observeIsSubscribed(1, "c1").first())
        assertNull(dao.getByKey(1, "c1"))
    }

    @Test
    fun clear_all_removes_all_subscriptions() = runTest {
        dao.upsert(sub(channelId = "c1"))
        dao.upsert(sub(channelId = "c2"))
        assertEquals(2, dao.observeAll().first().size)

        dao.clearAll()
        assertEquals(0, dao.observeAll().first().size)
    }
}
