package com.flowtube.app.ui.shorts

import com.flowtube.app.testing.FakeVideoService
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.VideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class ShortsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun unsupported_shorts_shows_unavailable_without_requesting_a_feed() = runTest(dispatcher) {
        val service = FakeVideoService(supportsShorts = false)
        val model = ShortsViewModel(service, dispatcher)

        model.load()
        advanceUntilIdle()

        assertEquals(ShortsUiState.Unavailable, model.state.value)
        assertEquals(0, service.trendingCallCount)
    }

    @Test
    fun supported_shorts_keeps_only_semantically_marked_items() = runTest(dispatcher) {
        val short = video("short", isShort = true)
        val regular = video("regular", isShort = false)
        val service = FakeVideoService(
            supportsShorts = true,
            trendingResponse = AppResult.Success(listOf(regular, short))
        )
        val model = ShortsViewModel(service, dispatcher)

        model.load()
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state is ShortsUiState.Content)
        assertEquals(listOf(short), (state as ShortsUiState.Content).videos)
    }

    private fun video(id: String, isShort: Boolean) = VideoSummary(
        key = ContentKey(0, id),
        title = id,
        canonicalUrl = "https://example.test/$id",
        channelKey = null,
        channelName = null,
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 10,
        viewCount = null,
        publishedTimestamp = null,
        isShort = isShort
    )
}
