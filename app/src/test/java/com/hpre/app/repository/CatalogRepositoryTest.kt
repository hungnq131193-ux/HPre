package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryTest {

    private fun summary(id: String) = VideoSummary(
        key = ContentKey(0, id),
        title = "Title $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 120,
        viewCount = 1000,
        publishedTimestamp = 10000L
    )

    @Test
    fun trending_caches_result_within_ttl() = runTest {
        var now = 1000L
        val fakeService = FakeVideoService(
            trendingResponse = AppResult.Success(listOf(summary("1"), summary("2")))
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { now }
        )

        // First call fetches from service
        val firstResult = repository.getTrending(forceRefresh = false)
        assertTrue(firstResult is AppResult.Success<*>)
        assertEquals(1, fakeService.trendingCallCount)

        // Second call within TTL returns cached result without invoking service
        now += 2000L
        val secondResult = repository.getTrending(forceRefresh = false)
        assertTrue(secondResult is AppResult.Success<*>)
        assertEquals(1, fakeService.trendingCallCount)
        assertEquals((firstResult as AppResult.Success<List<VideoSummary>>).value, (secondResult as AppResult.Success<List<VideoSummary>>).value)

        // Third call after TTL expiration fetches fresh result from service
        now += 4000L // Total now = 7000L (elapsed 6000L > 5000L TTL)
        val thirdResult = repository.getTrending(forceRefresh = false)
        assertTrue(thirdResult is AppResult.Success<*>)
        assertEquals(2, fakeService.trendingCallCount)
    }

    @Test
    fun trending_forceRefresh_bypasses_ttl_cache() = runTest {
        val now = 1000L
        val fakeService = FakeVideoService(
            trendingResponse = AppResult.Success(listOf(summary("1")))
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 10000L,
            timeProvider = { now }
        )

        repository.getTrending(forceRefresh = false)
        assertEquals(1, fakeService.trendingCallCount)

        repository.getTrending(forceRefresh = true)
        assertEquals(2, fakeService.trendingCallCount)
    }

    @Test
    fun trending_failure_is_not_cached() = runTest {
        var now = 1000L
        var call = 0
        val fakeService = FakeVideoService()
        fakeService.trendingHandler = {
            call++
            if (call == 1) {
                AppResult.Failure(AppError.NetworkError)
            } else {
                AppResult.Success(listOf(summary("recovered")))
            }
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { now }
        )

        val first = repository.getTrending(forceRefresh = false)
        assertTrue(first is AppResult.Failure)
        assertEquals(1, call)

        // Second call immediately should retry because failure was not cached
        now += 100L
        val second = repository.getTrending(forceRefresh = false)
        assertTrue(second is AppResult.Success<*>)
        assertEquals(2, call)
    }

    @Test
    fun search_first_page_caches_within_ttl() = runTest {
        var now = 1000L
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "query1" to SearchPage(items = emptyList(), nextPageToken = null)
            )
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { now }
        )

        val first = repository.search("query1", SearchFilter.ALL, pageToken = null, forceRefresh = false)
        assertTrue(first is AppResult.Success<*>)
        assertEquals(1, fakeService.searchCallCount)

        // Call again within TTL
        now += 2000L
        val second = repository.search("query1", SearchFilter.ALL, pageToken = null, forceRefresh = false)
        assertTrue(second is AppResult.Success<*>)
        assertEquals(1, fakeService.searchCallCount)

        // Different query fetches anew
        val diff = repository.search("query2", SearchFilter.ALL, pageToken = null, forceRefresh = false)
        assertTrue(diff is AppResult.Success<*>)
        assertEquals(2, fakeService.searchCallCount)
    }

    @Test
    fun search_pagination_with_token_does_not_use_first_page_cache() = runTest {
        val now = 1000L
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "query1" to SearchPage(items = emptyList(), nextPageToken = PageToken.Id("next_123"))
            )
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { now }
        )

        repository.search("query1", SearchFilter.ALL, pageToken = null, forceRefresh = false)
        assertEquals(1, fakeService.searchCallCount)

        // Next page request with token
        repository.search("query1", SearchFilter.ALL, pageToken = PageToken.Id("next_123"), forceRefresh = false)
        assertEquals(2, fakeService.searchCallCount)
    }

    @Test
    fun query_normalization_trims_and_collapses_whitespace() = runTest {
        val fakeService = FakeVideoService(
            searchResponses = mapOf(
                "hello world" to SearchPage(items = emptyList(), nextPageToken = null)
            )
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { 1000L }
        )

        val res1 = repository.search("  hello   world  ", SearchFilter.ALL)
        assertTrue(res1 is AppResult.Success<*>)
        assertEquals(1, fakeService.searchCallCount)

        // Same normalized query hits cache
        val res2 = repository.search("hello world", SearchFilter.ALL)
        assertTrue(res2 is AppResult.Success<*>)
        assertEquals(1, fakeService.searchCallCount)
    }

    @Test
    fun concurrent_same_trending_and_search_requests_are_deduplicated_without_blocking_mutex() = runTest {
        val fakeService = FakeVideoService()
        var trendingExecutions = 0
        fakeService.trendingHandler = {
            kotlinx.coroutines.delay(50)
            trendingExecutions++
            AppResult.Success(listOf(summary("dedup_trending")))
        }

        var searchExecutions = 0
        fakeService.searchHandler = { q, f, t ->
            kotlinx.coroutines.delay(50)
            searchExecutions++
            AppResult.Success(SearchPage(items = listOf(summary("dedup_search").let { SearchResultItem.VideoItem(it) })))
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { 1000L }
        )

        // Launch concurrent trending calls
        val trendingDeferred1 = async { repository.getTrending(forceRefresh = false) }
        val trendingDeferred2 = async { repository.getTrending(forceRefresh = false) }

        val resT1 = trendingDeferred1.await()
        val resT2 = trendingDeferred2.await()

        assertTrue(resT1 is AppResult.Success<*>)
        assertTrue(resT2 is AppResult.Success<*>)
        assertEquals(1, trendingExecutions)

        // Launch concurrent search calls
        val searchDeferred1 = async { repository.search("concurrent query", SearchFilter.ALL) }
        val searchDeferred2 = async { repository.search("concurrent query", SearchFilter.ALL) }

        val resS1 = searchDeferred1.await()
        val resS2 = searchDeferred2.await()

        assertTrue(resS1 is AppResult.Success<*>)
        assertTrue(resS2 is AppResult.Success<*>)
        assertEquals(1, searchExecutions)
    }

    @Test
    fun deduplicated_failures_are_removed_from_in_flight_map() = runTest {
        val fakeService = FakeVideoService()
        var searchAttempts = 0
        fakeService.searchHandler = { q, f, t ->
            searchAttempts++
            if (searchAttempts == 1) {
                AppResult.Failure(AppError.NetworkError)
            } else {
                AppResult.Success(SearchPage(items = emptyList()))
            }
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { 1000L }
        )

        val first = repository.search("fail_query", SearchFilter.ALL)
        assertTrue(first is AppResult.Failure)
        assertEquals(1, searchAttempts)

        // Subsequent call triggers fresh attempt because failed in-flight was cleaned up
        val second = repository.search("fail_query", SearchFilter.ALL)
        assertTrue(second is AppResult.Success<*>)
        assertEquals(2, searchAttempts)
    }

    @Test
    fun clearCache_invalidates_write_generation_so_in_flight_completion_cannot_repopulate_cache() = runTest {
        val fakeService = FakeVideoService()
        fakeService.trendingHandler = {
            kotlinx.coroutines.delay(50)
            AppResult.Success(listOf(summary("pre_clear_item")))
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 10000L,
            timeProvider = { 1000L }
        )

        // 1. Request start (in-flight pre-clear request)
        val initiatorDeferred = async { repository.getTrending(forceRefresh = false) }
        val waiterDeferred = async { repository.getTrending(forceRefresh = false) }
        kotlinx.coroutines.delay(10)

        // 2. Clear cache while in flight
        repository.clearCache()

        // 3. Request completion: original initiator and waiter still complete successfully
        val initiatorRes = initiatorDeferred.await()
        val waiterRes = waiterDeferred.await()
        assertTrue(initiatorRes is AppResult.Success<*>)
        assertTrue(waiterRes is AppResult.Success<*>)
        assertEquals("pre_clear_item", (initiatorRes as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)
        assertEquals("pre_clear_item", (waiterRes as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)

        // 4. Subsequent nonforce request must call service again and not get old pre-clear cache
        fakeService.trendingHandler = {
            AppResult.Success(listOf(summary("post_clear_fresh_item")))
        }
        val subsequentRes = repository.getTrending(forceRefresh = false)
        assertTrue(subsequentRes is AppResult.Success<*>)
        assertEquals("post_clear_fresh_item", (subsequentRes as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)
        // Service should have been called twice: 1 for pre-clear, 1 for post-clear
        assertEquals(2, fakeService.trendingCallCount)
    }

    @Test
    fun clearCache_invalidates_search_write_generation_so_in_flight_completion_cannot_repopulate_cache() = runTest {
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, f, t ->
            kotlinx.coroutines.delay(50)
            AppResult.Success(SearchPage(items = listOf(SearchResultItem.VideoItem(summary("pre_clear_search")))))
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 10000L,
            timeProvider = { 1000L }
        )

        val initiatorDeferred = async { repository.search("query", SearchFilter.ALL, pageToken = null, forceRefresh = false) }
        val waiterDeferred = async { repository.search("query", SearchFilter.ALL, pageToken = null, forceRefresh = false) }
        kotlinx.coroutines.delay(10)

        repository.clearCache()

        val initiatorRes = initiatorDeferred.await()
        val waiterRes = waiterDeferred.await()
        assertTrue(initiatorRes is AppResult.Success<*>)
        assertTrue(waiterRes is AppResult.Success<*>)

        fakeService.searchHandler = { q, f, t ->
            AppResult.Success(SearchPage(items = listOf(SearchResultItem.VideoItem(summary("post_clear_search")))))
        }
        val subsequentRes = repository.search("query", SearchFilter.ALL, pageToken = null, forceRefresh = false)
        assertTrue(subsequentRes is AppResult.Success<*>)
        val item = (subsequentRes as AppResult.Success<SearchPage>).value.items.first() as SearchResultItem.VideoItem
        assertEquals("post_clear_search", item.summary.key.nativeId)
        assertEquals(2, fakeService.searchCallCount)
    }

    @Test
    fun initiator_cancellation_while_service_blocked_leaves_waiter_unaffected_and_cleans_up_map() = runTest {
        val fakeService = FakeVideoService()
        val serviceDeferred = kotlinx.coroutines.CompletableDeferred<AppResult<List<VideoSummary>>>()
        fakeService.trendingHandler = {
            serviceDeferred.await()
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 10000L,
            timeProvider = { 1000L }
        )

        // Initiator launches and starts in-flight request
        val initiatorJob = async { repository.getTrending(forceRefresh = false) }
        kotlinx.coroutines.delay(10)

        // Waiter joins the in-flight request
        val waiterJob = async { repository.getTrending(forceRefresh = false) }
        kotlinx.coroutines.delay(10)

        // Cancel initiator while service is blocked
        initiatorJob.cancel()

        // Waiter job should still be active
        assertTrue(waiterJob.isActive)

        // Unblock service
        serviceDeferred.complete(AppResult.Success(listOf(summary("unblocked_item"))))

        // Assert waiter succeeds
        val waiterRes = waiterJob.await()
        assertTrue("Waiter must receive success even if initiator was cancelled", waiterRes is AppResult.Success<*>)
        assertEquals("unblocked_item", (waiterRes as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)

        // Next call should hit cache (or start new if expired, but here within TTL) without reusing in-flight map
        val cachedRes = repository.getTrending(forceRefresh = false)
        assertTrue(cachedRes is AppResult.Success<*>)
        assertEquals("unblocked_item", (cachedRes as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)
        // Only 1 call to service was made
        assertEquals(1, fakeService.trendingCallCount)
    }

    @Test
    fun initiator_cancellation_while_search_service_blocked_leaves_waiter_unaffected_and_cleans_up_map() = runTest {
        val fakeService = FakeVideoService()
        val searchServiceDeferred = kotlinx.coroutines.CompletableDeferred<AppResult<SearchPage>>()
        fakeService.searchHandler = { q, f, t ->
            searchServiceDeferred.await()
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 10000L,
            timeProvider = { 1000L }
        )

        val initiatorJob = async { repository.search("shared_query", SearchFilter.ALL) }
        kotlinx.coroutines.delay(10)

        val waiterJob = async { repository.search("shared_query", SearchFilter.ALL) }
        kotlinx.coroutines.delay(10)

        initiatorJob.cancel()
        assertTrue(waiterJob.isActive)

        searchServiceDeferred.complete(
            AppResult.Success(SearchPage(items = listOf(SearchResultItem.VideoItem(summary("unblocked_search")))))
        )

        val waiterRes = waiterJob.await()
        assertTrue("Waiter must receive success even if initiator was cancelled", waiterRes is AppResult.Success<*>)
        val item = (waiterRes as AppResult.Success<SearchPage>).value.items.first() as SearchResultItem.VideoItem
        assertEquals("unblocked_search", item.summary.key.nativeId)

        // Cache hit without new service call
        val cachedRes = repository.search("shared_query", SearchFilter.ALL)
        assertTrue(cachedRes is AppResult.Success<*>)
        assertEquals(1, fakeService.searchCallCount)
    }

    @Test
    fun trending_force_refresh_newer_response_wins_and_stale_response_does_not_overwrite_cache() = runTest {
        var callCount = 0
        val fakeService = FakeVideoService()
        fakeService.trendingHandler = {
            callCount++
            val call = callCount
            if (call == 1) {
                // Request 1 is delayed (stale)
                kotlinx.coroutines.delay(100)
                AppResult.Success(listOf(summary("stale_call_1")))
            } else {
                // Request 2 (forceRefresh) completes faster
                kotlinx.coroutines.delay(20)
                AppResult.Success(listOf(summary("fresh_call_2")))
            }
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 10000L,
            timeProvider = { 1000L }
        )

        // Launch call 1
        val call1Deferred = async { repository.getTrending(forceRefresh = false) }
        kotlinx.coroutines.delay(10)

        // Launch call 2 with forceRefresh
        val call2Deferred = async { repository.getTrending(forceRefresh = true) }

        val res2 = call2Deferred.await()
        val res1 = call1Deferred.await()

        assertTrue(res1 is AppResult.Success<*>)
        assertTrue(res2 is AppResult.Success<*>)
        assertEquals("stale_call_1", (res1 as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)
        assertEquals("fresh_call_2", (res2 as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)

        // Now verify cache contains fresh_call_2 and was NOT overwritten by stale_call_1
        val cachedRes = repository.getTrending(forceRefresh = false)
        assertTrue(cachedRes is AppResult.Success<*>)
        assertEquals("fresh_call_2", (cachedRes as AppResult.Success<List<VideoSummary>>).value.first().key.nativeId)
        assertEquals(2, fakeService.trendingCallCount) // Did not trigger call 3
    }

    @Test
    fun search_force_refresh_newer_response_wins_and_stale_response_does_not_overwrite_cache() = runTest {
        var callCount = 0
        val fakeService = FakeVideoService()
        fakeService.searchHandler = { q, f, t ->
            callCount++
            val call = callCount
            if (call == 1) {
                kotlinx.coroutines.delay(100)
                AppResult.Success(SearchPage(items = listOf(SearchResultItem.VideoItem(summary("stale_search_1")))))
            } else {
                kotlinx.coroutines.delay(20)
                AppResult.Success(SearchPage(items = listOf(SearchResultItem.VideoItem(summary("fresh_search_2")))))
            }
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 10000L,
            timeProvider = { 1000L }
        )

        val call1Deferred = async { repository.search("query", SearchFilter.ALL, pageToken = null, forceRefresh = false) }
        kotlinx.coroutines.delay(10)

        val call2Deferred = async { repository.search("query", SearchFilter.ALL, pageToken = null, forceRefresh = true) }

        val res2 = call2Deferred.await()
        val res1 = call1Deferred.await()

        assertTrue(res1 is AppResult.Success<*>)
        assertTrue(res2 is AppResult.Success<*>)

        // Verify cache contains fresh_search_2, not stale_search_1
        val cachedRes = repository.search("query", SearchFilter.ALL, pageToken = null, forceRefresh = false)
        assertTrue(cachedRes is AppResult.Success<*>)
        val item = (cachedRes as AppResult.Success<SearchPage>).value.items.first() as SearchResultItem.VideoItem
        assertEquals("fresh_search_2", item.summary.key.nativeId)
        assertEquals(2, fakeService.searchCallCount)
    }

    @Test
    fun video_service_non_cancellation_exception_maps_to_failure_unknown() = runTest {
        val fakeService = FakeVideoService()
        fakeService.trendingHandler = {
            throw RuntimeException("Simulated unexpected network crash")
        }
        fakeService.searchHandler = { _, _, _ ->
            throw IllegalStateException("Simulated extractor parsing state explosion")
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { 1000L }
        )

        val trendingRes = repository.getTrending(forceRefresh = false)
        assertTrue(trendingRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (trendingRes as AppResult.Failure).error)

        val searchRes = repository.search("fail", SearchFilter.ALL)
        assertTrue(searchRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (searchRes as AppResult.Failure).error)
    }

    @Test
    fun repository_scope_cancellation_cancels_underlying_service_and_cleans_up_in_flight() = runTest {
        val fakeService = FakeVideoService()
        var serviceCancelled = false
        val serviceStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

        fakeService.trendingHandler = {
            serviceStarted.complete(Unit)
            try {
                kotlinx.coroutines.awaitCancellation()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                serviceCancelled = true
                throw ce
            }
        }

        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        val repository = CatalogRepository(
            videoService = fakeService,
            ttlMs = 5000L,
            timeProvider = { 1000L },
            repositoryScope = testScope
        )

        // Caller 1 (initiator) launches in a separate scope
        val caller1Job = async {
            try {
                repository.getTrending(forceRefresh = false)
            } catch (e: Exception) {
                e
            }
        }

        serviceStarted.await()

        // Caller 2 (waiter) joins in flight
        val caller2Job = async {
            try {
                repository.getTrending(forceRefresh = false)
            } catch (e: Exception) {
                e
            }
        }

        // Cancel repository scope
        testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        // Both awaiting callers should complete without hanging
        val caller1Result = caller1Job.await()
        val caller2Result = caller2Job.await()

        assertTrue("Caller 1 must finish when repo scope cancels", caller1Result is kotlinx.coroutines.CancellationException || caller1Result is AppResult.Failure)
        assertTrue("Caller 2 must finish when repo scope cancels", caller2Result is kotlinx.coroutines.CancellationException || caller2Result is AppResult.Failure)
        assertTrue("Underlying service coroutine must observe cancellation", serviceCancelled)

        // Verify that in-flight map is completely cleared on cancellation
        assertEquals(0, repository.inFlightCountForTest)
    }

    @Test
    fun video_details_caches_within_ttl_and_forceRefresh_bypasses() = runTest {
        var now = 1000L
        val testVideoKey = ContentKey(0, "video_detail_1")
        val sampleDetail = VideoDetails(
            key = testVideoKey,
            title = "Detail Title",
            canonicalUrl = "https://example.com/watch?v=video_detail_1",
            description = "Detail Desc",
            channelKey = ContentKey(0, "c_1"),
            channelName = "Channel 1",
            channelAvatarUrl = null,
            subscriberCountText = "10K",
            thumbnailUrl = null,
            durationSeconds = 120,
            viewCount = 1000,
            likeCount = 50,
            publishedTimestamp = 10000L
        )

        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(sampleDetail) }
        )
        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { now }
        )

        // 1. Initial fetch: calls service
        var serviceCalls = 0
        fakeService.videoHandler = {
            serviceCalls++
            AppResult.Success(sampleDetail)
        }

        val firstRes = repository.video(testVideoKey, forceRefresh = false)
        assertTrue(firstRes is AppResult.Success<*>)
        assertEquals(1, serviceCalls)

        // 2. Cache hit within TTL
        now += 2000L
        val secondRes = repository.video(testVideoKey, forceRefresh = false)
        assertTrue(secondRes is AppResult.Success<*>)
        assertEquals(1, serviceCalls)

        // 3. Expiry after TTL
        now += 4000L // Elapsed 6000L > 5000L
        val thirdRes = repository.video(testVideoKey, forceRefresh = false)
        assertTrue(thirdRes is AppResult.Success<*>)
        assertEquals(2, serviceCalls)

        // 4. forceRefresh bypasses cache
        val forcedRes = repository.video(testVideoKey, forceRefresh = true)
        assertTrue(forcedRes is AppResult.Success<*>)
        assertEquals(3, serviceCalls)
    }

    @Test
    fun concurrent_identical_video_detail_and_search_append_requests_are_deduplicated() = runTest {
        val testVideoKey = ContentKey(0, "video_dedup")
        val sampleDetail = VideoDetails(
            key = testVideoKey,
            title = "Detail Dedup",
            canonicalUrl = "https://example.com/watch?v=video_dedup",
            description = "Detail Desc",
            channelKey = ContentKey(0, "c_1"),
            channelName = "Channel 1",
            channelAvatarUrl = null,
            subscriberCountText = "10K",
            thumbnailUrl = null,
            durationSeconds = 120,
            viewCount = 1000,
            likeCount = 50,
            publishedTimestamp = 10000L
        )

        val fakeService = FakeVideoService()
        var detailCalls = 0
        fakeService.videoHandler = {
            kotlinx.coroutines.delay(50)
            detailCalls++
            AppResult.Success(sampleDetail)
        }

        var appendCalls = 0
        fakeService.searchHandler = { q, f, t ->
            kotlinx.coroutines.delay(50)
            appendCalls++
            AppResult.Success(SearchPage(items = listOf(SearchResultItem.VideoItem(summary("append_item")))))
        }

        val repository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = this,
            ttlMs = 5000L,
            timeProvider = { 1000L }
        )

        // Concurrent video detail calls coalesce
        val detailJob1 = async { repository.video(testVideoKey) }
        val detailJob2 = async { repository.video(testVideoKey) }

        val detailRes1 = detailJob1.await()
        val detailRes2 = detailJob2.await()

        assertTrue(detailRes1 is AppResult.Success<*>)
        assertTrue(detailRes2 is AppResult.Success<*>)
        assertEquals(1, detailCalls)

        // Concurrent search append (with token) calls coalesce via typed distinct RequestKey
        val token = PageToken.Id("page_token_abc")
        val appendJob1 = async { repository.search("query", SearchFilter.ALL, pageToken = token) }
        val appendJob2 = async { repository.search("  query  ", SearchFilter.ALL, pageToken = token) }

        val appendRes1 = appendJob1.await()
        val appendRes2 = appendJob2.await()

        assertTrue(appendRes1 is AppResult.Success<*>)
        assertTrue(appendRes2 is AppResult.Success<*>)
        assertEquals(1, appendCalls)
    }
}
