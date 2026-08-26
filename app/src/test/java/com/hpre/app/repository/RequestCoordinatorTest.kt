package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestCoordinatorTest {

    private fun sampleSummary(id: String) = VideoSummary(
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

    private fun sampleDetails(id: String) = VideoDetails(
        key = ContentKey(0, id),
        title = "Details $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        description = "Desc",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = null,
        subscriberCountText = "1K",
        thumbnailUrl = null,
        durationSeconds = 120,
        viewCount = 1000,
        likeCount = 10,
        publishedTimestamp = 10000L
    )

    @Test
    fun request_key_equals_and_hashcode_work_correctly() {
        val key1 = RequestKey.trending("test_id")
        val key2 = RequestKey.trending("test_id")
        val key3 = RequestKey.searchFirst("test_id")
        val key4 = RequestKey.trending("other_id")

        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
        assertFalse(key1 == key3)
        assertFalse(key1 == key4)
        assertEquals("RequestKey(id=test_id, kind=Trending)", key1.toString())
    }

    @Test
    fun coalesces_concurrent_identical_requests() = runTest {
        val coordinator = RequestCoordinator(this)
        var executionCount = 0

        val key = RequestKey.trending("key1")
        val expected = listOf(sampleSummary("1"))
        val call1 = async {
            coordinator.execute(key) {
                delay(50)
                executionCount++
                AppResult.Success(expected)
            }
        }
        val call2 = async {
            coordinator.execute(key) {
                delay(50)
                executionCount++
                AppResult.Success(expected)
            }
        }

        val res1 = call1.await()
        val res2 = call2.await()

        assertEquals(1, executionCount)
        assertEquals(AppResult.Success(expected), res1)
        assertEquals(AppResult.Success(expected), res2)
    }

    @Test
    fun caller_cancellation_releases_subscriber_and_cancels_upstream_when_all_subscribers_cancel() = runTest {
        val coordinator = RequestCoordinator(this)
        var upstreamStarted = false
        var upstreamCancelled = false
        val key = RequestKey.trending("key_cancel")

        val call1 = async {
            coordinator.execute(key) {
                upstreamStarted = true
                try {
                    delay(500)
                    AppResult.Success(emptyList())
                } catch (ce: CancellationException) {
                    upstreamCancelled = true
                    throw ce
                }
            }
        }

        delay(20)
        assertTrue(upstreamStarted)
        assertEquals(1, coordinator.inFlightCountForTest)
        assertEquals(1, coordinator.subscriberCountForTest(key))

        // Cancel the only subscriber
        call1.cancel()
        advanceUntilIdle()

        assertTrue("Upstream work should be cancelled when the last subscriber cancels", upstreamCancelled)
        assertEquals(0, coordinator.inFlightCountForTest)
    }

    @Test
    fun caller_cancellation_does_not_cancel_upstream_if_other_subscribers_remain() = runTest {
        val coordinator = RequestCoordinator(this)
        var executionCount = 0
        val key = RequestKey.trending("key1")
        val expected = listOf(sampleSummary("1"))

        val call1 = async {
            coordinator.execute(key) {
                delay(100)
                executionCount++
                AppResult.Success(expected)
            }
        }
        val call2 = async {
            coordinator.execute(key) {
                delay(100)
                executionCount++
                AppResult.Success(expected)
            }
        }

        delay(20)
        assertEquals(2, coordinator.subscriberCountForTest(key))
        call1.cancel()
        delay(10)
        assertEquals(1, coordinator.subscriberCountForTest(key))

        val res2 = call2.await()
        assertEquals(AppResult.Success(expected), res2)
        assertEquals(1, executionCount)
        assertEquals(0, coordinator.inFlightCountForTest)
    }

    @Test
    fun different_type_kinds_do_not_collide_with_same_id() = runTest {
        val coordinator = RequestCoordinator(this)
        val searchKey = RequestKey.searchFirst("shared_id")
        val detailsKey = RequestKey.videoDetails("shared_id")

        var searchExec = 0
        var detailsExec = 0

        val searchPage = SearchPage(emptyList(), null)
        val videoDetails = sampleDetails("shared_id")

        val callSearch = async {
            coordinator.execute(searchKey) {
                delay(50)
                searchExec++
                AppResult.Success(searchPage)
            }
        }
        val callDetails = async {
            coordinator.execute(detailsKey) {
                delay(50)
                detailsExec++
                AppResult.Success(videoDetails)
            }
        }

        val resSearch = callSearch.await()
        val resDetails = callDetails.await()

        assertEquals(1, searchExec)
        assertEquals(1, detailsExec)
        assertEquals(AppResult.Success(searchPage), resSearch)
        assertEquals(AppResult.Success(videoDetails), resDetails)
    }

    @Test
    fun canceled_request_cleanup_does_not_erase_replacement_request() = runTest {
        val coordinator = RequestCoordinator(this)
        val key = RequestKey.trending("replacement_race_test")
        val block1Started = CompletableDeferred<Unit>()
        val block2Finished = CompletableDeferred<Unit>()

        // 1. Start call 1 and wait until it is in-flight
        val call1 = async {
            coordinator.execute(key) {
                block1Started.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } catch (ce: CancellationException) {
                    throw ce
                }
            }
        }

        block1Started.await()
        assertEquals(1, coordinator.inFlightCountForTest)

        // 2. Cancel call1
        call1.cancel()

        // 3. Immediately start call 2 with replacement request on same key before call1's finally has finished or in parallel
        val expected = listOf(sampleSummary("replacement"))
        val call2 = async {
            coordinator.execute(key) {
                block2Finished.complete(Unit)
                AppResult.Success(expected)
            }
        }

        val res2 = call2.await()
        assertEquals(AppResult.Success(expected), res2)
    }

    @Test
    fun close_cancels_and_clears_all_in_flight() = runTest {
        val coordinator = RequestCoordinator(this)
        var cancelled1 = false
        var cancelled2 = false
        val started1 = CompletableDeferred<Unit>()
        val started2 = CompletableDeferred<Unit>()

        val key1 = RequestKey.trending("req1")
        val key2 = RequestKey.videoDetails("req2")

        val call1 = async {
            try {
                coordinator.execute(key1) {
                    started1.complete(Unit)
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } catch (ce: CancellationException) {
                        cancelled1 = true
                        throw ce
                    }
                }
            } catch (e: Throwable) {
                e
            }
        }

        val call2 = async {
            try {
                coordinator.execute(key2) {
                    started2.complete(Unit)
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } catch (ce: CancellationException) {
                        cancelled2 = true
                        throw ce
                    }
                }
            } catch (e: Throwable) {
                e
            }
        }

        started1.await()
        started2.await()
        assertEquals(2, coordinator.inFlightCountForTest)

        coordinator.close()

        val r1 = call1.await()
        val r2 = call2.await()

        assertTrue("Work 1 must be cancelled", cancelled1)
        assertTrue("Work 2 must be cancelled", cancelled2)
        assertEquals(0, coordinator.inFlightCountForTest)
    }

    @Test
    fun scope_cancellation_cancels_in_flight_work_and_cleans_up() = runTest {
        val testScope = CoroutineScope(kotlinx.coroutines.Job())
        val coordinator = RequestCoordinator(testScope)
        var upstreamCancelled = false
        val key = RequestKey.trending("scope_cancel")

        val call = async {
            try {
                coordinator.execute(key) {
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } catch (ce: CancellationException) {
                        upstreamCancelled = true
                        throw ce
                    }
                }
            } catch (e: Exception) {
                e
            }
        }

        delay(20)
        testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        call.await()
        assertTrue(upstreamCancelled)
        assertEquals(0, coordinator.inFlightCountForTest)
    }

    @Test
    fun executes_sequentially_after_completion() = runTest {
        val coordinator = RequestCoordinator(this)
        var executionCount = 0
        val key = RequestKey.trending("key1")

        val expected1 = listOf(sampleSummary("first"))
        val expected2 = listOf(sampleSummary("second"))

        val res1 = coordinator.execute(key) {
            executionCount++
            AppResult.Success(expected1)
        }
        val res2 = coordinator.execute(key) {
            executionCount++
            AppResult.Success(expected2)
        }

        assertEquals(2, executionCount)
        assertEquals(AppResult.Success(expected1), res1)
        assertEquals(AppResult.Success(expected2), res2)
    }
}

