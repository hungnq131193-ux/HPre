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
    fun resolving_candidate_does_not_change_queue_identity() {
        val current = key("current")
        val next = key("next")
        val queue = AutoplayQueue()
        queue.resetForManualStart(current)
        queue.updateCandidates(current, listOf(next))
        assertEquals(next, queue.takeNext(current, 1L))
        assertTrue(queue.updateCandidates(current, listOf(next)))
        assertFalse(queue.updateCandidates(next, emptyList()))
    }

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
        assertTrue(queue.commit(current, first))
        assertEquals(second, queue.takeNext(first, sessionGeneration = 8L))
        assertTrue(queue.commit(first, second))
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
        assertTrue(queue.commit(first, second))

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
    fun manual_queue_advances_before_candidates_even_when_autoplay_disabled() {
        val current = key("current")
        val queued = key("queued")
        val suggested = key("suggested")
        val queue = AutoplayQueue()
        queue.resetForManualStart(current)
        queue.updateCandidates(current, listOf(suggested))

        assertTrue(queue.enqueue(QueuedItem(queued, "Queued video"), playNext = false))
        assertFalse(queue.enqueue(QueuedItem(current, "Same"), playNext = false))
        assertEquals(listOf(queued), queue.manualSnapshot.map { it.key })

        // Manual entries win over candidates and ignore the autoplay-off gate.
        assertEquals(queued, queue.takeNext(current, sessionGeneration = 3L, allowAdvance = false))
        assertTrue(queue.lastTakeWasManual)
        assertTrue(queue.commit(current, queued))

        assertEquals(suggested, queue.takeNext(queued, sessionGeneration = 4L, allowAdvance = true))
        assertFalse(queue.lastTakeWasManual)
        assertTrue(queue.commit(queued, suggested))
    }

    @Test
    fun manual_queue_remove_and_skip_to_drop_entries() {
        val current = key("current")
        val first = key("first")
        val second = key("second")
        val third = key("third")
        val queue = AutoplayQueue()
        queue.resetForManualStart(current)
        queue.enqueue(QueuedItem(first, "1"), playNext = false)
        queue.enqueue(QueuedItem(third, "3"), playNext = false)
        queue.enqueue(QueuedItem(second, "2"), playNext = true)
        assertEquals(listOf(second, first, third), queue.manualSnapshot.map { it.key })

        assertTrue(queue.removeManual(1))
        assertEquals(listOf(second, third), queue.manualSnapshot.map { it.key })
        assertFalse(queue.removeManual(5))

        assertTrue(queue.dropManualThrough(0))
        assertEquals(listOf(third), queue.manualSnapshot.map { it.key })
        assertFalse(queue.dropManualThrough(3))
    }

    @Test
    fun commit_rejects_keys_that_were_not_taken() {
        val current = key("current")
        val next = key("next")
        val queue = AutoplayQueue()
        queue.resetForManualStart(current)
        queue.updateCandidates(current, listOf(next))
        assertFalse(queue.commit(current, next))
        assertEquals(next, queue.takeNext(current, 1L))
        assertFalse(queue.commit(current, key("other")))
        assertTrue(queue.commit(current, next))
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
