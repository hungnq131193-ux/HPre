package com.hpre.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test
    fun playback_buffer_policy_preserves_fast_start_and_adds_weak_network_headroom() {
        assertEquals(30_000, HPrePlaybackService.MIN_PLAYBACK_BUFFER_MS)
        assertEquals(90_000, HPrePlaybackService.MAX_PLAYBACK_BUFFER_MS)
        assertEquals(2_500, HPrePlaybackService.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(8_000, HPrePlaybackService.BUFFER_AFTER_REBUFFER_MS)
    }
}
