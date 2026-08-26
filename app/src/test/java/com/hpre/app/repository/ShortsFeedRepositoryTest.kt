package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortsFeedRepositoryTest {
    private fun video(id: String, duration: Long?, short: Boolean = false, live: Boolean = false) = VideoSummary(
        key = ContentKey(0, id), title = id, canonicalUrl = "https://example.test/$id",
        channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
        durationSeconds = duration, viewCount = null, publishedTimestamp = null,
        isLive = live, isShort = short
    )

    @Test fun filters_exact_duration_boundary_deduplicates_and_prioritizes_semantic_shorts() {
        val semantic = video("semantic", 180, short = true)
        val semanticDuplicate = video("duplicate", 30, short = true)
        val result = ShortsFeedRepository.filterCandidates(
            listOf(video("one", 1), video("zero", 0), video("unknown", null), video("long", 181),
                video("live", 10, live = true), video("one", 1), semantic,
                video("duplicate", null), video("duplicate", 30), semanticDuplicate)
        )
        assertEquals(listOf("semantic", "duplicate", "one"), result.map { it.key.nativeId })
        assertEquals(true, result.first { it.key.nativeId == "duplicate" }.isShort)
    }

    @Test fun no_history_uses_three_fixed_queries_and_partial_failure_keeps_candidates() = runTest {
        val service = FakeVideoService()
        val called = mutableListOf<String>()
        service.searchHandler = { query, _, _ ->
            called += query
            if (query == "quick tutorial") AppResult.Failure(AppError.NetworkError)
            else AppResult.Success(SearchPage(listOf(SearchResultItem.VideoItem(video(query, 30)))))
        }
        val repository = ShortsFeedRepository(
            CatalogRepository(service, this), emptySearchHistory(), emptyHistory()
        )
        val result = (repository.load(false) as AppResult.Success<List<VideoSummary>>).value
        assertEquals(listOf("short video", "quick tutorial", "music short"), called)
        assertEquals(listOf("short video", "music short"), result.map { it.key.nativeId })
    }

    @Test fun all_failed_requests_return_failure() = runTest {
        val service = FakeVideoService(
            searchHandler = { _, _, _ -> AppResult.Failure(AppError.NetworkError) }
        )
        val repository = ShortsFeedRepository(
            CatalogRepository(service, this), emptySearchHistory(), emptyHistory()
        )
        assertEquals(AppResult.Failure(AppError.NetworkError), repository.load(false))
    }

    @Test fun local_topics_are_normalized_distinct_and_limited_to_three() = runTest {
        val called = mutableListOf<String>()
        val service = FakeVideoService(searchHandler = { query, _, _ ->
            called += query
            AppResult.Success(SearchPage(emptyList()))
        })
        val history = listOf(
            LocalSearchHistoryItem("  Compose  ", 4),
            LocalSearchHistoryItem("compose", 3),
            LocalSearchHistoryItem("Kotlin", 2),
            LocalSearchHistoryItem("Android", 1)
        )
        val repository = ShortsFeedRepository(
            CatalogRepository(service, this), searchHistory(history), emptyHistory()
        )
        repository.load(false)
        assertEquals(listOf("compose", "kotlin", "android"), called)
    }

    @Test fun cancellation_is_not_mapped_to_failure() = runTest {
        val service = FakeVideoService(
            searchHandler = { _, _, _ -> throw CancellationException("cancel") }
        )
        val repository = ShortsFeedRepository(
            CatalogRepository(service, this), emptySearchHistory(), emptyHistory()
        )
        var cancelled = false
        try {
            repository.load(false)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
    }

    private fun searchHistory(items: List<LocalSearchHistoryItem>) = object : SearchHistoryRepository {
        override fun observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>> = flowOf(items.take(limit))
        override suspend fun recordQuery(rawQuery: String, timestamp: Long) = AppResult.Success(Unit)
        override suspend fun deleteQuery(rawQuery: String) = AppResult.Success(Unit)
        override suspend fun clearHistory() = AppResult.Success(Unit)
    }

    private fun emptySearchHistory() = object : SearchHistoryRepository {
        override fun observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>> = flowOf(emptyList())
        override suspend fun recordQuery(rawQuery: String, timestamp: Long) = AppResult.Success(Unit)
        override suspend fun deleteQuery(rawQuery: String) = AppResult.Success(Unit)
        override suspend fun clearHistory() = AppResult.Success(Unit)
    }

    private fun emptyHistory() = object : HistoryRepository {
        override fun observeHistory(): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override suspend fun getHistoryItem(key: ContentKey) = AppResult.Success(null)
        override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
        override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
        override suspend fun clearHistory() = AppResult.Success(Unit)
    }
}
