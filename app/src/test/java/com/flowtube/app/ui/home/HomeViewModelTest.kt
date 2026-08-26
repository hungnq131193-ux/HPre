package com.flowtube.app.ui.home

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.repository.CatalogRepository
import com.flowtube.app.testing.FakeVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

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

    @Test
    fun load_emits_loading_then_content() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            trendingResponse = AppResult.Success(listOf(summary("1"), summary("2")))
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(repository = repository)

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
        val viewModel = HomeViewModel(repository = repository)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HomeUiState.Empty)
    }

    @Test
    fun load_emits_error_when_service_fails() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            trendingResponse = AppResult.Failure(AppError.NetworkError)
        )
        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(repository = repository)

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
        val viewModel = HomeViewModel(repository = repository)

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
        val fakeService = FakeVideoService()
        fakeService.trendingHandler = {
            callCount++
            val currentCall = callCount
            if (currentCall == 1) {
                // First call delayed
                kotlinx.coroutines.delay(1000)
                AppResult.Success(listOf(summary("stale_1")))
            } else {
                // Second call completes faster
                kotlinx.coroutines.delay(200)
                AppResult.Success(listOf(summary("fresh_2")))
            }
        }

        val repository = CatalogRepository(videoService = fakeService, repositoryScope = this)
        val viewModel = HomeViewModel(repository = repository)

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
        val viewModel = HomeViewModel(repository = repository)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected HomeUiState.Error, got $state", state is HomeUiState.Error)
        assertEquals(AppError.Unknown, (state as HomeUiState.Error).error)
    }
}
