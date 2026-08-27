package com.hpre.app.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistorySchedulerTest {
    @Test
    fun playing_ticks_once_per_interval_without_duplicate_jobs() = runTest {
        var writes = 0
        val scheduler = PlaybackHistoryScheduler(
            scope = backgroundScope,
            intervalMs = 10_000L,
            onWrite = { writes++ }
        )

        scheduler.update(isPlaying = true)
        scheduler.update(isPlaying = true)
        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(0, writes)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, writes)

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, writes)
    }

    @Test
    fun pause_and_stop_cancel_future_writes() = runTest {
        var writes = 0
        val scheduler = PlaybackHistoryScheduler(backgroundScope, 10_000L) { writes++ }

        scheduler.update(true)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, writes)

        scheduler.update(false)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(1, writes)

        scheduler.update(true)
        scheduler.stop()
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(1, writes)
    }
}
