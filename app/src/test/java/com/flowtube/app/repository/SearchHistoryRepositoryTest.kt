package com.flowtube.app.repository

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.database.dao.SearchHistoryDao
import com.flowtube.app.database.entity.SearchHistoryEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryRepositoryTest {

    private class FakeSearchHistoryDao : SearchHistoryDao {
        val storage = mutableMapOf<String, SearchHistoryEntity>()
        val flow = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
        var shouldThrowIoException = false
        var recordStarted: CompletableDeferred<Unit>? = null
        var recordGate: CompletableDeferred<Unit>? = null

        private fun updateFlow() {
            flow.value = storage.values.sortedByDescending { it.searchedTimestamp }
        }

        override fun observeAll(): Flow<List<SearchHistoryEntity>> = flow

        override fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>> = flow.map { list ->
            list.take(limit)
        }

        override suspend fun getByQuery(query: String): SearchHistoryEntity? {
            if (shouldThrowIoException) throw java.io.IOException("Disk read error")
            return storage[query]
        }

        override suspend fun upsert(entity: SearchHistoryEntity) {
            if (shouldThrowIoException) throw java.io.IOException("Disk write error")
            storage[entity.query] = entity
            updateFlow()
        }

        override suspend fun deleteByQuery(query: String) {
            if (shouldThrowIoException) throw java.io.IOException("Disk delete error")
            storage.remove(query)
            updateFlow()
        }

        override suspend fun clearAll() {
            if (shouldThrowIoException) throw java.io.IOException("Disk clear error")
            storage.clear()
            updateFlow()
        }

        override suspend fun getCount(): Int = storage.size

        override suspend fun trimOldest(maxEntries: Int) {
            if (shouldThrowIoException) throw java.io.IOException("Disk trim error")
            if (storage.size > maxEntries) {
                val sorted = storage.values.sortedByDescending { it.searchedTimestamp }
                val toKeep = sorted.take(maxEntries).map { it.query }.toSet()
                storage.keys.retainAll(toKeep)
                updateFlow()
            }
        }

        override suspend fun recordAndTrim(entity: SearchHistoryEntity, maxEntries: Int) {
            recordStarted?.complete(Unit)
            recordGate?.await()
            if (shouldThrowIoException) throw java.io.IOException("Disk record error")
            upsert(entity)
            trimOldest(maxEntries)
        }
    }

    @Test
    fun recordQuery_normalizes_and_trims_history() = runTest {
        val dao = FakeSearchHistoryDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 3)

        repo.recordQuery("   kotlin    flow   ", timestamp = 100L)
        repo.recordQuery("jetpack compose", timestamp = 200L)
        repo.recordQuery("android media3", timestamp = 300L)

        val list1 = repo.observeRecentQueries(10).first()
        assertEquals(3, list1.size)
        assertEquals("android media3", list1[0].query)
        assertEquals("jetpack compose", list1[1].query)
        assertEquals("kotlin flow", list1[2].query)

        // Adding 4th query should trim to max 3 entries
        repo.recordQuery("room database", timestamp = 400L)
        val list2 = repo.observeRecentQueries(10).first()
        assertEquals(3, list2.size)
        assertEquals(listOf("room database", "android media3", "jetpack compose"), list2.map { it.query })
    }

    @Test
    fun recordQuery_case_normalization_deduplicates_kotlin_and_Kotlin() = runTest {
        val dao = FakeSearchHistoryDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 5)

        repo.recordQuery("Kotlin", timestamp = 100L)
        assertEquals(listOf("kotlin"), repo.observeRecentQueries(5).first().map { it.query })

        repo.recordQuery("kotlin", timestamp = 200L)
        val recent = repo.observeRecentQueries(5).first()
        assertEquals(1, recent.size)
        assertEquals("kotlin", recent.first().query)
        assertEquals(200L, recent.first().searchedTimestamp)
    }

    @Test
    fun blank_query_is_ignored() = runTest {
        val dao = FakeSearchHistoryDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 5)

        val result = repo.recordQuery("   \t  \n  ")
        assertTrue(result is AppResult.Success)
        assertTrue(repo.observeRecentQueries(5).first().isEmpty())
    }

    @Test
    fun delete_and_clear_search_history() = runTest {
        val dao = FakeSearchHistoryDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 5)

        repo.recordQuery("query1", timestamp = 100L)
        repo.recordQuery("query2", timestamp = 200L)

        repo.deleteQuery("   query1   ") // should normalize and delete
        assertEquals(listOf("query2"), repo.observeRecentQueries(5).first().map { it.query })

        repo.clearHistory()
        assertTrue(repo.observeRecentQueries(5).first().isEmpty())
    }

    @Test
    fun io_exception_maps_to_failure_unknown() = runTest {
        val dao = FakeSearchHistoryDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 5)

        dao.shouldThrowIoException = true

        val recordRes = repo.recordQuery("crash")
        assertTrue(recordRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (recordRes as AppResult.Failure).error)

        val delRes = repo.deleteQuery("crash")
        assertTrue(delRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (delRes as AppResult.Failure).error)

        val clearRes = repo.clearHistory()
        assertTrue(clearRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (clearRes as AppResult.Failure).error)
    }

    @Test
    fun constructor_clamps_maxEntries_and_observe_clamps_limit() = runTest {
        val dao = FakeSearchHistoryDao()
        val dispatcher = StandardTestDispatcher(testScheduler)

        // Test injected max > 100 clamped to 100
        val repoOver = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 150)
        for (i in 1..120) {
            repoOver.recordQuery("q$i", timestamp = i.toLong())
        }
        assertEquals(100, dao.storage.size)

        // Test negative / 0 maxEntries clamped to 1
        val repoNeg = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = -5)
        repoNeg.recordQuery("single_item", timestamp = 1000L)
        assertEquals(1, dao.storage.size)

        // Test observeRecentQueries with limit > 100 clamped to 100, and limit <= 0 clamped to 1
        val repoNormal = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 100)
        dao.clearAll()
        for (i in 1..100) {
            repoNormal.recordQuery("query_$i", timestamp = i.toLong())
        }
        val observedOver = repoNormal.observeRecentQueries(limit = 200).first()
        assertEquals(100, observedOver.size)

        val observedZero = repoNormal.observeRecentQueries(limit = 0).first()
        assertEquals(1, observedZero.size)
    }

    @Test
    fun cancellation_exception_is_rethrown() = runTest {
        val dao = FakeSearchHistoryDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSearchHistoryRepository(dao, dispatcher, maxEntries = 5)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        dao.recordStarted = started
        dao.recordGate = gate

        val job = launch(dispatcher) {
            repo.recordQuery("test query")
        }

        // Advance dispatcher until DAO operation starts and blocks on gate
        testScheduler.runCurrent()
        assertTrue("Expected DAO operation to have started", started.isCompleted)

        // Cancel job during operation
        job.cancel(kotlinx.coroutines.CancellationException("Explicit cancellation"))
        testScheduler.runCurrent()
        job.join()

        assertTrue(job.isCancelled)
        // Verify no partial mutation occurred before gate release
        assertTrue("Storage should remain empty after cancellation", dao.storage.isEmpty())
    }
}
