package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.database.dao.HistoryDao
import com.hpre.app.database.entity.HistoryEntity
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.settings.PlaybackPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRepositoryTest {

    private class FakeHistoryDao : HistoryDao {
        val storage = mutableMapOf<Pair<Int, String>, HistoryEntity>()
        val flow = MutableStateFlow<List<HistoryEntity>>(emptyList())

        private fun updateFlow() {
            flow.value = storage.values.sortedByDescending { it.watchedTimestamp }
        }

        var shouldThrowIoException = false
        var upsertStarted: CompletableDeferred<Unit>? = null
        var upsertGate: CompletableDeferred<Unit>? = null

        override fun observeAll(): Flow<List<HistoryEntity>> = flow

        override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> =
            flow.map { it.take(limit) }

        override suspend fun getByKey(serviceId: Int, videoId: String): HistoryEntity? {
            if (shouldThrowIoException) throw java.io.IOException("Disk read error")
            return storage[Pair(serviceId, videoId)]
        }

        override suspend fun upsert(entity: HistoryEntity) {
            upsertStarted?.complete(Unit)
            upsertGate?.await()
            if (shouldThrowIoException) throw java.io.IOException("Disk write error")
            storage[Pair(entity.serviceId, entity.videoId)] = entity
            updateFlow()
        }

        override suspend fun deleteByKey(serviceId: Int, videoId: String) {
            if (shouldThrowIoException) throw java.io.IOException("Disk delete error")
            storage.remove(Pair(serviceId, videoId))
            updateFlow()
        }

        override suspend fun clearAll() {
            if (shouldThrowIoException) throw java.io.IOException("Disk clear error")
            storage.clear()
            updateFlow()
        }
    }

    private class FakePlaybackPreferences(
        initialHistoryEnabled: Boolean = true
    ) : PlaybackPreferences {
        val historyEnabledFlow = MutableStateFlow(initialHistoryEnabled)
        override val isHistoryEnabled: Flow<Boolean> = historyEnabledFlow
        override val isBackgroundPlaybackEnabled: Flow<Boolean> = flowOf(false)
        override val isPipEnabled: Flow<Boolean> = flowOf(false)

        override suspend fun setHistoryEnabled(enabled: Boolean) {
            historyEnabledFlow.value = enabled
        }
        override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) {}
        override suspend fun setPipEnabled(enabled: Boolean) {}
    }

    private val summary = VideoSummary(
        key = ContentKey(1, "vid1"),
        title = "Test Video",
        canonicalUrl = "https://example.com/watch?v=vid1",
        channelKey = ContentKey(1, "chan1"),
        channelName = "Test Channel",
        channelAvatarUrl = null,
        thumbnailUrl = "https://example.com/thumb.jpg",
        durationSeconds = 100L,
        viewCount = 1000L,
        publishedTimestamp = 5000L
    )

    @Test
    fun observeRecentHistory_limits_items_at_dao_boundary() = runTest {
        val dao = FakeHistoryDao()
        val repo = DefaultHistoryRepository(
            dao,
            FakePlaybackPreferences(),
            StandardTestDispatcher(testScheduler)
        )
        repeat(3) { index ->
            dao.upsert(
                HistoryEntity(
                    serviceId = 1,
                    videoId = "v$index",
                    canonicalUrl = "https://example.test/v$index",
                    title = "Video $index",
                    channelId = null,
                    channelName = null,
                    thumbnailUrl = null,
                    durationSeconds = null,
                    playbackPositionMs = 0,
                    watchedTimestamp = index.toLong()
                )
            )
        }

        val result = repo.observeRecentHistory(2).first()

        assertEquals(listOf("v2", "v1"), result.map { it.key.nativeId })
    }

    @Test
    fun recordHistory_stores_entity_when_history_is_enabled() = runTest {
        val dao = FakeHistoryDao()
        val prefs = FakePlaybackPreferences(initialHistoryEnabled = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultHistoryRepository(dao, prefs, dispatcher)

        val result = repo.recordHistory(summary, positionMs = 50_000L, watchedTimestamp = 1000L)
        assertTrue(result is AppResult.Success)

        val item = (repo.getHistoryItem(ContentKey(1, "vid1")) as AppResult.Success).value
        assertNotNull(item)
        assertEquals("Test Video", item?.title)
        assertEquals(50_000L, item?.playbackPositionMs)
    }

    @Test
    fun progress_update_without_metadata_preserves_existing_thumbnail_and_channel() = runTest {
        val dao = FakeHistoryDao()
        val repo = DefaultHistoryRepository(
            dao,
            FakePlaybackPreferences(),
            StandardTestDispatcher(testScheduler)
        )
        repo.recordHistory(summary, positionMs = 1_000L, watchedTimestamp = 1_000L)

        repo.recordHistory(
            summary.copy(
                title = "Video",
                canonicalUrl = "https://hpre.test/watch?v=vid1",
                channelKey = null,
                channelName = null,
                thumbnailUrl = null,
                durationSeconds = null
            ),
            positionMs = 20_000L,
            watchedTimestamp = 2_000L
        )

        val item = (repo.getHistoryItem(summary.key) as AppResult.Success).value
        assertEquals(summary.thumbnailUrl, item?.thumbnailUrl)
        assertEquals(summary.channelName, item?.channelName)
        assertEquals(summary.title, item?.title)
        assertEquals(summary.canonicalUrl, item?.canonicalUrl)
        assertEquals(20_000L, item?.playbackPositionMs)
    }

    @Test
    fun concurrent_progress_update_cannot_overwrite_metadata_write() = runTest {
        val dao = FakeHistoryDao()
        val gate = CompletableDeferred<Unit>()
        dao.upsertGate = gate
        val repo = DefaultHistoryRepository(
            dao,
            FakePlaybackPreferences(),
            StandardTestDispatcher(testScheduler)
        )
        val sparse = summary.copy(
            title = "Video",
            channelKey = null,
            channelName = null,
            thumbnailUrl = null,
            durationSeconds = null
        )

        val metadataWrite = launch { repo.recordHistory(summary, 1_000L, 1_000L) }
        testScheduler.runCurrent()
        val progressWrite = launch { repo.recordHistory(sparse, 2_000L, 2_000L) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        metadataWrite.join()
        progressWrite.join()

        val item = (repo.getHistoryItem(summary.key) as AppResult.Success).value
        assertEquals(summary.thumbnailUrl, item?.thumbnailUrl)
        assertEquals(summary.channelName, item?.channelName)
    }

    @Test
    fun recordHistory_enforces_95_percent_completion_policy_under_equal_and_over_threshold() = runTest {
        val dao = FakeHistoryDao()
        val prefs = FakePlaybackPreferences(initialHistoryEnabled = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultHistoryRepository(dao, prefs, dispatcher)

        // Case 1: position < 95% (e.g. 94_000 ms out of 100s = 100_000 ms) -> persists position 94_000L
        repo.recordHistory(summary, positionMs = 94_000L, watchedTimestamp = 1000L)
        var item = (repo.getHistoryItem(ContentKey(1, "vid1")) as AppResult.Success).value
        assertEquals(94_000L, item?.playbackPositionMs)

        // Case 2: position == 95% (95_000 ms out of 100_000 ms) -> persists position 0L (no resume)
        repo.recordHistory(summary, positionMs = 95_000L, watchedTimestamp = 2000L)
        item = (repo.getHistoryItem(ContentKey(1, "vid1")) as AppResult.Success).value
        assertEquals(0L, item?.playbackPositionMs)
        assertEquals(2000L, item?.watchedTimestamp)

        // Case 3: position > 95% (99_000 ms out of 100_000 ms) -> persists position 0L (no resume)
        repo.recordHistory(summary, positionMs = 99_000L, watchedTimestamp = 3000L)
        item = (repo.getHistoryItem(ContentKey(1, "vid1")) as AppResult.Success).value
        assertEquals(0L, item?.playbackPositionMs)
        assertEquals(3000L, item?.watchedTimestamp)
    }

    @Test
    fun recordHistory_when_history_disabled_does_not_persist_under_any_threshold() = runTest {
        val dao = FakeHistoryDao()
        val prefs = FakePlaybackPreferences(initialHistoryEnabled = false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultHistoryRepository(dao, prefs, dispatcher)

        repo.recordHistory(summary, positionMs = 10_000L, watchedTimestamp = 1000L)
        repo.recordHistory(summary, positionMs = 95_000L, watchedTimestamp = 2000L)
        repo.recordHistory(summary, positionMs = 99_000L, watchedTimestamp = 3000L)

        val item = (repo.getHistoryItem(ContentKey(1, "vid1")) as AppResult.Success).value
        assertNull(item)
        assertTrue(repo.observeHistory().first().isEmpty())
    }

    @Test
    fun recordHistory_does_not_store_entity_when_history_is_disabled() = runTest {
        val dao = FakeHistoryDao()
        val prefs = FakePlaybackPreferences(initialHistoryEnabled = false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultHistoryRepository(dao, prefs, dispatcher)

        val result = repo.recordHistory(summary, positionMs = 50_000L, watchedTimestamp = 1000L)
        assertTrue(result is AppResult.Success)

        val item = (repo.getHistoryItem(ContentKey(1, "vid1")) as AppResult.Success).value
        assertNull(item)
        assertTrue(repo.observeHistory().first().isEmpty())
    }

    @Test
    fun delete_and_clear_history() = runTest {
        val dao = FakeHistoryDao()
        val prefs = FakePlaybackPreferences(initialHistoryEnabled = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultHistoryRepository(dao, prefs, dispatcher)

        repo.recordHistory(summary, positionMs = 50_000L, watchedTimestamp = 1000L)
        repo.deleteHistoryItem(ContentKey(1, "vid1"))

        val item = (repo.getHistoryItem(ContentKey(1, "vid1")) as AppResult.Success).value
        assertNull(item)

        repo.recordHistory(summary, positionMs = 50_000L, watchedTimestamp = 2000L)
        assertEquals(1, repo.observeHistory().first().size)

        repo.clearHistory()
        assertEquals(0, repo.observeHistory().first().size)
    }

    @Test
    fun io_exception_maps_to_failure_unknown() = runTest {
        val dao = FakeHistoryDao()
        val prefs = FakePlaybackPreferences(initialHistoryEnabled = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultHistoryRepository(dao, prefs, dispatcher)

        dao.shouldThrowIoException = true

        val recordRes = repo.recordHistory(summary, positionMs = 50_000L)
        assertTrue(recordRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (recordRes as AppResult.Failure).error)

        val getRes = repo.getHistoryItem(ContentKey(1, "vid1"))
        assertTrue(getRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (getRes as AppResult.Failure).error)

        val delRes = repo.deleteHistoryItem(ContentKey(1, "vid1"))
        assertTrue(delRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (delRes as AppResult.Failure).error)

        val clearRes = repo.clearHistory()
        assertTrue(clearRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (clearRes as AppResult.Failure).error)
    }

    @Test
    fun cancellation_exception_is_rethrown_and_not_swallowed_as_failure() = runTest {
        val dao = FakeHistoryDao()
        val prefs = FakePlaybackPreferences(initialHistoryEnabled = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultHistoryRepository(dao, prefs, dispatcher)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        dao.upsertStarted = started
        dao.upsertGate = gate

        val job = launch(dispatcher) {
            repo.recordHistory(summary, positionMs = 50_000L)
        }

        // Advance dispatcher until DAO upsert actually starts and reaches blocking gate
        testScheduler.runCurrent()
        assertTrue("Expected DAO upsert to have started", started.isCompleted)

        // Cancel caller coroutine while DAO is blocked inside upsert
        job.cancel(kotlinx.coroutines.CancellationException("Explicit coroutine cancellation"))
        testScheduler.runCurrent()
        job.join()

        assertTrue(job.isCancelled)
        // Storage should have no write because cancellation occurred before gate was released and write completed
        assertTrue("Storage should not contain item on cancellation", dao.storage.isEmpty())
    }
}
