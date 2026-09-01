package com.hpre.app.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsPolicyTest {

    @Test
    fun controls_auto_hide_while_playing_and_user_is_idle() {
        assertTrue(
            PlayerControlsPolicy.shouldAutoHide(
                controlsVisible = true,
                isPlaying = true,
                isMenuOpen = false,
                isScrubbing = false
            )
        )
    }

    @Test
    fun controls_stay_visible_while_playback_is_paused() {
        assertFalse(
            "Paused playback must keep the controls on screen",
            PlayerControlsPolicy.shouldAutoHide(
                controlsVisible = true,
                isPlaying = false,
                isMenuOpen = false,
                isScrubbing = false
            )
        )
    }

    @Test
    fun controls_stay_visible_while_a_menu_is_open() {
        assertFalse(
            "An open speed/quality menu must not be yanked away mid-selection",
            PlayerControlsPolicy.shouldAutoHide(
                controlsVisible = true,
                isPlaying = true,
                isMenuOpen = true,
                isScrubbing = false
            )
        )
    }

    @Test
    fun controls_stay_visible_while_user_is_scrubbing() {
        assertFalse(
            "Controls must not disappear while the user drags the seek bar",
            PlayerControlsPolicy.shouldAutoHide(
                controlsVisible = true,
                isPlaying = true,
                isMenuOpen = false,
                isScrubbing = true
            )
        )
    }

    @Test
    fun already_hidden_controls_do_not_schedule_another_hide() {
        assertFalse(
            PlayerControlsPolicy.shouldAutoHide(
                controlsVisible = false,
                isPlaying = true,
                isMenuOpen = false,
                isScrubbing = false
            )
        )
    }

    @Test
    fun auto_hide_delay_stays_within_a_comfortable_reading_window() {
        assertEquals(3500L, PlayerControlsPolicy.AUTO_HIDE_DELAY_MS)
    }

    // --- Task 4 Coordinator Decision Logic Tests ---

    @Test
    fun isMinimizeGestureAllowed_only_in_portrait_non_fullscreen_non_pip() {
        assertTrue(
            "Allowed in portrait, non-fullscreen, non-pip",
            PlayerGesturePolicy.isMinimizeGestureAllowed(
                isFullscreen = false,
                isInPip = false,
                minimizeEnabled = true
            )
        )

        assertFalse(
            "Disallowed in fullscreen",
            PlayerGesturePolicy.isMinimizeGestureAllowed(
                isFullscreen = true,
                isInPip = false,
                minimizeEnabled = true
            )
        )

        assertFalse(
            "Disallowed in PiP",
            PlayerGesturePolicy.isMinimizeGestureAllowed(
                isFullscreen = false,
                isInPip = true,
                minimizeEnabled = true
            )
        )

        assertFalse(
            "Disallowed when minimizeEnabled flag is false",
            PlayerGesturePolicy.isMinimizeGestureAllowed(
                isFullscreen = false,
                isInPip = false,
                minimizeEnabled = false
            )
        )
    }

    @Test
    fun progress_polling_policy_constants() {
        assertEquals(500L, PlayerControlsPolicy.PROGRESS_POLL_INTERVAL_MS)
    }

    @Test
    fun resolveEffectiveDurationMs_forces_zero_when_structural_duration_non_positive() {
        assertEquals(
            "Structural 0 forces 0 even if localProgress is positive",
            0L,
            PlayerControlsPolicy.resolveEffectiveDurationMs(
                playbackStateDurationMs = 0L,
                localProgressDurationMs = 60_000L
            )
        )

        assertEquals(
            "Structural negative forces 0 even if localProgress is positive",
            0L,
            PlayerControlsPolicy.resolveEffectiveDurationMs(
                playbackStateDurationMs = -1L,
                localProgressDurationMs = 60_000L
            )
        )
    }

    @Test
    fun resolveEffectiveDurationMs_prefers_localProgress_when_structural_positive() {
        assertEquals(
            "Prefers positive localProgress when structural is positive",
            75_000L,
            PlayerControlsPolicy.resolveEffectiveDurationMs(
                playbackStateDurationMs = 60_000L,
                localProgressDurationMs = 75_000L
            )
        )

        assertEquals(
            "Fallbacks to structural when localProgress is 0",
            60_000L,
            PlayerControlsPolicy.resolveEffectiveDurationMs(
                playbackStateDurationMs = 60_000L,
                localProgressDurationMs = 0L
            )
        )

        assertEquals(
            "Fallbacks to structural when localProgress is negative",
            60_000L,
            PlayerControlsPolicy.resolveEffectiveDurationMs(
                playbackStateDurationMs = 60_000L,
                localProgressDurationMs = -100L
            )
        )
    }
}
