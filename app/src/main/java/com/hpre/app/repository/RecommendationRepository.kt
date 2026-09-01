package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.settings.PlaybackPreferences
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class RecommendationRequest(
    val forceRefresh: Boolean = false,
    val limit: Int = 100,
    val excludedKeys: Set<ContentKey> = emptySet()
)

fun interface HomeRecommendationSource {
    suspend fun home(request: RecommendationRequest): AppResult<List<VideoSummary>>
}

fun interface WatchRecommendationSource {
    suspend fun recommendations(
        key: ContentKey,
        details: VideoDetails,
        request: RecommendationRequest
    ): AppResult<List<VideoSummary>>
}

internal const val MAX_TOPIC_CONCURRENCY = 6
internal const val MAX_PAGES_PER_QUERY = 2
internal const val MAX_TOTAL_CONTINUATIONS = 6
internal const val COLLECTION_DEADLINE_MS = 5_000L
internal const val MAX_FEED_LIMIT = 100

private fun RecommendationRequest.safeLimit(): Int = limit.coerceIn(0, MAX_FEED_LIMIT)

private fun cleanCandidates(
    candidates: List<VideoSummary>,
    excludedKeys: Set<ContentKey>
): List<VideoSummary> = candidates
    .distinctBy(VideoSummary::key)
    .filterNot { it.key in excludedKeys }

