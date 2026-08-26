package com.hpre.app.ui.home

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.HomeRecommendationSource
import com.hpre.app.repository.LocalSearchHistoryItem
import com.hpre.app.repository.RecommendationRepository
import com.hpre.app.repository.SearchHistoryRepository
import com.hpre.app.repository.WatchHistoryItem
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeTopicFeedSource : TopicFeedSource {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var handler: suspend (String, Boolean) -> AppResult<List<VideoSummary>> = { _, _ ->
            AppResult.Success(emptyList())
        }

        override suspend fun videos(
            query: String,
            forceRefresh: Boolean
        ): AppResult<List<VideoSummary>> {
            calls += query to forceRefresh
            return handler(query, forceRefresh)
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

    private fun summary(id: String) = VideoSummary(
        key = ContentKey(0, id),
        title = "Title $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 120,
        viewCount = 1000,
        publishedTimestamp = 10000L
    )

    private fun recommendations(catalog: CatalogRepository) = RecommendationRepository(
        catalog,
        object : SearchHistoryRepository {
            override fun observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>> = flowOf(emptyList())
            override suspend fun recordQuery(rawQuery: String, timestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteQuery(rawQuery: String) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        },
        object : HistoryRepository {
            override fun observeHistory(): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
            override suspend fun getHistoryItem(key: ContentKey) = AppResult.Success(null)
            override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
    )

    @Test
    fun load_emits_loading_then_content() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            trendingResponse = AppResult.Success(listOf(summary("1"), summary("2")))
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(recommendations(repository), FakeTopicFeedSource())

        // Initially loading
        assertTrue(viewModel.uiState.value is HomeUiState.Loading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected Content state, got $state", state is HomeUiState.Content)
        assertEquals(2, (state as HomeUiState.Content).videos.size)
    }

    @Test
    fun load_emits_empty_when_list_is_empty() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            trendingResponse = AppResult.Success(emptyList())
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(recommendations(repository), FakeTopicFeedSource())

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HomeUiState.Empty)
    }

    @Test
    fun load_emits_error_when_service_fails() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            trendingResponse = AppResult.Failure(AppError.NetworkError)
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(recommendations(repository), FakeTopicFeedSource())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Error)
        assertEquals(AppError.NetworkError, (state as HomeUiState.Error).error)
    }

    @Test
    fun retry_reloads_trending_with_force_refresh() = runTest(testDispatcher) {
        var call = 0
        val fakeService = FakeVideoService()
        fakeService.trendingHandler = {
            call++
            if (call == 1) {
                AppResult.Failure(AppError.NetworkError)
            } else {
                AppResult.Success(listOf(summary("recovered")))
            }
        }
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(recommendations(repository), FakeTopicFeedSource())

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is HomeUiState.Error)

        viewModel.retry()
        assertTrue(viewModel.uiState.value is HomeUiState.Loading)

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Content)
        assertEquals("recovered", (state as HomeUiState.Content).videos.first().key.nativeId)
    }

    @Test
    fun delayed_stale_refresh_does_not_overwrite_newer_completion() = runTest(testDispatcher) {
        var callCount = 0
        val source = HomeRecommendationSource {
            callCount++
            val currentCall = callCount
            if (currentCall == 1) {
                withContext(NonCancellable) {
                    kotlinx.coroutines.delay(1000)
                    AppResult.Success(listOf(summary("stale_1")))
                }
            } else {
                // Second call completes faster
                kotlinx.coroutines.delay(200)
                AppResult.Success(listOf(summary("fresh_2")))
            }
        }
        val viewModel = HomeViewModel(source, FakeTopicFeedSource())

        // Advance 10ms to let call 1 hit delay
        testDispatcher.scheduler.advanceTimeBy(10)

        // Trigger retry/refresh (call 2 with forceRefresh = true)
        viewModel.retry()

        // Advance 250ms: call 2 completes
        testDispatcher.scheduler.advanceTimeBy(250)
        val state1 = viewModel.uiState.value
        assertTrue(state1 is HomeUiState.Content)
        assertEquals("fresh_2", (state1 as HomeUiState.Content).videos.first().key.nativeId)

        // Advance past call 1 completion (total > 1000ms from start)
        testDispatcher.scheduler.advanceTimeBy(1000)
        advanceUntilIdle()

        // uiState should STILL be fresh_2, not overwritten by stale_1
        val state2 = viewModel.uiState.value
        assertTrue(state2 is HomeUiState.Content)
        assertEquals("fresh_2", (state2 as HomeUiState.Content).videos.first().key.nativeId)
    }

    @Test
    fun unexpected_exception_from_repository_maps_to_safe_error_and_does_not_get_stuck_in_loading() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.trendingHandler = {
            throw RuntimeException("Unchecked unexpected repository crash")
        }

        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(recommendations(repository), FakeTopicFeedSource())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected HomeUiState.Error, got $state", state is HomeUiState.Error)
        assertEquals(AppError.Unknown, (state as HomeUiState.Error).error)
    }

    @Test
    fun selecting_topic_updates_chip_and_loads_topic_videos() = runTest(testDispatcher) {
        val topicSource = FakeTopicFeedSource().apply {
            handler = { _, _ -> AppResult.Success(listOf(summary("music"))) }
        }
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { AppResult.Success(listOf(summary("all"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceUntilIdle()

        assertEquals(1, viewModel.chipsState.value.selectedIndex)
        assertEquals(listOf("âm nhạc" to false), topicSource.calls)
        assertEquals(
            "music",
            (viewModel.uiState.value as HomeUiState.Content).videos.single().key.nativeId
        )
    }

    @Test
    fun retry_refreshes_current_topic_and_empty_keeps_selection() = runTest(testDispatcher) {
        val topicSource = FakeTopicFeedSource()
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { AppResult.Success(listOf(summary("all"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is HomeUiState.Empty)
        assertEquals(1, viewModel.chipsState.value.selectedIndex)

        viewModel.retry()
        advanceUntilIdle()
        assertEquals(listOf("âm nhạc" to false, "âm nhạc" to true), topicSource.calls)
    }

    @Test
    fun selecting_all_after_topic_loads_recommendations_again() = runTest(testDispatcher) {
        var recommendationCalls = 0
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource {
                recommendationCalls++
                AppResult.Success(listOf(summary("all_$recommendationCalls")))
            },
            topicFeedSource = FakeTopicFeedSource()
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceUntilIdle()
        viewModel.selectChip(0)
        advanceUntilIdle()

        assertEquals(2, recommendationCalls)
        assertEquals(0, viewModel.chipsState.value.selectedIndex)
    }

    @Test
    fun stale_topic_response_does_not_overwrite_newer_chip() = runTest(testDispatcher) {
        val topicSource = FakeTopicFeedSource().apply {
            handler = { query, _ ->
                if (query == "âm nhạc") {
                    withContext(NonCancellable) {
                        kotlinx.coroutines.delay(1_000)
                        AppResult.Success(listOf(summary("stale")))
                    }
                } else {
                    kotlinx.coroutines.delay(200)
                    AppResult.Success(listOf(summary("fresh")))
                }
            }
        }
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { AppResult.Success(listOf(summary("all"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceTimeBy(10)
        viewModel.selectChip(2)
        advanceTimeBy(250)

        assertEquals(
            "fresh",
            (viewModel.uiState.value as HomeUiState.Content).videos.single().key.nativeId
        )
        advanceUntilIdle()
        assertEquals(
            "fresh",
            (viewModel.uiState.value as HomeUiState.Content).videos.single().key.nativeId
        )
    }
}
