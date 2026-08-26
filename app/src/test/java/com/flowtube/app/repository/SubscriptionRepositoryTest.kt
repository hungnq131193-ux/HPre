package com.flowtube.app.repository

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.database.dao.SubscriptionDao
import com.flowtube.app.database.entity.SubscriptionEntity
import com.flowtube.app.model.Channel
import com.flowtube.app.model.ContentKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRepositoryTest {

    private class FakeSubscriptionDao : SubscriptionDao {
        val storage = mutableMapOf<Pair<Int, String>, SubscriptionEntity>()
        val flow = MutableStateFlow<List<SubscriptionEntity>>(emptyList())
        var shouldThrowIoException = false
        var upsertStarted: CompletableDeferred<Unit>? = null
        var upsertGate: CompletableDeferred<Unit>? = null

        private fun updateFlow() {
            flow.value = storage.values.sortedByDescending { it.subscribedTimestamp }
        }

        override fun observeAll(): Flow<List<SubscriptionEntity>> = flow

        override suspend fun getByKey(serviceId: Int, channelId: String): SubscriptionEntity? {
            if (shouldThrowIoException) throw java.io.IOException("Disk read error")
            return storage[Pair(serviceId, channelId)]
        }

        override fun observeIsSubscribed(serviceId: Int, channelId: String): Flow<Boolean> {
            return flow.map { storage.containsKey(Pair(serviceId, channelId)) }
        }

        override suspend fun isSubscribed(serviceId: Int, channelId: String): Boolean {
            if (shouldThrowIoException) throw java.io.IOException("Disk read error")
            return storage.containsKey(Pair(serviceId, channelId))
        }

        override suspend fun upsert(entity: SubscriptionEntity) {
            upsertStarted?.complete(Unit)
            upsertGate?.await()
            if (shouldThrowIoException) throw java.io.IOException("Disk write error")
            storage[Pair(entity.serviceId, entity.channelId)] = entity
            updateFlow()
        }

        override suspend fun deleteByKey(serviceId: Int, channelId: String) {
            if (shouldThrowIoException) throw java.io.IOException("Disk delete error")
            storage.remove(Pair(serviceId, channelId))
            updateFlow()
        }

        override suspend fun clearAll() {
            if (shouldThrowIoException) throw java.io.IOException("Disk clear error")
            storage.clear()
            updateFlow()
        }
    }

    private val sampleChannel = Channel(
        key = ContentKey(1, "chan1"),
        name = "Channel One",
        canonicalUrl = "https://example.com/channel/chan1",
        avatarUrl = "https://example.com/avatar.jpg",
        bannerUrl = null,
        subscriberCountText = "1M subscribers",
        description = "Channel description"
    )

    @Test
    fun subscribe_unsubscribe_and_observe() = runTest {
        val dao = FakeSubscriptionDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSubscriptionRepository(dao, dispatcher)

        assertFalse((repo.isSubscribed(sampleChannel.key) as AppResult.Success).value)

        val subResult = repo.subscribe(sampleChannel, subscribedTimestamp = 1000L)
        assertTrue(subResult is AppResult.Success)

        assertTrue((repo.isSubscribed(sampleChannel.key) as AppResult.Success).value)
        assertEquals(1, repo.observeSubscriptions().first().size)
        assertEquals("Channel One", repo.observeSubscriptions().first().first().name)

        val unsubResult = repo.unsubscribe(sampleChannel.key)
        assertTrue(unsubResult is AppResult.Success)

        assertFalse((repo.isSubscribed(sampleChannel.key) as AppResult.Success).value)
        assertTrue(repo.observeSubscriptions().first().isEmpty())
    }

    @Test
    fun clearSubscriptions_removes_all() = runTest {
        val dao = FakeSubscriptionDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSubscriptionRepository(dao, dispatcher)

        repo.subscribe(sampleChannel)
        repo.subscribe(sampleChannel.copy(key = ContentKey(1, "chan2"), name = "Channel Two"))
        assertEquals(2, repo.observeSubscriptions().first().size)

        repo.clearSubscriptions()
        assertEquals(0, repo.observeSubscriptions().first().size)
    }

    @Test
    fun io_exception_maps_to_failure_unknown() = runTest {
        val dao = FakeSubscriptionDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSubscriptionRepository(dao, dispatcher)

        dao.shouldThrowIoException = true

        val isSubRes = repo.isSubscribed(sampleChannel.key)
        assertTrue(isSubRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (isSubRes as AppResult.Failure).error)

        val subRes = repo.subscribe(sampleChannel)
        assertTrue(subRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (subRes as AppResult.Failure).error)

        val unsubRes = repo.unsubscribe(sampleChannel.key)
        assertTrue(unsubRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (unsubRes as AppResult.Failure).error)

        val clearRes = repo.clearSubscriptions()
        assertTrue(clearRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (clearRes as AppResult.Failure).error)
    }

    @Test
    fun cancellation_exception_is_rethrown() = runTest {
        val dao = FakeSubscriptionDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultSubscriptionRepository(dao, dispatcher)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        dao.upsertStarted = started
        dao.upsertGate = gate

        val job = launch(dispatcher) {
            repo.subscribe(sampleChannel)
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
