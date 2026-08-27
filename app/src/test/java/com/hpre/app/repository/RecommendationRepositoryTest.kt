package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.model.VideoDetails
import com.hpre.app.settings.PlaybackPreferences
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationRepositoryTest {
    private class FakePlaybackPreferences(enabled: Boolean) : PlaybackPreferences {
        override val isBackgroundPlaybackEnabled = flowOf(false)
        override val isPipEnabled = flowOf(false)
        override val isHistoryEnabled = MutableStateFlow(enabled)
        override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) = Unit
        override suspend fun setPipEnabled(enabled: Boolean) = Unit
        override suspend fun setHistoryEnabled(enabled: Boolean) { isHistoryEnabled.value = enabled }
    }

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

    private fun watched(
        id: String,
        title: String,
        channel: String? = "Channel",
        watchedAt: Long = 0L
    ) = WatchHistoryItem(
        key = ContentKey(0, id),
        canonicalUrl = "https://example.test/$id",
        title = title,
        channelKey = null,
        channelName = channel,
        thumbnailUrl = null,
        durationSeconds = 120,
        playbackPositionMs = 0L,
        watchedTimestamp = watchedAt
    )

    @Test fun every_watched_genre_contributes_a_topic_not_only_the_newest_entry() = runTest {
        // The reported problem: recommendations were built from a single history entry, so the
        // feed never reflected the other genres the user watches.
        val searched = mutableListOf<String>()
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { query, _, _ ->
            searched += query
            AppResult.Success(SearchPage(listOf(SearchResultItem.VideoItem(video(query, query)))))
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(emptyList()),
            history(
                listOf(
                    watched("a", "Hướng dẫn Kotlin cơ bản", channel = null, watchedAt = 300L),
                    watched("b", "Nấu ăn món Việt ngon", channel = null, watchedAt = 200L),
                    watched("c", "Bóng đá Ngoại hạng Anh", channel = null, watchedAt = 100L)
                )
            )
        )

        repository.home(false)

        val joined = searched.joinToString(" ")
        assertEquals(3, searched.size)
        assertTrue("Kotlin genre not searched: $joined", joined.contains("kotlin"))
        assertTrue("Cooking genre not searched: $joined", joined.contains("nấu"))
        assertTrue("Football genre not searched: $joined", joined.contains("bóng"))
    }

    @Test fun searches_use_genre_keywords_rather_than_the_full_raw_title() = runTest {
        val searched = mutableListOf<String>()
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { query, _, _ ->
            searched += query
            AppResult.Success(SearchPage(emptyList()))
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(emptyList()),
            history(
                listOf(
                    watched(
                        "a",
                        "[Vietsub] Official MV - Nhạc Trẻ Remix 2024 Full HD",
                        channel = null
                    )
                )
            )
        )

        repository.home(false)

        val query = searched.single()
        assertFalse("Packaging noise leaked into the query: $query", query.contains("vietsub"))
        assertFalse("Packaging noise leaked into the query: $query", query.contains("official"))
        assertFalse("A bare year makes the query too narrow: $query", query.contains("2024"))
        assertTrue("Genre signal lost: $query", query.contains("nhạc"))
    }

    @Test fun network_fan_out_stays_bounded_for_a_large_history() = runTest {
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { _, _, _ -> AppResult.Success(SearchPage(emptyList())) }
        val genres = listOf(
            "Hướng dẫn Kotlin", "Nấu ăn Việt", "Bóng đá Anh", "Du lịch Nhật",
            "Phim hành động", "Học tiếng Hàn", "Đánh giá điện thoại", "Nhạc trẻ remix"
        )
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(
                listOf("compose", "kotlin", "android").mapIndexed { index, query ->
                    LocalSearchHistoryItem(query, 100L - index)
                }
            ),
            history(
                genres.mapIndexed { index, title ->
                    watched("v$index", title, channel = "Kênh$index")
                }
            )
        )

        repository.home(false)

        assertEquals(RecommendationRepository.MAX_TOTAL_TOPICS, service.searchCallCount)
    }

    @Test fun explicit_searches_are_prioritised_over_inferred_history_topics() = runTest {
        val searched = mutableListOf<String>()
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { query, _, _ ->
            searched += query
            AppResult.Success(SearchPage(emptyList()))
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("compose", 1L))),
            history(listOf(watched("a", "Nấu ăn món Việt", channel = null)))
        )

        repository.home(false)

        assertEquals("compose", searched.first())
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

    @Test fun disabled_history_home_uses_trending_without_personal_searches() = runTest {
        val trending = video("trending")
        val service = FakeVideoService(trendingResponse = AppResult.Success(listOf(trending)))
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("private query", 1L))),
            history(listOf(watched("seen", "Private topic"))),
            service,
            FakePlaybackPreferences(false)
        )

        val result = repository.home(false) as AppResult.Success<List<VideoSummary>>

        assertEquals(listOf(trending), result.value)
        assertEquals(0, service.searchCallCount)
    }

    @Test fun watch_merges_related_and_supplemental_without_current_video() = runTest {
        val current = video("current", "Kotlin Compose tutorial")
        val related = video("related", "Related video")
        val supplemental = video("supplemental", "Kotlin Compose advanced")
        val service = FakeVideoService(
            relatedHandler = { AppResult.Success(listOf(current, related)) },
            trendingResponse = AppResult.Success(emptyList())
        )
        service.searchHandler = { _, _, _ ->
            AppResult.Success(SearchPage(listOf(SearchResultItem.VideoItem(supplemental))))
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this), searchHistory(emptyList()), history(emptyList()),
            service, FakePlaybackPreferences(true)
        )
        val details = VideoDetails(
            key = current.key, title = current.title, canonicalUrl = current.canonicalUrl,
            description = null, channelKey = null, channelName = "Current Channel",
            channelAvatarUrl = null, subscriberCountText = null, thumbnailUrl = null,
            durationSeconds = 120, viewCount = null, likeCount = null, publishedTimestamp = null
        )

        val result = repository.recommendations(current.key, details, false) as AppResult.Success<List<VideoSummary>>

        assertEquals(listOf("related", "supplemental"), result.value.map { it.key.nativeId })
        assertTrue(service.searchCallCount <= RecommendationRepository.MAX_TOTAL_TOPICS)
    }

    @Test fun watch_returns_empty_when_one_source_succeeds_empty_and_another_fails() = runTest {
        val current = video("current", "")
        val service = FakeVideoService(
            relatedHandler = { AppResult.Success(emptyList()) },
            trendingResponse = AppResult.Failure(AppError.NetworkError)
        )
        val repository = RecommendationRepository(
            CatalogRepository(service, this), searchHistory(emptyList()), history(emptyList()),
            service, FakePlaybackPreferences(false)
        )
        val details = VideoDetails(
            key = current.key, title = "", canonicalUrl = current.canonicalUrl,
            description = null, channelKey = null, channelName = null,
            channelAvatarUrl = null, subscriberCountText = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, likeCount = null, publishedTimestamp = null
        )

        val result = repository.recommendations(current.key, details, false)

        assertEquals(emptyList<VideoSummary>(), (result as AppResult.Success).value)
    }

    @Test fun home_returns_empty_when_trending_succeeds_empty_and_search_fails() = runTest {
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { _, _, _ -> AppResult.Failure(AppError.NetworkError) }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("topic", 1L))),
            history(emptyList())
        )

        val result = repository.home(false)

        assertEquals(emptyList<VideoSummary>(), (result as AppResult.Success).value)
    }
}
