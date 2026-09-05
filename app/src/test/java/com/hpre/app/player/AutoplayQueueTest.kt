package com.hpre.app.player

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.common.Player

class AutoplayQueueTest {
    private fun key(id: String) = ContentKey(0, id)

    @Test
    fun queue_filters_current_duplicates_and_visited_items_and_handles_each_generation_once() {
        val current = key("current")
        val first = key("first")
        val second = key("second")
        val queue = AutoplayQueue()
        queue.resetForManualStart(current)

        assertTrue(queue.updateCandidates(current, listOf(current, first, first, second, current)))
        assertEquals(first, queue.takeNext(current, sessionGeneration = 7L))
        assertNull(queue.takeNext(current, sessionGeneration = 7L))
        assertEquals(second, queue.takeNext(first, sessionGeneration = 8L))
        assertNull(queue.takeNext(second, sessionGeneration = 9L))
    }

    @Test
    fun stale_candidate_updates_are_rejected_and_manual_start_resets_loop_history() {
        val first = key("first")
        val second = key("second")
        val queue = AutoplayQueue()
        queue.resetForManualStart(first)
        queue.updateCandidates(first, listOf(second))
        assertEquals(second, queue.takeNext(first, 1L))

        assertFalse(queue.updateCandidates(first, listOf(key("stale"))))
        queue.resetForManualStart(first)
        assertTrue(queue.updateCandidates(first, listOf(second)))
        assertEquals(second, queue.takeNext(first, 2L))
    }

    @Test
    fun autoplay_requires_enabled_setting_and_an_allowed_lifecycle_state() {
        assertTrue(shouldStartAutoplay(enabled = true, lifecycleStarted = true, backgroundEnabled = false, pipActive = false))
        assertTrue(shouldStartAutoplay(enabled = true, lifecycleStarted = false, backgroundEnabled = true, pipActive = false))
        assertTrue(shouldStartAutoplay(enabled = true, lifecycleStarted = false, backgroundEnabled = false, pipActive = true))
        assertFalse(shouldStartAutoplay(enabled = false, lifecycleStarted = true, backgroundEnabled = true, pipActive = true))
        assertFalse(shouldStartAutoplay(enabled = true, lifecycleStarted = false, backgroundEnabled = false, pipActive = false))
    }

    @Test
    fun disabled_autoplay_marks_the_ended_generation_without_advancing_the_queue() {
        val current = key("current")
        val next = key("next")
        val queue = AutoplayQueue()
        queue.resetForManualStart(current)
        queue.updateCandidates(current, listOf(next))

        assertNull(queue.takeNext(current, sessionGeneration = 3L, allowAdvance = false))
        assertNull(queue.takeNext(current, sessionGeneration = 3L, allowAdvance = true))
        assertTrue(queue.updateCandidates(current, listOf(next)))
    }

    @Test
    fun autoplay_commit_rechecks_latest_setting_lifecycle_and_session_after_stream_resolution() {
        val current = key("current")

        assertTrue(canCommitAutoplay(
            expectedKey = current,
            currentKey = current,
            expectedSessionGeneration = 4L,
            currentSessionGeneration = 4L,
            expectedRequestGeneration = 7L,
            currentRequestGeneration = 7L,
            enabled = true,
            lifecycleStarted = false,
            backgroundEnabled = true,
            pipActive = false
        ))
        assertFalse(canCommitAutoplay(
            expectedKey = current,
            currentKey = current,
            expectedSessionGeneration = 4L,
            currentSessionGeneration = 4L,
            expectedRequestGeneration = 7L,
            currentRequestGeneration = 7L,
            enabled = false,
            lifecycleStarted = true,
            backgroundEnabled = true,
            pipActive = true
        ))
        assertFalse(canCommitAutoplay(
            expectedKey = current,
            currentKey = current,
            expectedSessionGeneration = 4L,
            currentSessionGeneration = 5L,
            expectedRequestGeneration = 7L,
            currentRequestGeneration = 7L,
            enabled = true,
            lifecycleStarted = true,
            backgroundEnabled = false,
            pipActive = false
        ))
    }

    @Test
    fun ended_callback_must_belong_to_the_current_media_item() {
        val old = key("old")
        val next = key("next")

        assertTrue(shouldHandleAutoplayEnded(Player.STATE_ENDED, old, old))
        assertFalse(shouldHandleAutoplayEnded(Player.STATE_BUFFERING, old, old))
        assertFalse(shouldHandleAutoplayEnded(Player.STATE_ENDED, next, old))
        assertFalse(shouldHandleAutoplayEnded(Player.STATE_ENDED, next, null))
    }
}
