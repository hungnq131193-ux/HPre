package com.hpre.app.extractor

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoExtractionCoordinatorTest {
    private fun bundle(key: ContentKey) = ExtractedVideoBundle(
        VideoDetails(key, "Title", "https://example.test/${key.nativeId}", null, null, null, null, null, null, null, null, null, null),
        StreamInfo(key, "Title"),
        emptyList()
    )

    @Test fun same_key_waiters_share_one_execution() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "same")
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun load(): AppResult<ExtractedVideoBundle> {
            calls++
            gate.await()
            return AppResult.Success(bundle(key))
        }
        val a = async { coordinator.execute(key) { load() } }
        val b = async { coordinator.execute(key) { load() } }
        val c = async { coordinator.execute(key) { load() } }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(a.await(), c.await())
        assertEquals(0, coordinator.inFlightCountForTest)
    }

    @Test fun different_keys_do_not_share() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        var calls = 0
        val a = ContentKey(0, "a")
        val b = ContentKey(0, "b")
        val results = listOf(a, b).map { key -> async {
            coordinator.execute(key) { calls++; AppResult.Success(bundle(key)) }
        } }
        results.forEach { it.await() }
        assertEquals(2, calls)
    }

    @Test fun success_obeys_ttl_and_failure_is_not_cached() = runTest {
        var now = 0L
        val coordinator = VideoExtractionCoordinator(this, ttlMs = 20_000L, nowMs = { now })
        val key = ContentKey(0, "ttl")
        var calls = 0
        suspend fun success() = coordinator.execute(key) { calls++; AppResult.Success(bundle(key)) }
        success(); success()
        assertEquals(1, calls)
        now = 20_000L
        success()
        assertEquals(2, calls)
        val failedKey = ContentKey(0, "failed")
        repeat(2) { coordinator.execute(failedKey) { calls++; AppResult.Failure(AppError.NetworkError) } }
        assertEquals(4, calls)
        assertEquals(0, coordinator.inFlightCountForTest)
    }

    @Test fun cancelling_one_waiter_does_not_cancel_remaining_waiter() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "cancel-one")
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { coordinator.execute(key) { calls++; gate.await(); AppResult.Success(bundle(key)) } }
        val second = async { coordinator.execute(key) { error("second loader must not run") } }
        runCurrent()
        first.cancel()
        runCurrent()
        gate.complete(Unit)
        assertTrue(second.await() is AppResult.Success)
        assertEquals(1, calls)
    }

    @Test fun cancelling_last_waiter_cancels_and_cleans_up() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "cancel-last")
        var cancelled = false
        val call = async {
            coordinator.execute(key) {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        runCurrent()
        call.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0, coordinator.inFlightCountForTest)
    }

    @Test fun default_cache_capacity_is_sixteen() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        repeat(17) { index ->
            val key = ContentKey(0, "$index")
            coordinator.execute(key) { AppResult.Success(bundle(key)) }
        }
        assertEquals(16, coordinator.cacheSizeForTest)
    }

    @Test fun extraction_count_increases_only_for_real_upstream_work() = runTest {
        val coordinator = VideoExtractionCoordinator(this, countExtractions = true)
        val key = ContentKey(0, "counted")

        repeat(3) {
            coordinator.execute(key) { AppResult.Success(bundle(key)) }
        }

        assertEquals(1, coordinator.extractionCountForTest(key))
    }
}
