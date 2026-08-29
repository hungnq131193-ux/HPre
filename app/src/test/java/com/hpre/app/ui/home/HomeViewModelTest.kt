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
import com.hpre.app.repository.RecommendationRequest
import com.hpre.app.repository.SearchHistoryRepository
import com.hpre.app.repository.WatchHistoryItem
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeTopicFeedSource : TopicFeedSource {
        val calls = mutableListOf<Pair<String, RecommendationRequest>>()
        var handler: suspend (String, RecommendationRequest) -> AppResult<List<VideoSummary>> = { _, _ ->
            AppResult.Success(emptyList())
        }

        override suspend fun videos(
            query: String,
            request: RecommendationRequest
        ): AppResult<List<VideoSummary>> {
            calls += query to request
            return handler(query, request)
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
        assertEquals(2, (state as HomeUiState.Content).content.videos.size)
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
        // Retrying from an error screen has no content to preserve, so Loading is still correct here.
        assertTrue(viewModel.uiState.value is HomeUiState.Loading)

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Content)
        assertEquals("recovered", (state as HomeUiState.Content).content.videos.first().key.nativeId)
    }

    @Test
    fun delayed_stale_refresh_does_not_overwrite_newer_completion() = runTest(testDispatcher) {
        var callCount = 0
        val source = HomeRecommendationSource { _ ->
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
        assertEquals("fresh_2", (state1 as HomeUiState.Content).content.videos.first().key.nativeId)

        // Advance past call 1 completion (total > 1000ms from start)
        testDispatcher.scheduler.advanceTimeBy(1000)
        advanceUntilIdle()

        // uiState should STILL be fresh_2, not overwritten by stale_1
        val state2 = viewModel.uiState.value
        assertTrue(state2 is HomeUiState.Content)
        assertEquals("fresh_2", (state2 as HomeUiState.Content).content.videos.first().key.nativeId)
    }

    @Test
    fun refresh_keeps_A_visible_and_excludes_all_A_keys() = runTest(testDispatcher) {
        var callCount = 0
        var capturedRequest: RecommendationRequest? = null
        val bDeferred = CompletableDeferred<AppResult<List<VideoSummary>>>()

        val source = HomeRecommendationSource { req ->
            callCount++
            if (callCount == 1) {
                AppResult.Success(List(100) { summary("a_$it") })
            } else {
                capturedRequest = req
                bDeferred.await()
            }
        }
        val viewModel = HomeViewModel(source, FakeTopicFeedSource())
        advanceUntilIdle()

        val stateA = viewModel.uiState.value
        assertTrue(stateA is HomeUiState.Content)
        val contentA = (stateA as HomeUiState.Content).content
        assertEquals(100, contentA.videos.size)
        assertEquals("a_0", contentA.videos.first().key.nativeId)
        assertEquals(false, contentA.isRefreshing)

        // Trigger refresh
        viewModel.refresh()
        runCurrent()

        val stateRefreshing = viewModel.uiState.value
        assertTrue(stateRefreshing is HomeUiState.Content)
        val contentRefreshing = (stateRefreshing as HomeUiState.Content).content
        assertEquals(100, contentRefreshing.videos.size)
        assertEquals("a_0", contentRefreshing.videos.first().key.nativeId)
        assertTrue(contentRefreshing.isRefreshing)
        assertEquals(null, contentRefreshing.refreshError)

        assertNotNull(capturedRequest)
        assertEquals(true, capturedRequest?.forceRefresh)
        val expectedExcluded = List(100) { ContentKey(0, "a_$it") }.toSet()
        assertEquals(expectedExcluded, capturedRequest?.excludedKeys)

        // Complete B
        bDeferred.complete(AppResult.Success(List(17) { summary("b_$it") }))
        advanceUntilIdle()

        val stateB = viewModel.uiState.value
        assertTrue(stateB is HomeUiState.Content)
        val contentB = (stateB as HomeUiState.Content).content
        assertEquals(17, contentB.videos.size)
        assertEquals("b_0", contentB.videos.first().key.nativeId)
        assertEquals(false, contentB.isRefreshing)
    }

    @Test
    fun refresh_repeated_A_to_B_to_A_eligibility() = runTest(testDispatcher) {
        val requests = mutableListOf<RecommendationRequest>()
        var callCount = 0
        val source = HomeRecommendationSource { req ->
            callCount++
            requests += req
            when (callCount) {
                1 -> AppResult.Success(List(50) { summary("a_$it") })
                2 -> AppResult.Success(List(30) { summary("b_$it") })
                else -> AppResult.Success(List(50) { summary("a_$it") }) // A is eligible again
            }
        }
        val viewModel = HomeViewModel(source, FakeTopicFeedSource())
        advanceUntilIdle()

        // Refresh 1: A -> B (excludes A)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertEquals(List(50) { ContentKey(0, "a_$it") }.toSet(), requests[1].excludedKeys)

        // Refresh 2: B -> A (excludes B, A is not excluded)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(3, requests.size)
        assertEquals(List(30) { ContentKey(0, "b_$it") }.toSet(), requests[2].excludedKeys)
        val contentFinal = (viewModel.uiState.value as HomeUiState.Content).content
        assertEquals(50, contentFinal.videos.size)
        assertEquals("a_0", contentFinal.videos.first().key.nativeId)
    }

    @Test
    fun refresh_failure_preserves_videos_and_sets_refreshError() = runTest(testDispatcher) {
        var callCount = 0
        val source = HomeRecommendationSource { _ ->
            callCount++
            if (callCount == 1) {
                AppResult.Success(listOf(summary("initial_1"), summary("initial_2")))
            } else {
                AppResult.Failure(AppError.NetworkError)
            }
        }
        val viewModel = HomeViewModel(source, FakeTopicFeedSource())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HomeUiState.Content)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected Content state on refresh failure, got $state", state is HomeUiState.Content)
        val content = (state as HomeUiState.Content).content
        assertEquals(2, content.videos.size)
        assertEquals(false, content.isRefreshing)
        assertEquals(AppError.NetworkError, content.refreshError)
    }

    @Test
    fun refresh_repeated_A_to_B_to_A_eligibility_for_topic_chip() = runTest(testDispatcher) {
        val requests = mutableListOf<RecommendationRequest>()
        var callCount = 0
        val topicSource = FakeTopicFeedSource().apply {
            handler = { _, req ->
                callCount++
                requests += req
                when (callCount) {
                    1 -> AppResult.Success(List(50) { summary("music_a_$it") })
                    2 -> AppResult.Success(List(25) { summary("music_b_$it") })
                    else -> AppResult.Success(List(50) { summary("music_a_$it") }) // A is eligible again
                }
            }
        }
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { AppResult.Success(emptyList()) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        // Select topic chip (initial load)
        viewModel.selectChip(1)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals(emptySet<ContentKey>(), requests[0].excludedKeys)

        // Refresh 1: A -> B
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertEquals(List(50) { ContentKey(0, "music_a_$it") }.toSet(), requests[1].excludedKeys)
        assertEquals(25, (viewModel.uiState.value as HomeUiState.Content).content.videos.size)

        // Refresh 2: B -> A
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(3, requests.size)
        assertEquals(List(25) { ContentKey(0, "music_b_$it") }.toSet(), requests[2].excludedKeys)
        assertEquals(50, (viewModel.uiState.value as HomeUiState.Content).content.videos.size)
    }

    @Test
    fun refresh_clean_empty_transitions_to_Empty_success_without_error() = runTest(testDispatcher) {
        var callCount = 0
        val source = HomeRecommendationSource { _ ->
            callCount++
            if (callCount == 1) {
                AppResult.Success(listOf(summary("init_1"), summary("init_2")))
            } else {
                AppResult.Success(emptyList()) // Clean empty on refresh
            }
        }
        val viewModel = HomeViewModel(source, FakeTopicFeedSource())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is HomeUiState.Content)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected HomeUiState.Empty on clean empty refresh, got $state", state is HomeUiState.Empty)
    }

    @Test
    fun stale_refresh_returns_under_NonCancellable_and_does_not_overwrite_newer_refresh() = runTest(testDispatcher) {
        var callCount = 0
        val source = HomeRecommendationSource { _ ->
            callCount++
            val cur = callCount
            if (cur == 2) {
                // First refresh call: slow, runs in NonCancellable to ensure it executes to completion after cancellation
                withContext(NonCancellable) {
                    kotlinx.coroutines.delay(800)
                    AppResult.Success(listOf(summary("stale_refresh_result")))
                }
            } else if (cur == 3) {
                // Second refresh call: quick
                kotlinx.coroutines.delay(100)
                AppResult.Success(listOf(summary("fast_refresh_result")))
            } else {
                AppResult.Success(listOf(summary("init")))
            }
        }
        val viewModel = HomeViewModel(source, FakeTopicFeedSource())
        advanceUntilIdle()

        // Start refresh 1
        viewModel.refresh()
        testDispatcher.scheduler.advanceTimeBy(10)

        // Start refresh 2 (cancelling refresh 1 job)
        viewModel.refresh()
        testDispatcher.scheduler.advanceTimeBy(150)

        // Fast refresh 2 finishes
        val contentAfter2 = (viewModel.uiState.value as HomeUiState.Content).content
        assertEquals("fast_refresh_result", contentAfter2.videos.single().key.nativeId)

        // Stale refresh 1 completes late under NonCancellable
        testDispatcher.scheduler.advanceTimeBy(1000)
        advanceUntilIdle()

        val contentFinal = (viewModel.uiState.value as HomeUiState.Content).content
        assertEquals("fast_refresh_result", contentFinal.videos.single().key.nativeId)
    }

    @Test
    fun refresh_pending_then_selectChip_ignores_stale_refresh_failure() = runTest(testDispatcher) {
        val slowRefreshSource = HomeRecommendationSource { req ->
            if (req.forceRefresh) {
                withContext(NonCancellable) {
                    kotlinx.coroutines.delay(500)
                    AppResult.Failure(AppError.NetworkError)
                }
            } else {
                AppResult.Success(listOf(summary("all_video")))
            }
        }
        val topicSource = FakeTopicFeedSource().apply {
            handler = { _, _ -> AppResult.Success(listOf(summary("topic_video"))) }
        }
        val viewModel = HomeViewModel(slowRefreshSource, topicSource)
        advanceUntilIdle()

        // Start refresh on All chip
        viewModel.refresh()
        testDispatcher.scheduler.advanceTimeBy(10)

        // User switches to Topic chip while refresh is pending
        viewModel.selectChip(1)
        advanceUntilIdle()

        // State must reflect topic content, not corrupted by late refresh failure
        val state = viewModel.uiState.value
        assertTrue("Expected Content on topic selection, got $state", state is HomeUiState.Content)
        assertEquals("topic_video", (state as HomeUiState.Content).content.videos.single().key.nativeId)
        assertEquals(null, (state as HomeUiState.Content).content.refreshError)
    }

    /**
     * Switching chips must not blank the feed.
     *
     * The previous list stays composed with [HomeContent.isLoadingSelection] set, which the screen
     * renders as a thin inline bar rather than replacing everything with a spinner.
     */
    @Test
    fun selectChip_keeps_previous_videos_visible_while_loading() = runTest(testDispatcher) {
        val topicSource = FakeTopicFeedSource().apply {
            handler = { _, _ ->
                kotlinx.coroutines.delay(500)
                AppResult.Success(listOf(summary("topic_video")))
            }
        }
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { _ -> AppResult.Success(listOf(summary("all_video"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is HomeUiState.Content)

        viewModel.selectChip(1)
        runCurrent()

        val loading = viewModel.uiState.value
        assertTrue("Chip switch must not drop to Loading, got $loading", loading is HomeUiState.Content)
        val loadingContent = (loading as HomeUiState.Content).content
        assertEquals("all_video", loadingContent.videos.single().key.nativeId)
        assertTrue("Expected isLoadingSelection while the new chip loads", loadingContent.isLoadingSelection)
        assertEquals(false, loadingContent.isRefreshing)

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Content)
        val content = (state as HomeUiState.Content).content
        assertEquals("topic_video", content.videos.single().key.nativeId)
        assertEquals(false, content.isLoadingSelection)
    }

    /** First ever load has nothing to keep on screen, so full-screen Loading is still correct. */
    @Test
    fun first_load_with_no_content_still_uses_loading_state() = runTest(testDispatcher) {
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { _ ->
                kotlinx.coroutines.delay(500)
                AppResult.Success(listOf(summary("all_video")))
            },
            topicFeedSource = FakeTopicFeedSource()
        )

        assertTrue(viewModel.uiState.value is HomeUiState.Loading)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is HomeUiState.Content)
    }

    /**
     * Returning to an already-loaded chip renders from cache with no request and no loading state.
     */
    @Test
    fun returning_to_cached_chip_renders_immediately_without_refetching() = runTest(testDispatcher) {
        var allCalls = 0
        val topicSource = FakeTopicFeedSource().apply {
            handler = { _, _ -> AppResult.Success(listOf(summary("music"))) }
        }
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { _ ->
                allCalls++
                AppResult.Success(listOf(summary("all_$allCalls")))
            },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()
        assertEquals(1, allCalls)

        viewModel.selectChip(1)
        advanceUntilIdle()
        assertEquals("music", (viewModel.uiState.value as HomeUiState.Content).content.videos.single().key.nativeId)

        // Back to "Tất cả": served from cache synchronously, no second repository call.
        viewModel.selectChip(0)
        runCurrent()
        val state = viewModel.uiState.value
        assertTrue("Cached chip must render Content immediately, got $state", state is HomeUiState.Content)
        val content = (state as HomeUiState.Content).content
        assertEquals("all_1", content.videos.single().key.nativeId)
        assertEquals("cached content must not show a loading indicator", false, content.isLoadingSelection)

        advanceUntilIdle()
        assertEquals("cache hit must not trigger a request", 1, allCalls)
    }

    /** An explicit retry bypasses the cache: the user asked for new content. */
    @Test
    fun retry_bypasses_chip_cache() = runTest(testDispatcher) {
        var allCalls = 0
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { _ ->
                allCalls++
                AppResult.Success(listOf(summary("all_$allCalls")))
            },
            topicFeedSource = FakeTopicFeedSource()
        )
        advanceUntilIdle()
        assertEquals(1, allCalls)

        viewModel.retry()
        advanceUntilIdle()
        assertEquals(2, allCalls)
        assertEquals(
            "all_2",
            (viewModel.uiState.value as HomeUiState.Content).content.videos.single().key.nativeId
        )
    }

    /**
     * A failed chip switch keeps the visible list and reports the error inline.
     *
     * Wiping working content for an error screen is the worst outcome: the user loses what they had
     * and gets no way back except a retry.
     */
    @Test
    fun failed_chip_switch_keeps_visible_content_and_reports_error_inline() = runTest(testDispatcher) {
        val topicSource = FakeTopicFeedSource().apply {
            handler = { _, _ -> AppResult.Failure(AppError.NetworkError) }
        }
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { _ -> AppResult.Success(listOf(summary("all_video"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected Content with inline error, got $state", state is HomeUiState.Content)
        val content = (state as HomeUiState.Content).content
        assertEquals("all_video", content.videos.single().key.nativeId)
        assertEquals(AppError.NetworkError, content.refreshError)
        assertEquals(false, content.isLoadingSelection)
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
            repository = HomeRecommendationSource { _ -> AppResult.Success(listOf(summary("all"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceUntilIdle()

        assertEquals(1, viewModel.chipsState.value.selectedIndex)
        assertEquals(1, topicSource.calls.size)
        assertEquals("âm nhạc", topicSource.calls.first().first)
        assertEquals(false, topicSource.calls.first().second.forceRefresh)
        assertEquals(
            "music",
            (viewModel.uiState.value as HomeUiState.Content).content.videos.single().key.nativeId
        )
    }

    @Test
    fun retry_refreshes_current_topic_and_empty_keeps_selection() = runTest(testDispatcher) {
        val topicSource = FakeTopicFeedSource()
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { _ -> AppResult.Success(listOf(summary("all"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is HomeUiState.Empty)
        assertEquals(1, viewModel.chipsState.value.selectedIndex)

        viewModel.retry()
        advanceUntilIdle()
        assertEquals(2, topicSource.calls.size)
        assertEquals("âm nhạc", topicSource.calls[0].first)
        assertEquals(false, topicSource.calls[0].second.forceRefresh)
        assertEquals("âm nhạc", topicSource.calls[1].first)
        assertEquals(true, topicSource.calls[1].second.forceRefresh)
    }

    /**
     * Returning to "Tất cả" restores its feed from cache instead of re-requesting it.
     *
     * This previously asserted a second repository call. That refetch is exactly the cost the chip
     * cache removes: the round trip produced a spinner and a blank feed for content already fetched
     * seconds earlier. Selection still has to switch back, which is what matters to the user.
     */
    @Test
    fun selecting_all_after_topic_restores_cached_recommendations() = runTest(testDispatcher) {
        var recommendationCalls = 0
        val viewModel = HomeViewModel(
            repository = HomeRecommendationSource { _ ->
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

        assertEquals(1, recommendationCalls)
        assertEquals(0, viewModel.chipsState.value.selectedIndex)
        assertEquals(
            "all_1",
            (viewModel.uiState.value as HomeUiState.Content).content.videos.single().key.nativeId
        )
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
            repository = HomeRecommendationSource { _ -> AppResult.Success(listOf(summary("all"))) },
            topicFeedSource = topicSource
        )
        advanceUntilIdle()

        viewModel.selectChip(1)
        advanceTimeBy(10)
        viewModel.selectChip(2)
        advanceTimeBy(250)

        assertEquals(
            "fresh",
            (viewModel.uiState.value as HomeUiState.Content).content.videos.single().key.nativeId
        )
        advanceUntilIdle()
        assertEquals(
            "fresh",
            (viewModel.uiState.value as HomeUiState.Content).content.videos.single().key.nativeId
        )
    }
}
