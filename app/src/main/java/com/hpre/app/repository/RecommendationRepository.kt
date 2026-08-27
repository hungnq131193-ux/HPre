package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.settings.PlaybackPreferences
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

fun interface HomeRecommendationSource {
    suspend fun home(forceRefresh: Boolean): AppResult<List<VideoSummary>>
}

fun interface WatchRecommendationSource {
    suspend fun recommendations(
        key: ContentKey,
        details: VideoDetails,
        forceRefresh: Boolean
    ): AppResult<List<VideoSummary>>
}

class RecommendationRepository(
    private val catalogRepository: CatalogRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val historyRepository: HistoryRepository,
    private val videoService: VideoService? = null,
    private val playbackPreferences: PlaybackPreferences? = null
) : HomeRecommendationSource, WatchRecommendationSource {
    override suspend fun home(forceRefresh: Boolean): AppResult<List<VideoSummary>> {
        if (playbackPreferences?.isHistoryEnabled?.first() == false) {
            return catalogRepository.getTrending(forceRefresh)
        }
        val recentQueries = searchHistoryRepository.observeRecentQueries(MAX_SEARCH_QUERIES)
            .first()
            .map(LocalSearchHistoryItem::query)
        val watchHistory = historyRepository.observeRecentHistory(MAX_WATCH_HISTORY_SIGNALS).first()

        val channelFrequency = watchHistory
            .mapNotNull(WatchHistoryItem::channelName)
            .groupingBy(::normalize)
            .eachCount()

        // Genre topics from the whole watch history, not just the newest entry. Raw titles make
        // poor queries (episode numbers, years, "vietsub", ...), so search the salient words.
        val historyTopics = TopicExtractor.topicsFromHistory(
            recentTitles = watchHistory.map(WatchHistoryItem::title),
            channelFrequency = channelFrequency,
            maxTitleTopics = MAX_TITLE_TOPICS,
            maxChannelTopics = MAX_CHANNEL_TOPICS
        )

        // Explicit searches are the strongest statement of intent, so they lead.
        val topics = (recentQueries.map(::normalize) + historyTopics.map(InterestTopic::query))
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_TOTAL_TOPICS)

        // New user with no usable topics still gets Vietnam trending, ranked against any retained
        // watch signals so watched suppression and channel diversity remain consistent.
        if (topics.isEmpty()) {
            return when (val result = catalogRepository.getTrending(forceRefresh)) {
                is AppResult.Success -> AppResult.Success(
                    RecommendationRanker.rank(
                        candidates = result.value,
                        signals = LocalInterestSignals(
                            recentQueries = emptyList(),
                            watchedChannelFrequency = channelFrequency,
                            recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
                        ),
                        context = RecommendationContext(
                            nowEpochSeconds = System.currentTimeMillis() / 1000L
                        ),
                        limit = FEED_LIMIT
                    )
                )
                is AppResult.Failure -> result
            }
        }

        val (searchResults, trendingResult) = supervisorScope {
            val trending = async { safeRequest { catalogRepository.getTrending(forceRefresh) } }
            val searches = topics.map { topic ->
                async {
                    try {
                        catalogRepository.search(
                            query = topic,
                            filter = SearchFilter.VIDEOS,
                            forceRefresh = forceRefresh
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        AppResult.Failure(AppError.Unknown)
                    }
                }
            }.awaitAll()
            searches to trending.await()
        }
        val candidates = searchResults.flatMap { result ->
            when (result) {
                is AppResult.Success -> result.value.items.mapNotNull { item ->
                    (item as? SearchResultItem.VideoItem)?.summary
                }
                is AppResult.Failure -> emptyList()
            }
        }
        val signals = LocalInterestSignals(
            recentQueries = topics,
            watchedChannelFrequency = channelFrequency,
            recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
        )
        val trending = (trendingResult as? AppResult.Success)?.value.orEmpty()
        val merged = RecommendationRanker.rank(
            candidates = candidates + trending,
            signals = signals,
            context = RecommendationContext(nowEpochSeconds = System.currentTimeMillis() / 1000L),
            limit = FEED_LIMIT
        )

        if (merged.isNotEmpty()) return AppResult.Success(merged)
        val anySourceSucceeded = trendingResult is AppResult.Success ||
            searchResults.any { it is AppResult.Success }
        if (anySourceSucceeded) return AppResult.Success(emptyList())
        return when {
            trendingResult is AppResult.Failure -> trendingResult
            else -> searchResults.filterIsInstance<AppResult.Failure>().firstOrNull()
                ?: AppResult.Success(emptyList())
        }
    }

    override suspend fun recommendations(
        key: ContentKey,
        details: VideoDetails,
        forceRefresh: Boolean
    ): AppResult<List<VideoSummary>> {
        val service = videoService ?: return AppResult.Failure(AppError.Unknown)
        val historyEnabled = playbackPreferences?.isHistoryEnabled?.first() != false
        val watchHistory = if (historyEnabled) {
            historyRepository.observeRecentHistory(MAX_WATCH_HISTORY_SIGNALS).first()
        } else {
            emptyList()
        }
        val recentQueries = if (historyEnabled) {
            searchHistoryRepository.observeRecentQueries(MAX_SEARCH_QUERIES).first()
                .map(LocalSearchHistoryItem::query)
        } else {
            emptyList()
        }
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
        }.map(::normalize).filter(String::isNotBlank).distinct().take(MAX_TOTAL_TOPICS)

        val (relatedResult, searchResults, trendingResult) = supervisorScope {
            val related = async { safeRequest { service.related(key) } }
            val searches = topics.map { topic ->
                async {
                    safeRequest {
                        catalogRepository.search(
                            query = topic,
                            filter = SearchFilter.VIDEOS,
                            forceRefresh = forceRefresh
                        )
                    }
                }
            }
            val trending = async { safeRequest { catalogRepository.getTrending(forceRefresh) } }
            Triple(related.await(), searches.awaitAll(), trending.await())
        }
        val related = (relatedResult as? AppResult.Success)?.value.orEmpty()
        val searched = searchResults.flatMap { result ->
            (result as? AppResult.Success)?.value?.items.orEmpty().mapNotNull { item ->
                (item as? SearchResultItem.VideoItem)?.summary
            }
        }
        val trending = (trendingResult as? AppResult.Success)?.value.orEmpty()
        val signals = LocalInterestSignals(
            recentQueries = topics,
            watchedChannelFrequency = channelFrequency,
            recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
        )
        val merged = RecommendationRanker.rank(
            candidates = related + searched + trending,
            signals = signals,
            context = RecommendationContext(
                currentKey = key,
                currentChannelName = details.channelName,
                providerRelatedKeys = related.map(VideoSummary::key).toSet(),
                nowEpochSeconds = System.currentTimeMillis() / 1000L
            ),
            limit = FEED_LIMIT
        )
        if (merged.isNotEmpty()) return AppResult.Success(merged)

        val anySourceSucceeded = relatedResult is AppResult.Success ||
            searchResults.any { it is AppResult.Success } ||
            trendingResult is AppResult.Success
        if (anySourceSucceeded) return AppResult.Success(emptyList())

        val failures = buildList<AppResult.Failure> {
            (relatedResult as? AppResult.Failure)?.let(::add)
            addAll(searchResults.filterIsInstance<AppResult.Failure>())
            (trendingResult as? AppResult.Failure)?.let(::add)
        }
        return failures.firstOrNull() ?: AppResult.Success(emptyList())
    }

    private suspend fun <T> safeRequest(block: suspend () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        AppResult.Failure(AppError.Unknown)
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace("\\s+".toRegex(), " ")

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

        const val FEED_LIMIT = 30
    }
}
