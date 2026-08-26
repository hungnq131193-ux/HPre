package com.flowtube.app.repository

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.core.error.RetryPolicy
import com.flowtube.app.model.ChannelDetails
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

data class SubscriptionFeed(
    val videos: List<VideoSummary>,
    val failedChannels: List<ContentKey>
)

class SubscriptionFeedRepository(
    private val subscriptionRepository: SubscriptionRepository,
    private val videoService: VideoService,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val timeoutMs: Long = 10_000L,
    private val cacheTtlMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class CachedChannel(val details: ChannelDetails, val storedAtMs: Long)
    private val cache = mutableMapOf<ContentKey, CachedChannel>()

    suspend fun refreshAll(forceRefresh: Boolean = false): SubscriptionFeed = coroutineScope {
        val subscriptions = subscriptionRepository.observeSubscriptions().first()
        val semaphore = Semaphore(3)
        val results = subscriptions.map { subscription ->
            async {
                semaphore.withPermit {
                    subscription.channelKey to loadChannel(subscription.channelKey, forceRefresh)
                }
            }
        }.awaitAll()

        val failed = results.mapNotNull { (key, result) -> key.takeIf { result !is AppResult.Success } }
        val videos = results.flatMap { (_, result) ->
            (result as? AppResult.Success)?.value?.videos.orEmpty()
        }.distinctBy { it.key }
            .sortedWith(compareByDescending<VideoSummary> { it.publishedTimestamp != null }
                .thenByDescending { it.publishedTimestamp })
        SubscriptionFeed(videos, failed)
    }

    private suspend fun loadChannel(key: ContentKey, forceRefresh: Boolean): AppResult<ChannelDetails> {
        val now = clock()
        val cached = cache[key]
        if (!forceRefresh && cached != null && now - cached.storedAtMs <= cacheTtlMs) {
            return AppResult.Success(cached.details)
        }

        var attempt = 0
        while (true) {
            val result = try {
                withTimeout(timeoutMs) { videoService.channel(key) }
            } catch (_: TimeoutCancellationException) {
                AppResult.Failure(AppError.NetworkError)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppResult.Failure(AppError.Unknown)
            }
            if (result is AppResult.Success) {
                cache[key] = CachedChannel(result.value, clock())
                return result
            }
            val error = (result as AppResult.Failure).error
            val retryDelay = retryPolicy.getRetryDelayMs(error, attempt) ?: return result
            attempt++
            delay(retryDelay)
        }
    }
}
