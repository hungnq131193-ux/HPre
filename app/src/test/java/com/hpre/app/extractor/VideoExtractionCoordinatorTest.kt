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

    @Test fun signed_url_expiry_does_not_evict_cached_metadata_bundle() = runTest {
        var now = 100_000L
        val coordinator = VideoExtractionCoordinator(scope = this, ttlMs = 20_000L, nowMs = { now })
        val key = ContentKey(0, "signed")
        var calls = 0
        suspend fun load() = coordinator.execute(key) { calls++; AppResult.Success(bundle(key)) }

        load()
        now = 112_000L
        load()
        assertEquals(1, calls)
        now = 120_000L
        load()
        assertEquals(2, calls)
    }

    @Test fun failed_refresh_preserves_the_previous_metadata_bundle() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "refresh-failure")
        val old = bundle(key)
        coordinator.execute(key) { AppResult.Success(old) }

        assertEquals(
            AppResult.Failure(AppError.NetworkError),
            coordinator.execute(key, forceRefresh = true) { AppResult.Failure(AppError.NetworkError) }
        )
        assertEquals(AppResult.Success(old), coordinator.execute(key) { error("old cache must survive") })
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

    @Test fun forced_refresh_bypasses_cached_signed_stream_urls() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "expired")
        val old = bundle(key)
        val fresh = old.copy(streamInfo = old.streamInfo.copy(title = "fresh URL generation"))
        coordinator.execute(key) { AppResult.Success(old) }
        val result = coordinator.execute(key, forceRefresh = true) { AppResult.Success(fresh) }
        assertEquals(AppResult.Success(fresh), result)
        assertEquals(result, coordinator.execute(key) { error("fresh result should be cached") })
    }

    @Test fun concurrent_refreshes_share_new_work_and_old_completion_cannot_poison_cache() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "refresh-in-flight")
        val oldGate = CompletableDeferred<Unit>()
        val freshGate = CompletableDeferred<Unit>()
        val oldBundle = bundle(key)
        val freshBundle = oldBundle.copy(streamInfo = oldBundle.streamInfo.copy(title = "refreshed"))
        val old = async { coordinator.execute(key) { oldGate.await(); AppResult.Success(oldBundle) } }
        runCurrent()
        val fresh = async { coordinator.execute(key, true) { freshGate.await(); AppResult.Success(freshBundle) } }
        val anotherRefresh = async { coordinator.execute(key, true) { error("must join refresh") } }
        runCurrent()
        freshGate.complete(Unit)
        assertEquals(AppResult.Success(freshBundle), fresh.await())
        assertEquals(fresh.await(), anotherRefresh.await())
        oldGate.complete(Unit)
        assertEquals(AppResult.Success(oldBundle), old.await())
        assertEquals(fresh.await(), coordinator.execute(key) { error("old request replaced fresh cache") })
    }

    @Test fun normal_request_joins_active_refresh_and_second_refresh_does_not_run_loader() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "refresh-generation")
        val gate = CompletableDeferred<Unit>()
        var refreshCalls = 0
        val refresh = async { coordinator.execute(key, true) {
            refreshCalls++; gate.await(); AppResult.Success(bundle(key))
        } }
        runCurrent()
        val normal = async { coordinator.execute(key) { error("normal must join refresh") } }
        val secondRefresh = async { coordinator.execute(key, true) { error("refresh must join refresh") } }
        gate.complete(Unit)
        assertEquals(refresh.await(), normal.await())
        assertEquals(refresh.await(), secondRefresh.await())
        assertEquals(1, refreshCalls)
    }

    @Test fun normal_replaced_by_refresh_cancelled_before_normal_c_late_a_cannot_poison_c() = runTest {
        val coordinator = VideoExtractionCoordinator(this)
        val key = ContentKey(0, "complex-race")
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val gateC = CompletableDeferred<Unit>()
        val bundleA = bundle(key).copy(details = bundle(key).details.copy(title = "A"))
        val bundleB = bundle(key).copy(details = bundle(key).details.copy(title = "B"))
        val bundleC = bundle(key).copy(details = bundle(key).details.copy(title = "C"))

        var callsA = 0
        var callsB = 0
        var callsC = 0

        // 1. Normal A starts
        val jobA = async {
            coordinator.execute(key) {
                callsA++
                gateA.await()
                AppResult.Success(bundleA)
            }
        }
        runCurrent()
        assertEquals(1, callsA)

        // 2. Normal A is replaced by refresh B
        val jobB = async {
            coordinator.execute(key, forceRefresh = true) {
                callsB++
                gateB.await()
                AppResult.Success(bundleB)
            }
        }
        runCurrent()
        assertEquals(1, callsB)

        // 3. B's only waiter is cancelled
        jobB.cancel()
        runCurrent()

        // 4. Normal C starts
        val jobC = async {
            coordinator.execute(key) {
                callsC++
                gateC.await()
                AppResult.Success(bundleC)
            }
        }
        runCurrent()
        assertEquals(1, callsC)

        // 5. A completes late
        gateA.complete(Unit)
        assertEquals(AppResult.Success(bundleA), jobA.await())
        runCurrent()

        // 6. C completes
        gateC.complete(Unit)
        assertEquals(AppResult.Success(bundleC), jobC.await())

        // A cannot poison C's cache, cached value must be C
        val cached = coordinator.execute(key) { error("Cache should contain C") }
        assertEquals(AppResult.Success(bundleC), cached)

        // All in-flight entries clean up
        assertEquals(0, coordinator.inFlightCountForTest)
    }
}