class RecommendationRepository(
    private val catalogRepository: CatalogRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val historyRepository: HistoryRepository,
    private val videoService: VideoService? = null,
    private val playbackPreferences: PlaybackPreferences? = null
) : HomeRecommendationSource, WatchRecommendationSource {
    // Shared across Home/Watch requests: leave extractor workers available for video opens/seeks.
    private val backgroundRequests = Semaphore(2)
    private val whitespace = Regex("\\s+")

    private class SourceRequestCancelled(
        val cancellation: CancellationException
    ) : RuntimeException(cancellation)

    private data class LocalRecommendationInputs(
        val recentQueries: List<String>,
        val watchHistory: List<WatchHistoryItem>
    )

    /**
     * Room owns the dispatcher used by both flows, so starting these independent reads together is
     * safe and removes their additive latency from Home and Watch recommendation startup.
     */
    private suspend fun loadLocalRecommendationInputs(): LocalRecommendationInputs = coroutineScope {
        val recentQueries = async {
            searchHistoryRepository.observeRecentQueries(MAX_SEARCH_QUERIES)
                .first()
                .map(LocalSearchHistoryItem::query)
        }
        val watchHistory = async {
            historyRepository.observeRecentHistory(MAX_WATCH_HISTORY_SIGNALS).first()
        }
        LocalRecommendationInputs(
            recentQueries = recentQueries.await(),
            watchHistory = watchHistory.await()
        )
    }

    override suspend fun home(request: RecommendationRequest): AppResult<List<VideoSummary>> {
        val targetLimit = request.safeLimit()
        if (targetLimit <= 0) return AppResult.Success(emptyList())

        if (playbackPreferences?.isHistoryEnabled?.first() == false) {
            return when (val trendingResult = catalogRepository.getTrending(request.forceRefresh)) {
                is AppResult.Success -> {
                    val clean = cleanCandidates(trendingResult.value, request.excludedKeys)
                    AppResult.Success(
                        RecommendationRanker.rank(
                            candidates = clean,
                            signals = LocalInterestSignals(emptyList(), emptyMap(), emptySet()),
                            context = RecommendationContext(
                                nowEpochSeconds = System.currentTimeMillis() / 1000L
                            ),
                            limit = targetLimit
                        )
                    )
                }
                is AppResult.Failure -> trendingResult
            }
        }

        val localInputs = loadLocalRecommendationInputs()
        val recentQueries = localInputs.recentQueries
        val watchHistory = localInputs.watchHistory

        val channelFrequency = watchHistory
            .mapNotNull(WatchHistoryItem::channelName)
            .groupingBy(::normalize)
            .eachCount()

        val historyTopics = TopicExtractor.topicsFromHistory(
            recentTitles = watchHistory.map(WatchHistoryItem::title),
            channelFrequency = channelFrequency,
            maxTitleTopics = MAX_TITLE_TOPICS,
            maxChannelTopics = MAX_CHANNEL_TOPICS
        )

        val topics = (recentQueries.map(::normalize) + historyTopics.map(InterestTopic::query))
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_TOPIC_CONCURRENCY)

        if (topics.isEmpty()) {
            return when (val result = catalogRepository.getTrending(request.forceRefresh)) {
                is AppResult.Success -> {
                    val clean = cleanCandidates(result.value, request.excludedKeys)
                    AppResult.Success(
                        RecommendationRanker.rank(
                            candidates = clean,
                            signals = LocalInterestSignals(
                                recentQueries = emptyList(),
                                watchedChannelFrequency = channelFrequency,
                                recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
                            ),
                            context = RecommendationContext(
                                nowEpochSeconds = System.currentTimeMillis() / 1000L
                            ),
                            limit = targetLimit
                        )
                    )
                }
                is AppResult.Failure -> result
            }
        }

        val signals = LocalInterestSignals(
            recentQueries = topics,
            watchedChannelFrequency = channelFrequency,
            recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
        )

        val state = CollectionState()

        try {
            withTimeoutOrNull(COLLECTION_DEADLINE_MS) {
                coroutineScope {
                    launch {
                        val res = safeRequest { catalogRepository.getTrending(request.forceRefresh) }
                        state.recordResult(res)
                    }
                    for (topic in topics) {
                        launch {
                            val res = safeRequest {
                                catalogRepository.search(
                                    query = topic,
                                    filter = SearchFilter.VIDEOS,
                                    pageToken = null,
                                    forceRefresh = request.forceRefresh
                                )
                            }
                            state.recordSearchResult(topic, res)
                        }
                    }
                }

                collectContinuations(topics, targetLimit, request, state)
            }
        } catch (cancelled: SourceRequestCancelled) {
            throw cancelled.cancellation
        }

        val candidates = state.snapshotCandidates()
        val clean = cleanCandidates(candidates, request.excludedKeys)
        val ranked = RecommendationRanker.rank(
            candidates = clean,
            signals = signals,
            context = RecommendationContext(nowEpochSeconds = System.currentTimeMillis() / 1000L),
            limit = targetLimit
        )

        if (ranked.isNotEmpty()) return AppResult.Success(ranked)
        if (state.hasAnySuccess()) return AppResult.Success(emptyList())

        return state.firstFailure() ?: AppResult.Success(emptyList())
    }

    override suspend fun recommendations(
        key: ContentKey,
        details: VideoDetails,
        request: RecommendationRequest
    ): AppResult<List<VideoSummary>> {
        val targetLimit = request.safeLimit()
        if (targetLimit <= 0) return AppResult.Success(emptyList())

        val service = videoService ?: return AppResult.Failure(AppError.Unknown)
        val historyEnabled = playbackPreferences?.isHistoryEnabled?.first() != false
        val localInputs = if (historyEnabled) {
            loadLocalRecommendationInputs()
        } else {
            LocalRecommendationInputs(emptyList(), emptyList())
        }
        val watchHistory = localInputs.watchHistory
        val recentQueries = localInputs.recentQueries
        val channelFrequency = watchHistory
            .mapNotNull(WatchHistoryItem::channelName)
            .groupingBy(::normalize)
            .eachCount()
        val currentTitleTopic = TopicExtractor.keywords(details.title)
            .joinToString(" ")
            .takeIf(String::isNotBlank)
        val localTopics = if (historyEnabled) {
            TopicExtractor.topicsFromHistory(
                recentTitles = watchHistory.map(WatchHistoryItem::title),
                channelFrequency = channelFrequency,
                maxTitleTopics = MAX_TITLE_TOPICS,
                maxChannelTopics = MAX_CHANNEL_TOPICS
            ).map(InterestTopic::query)
        } else {
            emptyList()
        }
        val topics = buildList {
            currentTitleTopic?.let(::add)
            details.channelName?.takeIf(String::isNotBlank)?.let(::add)
            addAll(recentQueries)
            addAll(localTopics)
        }.map(::normalize).filter(String::isNotBlank).distinct().take(MAX_TOPIC_CONCURRENCY)

        val signals = LocalInterestSignals(
            recentQueries = topics,
            watchedChannelFrequency = channelFrequency,
            recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
        )

        val state = CollectionState()
        var relatedKeys: Set<ContentKey> = emptySet()
        val relatedLock = Mutex()

        try {
            withTimeoutOrNull(COLLECTION_DEADLINE_MS) {
                coroutineScope {
                    launch {
                        val res = safeRequest { service.related(key) }
                        if (res is AppResult.Success) {
                            val keys = res.value.map(VideoSummary::key).toSet()
                            relatedLock.withLock { relatedKeys = keys }
                        }
                        state.recordResult(res)
                    }
                    launch {
                        val res = safeRequest { catalogRepository.getTrending(request.forceRefresh) }
                        state.recordResult(res)
                    }
                    for (topic in topics) {
                        launch {
                            val res = safeRequest {
                                catalogRepository.search(
                                    query = topic,
                                    filter = SearchFilter.VIDEOS,
                                    pageToken = null,
                                    forceRefresh = request.forceRefresh
                                )
                            }
                            state.recordSearchResult(topic, res)
                        }
                    }
                }

                collectContinuations(topics, targetLimit, request, state)
            }
        } catch (cancelled: SourceRequestCancelled) {
            throw cancelled.cancellation
        }

        val candidates = state.snapshotCandidates()
        val clean = cleanCandidates(candidates, request.excludedKeys)
        val finalRelatedKeys = relatedLock.withLock { relatedKeys }
        val ranked = RecommendationRanker.rank(
            candidates = clean,
            signals = signals,
            context = RecommendationContext(
                currentKey = key,
                currentChannelName = details.channelName,
                providerRelatedKeys = finalRelatedKeys,
                nowEpochSeconds = System.currentTimeMillis() / 1000L
            ),
            limit = targetLimit
        )

        if (ranked.isNotEmpty()) return AppResult.Success(ranked)
        if (state.hasAnySuccess()) return AppResult.Success(emptyList())

        return state.firstFailure() ?: AppResult.Success(emptyList())
    }

    private suspend fun collectContinuations(
        topics: List<String>,
        targetLimit: Int,
        request: RecommendationRequest,
        state: CollectionState
    ) {
        var continuationsUsed = 0
        while (continuationsUsed < MAX_TOTAL_CONTINUATIONS) {
            val currentCleanCount = cleanCandidates(state.snapshotCandidates(), request.excludedKeys).size
            if (currentCleanCount >= targetLimit) break

            val (topic, token) = state.claimNextContinuationToken(topics) ?: break
            val pageResult = safeRequest {
                catalogRepository.search(
                    query = topic,
                    filter = SearchFilter.VIDEOS,
                    pageToken = token,
                    forceRefresh = request.forceRefresh
                )
            }
            continuationsUsed++
            if (pageResult is AppResult.Success) {
                state.addCandidates(pageResult.value.items.mapNotNull { (it as? SearchResultItem.VideoItem)?.summary })
            }
        }
    }

    private class CollectionState {
        private val mutex = Mutex()
        private val candidates = mutableListOf<VideoSummary>()
        private val nextTokens = mutableMapOf<String, PageToken?>()
        private val pageCountByTopic = mutableMapOf<String, Int>()
        private var anySuccess = false
        private val failures = mutableListOf<AppResult.Failure>()

        suspend fun recordResult(result: AppResult<List<VideoSummary>>) {
            mutex.withLock {
                when (result) {
                    is AppResult.Success -> {
                        anySuccess = true
                        candidates += result.value
                    }
                    is AppResult.Failure -> {
                        failures += result
                    }
                }
            }
        }

        suspend fun recordSearchResult(topic: String, result: AppResult<com.hpre.app.model.SearchPage>) {
            mutex.withLock {
                pageCountByTopic[topic] = (pageCountByTopic[topic] ?: 0) + 1
                when (result) {
                    is AppResult.Success -> {
                        anySuccess = true
                        candidates += result.value.items.mapNotNull { (it as? SearchResultItem.VideoItem)?.summary }
                        nextTokens[topic] = result.value.nextPageToken
                    }
                    is AppResult.Failure -> {
                        failures += result
                        nextTokens[topic] = null
                    }
                }
            }
        }

        suspend fun claimNextContinuationToken(topics: List<String>): Pair<String, PageToken>? {
            return mutex.withLock {
                val eligibleTopic = topics.firstOrNull { topic ->
                    val pages = pageCountByTopic[topic] ?: 0
                    pages < MAX_PAGES_PER_QUERY && nextTokens[topic] != null
                } ?: return@withLock null
                val token = nextTokens[eligibleTopic]!!
                nextTokens[eligibleTopic] = null
                pageCountByTopic[eligibleTopic] = (pageCountByTopic[eligibleTopic] ?: 0) + 1
                eligibleTopic to token
            }
        }

        suspend fun addCandidates(newCandidates: List<VideoSummary>) {
            mutex.withLock {
                candidates += newCandidates
            }
        }

        suspend fun snapshotCandidates(): List<VideoSummary> {
            return mutex.withLock { candidates.toList() }
        }

        suspend fun hasAnySuccess(): Boolean {
            return mutex.withLock { anySuccess }
        }

        suspend fun firstFailure(): AppResult.Failure? {
            return mutex.withLock { failures.firstOrNull() }
        }
    }

    private suspend fun <T> safeRequest(block: suspend () -> AppResult<T>): AppResult<T> = try {
        backgroundRequests.withPermit { block() }
    } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) throw cancelled
        throw SourceRequestCancelled(cancelled)
    } catch (_: Throwable) {
        AppResult.Failure(AppError.Unknown)
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(whitespace, " ")

    companion object {
        const val MAX_SEARCH_QUERIES = 3

        /** Genre topics taken from recently watched titles. */
        const val MAX_TITLE_TOPICS = 4

        /** Frequently watched channels searched as their own topic. */
        const val MAX_CHANNEL_TOPICS = 2

        /**
         * Upper bound on parallel provider searches per feed load. Each topic is one network
         * request, so this caps fan-out and keeps the app clear of provider rate limiting.
         */
        const val MAX_TOTAL_TOPICS = 6

        const val MAX_WATCH_HISTORY_SIGNALS = 100
    }
}
