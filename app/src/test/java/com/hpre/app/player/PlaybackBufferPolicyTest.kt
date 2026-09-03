package com.hpre.app.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(750, HPrePlaybackService.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(8_000, HPrePlaybackService.BUFFER_AFTER_REBUFFER_MS)
        assertEquals(15_000L, HPrePlaybackService.BUFFERING_WATCHDOG_TIMEOUT_MS)
    }

    @Test
    fun buffering_watchdog_disarm_rules_cover_first_frame_audio_only_and_terminal_states() {
        // Active pre-first-frame buffering -> do NOT disarm
        assertFalse(
            shouldDisarmBufferingWatchdog(
                renderedFirstFrameCount = 0,
                streamType = PlaybackStreamType.PROGRESSIVE,
                playbackState = Player.STATE_BUFFERING
            )
        )
        assertFalse(
            shouldDisarmBufferingWatchdog(
                renderedFirstFrameCount = 0,
                streamType = PlaybackStreamType.HLS,
                playbackState = Player.STATE_READY
            )
        )

        // First frame rendered -> disarm
        assertTrue(
            shouldDisarmBufferingWatchdog(
                renderedFirstFrameCount = 1,
                streamType = PlaybackStreamType.PROGRESSIVE,
                playbackState = Player.STATE_BUFFERING
            )
        )

        // Audio-only reached READY -> disarm
        assertTrue(
            shouldDisarmBufferingWatchdog(
                renderedFirstFrameCount = 0,
                streamType = PlaybackStreamType.AUDIO_ONLY,
                playbackState = Player.STATE_READY
            )
        )

        // Player ended -> disarm
        assertTrue(
            shouldDisarmBufferingWatchdog(
                renderedFirstFrameCount = 0,
                streamType = PlaybackStreamType.PROGRESSIVE,
                playbackState = Player.STATE_ENDED
            )
        )
    }
}
