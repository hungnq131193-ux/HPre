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

    // --- Task 4 Pure Drag Classification & Minimization Policy Tests ---

    private val defaultConfig = PlayerGestureConfig(
        touchSlopPx = 18f,
        minimizeDistancePx = 168f, // e.g. 56dp * 3
        minimizeVelocityPxPerSecond = 1800f // e.g. 600dp/s * 3
    )

    @Test
    fun drag_below_slop_is_undecided() {
        assertEquals(
            PlayerDragDecision.UNDECIDED,
            PlayerGesturePolicy.classifyDrag(totalX = 5f, totalY = 10f, touchSlopPx = 18f)
        )
        assertEquals(
            PlayerDragDecision.UNDECIDED,
            PlayerGesturePolicy.classifyDrag(totalX = -10f, totalY = -5f, touchSlopPx = 18f)
        )
    }

    @Test
    fun dominant_horizontal_drag_is_classified_as_horizontal() {
        assertEquals(
            PlayerDragDecision.HORIZONTAL,
            PlayerGesturePolicy.classifyDrag(totalX = 25f, totalY = 10f, touchSlopPx = 18f)
        )
        assertEquals(
            PlayerDragDecision.HORIZONTAL,
            PlayerGesturePolicy.classifyDrag(totalX = -30f, totalY = 5f, touchSlopPx = 18f)
        )
    }

    @Test
    fun dominant_downward_drag_is_classified_as_vertical_down() {
        assertEquals(
            PlayerDragDecision.VERTICAL_DOWN,
            PlayerGesturePolicy.classifyDrag(totalX = 5f, totalY = 25f, touchSlopPx = 18f)
        )
        assertEquals(
            PlayerDragDecision.VERTICAL_DOWN,
            PlayerGesturePolicy.classifyDrag(totalX = -10f, totalY = 30f, touchSlopPx = 18f)
        )
    }

    @Test
    fun dominant_upward_drag_is_classified_as_rejected() {
        assertEquals(
            PlayerDragDecision.REJECTED,
            PlayerGesturePolicy.classifyDrag(totalX = 5f, totalY = -25f, touchSlopPx = 18f)
        )
        assertEquals(
            PlayerDragDecision.REJECTED,
            PlayerGesturePolicy.classifyDrag(totalX = -5f, totalY = -30f, touchSlopPx = 18f)
        )
    }

    @Test
    fun shouldMinimize_returns_true_when_distance_threshold_met_and_enabled() {
        val result = PlayerGesturePolicy.shouldMinimize(
            totalY = 200f,
            velocityY = 0f,
            config = defaultConfig,
            enabled = true,
            startedInProtectedRegion = false
        )
        assertTrue("Downward drag exceeding distance threshold should minimize", result)
    }

    @Test
    fun shouldMinimize_returns_true_when_velocity_threshold_met_with_positive_distance_and_enabled() {
        val result = PlayerGesturePolicy.shouldMinimize(
            totalY = 50f,
            velocityY = 2000f,
            config = defaultConfig,
            enabled = true,
            startedInProtectedRegion = false
        )
        assertTrue("Fast downward fling should minimize even if distance is under full threshold", result)
    }

    @Test
    fun shouldMinimize_returns_false_when_neither_distance_nor_velocity_met() {
        val result = PlayerGesturePolicy.shouldMinimize(
            totalY = 50f,
            velocityY = 500f,
            config = defaultConfig,
            enabled = true,
            startedInProtectedRegion = false
        )
        assertFalse("Small slow drag should not minimize", result)
    }

    @Test
    fun shouldMinimize_returns_false_when_disabled() {
        val result = PlayerGesturePolicy.shouldMinimize(
            totalY = 300f,
            velocityY = 3000f,
            config = defaultConfig,
            enabled = false,
            startedInProtectedRegion = false
        )
        assertFalse("Minimization must not trigger when disabled", result)
    }

    @Test
    fun shouldMinimize_returns_false_when_started_in_protected_region() {
        val result = PlayerGesturePolicy.shouldMinimize(
            totalY = 300f,
            velocityY = 3000f,
            config = defaultConfig,
            enabled = true,
            startedInProtectedRegion = true
        )
        assertFalse("Minimization must not trigger if gesture started in protected control region", result)
    }

    @Test
    fun shouldMinimize_returns_false_for_negative_or_upward_movement() {
        val result = PlayerGesturePolicy.shouldMinimize(
            totalY = -200f,
            velocityY = -2000f,
            config = defaultConfig,
            enabled = true,
            startedInProtectedRegion = false
        )
        assertFalse("Upward movement must never trigger minimize", result)
    }

    @Test
    fun isPointInProtectedRegion_correctly_identifies_inside_and_outside_points() {
        val bounds = listOf(
            androidx.compose.ui.geometry.Rect(left = 100f, top = 100f, right = 200f, bottom = 200f),
            androidx.compose.ui.geometry.Rect(left = 0f, top = 400f, right = 500f, bottom = 500f)
        )

        assertTrue(PlayerGesturePolicy.isPointInProtectedRegion(150f, 150f, bounds))
        assertTrue(PlayerGesturePolicy.isPointInProtectedRegion(50f, 450f, bounds))
        assertFalse(PlayerGesturePolicy.isPointInProtectedRegion(50f, 50f, bounds))
        assertFalse(PlayerGesturePolicy.isPointInProtectedRegion(300f, 300f, bounds))
    }
}
