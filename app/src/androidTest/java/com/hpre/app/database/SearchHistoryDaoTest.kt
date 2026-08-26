package com.hpre.app.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.database.entity.SearchHistoryEntity
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
class SearchHistoryDaoTest {

    private lateinit var database: HPreDatabase
    private val dao get() = database.searchHistoryDao()

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HPreDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun upsert_and_retrieve_search_query() = runTest {
        dao.upsert(SearchHistoryEntity("kotlin coroutines", 1000L))
        val item = dao.getByQuery("kotlin coroutines")
        assertNotNull(item)
        assertEquals("kotlin coroutines", item?.query)
        assertEquals(1000L, item?.searchedTimestamp)

        // Updating timestamp for same query
        dao.upsert(SearchHistoryEntity("kotlin coroutines", 2000L))
        val updated = dao.getByQuery("kotlin coroutines")
        assertEquals(2000L, updated?.searchedTimestamp)
        assertEquals(1, dao.getCount())
    }

    @Test
    fun delete_by_query_removes_single_query() = runTest {
        dao.upsert(SearchHistoryEntity("query 1", 100L))
        dao.upsert(SearchHistoryEntity("query 2", 200L))

        dao.deleteByQuery("query 1")
        assertNull(dao.getByQuery("query 1"))
        assertNotNull(dao.getByQuery("query 2"))
        assertEquals(1, dao.getCount())
    }

    @Test
    fun clear_all_removes_all_queries() = runTest {
        dao.upsert(SearchHistoryEntity("q1", 100L))
        dao.upsert(SearchHistoryEntity("q2", 200L))
        assertEquals(2, dao.getCount())

        dao.clearAll()
        assertEquals(0, dao.getCount())
    }

    @Test
    fun record_and_trim_limits_history_to_max_entries() = runTest {
        for (i in 1..10) {
            dao.recordAndTrim(SearchHistoryEntity("query_$i", i * 100L), maxEntries = 5)
        }

        val all = dao.observeAll().first()
        assertEquals(5, all.size)
        // Most recent 5 should be query_10 down to query_6
        assertEquals(listOf("query_10", "query_9", "query_8", "query_7", "query_6"), all.map { it.query })
    }
}
