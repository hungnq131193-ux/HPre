package com.hpre.app.ui.search

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.LocalSearchHistoryItem
import com.hpre.app.repository.SearchHistoryRepository
import com.hpre.app.settings.PlaybackPreferences
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSearchHistoryRepository(
        initial: List<LocalSearchHistoryItem> = emptyList()
    ) : SearchHistoryRepository {
        val items = MutableStateFlow(initial)
        val recorded = mutableListOf<String>()

        override fun observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>> =
            items.map { it.take(limit) }

        override suspend fun recordQuery(rawQuery: String, timestamp: Long): AppResult<Unit> {
            recorded += rawQuery
            items.value = listOf(LocalSearchHistoryItem(rawQuery, timestamp)) +
                items.value.filterNot { it.query == rawQuery }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteQuery(rawQuery: String): AppResult<Unit> {
            items.value = items.value.filterNot { it.query == rawQuery }
            return AppResult.Success(Unit)
        }

        override suspend fun clearHistory(): AppResult<Unit> {
            items.value = emptyList()
            return AppResult.Success(Unit)
        }
    }

    private class FakePlaybackPreferences(enabled: Boolean) : PlaybackPreferences {
        override val isBackgroundPlaybackEnabled = flowOf(false)
        override val isPipEnabled = flowOf(false)
        override val isHistoryEnabled = MutableStateFlow(enabled)
        override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) = Unit
        override suspend fun setPipEnabled(enabled: Boolean) = Unit
        override suspend fun setHistoryEnabled(enabled: Boolean) {
            isHistoryEnabled.value = enabled
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

    private fun videoItem(id: String) = SearchResultItem.VideoItem(
        VideoSummary(
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
    )

    private fun page(vararg ids: String, nextToken: PageToken? = null) = SearchPage(
        items = ids.map { videoItem(it) },
        nextPageToken = nextToken
    )

    @Test
    fun long_search_keeps_a_bounded_window_and_retry_returns_to_first_results() = runTest(testDispatcher) {
        val service = FakeVideoService().apply {
            searchHandler = { _, _, token ->
                val pageIndex = (token as? PageToken.Id)?.id?.toInt() ?: 0
                AppResult.Success(SearchPage(
                    (pageIndex * 50 until (pageIndex + 1) * 50).map { videoItem("$it") },
                    PageToken.Id("${pageIndex + 1}")
                ))
            }
        }
        val model = SearchViewModel(CatalogRepository(service, repositoryScope = this), service)
        model.onQuerySubmitted("long search")
        advanceUntilIdle()
        repeat(8) { model.loadNextPage(); advanceUntilIdle() }
        val state = model.uiState.value as SearchUiState.Content
        assertEquals(300, state.items.size)
        assertEquals("150", (state.items.first() as SearchResultItem.VideoItem).summary.key.nativeId)
        assertTrue(state.earlierResultsDropped)
        model.retry()
        advanceUntilIdle()
        val restarted = model.uiState.value as SearchUiState.Content
        assertEquals("0", (restarted.items.first() as SearchResultItem.VideoItem).summary.key.nativeId)
        assertFalse(restarted.earlierResultsDropped)
    }

    @Test
    fun blank_query_makes_no_search_or_suggestions_call() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQueryChanged("")
        advanceUntilIdle()

        assertEquals(0, fakeService.searchCallCount)
        assertEquals(0, fakeService.suggestionsCallCount)
        assertTrue(viewModel.uiState.value is SearchUiState.Idle)

        viewModel.onQueryChanged("   ")
        advanceUntilIdle()

        assertEquals(0, fakeService.searchCallCount)
        assertEquals(0, fakeService.suggestionsCallCount)
        assertTrue(viewModel.uiState.value is SearchUiState.Idle)
    }

    @Test
    fun debounce_400ms_delays_search_and_only_executes_latest_query() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "old" to page("old"),
                "new" to page("new")
            )
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQueryChanged("old")
        advanceTimeBy(200) // Not yet reached 400ms

        viewModel.onQueryChanged("new")
        advanceTimeBy(300) // Only 300ms since "new", "old" cancelled

        assertEquals(0, fakeService.searchCallCount)

        advanceTimeBy(150) // Total > 400ms for "new"
        advanceUntilIdle()

        assertEquals(1, fakeService.searchCallCount)
        val state = viewModel.uiState.value
        assertTrue(state is SearchUiState.Content)
        val content = state as SearchUiState.Content
        assertEquals(1, content.items.size)
        assertEquals(
            "Title new",
            (content.items.first() as SearchResultItem.VideoItem).summary.title
        )
    }

    @Test
    fun suggestions_are_fetched_when_service_supports_it() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            supportsSearchSuggestions = true,
            suggestionsResponses = mapOf(
                "test" to listOf("test 1", "test 2")
            )
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQueryChanged("test")
        advanceUntilIdle()

        assertEquals(listOf("test 1", "test 2"), viewModel.suggestions.value)
        assertEquals(1, fakeService.suggestionsCallCount)
    }

    @Test
    fun suggestions_are_not_fetched_when_service_does_not_support_it() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            supportsSearchSuggestions = false,
            suggestionsResponses = mapOf(
                "test" to listOf("test 1", "test 2")
            )
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQueryChanged("test")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.suggestions.value)
        assertEquals(0, fakeService.suggestionsCallCount)
    }

    @Test
    fun explicit_search_submit_executes_immediately_without_waiting_400ms() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            searchResponses = mapOf("submit_query" to page("sub_1"))
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("submit_query")
        // Since onQuerySubmitted is explicit, it immediately triggers search
        advanceUntilIdle()

        assertEquals(1, fakeService.searchCallCount)
        val state = viewModel.uiState.value
        assertTrue(state is SearchUiState.Content)
        assertEquals(1, (state as SearchUiState.Content).items.size)
    }

    @Test
    fun empty_results_produce_empty_state() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            searchResponses = mapOf("empty_query" to page())
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("empty_query")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SearchUiState.Empty)
    }

    @Test
    fun search_error_produces_error_state_and_retry_repeats_current_query() = runTest(testDispatcher) {
        var call = 0
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { query, _, _ ->
            call++
            if (call == 1) {
                AppResult.Failure(AppError.NetworkError)
            } else {
                AppResult.Success(page("recovered_$query"))
            }
        }
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("err_query")
        advanceUntilIdle()

        val errState = viewModel.uiState.value
        assertTrue(errState is SearchUiState.Error)
        assertEquals(AppError.NetworkError, (errState as SearchUiState.Error).error)

        // Retry
        viewModel.retry()
        advanceUntilIdle()

        val contentState = viewModel.uiState.value
        assertTrue(contentState is SearchUiState.Content)
        assertEquals(1, (contentState as SearchUiState.Content).items.size)
    }

    @Test
    fun pagination_appends_items_and_locks_in_flight() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { query, filter, token ->
            if (token == null) {
                AppResult.Success(page("p1", nextToken = PageToken.Id("tok_2")))
            } else if (token == PageToken.Id("tok_2")) {
                AppResult.Success(page("p2", nextToken = null))
            } else {
                AppResult.Success(page())
            }
        }
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("page_query")
        advanceUntilIdle()

        val state1 = viewModel.uiState.value
        assertTrue(state1 is SearchUiState.Content)
        val content1 = state1 as SearchUiState.Content
        assertEquals(1, content1.items.size)
        assertEquals(PageToken.Id("tok_2"), content1.nextPageToken)
        assertFalse(content1.isLoadingNextPage)

        // Request next page
        viewModel.loadNextPage()
        // Calling loadNextPage again immediately while in flight shouldn't trigger duplicate
        viewModel.loadNextPage()

        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertTrue(state2 is SearchUiState.Content)
        val content2 = state2 as SearchUiState.Content
        assertEquals(2, content2.items.size)
        assertEquals(null, content2.nextPageToken)
        assertEquals(2, fakeService.searchCallCount)
    }

    @Test
    fun search_filter_change_triggers_fresh_search_with_new_filter() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "q" to page("item_videos")
            )
        )
        var capturedFilter: SearchFilter? = null
        fakeService.searchHandler = { query, filter, token ->
            capturedFilter = filter
            AppResult.Success(page("item_${filter.name}"))
        }
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("q")
        advanceUntilIdle()
        assertEquals(SearchFilter.ALL, capturedFilter)

        viewModel.onFilterChanged(SearchFilter.VIDEOS)
        advanceUntilIdle()
        assertEquals(SearchFilter.VIDEOS, capturedFilter)
    }

    @Test
    fun persisted_queries_are_observed_and_explicit_submit_records_once() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val history = FakeSearchHistoryRepository(listOf(LocalSearchHistoryItem("saved query", 1L)))
        val viewModel = SearchViewModel(
            repository,
            fakeService,
            history,
            FakePlaybackPreferences(true)
        )

        advanceUntilIdle()
        assertEquals(listOf("saved query"), viewModel.historyState.value.items.map { it.query })

        viewModel.onQuerySubmitted("compose")
        advanceUntilIdle()
        assertEquals(listOf("compose"), history.recorded)
        assertEquals("compose", viewModel.historyState.value.items.first().query)
    }

    @Test
    fun debounced_typing_does_not_record_and_disabled_history_blocks_submit_recording() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val history = FakeSearchHistoryRepository()
        val preferences = FakePlaybackPreferences(true)
        val viewModel = SearchViewModel(repository, fakeService, history, preferences)

        viewModel.onQueryChanged("typed query")
        advanceTimeBy(500)
        advanceUntilIdle()
        assertTrue(history.recorded.isEmpty())

        preferences.setHistoryEnabled(false)
        viewModel.onQuerySubmitted("private query")
        advanceUntilIdle()
        assertTrue(history.recorded.isEmpty())
    }

    @Test
    fun delete_and_clear_use_persisted_repository() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val history = FakeSearchHistoryRepository(
            listOf(
                LocalSearchHistoryItem("first query", 2L),
                LocalSearchHistoryItem("second query", 1L)
            )
        )
        val viewModel = SearchViewModel(repository, fakeService, history, FakePlaybackPreferences(true))

        viewModel.removeRecentQuery("first query")
        advanceUntilIdle()
        assertEquals(listOf("second query"), viewModel.historyState.value.items.map { it.query })

        viewModel.clearRecentQueries()
        advanceUntilIdle()
        assertTrue(viewModel.historyState.value.items.isEmpty())
    }

    @Test
    fun delayed_non_cooperative_fake_discards_stale_results_for_query_and_filter_changes() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        var searchCall = 0
        fakeService.searchHandler = { q, f, token ->
            searchCall++
            val thisCall = searchCall
            if (q == "queryA") {
                // Non-cooperative long delay (does not check cancellation if executed in background)
                kotlinx.coroutines.delay(1000)
                AppResult.Success(page("res_queryA"))
            } else if (q == "queryB") {
                kotlinx.coroutines.delay(100)
                AppResult.Success(page("res_queryB"))
            } else {
                AppResult.Success(page("res_other"))
            }
        }

        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        // Submit Query A
        viewModel.onQuerySubmitted("queryA")
        testDispatcher.scheduler.advanceTimeBy(10)

        // Submit Query B while Query A is pending
        viewModel.onQuerySubmitted("queryB")

        // Advance 200ms: Query B completes
        testDispatcher.scheduler.advanceTimeBy(200)
        val stateB = viewModel.uiState.value
        assertTrue(stateB is SearchUiState.Content)
        assertEquals("res_queryB", (stateB as SearchUiState.Content).items.first().let { (it as SearchResultItem.VideoItem).summary.key.nativeId })

        // Advance past Query A completion (total > 1000ms)
        testDispatcher.scheduler.advanceTimeBy(1000)
        advanceUntilIdle()

        // State must remain Query B, NOT Query A
        val finalState = viewModel.uiState.value
        assertTrue(finalState is SearchUiState.Content)
        assertEquals("res_queryB", (finalState as SearchUiState.Content).items.first().let { (it as SearchResultItem.VideoItem).summary.key.nativeId })
    }

    @Test
    fun filter_change_while_pagination_pending_cancels_and_discards_stale_page_completion() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, f, token ->
            if (token == null && f == SearchFilter.ALL) {
                AppResult.Success(page("initial_all", nextToken = PageToken.Id("next_1")))
            } else if (token == PageToken.Id("next_1")) {
                // Page 2 request takes long time
                kotlinx.coroutines.delay(1000)
                AppResult.Success(page("page2_stale_all"))
            } else if (token == null && f == SearchFilter.VIDEOS) {
                // Fresh filter search completes quickly
                kotlinx.coroutines.delay(100)
                AppResult.Success(page("fresh_videos"))
            } else {
                AppResult.Success(page())
            }
        }

        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("kotlin")
        advanceUntilIdle()

        // Trigger load next page
        viewModel.loadNextPage()
        testDispatcher.scheduler.advanceTimeBy(10)

        // Change filter while page is in flight
        viewModel.onFilterChanged(SearchFilter.VIDEOS)

        // Advance 200ms: Filter change completes
        testDispatcher.scheduler.advanceTimeBy(200)
        val stateVideos = viewModel.uiState.value
        assertTrue(stateVideos is SearchUiState.Content)
        assertEquals("fresh_videos", (stateVideos as SearchUiState.Content).items.first().let { (it as SearchResultItem.VideoItem).summary.key.nativeId })

        // Advance past stale page completion
        testDispatcher.scheduler.advanceTimeBy(1000)
        advanceUntilIdle()

        // Content must still only have fresh_videos, not appended with page2_stale_all
        val stateFinal = viewModel.uiState.value
        assertTrue(stateFinal is SearchUiState.Content)
        val items = (stateFinal as SearchUiState.Content).items
        assertEquals(1, items.size)
        assertEquals("fresh_videos", (items.first() as SearchResultItem.VideoItem).summary.key.nativeId)
    }

    @Test
    fun pagination_merges_and_deduplicates_by_content_key_retaining_first_order() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, f, token ->
            if (token == null) {
                AppResult.Success(page("item1", "item2", nextToken = PageToken.Id("next_dup")))
            } else {
                // Page 2 contains duplicate "item2" and new "item3"
                AppResult.Success(page("item2", "item3", nextToken = null))
            }
        }

        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("test_dedup")
        advanceUntilIdle()

        viewModel.loadNextPage()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SearchUiState.Content)
        val items = (state as SearchUiState.Content).items
        assertEquals(3, items.size)
        assertEquals("item1", (items[0] as SearchResultItem.VideoItem).summary.key.nativeId)
        assertEquals("item2", (items[1] as SearchResultItem.VideoItem).summary.key.nativeId)
        assertEquals("item3", (items[2] as SearchResultItem.VideoItem).summary.key.nativeId)
    }

    /**
     * Typing a new query keeps the previous results composed instead of clearing to a spinner.
     */
    @Test
    fun new_search_keeps_previous_results_visible_while_running() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, _, _ ->
            if (q == "second") {
                kotlinx.coroutines.delay(1_000)
                AppResult.Success(page("second_result"))
            } else {
                AppResult.Success(page("first_result"))
            }
        }
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("first")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is SearchUiState.Content)

        viewModel.onQuerySubmitted("second")
        advanceTimeBy(50)

        val during = viewModel.uiState.value
        assertTrue("Search must not clear results to Loading, got $during", during is SearchUiState.Content)
        val duringContent = during as SearchUiState.Content
        assertEquals("first_result", (duringContent.items.single() as SearchResultItem.VideoItem).summary.key.nativeId)
        assertTrue("Expected isSearching while the new query runs", duringContent.isSearching)

        advanceUntilIdle()
        val after = viewModel.uiState.value as SearchUiState.Content
        assertEquals("second_result", (after.items.single() as SearchResultItem.VideoItem).summary.key.nativeId)
        assertFalse(after.isSearching)
    }

    /** With no results on screen there is nothing to preserve, so Loading is still correct. */
    @Test
    fun first_search_with_no_results_on_screen_uses_loading_state() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { _, _, _ ->
            kotlinx.coroutines.delay(1_000)
            AppResult.Success(page("result"))
        }
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("first")
        advanceTimeBy(50)
        assertTrue(viewModel.uiState.value is SearchUiState.Loading)

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is SearchUiState.Content)
    }

    /**
     * Back-navigation into a recent query renders from cache with no new request.
     *
     * Debounced typing is what replays a cached query; an explicit submit always refetches.
     */
    @Test
    fun repeating_a_recent_query_via_typing_serves_from_cache() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "alpha" to page("alpha_result"),
                "beta" to page("beta_result")
            )
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQueryChanged("alpha")
        advanceUntilIdle()
        assertEquals(1, fakeService.searchCallCount)

        viewModel.onQueryChanged("beta")
        advanceUntilIdle()
        assertEquals(2, fakeService.searchCallCount)

        // Back to "alpha": served from cache, no third request.
        viewModel.onQueryChanged("alpha")
        advanceUntilIdle()
        assertEquals("cache hit must not issue a request", 2, fakeService.searchCallCount)
        val state = viewModel.uiState.value
        assertTrue(state is SearchUiState.Content)
        val content = state as SearchUiState.Content
        assertEquals("alpha_result", (content.items.single() as SearchResultItem.VideoItem).summary.key.nativeId)
        assertFalse(content.isSearching)
    }

    /** An explicit submit means the user wants fresh results, so it bypasses the cache. */
    @Test
    fun explicit_submit_bypasses_the_result_cache() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(searchResponses = mapOf("alpha" to page("alpha_result")))
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("alpha")
        advanceUntilIdle()
        assertEquals(1, fakeService.searchCallCount)

        viewModel.onQuerySubmitted("alpha")
        advanceUntilIdle()
        assertEquals(2, fakeService.searchCallCount)
    }

    /**
     * A failed search shows the error even when stale results are visible.
     *
     * Those results answer a different query, so keeping them on screen would present them as
     * results for what the user just searched.
     */
    @Test
    fun failed_search_reports_error_rather_than_keeping_stale_results() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, _, _ ->
            if (q == "good") AppResult.Success(page("good_result"))
            else AppResult.Failure(AppError.NetworkError)
        }
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("good")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is SearchUiState.Content)

        viewModel.onQuerySubmitted("bad")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected Error for a failed search, got $state", state is SearchUiState.Error)
        assertEquals(AppError.NetworkError, (state as SearchUiState.Error).error)
    }

    @Test
    fun unexpected_exception_from_repository_maps_to_safe_error_and_does_not_get_stuck_in_loading() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { _, _, _ ->
            throw RuntimeException("Simulated unexpected unhandled exception")
        }

        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("crash_test")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected SearchUiState.Error, got $state", state is SearchUiState.Error)
        assertEquals(AppError.Unknown, (state as SearchUiState.Error).error)
    }

    @Test
    fun unexpected_exception_during_pagination_recovers_state_without_stuck_loading() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, f, token ->
            if (token == null) {
                AppResult.Success(page("page1", nextToken = PageToken.Id("tok_crash")))
            } else {
                throw RuntimeException("Crash during pagination")
            }
        }

        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = SearchViewModel(repository = repository, videoService = fakeService)

        viewModel.onQuerySubmitted("page_crash_test")
        advanceUntilIdle()

        val state1 = viewModel.uiState.value
        assertTrue(state1 is SearchUiState.Content)

        viewModel.loadNextPage()
        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertTrue(state2 is SearchUiState.Content)
        assertFalse((state2 as SearchUiState.Content).isLoadingNextPage)
        assertEquals(1, state2.items.size)
    }
}
