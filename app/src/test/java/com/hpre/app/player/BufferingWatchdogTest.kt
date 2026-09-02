package com.hpre.app.player

import androidx.media3.common.Player
import com.hpre.app.model.ContentKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BufferingWatchdogTest {

    private val testScope = TestScope()
    private val testDispatcher = StandardTestDispatcher(testScope.testScheduler)

    @Test
    fun watchdog_arms_on_buffering_and_fires_timeout_after_configured_delay() = testScope.runTest {
        var timeoutFiredCount = 0
        var firedSessionGen = 0L
        var firedMediaGen = 0L

        val watchdog = BufferingWatchdog(
            scope = testScope,
            dispatcher = testDispatcher,
            timeoutMs = 15_000L,
            onTimeout = { sessionGen, mediaGen ->
                timeoutFiredCount++
                firedSessionGen = sessionGen
                firedMediaGen = mediaGen
            }
        )

        watchdog.onPrepare(sessionGen = 1L, mediaGen = 10L)
        assertFalse(watchdog.isArmed)

        // Enter buffering before first frame
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_BUFFERING,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.PROGRESSIVE
        )
        assertTrue(watchdog.isArmed)

        // Advance time before timeout
        testScheduler.advanceTimeBy(14_000L)
        testScheduler.runCurrent()
        assertEquals(0, timeoutFiredCount)
        assertTrue(watchdog.isArmed)

        // Advance time past 15s timeout
        testScheduler.advanceTimeBy(1_100L)
        testScheduler.runCurrent()

        assertEquals(1, timeoutFiredCount)
        assertEquals(1L, firedSessionGen)
        assertEquals(10L, firedMediaGen)
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun watchdog_disarms_and_cancels_when_first_frame_rendered() = testScope.runTest {
        var timeoutFiredCount = 0
        val watchdog = BufferingWatchdog(
            scope = testScope,
            dispatcher = testDispatcher,
            timeoutMs = 15_000L,
            onTimeout = { _, _ -> timeoutFiredCount++ }
        )

        watchdog.onPrepare(sessionGen = 1L, mediaGen = 1L)
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_BUFFERING,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.PROGRESSIVE
        )
        assertTrue(watchdog.isArmed)

        // First frame arrives at 5s
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_BUFFERING,
            renderedFirstFrameCount = 1,
            streamType = PlaybackStreamType.PROGRESSIVE
        )
        assertFalse(watchdog.isArmed)

        // Advance past 15s: timeout must NOT fire
        testScheduler.advanceTimeBy(20_000L)
        testScheduler.runCurrent()
        assertEquals(0, timeoutFiredCount)
    }

    @Test
    fun watchdog_disarms_when_audio_only_reaches_ready() = testScope.runTest {
        var timeoutFiredCount = 0
        val watchdog = BufferingWatchdog(
            scope = testScope,
            dispatcher = testDispatcher,
            timeoutMs = 15_000L,
            onTimeout = { _, _ -> timeoutFiredCount++ }
        )

        watchdog.onPrepare(sessionGen = 1L, mediaGen = 1L)
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_BUFFERING,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.AUDIO_ONLY
        )
        assertTrue(watchdog.isArmed)

        // Audio-only reaches READY
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_READY,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.AUDIO_ONLY
        )
        assertFalse(watchdog.isArmed)

        testScheduler.advanceTimeBy(20_000L)
        testScheduler.runCurrent()
        assertEquals(0, timeoutFiredCount)
    }

    @Test
    fun watchdog_cancels_when_leaving_buffering_state() = testScope.runTest {
        var timeoutFiredCount = 0
        val watchdog = BufferingWatchdog(
            scope = testScope,
            dispatcher = testDispatcher,
            timeoutMs = 15_000L,
            onTimeout = { _, _ -> timeoutFiredCount++ }
        )

        watchdog.onPrepare(sessionGen = 1L, mediaGen = 1L)
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_BUFFERING,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.PROGRESSIVE
        )
        assertTrue(watchdog.isArmed)

        // Leaves buffering to READY
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_READY,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.PROGRESSIVE
        )
        assertFalse(watchdog.isArmed)

        testScheduler.advanceTimeBy(20_000L)
        testScheduler.runCurrent()
        assertEquals(0, timeoutFiredCount)
    }

    @Test
    fun stale_watchdog_timeout_is_cancelled_by_new_prepare_or_session_change() = testScope.runTest {
        var timeoutFiredCount = 0
        val watchdog = BufferingWatchdog(
            scope = testScope,
            dispatcher = testDispatcher,
            timeoutMs = 15_000L,
            onTimeout = { _, _ -> timeoutFiredCount++ }
        )

        // Session 1 buffers
        watchdog.onPrepare(sessionGen = 1L, mediaGen = 1L)
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_BUFFERING,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.PROGRESSIVE
        )
        assertTrue(watchdog.isArmed)

        // At 10s, new prepare for Session 2 arrives
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        watchdog.onPrepare(sessionGen = 2L, mediaGen = 2L)
        assertFalse("New prepare must cancel pending watchdog", watchdog.isArmed)

        // Advance past original 15s
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(0, timeoutFiredCount)
    }

    @Test
    fun watchdog_cancel_and_reset_disarm_cleanly() = testScope.runTest {
        var timeoutFiredCount = 0
        val watchdog = BufferingWatchdog(
            scope = testScope,
            dispatcher = testDispatcher,
            timeoutMs = 15_000L,
            onTimeout = { _, _ -> timeoutFiredCount++ }
        )

        watchdog.onPrepare(sessionGen = 1L, mediaGen = 1L)
        watchdog.onPlaybackStateOrRenderChanged(
            playbackState = Player.STATE_BUFFERING,
            renderedFirstFrameCount = 0,
            streamType = PlaybackStreamType.PROGRESSIVE
        )
        assertTrue(watchdog.isArmed)

        watchdog.reset()
        assertFalse(watchdog.isArmed)

        testScheduler.advanceTimeBy(20_000L)
        testScheduler.runCurrent()
        assertEquals(0, timeoutFiredCount)
    }
}
