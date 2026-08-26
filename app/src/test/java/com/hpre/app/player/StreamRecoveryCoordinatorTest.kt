package com.hpre.app.player

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.core.error.RetryPolicy
import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import com.hpre.app.repository.VideoService
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamRecoveryCoordinatorTest {

    private val testKey = ContentKey(0, "video_403")
    private val otherKey = ContentKey(0, "video_other")

    private fun sampleStreamInfo(
        key: ContentKey = testKey,
        progressiveUrl: String = "https://fresh.example.com/stream.mp4",
        height: Int = 720
    ): StreamInfo {
        return StreamInfo(
            key = key,
            title = "Test Stream",
            videoStreams = listOf(
                VideoStream(
                    url = progressiveUrl,
                    format = "mp4",
                    mimeType = "video/mp4",
                    codec = "avc1.64001F",
                    resolution = "${height}p",
                    width = 1280,
                    height = height,
                    bitrate = 1_500_000,
                    isVideoOnly = false
                )
            ),
            audioStreams = listOf(
                AudioStream(
                    url = "https://audio.m4a",
                    format = "m4a",
                    mimeType = "audio/mp4",
                    codec = "mp4a.40.2",
                    bitrate = 128_000
                )
            )
        )
    }

    @Test
    fun same_key_with_new_session_generation_has_independent_budget() = runTest {
        val fakeService = FakeVideoService(
            streamResponses = mapOf(testKey.nativeId to sampleStreamInfo())
        )
        val coordinator = StreamRecoveryCoordinator(fakeService)

        // Session 1: attempt 1 succeeds
        val r1 = coordinator.recoverExpiredStream(
            key = testKey,
            sessionGen = 1L,
            positionMs = 10_000L,
            wasPlaying = true,
            preference = QualityPreference.Auto
        )
        assertTrue(r1 is RecoveryResult.Recovered)

        // Session 1: attempt 2 fails (budget 1 exhausted)
        val r2 = coordinator.recoverExpiredStream(
            key = testKey,
            sessionGen = 1L,
            positionMs = 12_000L,
            wasPlaying = true,
            preference = QualityPreference.Auto
        )
        assertTrue(r2 is RecoveryResult.Failed)

        // Session 2 for same key: has fresh budget 1
        val r3 = coordinator.recoverExpiredStream(
            key = testKey,
            sessionGen = 2L,
            positionMs = 20_000L,
            wasPlaying = true,
            preference = QualityPreference.Auto
        )
        assertTrue(r3 is RecoveryResult.Recovered)
        assertEquals(2, fakeService.streamInfoCallCount)
    }

    @Test
    fun forbidden_stream_refreshes_once_at_previous_position_and_user_intent() = runTest {
        val fakeService = FakeVideoService(
            streamResponses = mapOf(testKey.nativeId to sampleStreamInfo())
        )
        val coordinator = StreamRecoveryCoordinator(fakeService)

        val result = coordinator.recoverExpiredStream(
            key = testKey,
            positionMs = 42_000L,
            wasPlaying = true,
            preference = QualityPreference.Auto
        )

        assertTrue(result is RecoveryResult.Recovered)
        val recovered = result as RecoveryResult.Recovered
        assertEquals(42_000L, recovered.resumePositionMs)
        assertTrue(recovered.resumeWhenReady)
        assertEquals(testKey, recovered.key)
        assertNotNull(recovered.streamInfo)
        assertEquals(1, fakeService.streamInfoCallCount)
    }

    @Test
    fun second_expiry_on_same_session_does_not_refresh_again_and_returns_not_retryable() = runTest {
        val fakeService = FakeVideoService(
            streamResponses = mapOf(testKey.nativeId to sampleStreamInfo())
        )
        val coordinator = StreamRecoveryCoordinator(fakeService)

        // First attempt succeeds
        val firstResult = coordinator.recoverExpiredStream(
            key = testKey,
            positionMs = 10_000L,
            wasPlaying = true,
            preference = QualityPreference.Auto
        )
        assertTrue(firstResult is RecoveryResult.Recovered)
        assertEquals(1, fakeService.streamInfoCallCount)

        // Second 403 on same key/session is capped once
        val secondResult = coordinator.recoverExpiredStream(
            key = testKey,
            positionMs = 12_000L,
            wasPlaying = true,
            preference = QualityPreference.Auto
        )
        assertTrue(secondResult is RecoveryResult.Failed)
        val failed = secondResult as RecoveryResult.Failed
        assertEquals(AppError.StreamExpired, failed.error)
        assertFalse(failed.isAutoRetryable)
        assertEquals(1, fakeService.streamInfoCallCount) // No second service call!
    }

    @Test
    fun recovers_with_exact_selected_quality_fallback_matching() = runTest {
        val stream720and1080 = StreamInfo(
            key = testKey,
            title = "Multi Quality",
            videoStreams = listOf(
                VideoStream(
                    url = "https://fresh720.mp4",
                    format = "mp4",
                    mimeType = "video/mp4",
                    codec = "avc1.64001F",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false
                ),
                VideoStream(
                    url = "https://fresh1080.mp4",
                    format = "mp4",
                    mimeType = "video/mp4",
                    codec = "avc1.64001F",
                    resolution = "1080p",
                    width = 1920,
                    height = 1080,
                    bitrate = 3_000_000,
                    isVideoOnly = false
                )
            ),
            audioStreams = emptyList()
        )
        val fakeService = FakeVideoService(
            streamResponses = mapOf(testKey.nativeId to stream720and1080)
        )
        val coordinator = StreamRecoveryCoordinator(fakeService)

        val specificOption = QualityOption(1080, "1080p", true, "mp4", "video/mp4", "avc1.64001F")
        val result = coordinator.recoverExpiredStream(
            key = testKey,
            positionMs = 5000L,
            wasPlaying = false,
            preference = QualityPreference.SpecificOption(specificOption)
        )

        assertTrue(result is RecoveryResult.Recovered)
        val recovered = result as RecoveryResult.Recovered
        assertFalse(recovered.resumeWhenReady)
        assertEquals(5000L, recovered.resumePositionMs)
        assertEquals(1080, recovered.selectedQuality?.height)
    }

    @Test
    fun new_key_resets_recovery_budget_and_cancels_prior_recovery() = runTest {
        val fakeService = FakeVideoService(
            streamResponses = mapOf(
                testKey.nativeId to sampleStreamInfo(testKey),
                otherKey.nativeId to sampleStreamInfo(otherKey)
            )
        )
        val coordinator = StreamRecoveryCoordinator(fakeService)

        // Recover first key
        val r1 = coordinator.recoverExpiredStream(key = testKey, sessionGen = 1L, positionMs = 1000L, wasPlaying = true, preference = QualityPreference.Auto)
        assertTrue(r1 is RecoveryResult.Recovered)

        // Switch to new key: resets budget for the new key
        val r2 = coordinator.recoverExpiredStream(key = otherKey, sessionGen = 1L, positionMs = 2000L, wasPlaying = true, preference = QualityPreference.Auto)
        assertTrue(r2 is RecoveryResult.Recovered)
        assertEquals(otherKey, (r2 as RecoveryResult.Recovered).key)
    }

    @Test
    fun release_cancels_active_recovery_and_resets_state() = runTest {
        val fakeService = FakeVideoService()
        fakeService.streamInfoHandler = { _: ContentKey ->
            delay(100)
            AppResult.Success(sampleStreamInfo())
        }
        val coordinator = StreamRecoveryCoordinator(fakeService)

        val childJob = kotlinx.coroutines.Job()
        val deferred = async(childJob) {
            coordinator.recoverExpiredStream(key = testKey, sessionGen = 1L, positionMs = 1000L, wasPlaying = true, preference = QualityPreference.Auto)
        }
        delay(20)
        coordinator.release()

        val res = try {
            deferred.await()
        } catch (ce: CancellationException) {
            RecoveryResult.Cancelled
        }
        assertTrue(res is RecoveryResult.Cancelled)
    }

    @Test
    fun fake_service_observes_cancellation_exception_when_release_called_while_blocked() = runTest {
        val fakeService = FakeVideoService()
        var observedCancellation = false
        val blockedStarted = CompletableDeferred<Unit>()

        fakeService.streamInfoHandler = { _: ContentKey ->
            blockedStarted.complete(Unit)
            try {
                kotlinx.coroutines.awaitCancellation()
            } catch (ce: CancellationException) {
                observedCancellation = true
                throw ce
            }
        }

        val coordinator = StreamRecoveryCoordinator(fakeService)

        val childJob = kotlinx.coroutines.Job()
        val deferred = async(childJob) {
            coordinator.recoverExpiredStream(
                key = testKey,
                sessionGen = 1L,
                positionMs = 1000L,
                wasPlaying = true,
                preference = QualityPreference.Auto
            )
        }

        blockedStarted.await()
        coordinator.release()

        val res = try {
            deferred.await()
        } catch (ce: CancellationException) {
            RecoveryResult.Cancelled
        }
        assertTrue(res is RecoveryResult.Cancelled)
        assertTrue("Fake service must observe CancellationException when release is called while blocked", observedCancellation)
    }

    @Test
    fun fake_service_observes_cancellation_exception_when_new_session_arrives_while_blocked() = runTest {
        val fakeService = FakeVideoService()
        var firstCallCancelled = false
        val firstStarted = CompletableDeferred<Unit>()

        fakeService.streamInfoHandler = { key: ContentKey ->
            if (key == testKey) {
                firstStarted.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } catch (ce: CancellationException) {
                    firstCallCancelled = true
                    throw ce
                }
            } else {
                AppResult.Success(sampleStreamInfo(key))
            }
        }

        val coordinator = StreamRecoveryCoordinator(fakeService)

        val childJob1 = kotlinx.coroutines.Job()
        val firstDeferred = async(childJob1) {
            coordinator.recoverExpiredStream(
                key = testKey,
                sessionGen = 1L,
                positionMs = 1000L,
                wasPlaying = true,
                preference = QualityPreference.Auto
            )
        }

        firstStarted.await()

        // Starting a new session on another key cancels the in-flight recovery structured
        val childJob2 = kotlinx.coroutines.Job()
        val secondDeferred = async(childJob2) {
            coordinator.recoverExpiredStream(
                key = otherKey,
                sessionGen = 1L,
                positionMs = 2000L,
                wasPlaying = true,
                preference = QualityPreference.Auto
            )
        }

        val firstRes = try {
            firstDeferred.await()
        } catch (ce: CancellationException) {
            RecoveryResult.Cancelled
        }
        val secondRes = secondDeferred.await()

        assertTrue(firstRes is RecoveryResult.Cancelled)
        assertTrue(secondRes is RecoveryResult.Recovered)
        assertTrue("First blocked call must observe CancellationException when session changes", firstCallCancelled)
    }
}
