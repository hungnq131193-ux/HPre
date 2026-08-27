package com.hpre.app.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGesturePolicyTest {

    private val width = 1000f

    @Test
    fun double_tap_on_the_left_edge_rewinds() {
        assertEquals(SeekGesture.REWIND, PlayerGesturePolicy.gestureForTap(50f, width))
        assertEquals(SeekGesture.REWIND, PlayerGesturePolicy.gestureForTap(399f, width))
    }

    @Test
    fun double_tap_on_the_right_edge_fast_forwards() {
        assertEquals(SeekGesture.FORWARD, PlayerGesturePolicy.gestureForTap(601f, width))
        assertEquals(SeekGesture.FORWARD, PlayerGesturePolicy.gestureForTap(980f, width))
    }

    @Test
    fun centre_band_is_inert_so_it_does_not_steal_taps_aimed_at_the_play_button() {
        assertEquals(SeekGesture.NONE, PlayerGesturePolicy.gestureForTap(400f, width))
        assertEquals(SeekGesture.NONE, PlayerGesturePolicy.gestureForTap(500f, width))
        assertEquals(SeekGesture.NONE, PlayerGesturePolicy.gestureForTap(600f, width))
    }

    @Test
    fun zones_are_proportional_rather_than_tied_to_one_screen_size() {
        // A quarter of the way in is a rewind on any width.
        listOf(320f, 720f, 1440f, 2560f).forEach { w ->
            assertEquals(
                "Left quarter of width $w should rewind",
                SeekGesture.REWIND,
                PlayerGesturePolicy.gestureForTap(w * 0.25f, w)
            )
            assertEquals(
                "Right quarter of width $w should forward",
                SeekGesture.FORWARD,
                PlayerGesturePolicy.gestureForTap(w * 0.75f, w)
            )
            assertEquals(
                "Dead centre of width $w should stay inert",
                SeekGesture.NONE,
                PlayerGesturePolicy.gestureForTap(w * 0.5f, w)
            )
        }
    }

    @Test
    fun a_zero_width_surface_yields_no_gesture_instead_of_dividing_by_zero() {
        assertEquals(SeekGesture.NONE, PlayerGesturePolicy.gestureForTap(0f, 0f))
        assertEquals(SeekGesture.NONE, PlayerGesturePolicy.gestureForTap(10f, -5f))
    }

    @Test
    fun seeking_is_refused_when_duration_is_unknown_so_live_streams_are_not_scrubbed() {
        assertFalse(PlayerGesturePolicy.isSeekAllowed(0L))
        assertFalse(PlayerGesturePolicy.isSeekAllowed(-1L))
        assertTrue(PlayerGesturePolicy.isSeekAllowed(1L))
        assertTrue(PlayerGesturePolicy.isSeekAllowed(60_000L))
    }

    @Test
    fun seek_step_matches_the_button_controls_so_both_paths_agree() {
        assertEquals(10_000L, PlayerGesturePolicy.SEEK_STEP_MS)
    }
}
