package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.model.VideoDetails
import com.hpre.app.settings.PlaybackPreferences
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
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

    private fun video(index: Int) = video("v$index", "Video $index")

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

    private fun <T> AppResult<T>.valueOrThrow(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> error("Expected success but got failure: $error")
    }

    @Test
    fun `home returns at most 100 clean full content keys`() = runTest {
        val excluded = (0 until 20).map(::video).map { it.key }.toSet()
        val allVideos = (0 until 140).map(::video)
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { _, _, _ ->
            AppResult.Success(SearchPage(allVideos.map { SearchResultItem.VideoItem(it) }))
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("topic", 1L))),
            history(emptyList())
        )
        val result = repository.home(
            RecommendationRequest(limit = 100, excludedKeys = excluded)
        ).valueOrThrow()

        assertEquals(100, result.size)
        assertEquals(result.size, result.map { it.key }.toSet().size)
        assertTrue(result.none { it.key in excluded })
    }

    @Test
    fun `undersupply never refills with excluded videos`() = runTest {
        val oldBatch = (0 until 100).map(::video)
        val fresh = (100 until 117).map(::video)
        val candidates = oldBatch + fresh
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { _, _, _ ->
            AppResult.Success(SearchPage(candidates.map { SearchResultItem.VideoItem(it) }))
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("topic", 1L))),
            history(emptyList())
        )
        val result = repository.home(
            RecommendationRequest(excludedKeys = oldBatch.map { it.key }.toSet())
        ).valueOrThrow()

        assertEquals(fresh.map { it.key }, result.map { it.key })
    }

    @Test
    fun `deduplication preserves distinct service ID for identical native ID`() = runTest {
        val ytVideo = VideoSummary(
            key = ContentKey(0, "dup_native"), title = "YouTube", canonicalUrl = "https://yt.test/dup",
            channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = 120, viewCount = null, publishedTimestamp = null
        )
        val dmVideo = VideoSummary(
            key = ContentKey(1, "dup_native"), title = "DailyMotion", canonicalUrl = "https://dm.test/dup",
            channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = 120, viewCount = null, publishedTimestamp = null
        )
        val service = FakeVideoService(trendingResponse = AppResult.Success(emptyList()))
        service.searchHandler = { _, _, _ ->
            AppResult.Success(SearchPage(listOf(
                SearchResultItem.VideoItem(ytVideo),
                SearchResultItem.VideoItem(dmVideo)
            )))
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("topic", 1L))),
            history(emptyList())
        )
        val result = repository.home(RecommendationRequest()).valueOrThrow()

        assertEquals(2, result.size)
        assertEquals(setOf(ContentKey(0, "dup_native"), ContentKey(1, "dup_native")), result.map { it.key }.toSet())
    }

    private class ContinuationRecordingService : com.hpre.app.repository.VideoService {
        override val serviceId: Int = 0
        override val serviceName: String = "RecordingFake"
        override val supportsShorts: Boolean = false
        override val supportsComments: Boolean = false
        override val supportsSearchSuggestions: Boolean = false

        var maxConcurrentSearches = 0
        private var currentConcurrentSearches = 0
        private val lock = Any()
        val pageCountByQuery = mutableMapOf<String, Int>()
        var continuationCalls = 0
        val searchCalls = mutableListOf<Pair<String, PageToken?>>()

        var searchResponseProvider: suspend (query: String, pageToken: PageToken?) -> AppResult<SearchPage> = { query, token ->
            val pageNum = if (token == null) 1 else 2
            val startIndex = if (token == null) 0 else 20
            val items = (startIndex until startIndex + 20).map { i ->
                SearchResultItem.VideoItem(
                    VideoSummary(
                        key = ContentKey(0, "${query}_$i"),
                        title = "$query video $i",
                        canonicalUrl = "https://example.test/${query}_$i",
                        channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                        durationSeconds = 120, viewCount = null, publishedTimestamp = null
                    )
                )
            }
            val nextToken = if (pageNum == 1) PageToken.Id("next_${query}_2") else null
            AppResult.Success(SearchPage(items = items, nextPageToken = nextToken))
        }

        override suspend fun search(query: String, filter: SearchFilter, pageToken: PageToken?): AppResult<SearchPage> {
            synchronized(lock) {
                currentConcurrentSearches++
                if (currentConcurrentSearches > maxConcurrentSearches) {
                    maxConcurrentSearches = currentConcurrentSearches
                }
                pageCountByQuery[query] = (pageCountByQuery[query] ?: 0) + 1
                if (pageToken != null) {
                    continuationCalls++
                }
                searchCalls += query to pageToken
            }
            val result = searchResponseProvider(query, pageToken)
            synchronized(lock) {
                currentConcurrentSearches--
            }
            return result
        }

        override suspend fun suggestions(query: String): AppResult<List<String>> = AppResult.Success(emptyList())
        override suspend fun video(key: ContentKey): AppResult<VideoDetails> = throw NotImplementedError()
        override suspend fun streamInfo(key: ContentKey): AppResult<com.hpre.app.model.StreamInfo> = throw NotImplementedError()
        override suspend fun channel(key: ContentKey): AppResult<com.hpre.app.model.ChannelDetails> = throw NotImplementedError()
        override suspend fun related(key: ContentKey): AppResult<List<VideoSummary>> = throw NotImplementedError()
        override suspend fun playlist(key: ContentKey): AppResult<com.hpre.app.model.PlaylistDetails> = throw NotImplementedError()
        override suspend fun comments(key: ContentKey, pageToken: PageToken?): AppResult<com.hpre.app.model.CommentPage> = throw NotImplementedError()
        override suspend fun trending(): AppResult<List<VideoSummary>> = AppResult.Success(emptyList())
    }

    @Test
    fun `continuation budget respects concurrency, max pages per query, total continuations, and reaches 100`() = runTest {
        val fake = ContinuationRecordingService()
        val queries = (1..6).map { "query$it" }.mapIndexed { idx, q -> LocalSearchHistoryItem(q, 100L - idx) }
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 100)).valueOrThrow()

        assertTrue("Max concurrent background searches ${fake.maxConcurrentSearches} > 2", fake.maxConcurrentSearches <= 2)
        assertTrue("Page count by query exceeded 2: ${fake.pageCountByQuery}", fake.pageCountByQuery.values.all { it <= 2 })
        assertTrue("Continuation calls ${fake.continuationCalls} > 6", fake.continuationCalls <= 6)
        assertEquals(100, result.size)
    }

    @Test
    fun `records exact query and page token sequence without calling token twice`() = runTest {
        val fake = ContinuationRecordingService()
        val queries = listOf("alpha", "beta").mapIndexed { idx, q -> LocalSearchHistoryItem(q, 100L - idx) }
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 60)).valueOrThrow()

        // Two initial pages provide 40 candidates. The first deterministic continuation reaches
        // the requested limit of 60, so the collector must stop without an unnecessary beta call.
        assertEquals(3, fake.searchCalls.size)

        // Initial calls have null token
        val initialCalls = fake.searchCalls.filter { it.second == null }
        assertEquals(setOf("alpha", "beta"), initialCalls.map { it.first }.toSet())

        // Continuation calls have non-null token
        val continuationCalls = fake.searchCalls.filter { it.second != null }
        assertEquals(1, continuationCalls.size)
        // Topic priority order: alpha is first in queries list.
        assertEquals("alpha" to PageToken.Id("next_alpha_2"), continuationCalls[0])

        // Verify each query called at most MAX_PAGES_PER_QUERY (2)
        assertEquals(2, fake.searchCalls.count { it.first == "alpha" })
        assertEquals(1, fake.searchCalls.count { it.first == "beta" })

        // Verify no token is called twice
        val tokensCalled = continuationCalls.map { it.second }
        assertEquals(tokensCalled.size, tokensCalled.distinct().size)
    }

    @Test
    fun `max pages per query constrains continuations even if tokens are returned`() = runTest {
        val fake = ContinuationRecordingService()
        // Provide token on every call, even continuation
        fake.searchResponseProvider = { query, token ->
            val items = (0 until 10).map { i ->
                SearchResultItem.VideoItem(
                    VideoSummary(
                        key = ContentKey(0, "${query}_${token}_$i"),
                        title = "$query video $i",
                        canonicalUrl = "https://example.test/${query}_${token}_$i",
                        channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                        durationSeconds = 120, viewCount = null, publishedTimestamp = null
                    )
                )
            }
            AppResult.Success(SearchPage(items = items, nextPageToken = PageToken.Id("token_${query}_next")))
        }

        val queries = listOf("single_topic").mapIndexed { idx, q -> LocalSearchHistoryItem(q, 100L - idx) }
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 100)).valueOrThrow()

        // Even though targetLimit=100 and cleanCount=20 < 100, and nextToken is present,
        // it must stop at MAX_PAGES_PER_QUERY (2 total calls: 1 initial + 1 continuation)
        assertEquals(2, fake.searchCalls.size)
        assertEquals(2, fake.pageCountByQuery["single_topic"])
        assertEquals(20, result.size)
    }

    @Test
    fun `continuation failure keeps prior candidate items`() = runTest {
        val fake = ContinuationRecordingService()
        fake.searchResponseProvider = { query, token ->
            if (token != null) {
                AppResult.Failure(AppError.NetworkError)
            } else {
                val items = (0 until 15).map { i ->
                    SearchResultItem.VideoItem(
                        VideoSummary(
                            key = ContentKey(0, "${query}_$i"),
                            title = "$query video $i",
                            canonicalUrl = "https://example.test/${query}_$i",
                            channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                            durationSeconds = 120, viewCount = null, publishedTimestamp = null
                        )
                    )
                }
                AppResult.Success(SearchPage(items = items, nextPageToken = PageToken.Id("token_next")))
            }
        }

        val queries = listOf("topic_fail").mapIndexed { idx, q -> LocalSearchHistoryItem(q, 100L - idx) }
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 50)).valueOrThrow()

        assertEquals(15, result.size)
    }

    @Test
    fun `continuation is driven by clean count after exclusion and deduplication`() = runTest {
        val fake = ContinuationRecordingService()
        // Query 1 page 1 returns 20 items, but 10 of them are excluded
        // Query 2 page 1 returns 20 items
        // Total clean from page 1 = 10 + 20 = 30.
        // Target limit = 50. Since 30 < 50, continuation must be triggered.
        val excluded = (0 until 10).map { video("q1_$it") }.map { it.key }.toSet()
        fake.searchResponseProvider = { query, token ->
            val pageNum = if (token == null) 1 else 2
            val startIndex = if (token == null) 0 else 20
            val items = (startIndex until startIndex + 20).map { i ->
                SearchResultItem.VideoItem(
                    VideoSummary(
                        key = ContentKey(0, "${query}_$i"),
                        title = "$query video $i",
                        canonicalUrl = "https://example.test/${query}_$i",
                        channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                        durationSeconds = 120, viewCount = null, publishedTimestamp = null
                    )
                )
            }
            val nextToken = if (pageNum == 1) PageToken.Id("next_${query}_2") else null
            AppResult.Success(SearchPage(items = items, nextPageToken = nextToken))
        }

        val queries = listOf("q1", "q2").mapIndexed { idx, q -> LocalSearchHistoryItem(q, 100L - idx) }
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 50, excludedKeys = excluded)).valueOrThrow()

        // Continuations were called because clean count on first page was 30 < 50
        assertTrue(fake.continuationCalls >= 1)
        assertTrue(result.none { it.key in excluded })
        assertEquals(50, result.size)
    }

    @Test
    fun `no continuation call when first pages produce 100 clean candidates`() = runTest {
        val fake = ContinuationRecordingService()
        fake.searchResponseProvider = { query, _ ->
            val items = (0 until 34).map { i ->
                SearchResultItem.VideoItem(
                    VideoSummary(
                        key = ContentKey(0, "${query}_$i"),
                        title = "$query video $i",
                        canonicalUrl = "https://example.test/${query}_$i",
                        channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                        durationSeconds = 120, viewCount = null, publishedTimestamp = null
                    )
                )
            }
            // Three explicit queries * 34 unique items exceed the 100-item target on page 1.
            AppResult.Success(SearchPage(items = items, nextPageToken = PageToken.Id("next_token")))
        }
        val queries = (1..3).map { "topic$it" }.mapIndexed { idx, q -> LocalSearchHistoryItem(q, 100L - idx) }
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 100)).valueOrThrow()

        assertEquals(100, result.size)
        assertEquals(0, fake.continuationCalls)
    }

    @Test
    fun `no loop on null token when undersupplied`() = runTest {
        val fake = ContinuationRecordingService()
        fake.searchResponseProvider = { query, _ ->
            val items = (0 until 5).map { i ->
                SearchResultItem.VideoItem(
                    VideoSummary(
                        key = ContentKey(0, "${query}_$i"),
                        title = "$query video $i",
                        canonicalUrl = "https://example.test/${query}_$i",
                        channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                        durationSeconds = 120, viewCount = null, publishedTimestamp = null
                    )
                )
            }
            AppResult.Success(SearchPage(items = items, nextPageToken = null))
        }
        val queries = listOf(LocalSearchHistoryItem("topic1", 100L))
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 100)).valueOrThrow()

        assertEquals(5, result.size)
        assertEquals(0, fake.continuationCalls)
        assertEquals(1, fake.pageCountByQuery["topic1"])
    }

    @Test
    fun `initial fan out timeout returns completed sources and ignores hung sources`() = runTest {
        val fake = ContinuationRecordingService()
        fake.searchResponseProvider = { query, _ ->
            if (query == "hung_topic") {
                kotlinx.coroutines.delay(20_000L)
                AppResult.Success(SearchPage(emptyList()))
            } else {
                val items = (0 until 10).map { i ->
                    SearchResultItem.VideoItem(
                        VideoSummary(
                            key = ContentKey(0, "${query}_$i"),
                            title = "$query video $i",
                            canonicalUrl = "https://example.test/${query}_$i",
                            channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                            durationSeconds = 120, viewCount = null, publishedTimestamp = null
                        )
                    )
                }
                AppResult.Success(SearchPage(items = items, nextPageToken = null))
            }
        }
        val queries = listOf(
            LocalSearchHistoryItem("fast_topic", 100L),
            LocalSearchHistoryItem("hung_topic", 99L)
        )
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 100)).valueOrThrow()

        assertEquals(10, result.size)
        assertTrue(result.all { it.key.nativeId.startsWith("fast_topic") })
    }

    @Test
    fun `continuation timeout returns accumulated clean candidates without failing entire request`() = runTest {
        val fake = ContinuationRecordingService()
        fake.searchResponseProvider = { query, token ->
            if (token != null) {
                // Continuation hangs past 10s timeout
                kotlinx.coroutines.delay(20_000L)
                AppResult.Success(SearchPage(emptyList()))
            } else {
                val items = (0 until 10).map { i ->
                    SearchResultItem.VideoItem(
                        VideoSummary(
                            key = ContentKey(0, "${query}_$i"),
                            title = "$query video $i",
                            canonicalUrl = "https://example.test/${query}_$i",
                            channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                            durationSeconds = 120, viewCount = null, publishedTimestamp = null
                        )
                    )
                }
                AppResult.Success(SearchPage(items = items, nextPageToken = PageToken.Id("token_$query")))
            }
        }
        val queries = listOf(LocalSearchHistoryItem("topic1", 100L))
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 100)).valueOrThrow()

        assertEquals(10, result.size)
        assertTrue(result.all { it.key.nativeId.startsWith("topic1") })
    }

    @Test
    fun `watch recommendation collects clean candidates and handles timeout similarly`() = runTest {
        val current = video("current", "Watch Current")
        val related = video("related_1", "Related Video")
        val service = FakeVideoService(
            relatedHandler = { AppResult.Success(listOf(current, related)) },
            trendingResponse = AppResult.Success(emptyList())
        )
        service.searchHandler = { query, _, _ ->
            if (query == "hung") {
                kotlinx.coroutines.delay(20_000L)
                AppResult.Success(SearchPage(emptyList()))
            } else {
                val items = (0 until 10).map { i ->
                    SearchResultItem.VideoItem(video("${query}_$i", "$query $i"))
                }
                AppResult.Success(SearchPage(items = items))
            }
        }
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            searchHistory(listOf(LocalSearchHistoryItem("fast", 100L), LocalSearchHistoryItem("hung", 90L))),
            history(emptyList()),
            service,
            FakePlaybackPreferences(true)
        )
        val details = VideoDetails(
            key = current.key, title = current.title, canonicalUrl = current.canonicalUrl,
            description = null, channelKey = null, channelName = "Channel",
            channelAvatarUrl = null, subscriberCountText = null, thumbnailUrl = null,
            durationSeconds = 120, viewCount = null, likeCount = null, publishedTimestamp = null
        )

        val result = repository.recommendations(current.key, details, RecommendationRequest(limit = 100)).valueOrThrow()

        assertTrue(result.any { it.key == related.key })
        assertTrue(result.none { it.key == current.key })
        assertTrue(result.any { it.key.nativeId.startsWith("fast_") })
    }

    @Test
    fun `one failed topic does not discard successful sources`() = runTest {
        val fake = ContinuationRecordingService()
        fake.searchResponseProvider = { query, _ ->
            if (query == "failing_topic") {
                AppResult.Failure(AppError.NetworkError)
            } else {
                val items = (0 until 10).map { i ->
                    SearchResultItem.VideoItem(
                        VideoSummary(
                            key = ContentKey(0, "${query}_$i"),
                            title = "$query video $i",
                            canonicalUrl = "https://example.test/${query}_$i",
                            channelKey = null, channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = null,
                            durationSeconds = 120, viewCount = null, publishedTimestamp = null
                        )
                    )
                }
                AppResult.Success(SearchPage(items = items, nextPageToken = null))
            }
        }
        val queries = listOf(
            LocalSearchHistoryItem("ok_topic", 100L),
            LocalSearchHistoryItem("failing_topic", 99L)
        )
        val repository = RecommendationRepository(
            CatalogRepository(fake, this),
            searchHistory(queries),
            history(emptyList())
        )

        val result = repository.home(RecommendationRequest(limit = 100)).valueOrThrow()

        assertEquals(10, result.size)
        assertTrue(result.all { it.key.nativeId.startsWith("ok_topic") })
    }

    @Test fun empty_history_uses_trending_without_searching() = runTest {
        val trending = video("trending")
        val service = FakeVideoService(trendingResponse = AppResult.Success(listOf(trending)))
        val repository = RecommendationRepository(
            CatalogRepository(service, this), searchHistory(emptyList()), history(emptyList())
        )

        assertEquals(
            listOf(trending),
            (repository.home(RecommendationRequest(forceRefresh = false)) as AppResult.Success<List<VideoSummary>>).value
        )
        assertEquals(0, service.searchCallCount)
        assertEquals(1, service.trendingCallCount)
    }

    @Test
    fun `home reads independent local recommendation inputs in parallel`() = runTest {
        val localReadDelayMs = 1_000L
        val delayedSearchHistory = object : SearchHistoryRepository {
            override fun observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>> = flow {
                delay(localReadDelayMs)
                emit(emptyList())
            }
            override suspend fun recordQuery(rawQuery: String, timestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteQuery(rawQuery: String) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val delayedWatchHistory = object : HistoryRepository {
            override fun observeHistory(): Flow<List<WatchHistoryItem>> = flow {
                delay(localReadDelayMs)
                emit(emptyList())
            }
            override suspend fun getHistoryItem(key: ContentKey) = AppResult.Success(null)
            override suspend fun recordHistory(
                summary: VideoSummary,
                positionMs: Long,
                watchedTimestamp: Long
            ) = AppResult.Success(Unit)
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val service = FakeVideoService(
            trendingResponse = AppResult.Success(listOf(video("trending")))
        )
        val repository = RecommendationRepository(
            CatalogRepository(service, this),
            delayedSearchHistory,
            delayedWatchHistory
        )

        val result = repository.home(RecommendationRequest()).valueOrThrow()

        assertEquals(listOf("trending"), result.map { it.key.nativeId })
        assertEquals(
            "Two independent local reads must overlap instead of adding their delays",
            localReadDelayMs,
            currentTime
        )
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

        val result = (repository.home(RecommendationRequest(forceRefresh = false)) as AppResult.Success<List<VideoSummary>>).value
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

        repository.home(RecommendationRequest(forceRefresh = false))

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

        repository.home(RecommendationRequest(forceRefresh = false))

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

        repository.home(RecommendationRequest(forceRefresh = false))

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

        repository.home(RecommendationRequest(forceRefresh = false))

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
            repository.home(RecommendationRequest(forceRefresh = false))
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

        val result = repository.home(RecommendationRequest(forceRefresh = false)) as AppResult.Success<List<VideoSummary>>

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

        val result = repository.recommendations(current.key, details, RecommendationRequest(forceRefresh = false)) as AppResult.Success<List<VideoSummary>>

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

        val result = repository.recommendations(current.key, details, RecommendationRequest(forceRefresh = false))

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

        val result = repository.home(RecommendationRequest(forceRefresh = false))

        assertEquals(emptyList<VideoSummary>(), (result as AppResult.Success).value)
    }
}
