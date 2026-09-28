package com.hpre.app.ui.library

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.repository.SubscriptionFeedRepository
import com.hpre.app.repository.SubscriptionRepository
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class SubscriptionFeedViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeSubscriptionRepo : SubscriptionRepository {
        val subFlow = MutableStateFlow<List<LocalSubscription>>(emptyList())
        override fun observeSubscriptions(): Flow<List<LocalSubscription>> = subFlow
        override fun observeIsSubscribed(key: ContentKey): Flow<Boolean> =
            MutableStateFlow(subFlow.value.any { it.channelKey == key })
        override suspend fun isSubscribed(key: ContentKey): AppResult<Boolean> =
            AppResult.Success(subFlow.value.any { it.channelKey == key })
        override suspend fun subscribe(channel: Channel, subscribedTimestamp: Long): AppResult<Unit> =
            AppResult.Success(Unit)
        override suspend fun unsubscribe(key: ContentKey): AppResult<Unit> {
            subFlow.value = subFlow.value.filterNot { it.channelKey == key }
            return AppResult.Success(Unit)
        }
        override suspend fun clearSubscriptions(): AppResult<Unit> = AppResult.Success(Unit)
    }

    private fun channelDetails(key: ContentKey, videoIds: List<String>) = ChannelDetails(
        channel = Channel(key, key.nativeId, "https://t/${key.nativeId}", null, null, null, null),
        videos = videoIds.map { id ->
            VideoSummary(
                key = ContentKey(0, id),
                title = "Video $id",
                canonicalUrl = "https://t/$id",
                channelKey = key,
                channelName = key.nativeId,
                channelAvatarUrl = null,
                thumbnailUrl = null,
                durationSeconds = 60L,
                viewCount = 1L,
                publishedTimestamp = id.removePrefix("v").toLongOrNull() ?: 0L
            )
        }
    )

    private fun fixture(
        channelHandler: suspend (ContentKey) -> AppResult<ChannelDetails>
    ): Triple<FakeSubscriptionRepo, FakeVideoService, SubscriptionFeedViewModel> {
        val subRepo = FakeSubscriptionRepo()
        val service = FakeVideoService(channelHandler = channelHandler)
        val repo = SubscriptionFeedRepository(
            subscriptionRepository = subRepo,
            videoService = service,
            timeoutMs = 5_000L,
            cacheTtlMs = 0L
        )
        return Triple(subRepo, service, SubscriptionFeedViewModel(repo))
    }

    @Test
    fun refresh_failure_keeps_existing_videos_and_sets_refreshError() = runTest {
        var fail = false
        val (subRepo, _, model) = fixture { key ->
            if (fail) AppResult.Failure(AppError.NetworkError)
            else AppResult.Success(channelDetails(key, listOf("v1")))
        }
        subRepo.subFlow.value = listOf(
            LocalSubscription(ContentKey(0, "c1"), "https://t/c1", "C1", null, 1L)
        )
        advanceUntilIdle()
        val content = model.state.value as SubscriptionFeedUiState.Content
        assertEquals(listOf("v1"), content.videos.map { it.key.nativeId })

        fail = true
        model.refresh()
        advanceUntilIdle()

        val after = model.state.value as SubscriptionFeedUiState.Content
        assertEquals(listOf("v1"), after.videos.map { it.key.nativeId })
        assertEquals(AppError.NetworkError, after.refreshError)
        assertEquals(false, after.isRefreshing)
    }

    @Test
    fun refresh_success_replaces_videos_and_clears_error() = runTest {
        var ids = listOf("v1")
        var failFirst = false
        val (subRepo, _, model) = fixture { key ->
            if (failFirst) AppResult.Failure(AppError.NetworkError)
            else AppResult.Success(channelDetails(key, ids))
        }
        subRepo.subFlow.value = listOf(
            LocalSubscription(ContentKey(0, "c1"), "https://t/c1", "C1", null, 1L)
        )
        advanceUntilIdle()

        failFirst = true
        model.refresh()
        advanceUntilIdle()
        assertTrue((model.state.value as SubscriptionFeedUiState.Content).refreshError != null)

        failFirst = false
        ids = listOf("v2", "v3")
        model.refresh()
        advanceUntilIdle()
        val content = model.state.value as SubscriptionFeedUiState.Content
        assertEquals(listOf("v2", "v3"), content.videos.map { it.key.nativeId }.sorted())
        assertEquals(null, content.refreshError)
    }

    @Test
    fun failed_refresh_does_not_flip_content_to_loading() = runTest {
        var fail = false
        val (subRepo, _, model) = fixture { key ->
            if (fail) AppResult.Failure(AppError.NetworkError)
            else AppResult.Success(channelDetails(key, listOf("v1")))
        }
        subRepo.subFlow.value = listOf(
            LocalSubscription(ContentKey(0, "c1"), "https://t/c1", "C1", null, 1L)
        )
        advanceUntilIdle()
        assertTrue(model.state.value is SubscriptionFeedUiState.Content)

        fail = true
        model.refresh()
        advanceUntilIdle()
        // Videos must never be replaced by Loading/Error once the user has content.
        assertTrue(model.state.value is SubscriptionFeedUiState.Content)
    }
}
