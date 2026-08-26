package com.hpre.app.ui.library

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.repository.LocalPlaylistWithEntries
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.repository.SubscriptionRepository
import com.hpre.app.repository.WatchHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeHistoryRepo : HistoryRepository {
        val listFlow = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
        override fun observeHistory(): Flow<List<WatchHistoryItem>> = listFlow
        override suspend fun getHistoryItem(key: ContentKey): AppResult<WatchHistoryItem?> =
            AppResult.Success(listFlow.value.firstOrNull { it.key == key })
        override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long): AppResult<Unit> =
            AppResult.Success(Unit)
        override suspend fun deleteHistoryItem(key: ContentKey): AppResult<Unit> {
            listFlow.value = listFlow.value.filterNot { it.key == key }
            return AppResult.Success(Unit)
        }
        override suspend fun clearHistory(): AppResult<Unit> {
            listFlow.value = emptyList()
            return AppResult.Success(Unit)
        }
    }

    private class FakeSubscriptionRepo : SubscriptionRepository {
        val subFlow = MutableStateFlow<List<LocalSubscription>>(emptyList())
        override fun observeSubscriptions(): Flow<List<LocalSubscription>> = subFlow
        override fun observeIsSubscribed(key: ContentKey): Flow<Boolean> =
            MutableStateFlow(subFlow.value.any { it.channelKey == key })
        override suspend fun isSubscribed(key: ContentKey): AppResult<Boolean> =
            AppResult.Success(subFlow.value.any { it.channelKey == key })
        override suspend fun subscribe(channel: com.hpre.app.model.Channel, subscribedTimestamp: Long): AppResult<Unit> {
            subFlow.value = subFlow.value + LocalSubscription(
                channelKey = channel.key,
                canonicalUrl = channel.canonicalUrl,
                name = channel.name,
                avatarUrl = channel.avatarUrl,
                subscribedTimestamp = subscribedTimestamp
            )
            return AppResult.Success(Unit)
        }
        override suspend fun unsubscribe(key: ContentKey): AppResult<Unit> {
            subFlow.value = subFlow.value.filterNot { it.channelKey == key }
            return AppResult.Success(Unit)
        }
        override suspend fun clearSubscriptions(): AppResult<Unit> {
            subFlow.value = emptyList()
            return AppResult.Success(Unit)
        }
    }

    private class FakePlaylistRepo : PlaylistRepository {
        val playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
        val detailFlows = mutableMapOf<Long, MutableStateFlow<LocalPlaylistWithEntries?>>()
        private var nextId = 1L

        override fun observePlaylists(): Flow<List<LocalPlaylist>> = playlistsFlow
        override fun observePlaylistWithEntries(playlistId: Long): Flow<LocalPlaylistWithEntries?> {
            return detailFlows.getOrPut(playlistId) {
                val p = playlistsFlow.value.firstOrNull { it.playlistId == playlistId }
                MutableStateFlow(p?.let { LocalPlaylistWithEntries(it, emptyList()) })
            }
        }
        override suspend fun getPlaylist(playlistId: Long): AppResult<LocalPlaylist?> =
            AppResult.Success(playlistsFlow.value.firstOrNull { it.playlistId == playlistId })

        override suspend fun createPlaylist(title: String, timestamp: Long): AppResult<Long> {
            val id = nextId++
            val newP = LocalPlaylist(id, title, timestamp, timestamp, 0)
            playlistsFlow.value = playlistsFlow.value + newP
            detailFlows[id] = MutableStateFlow(LocalPlaylistWithEntries(newP, emptyList()))
            return AppResult.Success(id)
        }

        override suspend fun renamePlaylist(playlistId: Long, newTitle: String, timestamp: Long): AppResult<Unit> {
            playlistsFlow.value = playlistsFlow.value.map {
                if (it.playlistId == playlistId) it.copy(title = newTitle, updatedTimestamp = timestamp) else it
            }
            detailFlows[playlistId]?.let { flow ->
                flow.value?.let { current ->
                    flow.value = current.copy(playlist = current.playlist.copy(title = newTitle, updatedTimestamp = timestamp))
                }
            }
            return AppResult.Success(Unit)
        }

        override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> {
            playlistsFlow.value = playlistsFlow.value.filterNot { it.playlistId == playlistId }
            detailFlows.remove(playlistId)
            return AppResult.Success(Unit)
        }

        override suspend fun addEntry(playlistId: Long, video: VideoSummary, addedTimestamp: Long): AppResult<Unit> {
            detailFlows[playlistId]?.let { flow ->
                val current = flow.value ?: return@let
                val newEntry = com.hpre.app.repository.LocalPlaylistEntry(
                    playlistId = playlistId,
                    videoKey = video.key,
                    canonicalUrl = video.canonicalUrl,
                    title = video.title,
                    channelKey = video.channelKey,
                    channelName = video.channelName,
                    thumbnailUrl = video.thumbnailUrl,
                    durationSeconds = video.durationSeconds,
                    addedTimestamp = addedTimestamp,
                    sortOrder = current.entries.size
                )
                val newEntries = current.entries + newEntry
                val updatedPlaylist = current.playlist.copy(entryCount = newEntries.size, updatedTimestamp = addedTimestamp)
                flow.value = LocalPlaylistWithEntries(updatedPlaylist, newEntries)
                playlistsFlow.value = playlistsFlow.value.map {
                    if (it.playlistId == playlistId) updatedPlaylist else it
                }
            }
            return AppResult.Success(Unit)
        }

        override suspend fun removeEntry(playlistId: Long, videoKey: ContentKey, updatedTimestamp: Long): AppResult<Unit> {
            detailFlows[playlistId]?.let { flow ->
                val current = flow.value ?: return@let
                val newEntries = current.entries.filterNot { it.videoKey == videoKey }.mapIndexed { index, e ->
                    e.copy(sortOrder = index)
                }
                val updatedPlaylist = current.playlist.copy(entryCount = newEntries.size, updatedTimestamp = updatedTimestamp)
                flow.value = LocalPlaylistWithEntries(updatedPlaylist, newEntries)
                playlistsFlow.value = playlistsFlow.value.map {
                    if (it.playlistId == playlistId) updatedPlaylist else it
                }
            }
            return AppResult.Success(Unit)
        }

        override suspend fun reorderEntries(playlistId: Long, fromIndex: Int, toIndex: Int, updatedTimestamp: Long): AppResult<Unit> {
            detailFlows[playlistId]?.let { flow ->
                val current = flow.value ?: return@let
                val mutable = current.entries.toMutableList()
                val item = mutable.removeAt(fromIndex)
                mutable.add(toIndex, item)
                val reindexed = mutable.mapIndexed { idx, e -> e.copy(sortOrder = idx) }
                val updatedPlaylist = current.playlist.copy(updatedTimestamp = updatedTimestamp)
                flow.value = LocalPlaylistWithEntries(updatedPlaylist, reindexed)
            }
            return AppResult.Success(Unit)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun clearHistory_and_deleteHistoryItem_updates_state() = runTest {
        val historyRepo = FakeHistoryRepo()
        val subRepo = FakeSubscriptionRepo()
        val playlistRepo = FakePlaylistRepo()
        val viewModel = LibraryViewModel(historyRepo, subRepo, playlistRepo)

        val item1 = WatchHistoryItem(
            key = ContentKey(1, "v1"),
            canonicalUrl = "https://example.com/v1",
            title = "Video 1",
            channelKey = null,
            channelName = null,
            thumbnailUrl = null,
            durationSeconds = 120L,
            playbackPositionMs = 50000L,
            watchedTimestamp = 1000L
        )
        val item2 = WatchHistoryItem(
            key = ContentKey(1, "v2"),
            canonicalUrl = "https://example.com/v2",
            title = "Video 2",
            channelKey = null,
            channelName = null,
            thumbnailUrl = null,
            durationSeconds = 180L,
            playbackPositionMs = 20000L,
            watchedTimestamp = 2000L
        )
        historyRepo.listFlow.value = listOf(item2, item1)

        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.history.value.size)

        viewModel.deleteHistoryItem(ContentKey(1, "v1"))
        testScheduler.advanceUntilIdle()
        assertEquals(1, viewModel.history.value.size)
        assertEquals("v2", viewModel.history.value.first().key.nativeId)

        viewModel.clearHistory()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.history.value.isEmpty())
    }

    @Test
    fun subscription_unsubscribe_removes_item() = runTest {
        val historyRepo = FakeHistoryRepo()
        val subRepo = FakeSubscriptionRepo()
        val playlistRepo = FakePlaylistRepo()
        val viewModel = LibraryViewModel(historyRepo, subRepo, playlistRepo)

        subRepo.subFlow.value = listOf(
            LocalSubscription(ContentKey(1, "c1"), "https://example.com/c1", "Channel 1", null, 1000L)
        )
        testScheduler.advanceUntilIdle()
        assertEquals(1, viewModel.subscriptions.value.size)

        viewModel.unsubscribe(ContentKey(1, "c1"))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.subscriptions.value.isEmpty())
    }

    @Test
    fun playlist_crud_and_reorder_works_correctly() = runTest {
        val historyRepo = FakeHistoryRepo()
        val subRepo = FakeSubscriptionRepo()
        val playlistRepo = FakePlaylistRepo()
        val viewModel = LibraryViewModel(historyRepo, subRepo, playlistRepo)

        viewModel.createPlaylist("My Favorite")
        testScheduler.advanceUntilIdle()
        assertEquals(1, viewModel.playlists.value.size)
        val pId = viewModel.playlists.value.first().playlistId
        assertEquals("My Favorite", viewModel.playlists.value.first().title)

        viewModel.renamePlaylist(pId, "Favorites 2026")
        testScheduler.advanceUntilIdle()
        assertEquals("Favorites 2026", viewModel.playlists.value.first().title)

        val v1 = VideoSummary(ContentKey(1, "v1"), "V1", "https://example.com/v1", null, null, null, null, 100L, 10L, 10L)
        val v2 = VideoSummary(ContentKey(1, "v2"), "V2", "https://example.com/v2", null, null, null, null, 200L, 20L, 20L)
        viewModel.addVideoToPlaylist(pId, v1)
        viewModel.addVideoToPlaylist(pId, v2)
        testScheduler.advanceUntilIdle()

        viewModel.loadPlaylistDetail(pId)
        testScheduler.advanceUntilIdle()
        val detail = viewModel.playlistDetail.value
        assertEquals(2, detail?.entries?.size)
        assertEquals("v1", detail?.entries?.get(0)?.videoKey?.nativeId)
        assertEquals("v2", detail?.entries?.get(1)?.videoKey?.nativeId)

        viewModel.reorderPlaylistEntries(pId, fromIndex = 0, toIndex = 1)
        testScheduler.advanceUntilIdle()
        val reordered = viewModel.playlistDetail.value
        assertEquals("v2", reordered?.entries?.get(0)?.videoKey?.nativeId)
        assertEquals("v1", reordered?.entries?.get(1)?.videoKey?.nativeId)

        viewModel.removeVideoFromPlaylist(pId, ContentKey(1, "v2"))
        testScheduler.advanceUntilIdle()
        val afterRemove = viewModel.playlistDetail.value
        assertEquals(1, afterRemove?.entries?.size)
        assertEquals("v1", afterRemove?.entries?.get(0)?.videoKey?.nativeId)

        viewModel.deletePlaylist(pId)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.playlists.value.isEmpty())
    }
}
