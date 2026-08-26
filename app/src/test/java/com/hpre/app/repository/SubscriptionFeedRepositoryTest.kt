package com.hpre.app.repository

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionFeedRepositoryTest {
    @Test
    fun refresh_never_exceeds_three_concurrent_channel_requests() = runTest {
        val subscriptions = (1..7).map { index ->
            LocalSubscription(ContentKey(0, "c$index"), "https://example.test/c$index", "C$index", null, index.toLong())
        }
        var active = 0
        var maximumActive = 0
        val startedThree = CompletableDeferred<Unit>()
        val service = FakeVideoService(channelHandler = { key ->
            active++
            maximumActive = maxOf(maximumActive, active)
            if (maximumActive == 3) startedThree.complete(Unit)
            delay(100)
            active--
            AppResult.Success(ChannelDetails(channel(key), listOf(video(key.nativeId))))
        })
        val repository = SubscriptionFeedRepository(
            subscriptionRepository = FakeSubscriptions(subscriptions),
            videoService = service
        )

        val result = repository.refreshAll()
        startedThree.await()

        assertTrue(result.videos.isNotEmpty())
        assertTrue(maximumActive <= 3)
        assertEquals(7, result.videos.size)
    }

    @Test
    fun timed_out_channel_is_reported_as_failure_instead_of_cancelling_refresh() = runTest {
        val slowKey = ContentKey(0, "slow")
        val service = FakeVideoService(channelHandler = { key ->
            delay(1_000)
            AppResult.Success(ChannelDetails(channel(key), listOf(video(key.nativeId))))
        })
        val repository = SubscriptionFeedRepository(
            subscriptionRepository = FakeSubscriptions(
                listOf(
                    LocalSubscription(slowKey, "https://example.test/slow", "Slow", null, 1)
                )
            ),
            videoService = service,
            timeoutMs = 100
        )

        val result = repository.refreshAll()

        assertEquals(listOf(slowKey), result.failedChannels)
        assertTrue(result.videos.isEmpty())
    }

    private fun channel(key: ContentKey) = Channel(
        key = key,
        name = key.nativeId,
        canonicalUrl = "https://example.test/${key.nativeId}",
        avatarUrl = null,
        bannerUrl = null,
        subscriberCountText = null,
        description = null
    )

    private fun video(id: String) = VideoSummary(
        key = ContentKey(0, "v$id"),
        title = id,
        canonicalUrl = "https://example.test/v$id",
        channelKey = ContentKey(0, id),
        channelName = id,
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 1,
        viewCount = null,
        publishedTimestamp = id.removePrefix("c").toLong()
    )

    private class FakeSubscriptions(items: List<LocalSubscription>) : SubscriptionRepository {
        private val state = MutableStateFlow(items)
        override fun observeSubscriptions(): Flow<List<LocalSubscription>> = state
        override fun observeIsSubscribed(key: ContentKey): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun isSubscribed(key: ContentKey) = AppResult.Success(false)
        override suspend fun subscribe(channel: Channel, subscribedTimestamp: Long) = AppResult.Success(Unit)
        override suspend fun unsubscribe(key: ContentKey) = AppResult.Success(Unit)
        override suspend fun clearSubscriptions() = AppResult.Success(Unit)
    }
}
