package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationRepositoryTest {
    private fun video(id: String, title: String = id) = VideoSummary(
        key = ContentKey(0, id), title = title, canonicalUrl = "https://example.test/$id",
        channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
        durationSeconds = 120, viewCount = null, publishedTimestamp = null
    )

    private fun searchHistory(items: List<LocalSearchHistoryItem>) = object : SearchHistoryRepository {
        override fun observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>> = flowOf(items.take(limit))
        override suspend fun recordQuery(rawQuery: String, timestamp: Long) = AppResult.Success(Unit)
        override suspend fun deleteQuery(rawQuery: String) = AppResult.Success(Unit)
        override suspend fun clearHistory() = AppResult.Success(Unit)
    }

    private fun history(items: List<WatchHistoryItem>) = object : HistoryRepository {
        override fun observeHistory(): Flow<List<WatchHistoryItem>> = flowOf(items)
        override suspend fun getHistoryItem(key: ContentKey) = AppResult.Success(items.firstOrNull { it.key == key })
        override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
        override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
        override suspend fun clearHistory() = AppResult.Success(Unit)
    }

    @Test fun empty_history_uses_trending_without_searching() = runTest {
        val trending = video("trending")
        val service = FakeVideoService(trendingResponse = AppResult.Success(listOf(trending)))
        val repository = RecommendationRepository(
            CatalogRepository(service, this), searchHistory(emptyList()), history(emptyList())
        )

        assertEquals(
            listOf(trending),
            (repository.home(false) as AppResult.Success<List<VideoSummary>>).value
        )
        assertEquals(0, service.searchCallCount)
        assertEquals(1, service.trendingCallCount)
    }

    @Test fun uses_at_most_three_queries_and_survives_partial_failure() = runTest {
        val service = FakeVideoService(trendingResponse = AppResult.Success(listOf(video("fallback"))))
        service.searchHandler = { query, _, _ ->
            if (query == "broken") AppResult.Failure(AppError.NetworkError)
            else AppResult.Success(SearchPage(listOf(SearchResultItem.VideoItem(video(query, "$query tutorial")))))
        }
        val queries = listOf("compose", "broken", "kotlin", "ignored").mapIndexed { index, query ->
            LocalSearchHistoryItem(query, 100L - index)
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this), searchHistory(queries), history(emptyList())
        )

        val result = (repository.home(false) as AppResult.Success<List<VideoSummary>>).value
        assertEquals(3, service.searchCallCount)
        assertEquals(listOf("compose", "kotlin", "fallback"), result.map { it.key.nativeId })
    }

    @Test fun cancellation_is_propagated() = runTest {
        val service = FakeVideoService(
            searchHandler = { _, _, _ -> throw CancellationException("cancel") },
            trendingResponse = AppResult.Success(emptyList())
        )
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("compose", 1))),
            history(emptyList())
        )
        var cancelled = false
        try {
            repository.home(false)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
    }
}
