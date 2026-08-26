package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

fun interface HomeRecommendationSource {
    suspend fun home(forceRefresh: Boolean): AppResult<List<VideoSummary>>
}

class RecommendationRepository(
    private val catalogRepository: CatalogRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val historyRepository: HistoryRepository
) : HomeRecommendationSource {
    override suspend fun home(forceRefresh: Boolean): AppResult<List<VideoSummary>> {
        val recentQueries = searchHistoryRepository.observeRecentQueries(MAX_SEARCH_QUERIES)
            .first()
            .map(LocalSearchHistoryItem::query)
        val watchHistory = historyRepository.observeHistory().first()
        val watchTerms = watchHistory
            .flatMap { listOfNotNull(it.title, it.channelName) }
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_WATCH_TERMS)
        val topics = (recentQueries + watchTerms)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()

        if (topics.isEmpty()) return catalogRepository.getTrending(forceRefresh)

        val (searchResults, trendingResult) = supervisorScope {
            val trending = async { catalogRepository.getTrending(forceRefresh) }
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
            watchedChannelFrequency = watchHistory
                .mapNotNull(WatchHistoryItem::channelName)
                .groupingBy(::normalize)
                .eachCount(),
            recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
        )
        val ranked = RecommendationRanker.rank(candidates, signals, FEED_LIMIT)
        val trending = (trendingResult as? AppResult.Success)?.value.orEmpty()
        val merged = (ranked + trending)
            .distinctBy(VideoSummary::key)
            .take(FEED_LIMIT)

        if (merged.isNotEmpty()) return AppResult.Success(merged)
        return when {
            trendingResult is AppResult.Failure -> trendingResult
            else -> searchResults.filterIsInstance<AppResult.Failure>().firstOrNull()
                ?: AppResult.Success(emptyList())
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace("\\s+".toRegex(), " ")

    companion object {
        const val MAX_SEARCH_QUERIES = 3
        const val MAX_WATCH_TERMS = 2
        const val FEED_LIMIT = 30
    }
}
