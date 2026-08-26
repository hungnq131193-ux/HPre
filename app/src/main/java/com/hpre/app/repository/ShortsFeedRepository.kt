package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

fun interface ShortsFeedSource {
    suspend fun load(forceRefresh: Boolean): AppResult<List<VideoSummary>>
}

class ShortsFeedRepository(
    private val catalogRepository: CatalogRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val historyRepository: HistoryRepository
) : ShortsFeedSource {
    override suspend fun load(forceRefresh: Boolean): AppResult<List<VideoSummary>> {
        val searchTopics = searchHistoryRepository.observeRecentQueries()
            .first()
            .map(LocalSearchHistoryItem::query)
        val watchTopics = historyRepository.observeHistory().first()
            .flatMap { listOfNotNull(it.title, it.channelName) }
        val localTopics = (searchTopics + watchTopics)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_TOPICS)
        val topics = localTopics.ifEmpty { DEFAULT_TOPICS }

        val results = supervisorScope {
            topics.map { topic ->
                async {
                    try {
                        catalogRepository.search(
                            query = topic,
                            filter = SearchFilter.VIDEOS,
                            forceRefresh = forceRefresh
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        AppResult.Failure(AppError.Unknown)
                    }
                }
            }.awaitAll()
        }
        val candidates = results.flatMap { result ->
            when (result) {
                is AppResult.Success -> result.value.items.mapNotNull { item ->
                    (item as? SearchResultItem.VideoItem)?.summary
                }
                is AppResult.Failure -> emptyList()
            }
        }
        val filtered = filterCandidates(candidates)
        if (filtered.isNotEmpty() || results.any { it is AppResult.Success }) {
            return AppResult.Success(filtered)
        }
        return results.filterIsInstance<AppResult.Failure>().firstOrNull()
            ?: AppResult.Success(emptyList())
    }

    companion object {
        const val MAX_DURATION_SECONDS = 180L
        const val MAX_TOPICS = 3
        val DEFAULT_TOPICS = listOf("short video", "quick tutorial", "music short")

        fun filterCandidates(items: List<VideoSummary>): List<VideoSummary> = items
            .filter { video ->
                !video.isLive && video.durationSeconds != null &&
                    video.durationSeconds in 1..MAX_DURATION_SECONDS
            }
            .sortedByDescending(VideoSummary::isShort)
            .distinctBy(VideoSummary::key)

        private fun normalize(value: String): String = value
            .trim()
            .lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), " ")
    }
}
