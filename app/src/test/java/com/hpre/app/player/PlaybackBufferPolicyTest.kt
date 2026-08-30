package com.hpre.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test
    fun compressed_media_budget_scales_for_low_memory_devices_and_has_an_upper_bound() {
        val mib = 1024 * 1024
        assertEquals(16 * mib, PlaybackMemoryBudget.targetBytes(128, lowRam = true))
        assertEquals(32 * mib, PlaybackMemoryBudget.targetBytes(512, lowRam = true))
        assertEquals(32 * mib, PlaybackMemoryBudget.targetBytes(128, lowRam = false))
        assertEquals(64 * mib, PlaybackMemoryBudget.targetBytes(512, lowRam = false))
        assertEquals(64 * mib, PlaybackMemoryBudget.targetBytes(2048, lowRam = false))
    }
    @Test
    fun playback_buffer_policy_preserves_fast_start_and_adds_weak_network_headroom() {
        assertEquals(30_000, HPrePlaybackService.MIN_PLAYBACK_BUFFER_MS)
        assertEquals(90_000, HPrePlaybackService.MAX_PLAYBACK_BUFFER_MS)
        assertEquals(2_500, HPrePlaybackService.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(8_000, HPrePlaybackService.BUFFER_AFTER_REBUFFER_MS)
    }
}
