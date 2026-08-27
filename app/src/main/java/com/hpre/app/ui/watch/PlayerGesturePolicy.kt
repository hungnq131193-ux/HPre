package com.hpre.app.ui.watch

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** What a double tap on the video surface should do, based on where it landed. */
enum class SeekGesture { REWIND, FORWARD, NONE }

/**
 * Decision reached by the gesture coordinator when tracking pointer drag movement.
 */
enum class PlayerDragDecision {
    UNDECIDED,
    HORIZONTAL,
    VERTICAL_DOWN,
    REJECTED
}

/**
 * Pixel-space configuration values for player gesture handling.
 */
data class PlayerGestureConfig(
    val touchSlopPx: Float,
    val minimizeDistancePx: Float,
    val minimizeVelocityPxPerSecond: Float
)

/**
 * Pure policy rules for player gestures: double-tap seek zones, drag classification, and
 * swipe-to-minimize validation.
 */
object PlayerGesturePolicy {
    const val SEEK_STEP_MS = 10_000L

    /** Fraction of the width on each side that reacts to a double tap. */
    const val EDGE_ZONE_FRACTION = 0.4f

    /** Centralized default gesture constants in dp */
    val DEFAULT_MINIMIZE_DISTANCE_DP: Dp = 56.dp
    val DEFAULT_MINIMIZE_VELOCITY_DP_PER_SECOND: Dp = 600.dp

    fun gestureForTap(tapX: Float, width: Float): SeekGesture {
        if (width <= 0f) return SeekGesture.NONE
        val fraction = tapX / width
        return when {
            fraction < EDGE_ZONE_FRACTION -> SeekGesture.REWIND
            fraction > 1f - EDGE_ZONE_FRACTION -> SeekGesture.FORWARD
            else -> SeekGesture.NONE
        }
    }

    /** Seek deltas must never be applied to a stream with no known duration. */
    fun isSeekAllowed(durationMs: Long): Boolean = durationMs > 0L

    /**
     * Checks whether a point (x, y) in root coordinator coordinates falls inside any of the
     * protected control bounds.
     */
    fun isPointInProtectedRegion(x: Float, y: Float, protectedBounds: Collection<Rect>): Boolean {
        for (bounds in protectedBounds) {
            if (bounds.contains(androidx.compose.ui.geometry.Offset(x, y))) {
                return true
            }
        }
        return false
    }

    /**
     * Classifies pointer drag movement once touch slop has been crossed.
     *
     * - Returns [PlayerDragDecision.UNDECIDED] if total movement does not exceed touch slop.
     * - Returns [PlayerDragDecision.HORIZONTAL] if horizontal movement is dominant.
     * - Returns [PlayerDragDecision.VERTICAL_DOWN] if downward vertical movement is dominant (totalY > 0).
     * - Returns [PlayerDragDecision.REJECTED] if upward vertical movement is dominant (totalY <= 0).
     */
    fun classifyDrag(totalX: Float, totalY: Float, touchSlopPx: Float): PlayerDragDecision {
        val absX = abs(totalX)
        val absY = abs(totalY)
        if (absX < touchSlopPx && absY < touchSlopPx) {
            return PlayerDragDecision.UNDECIDED
        }
        return if (absX > absY) {
            PlayerDragDecision.HORIZONTAL
        } else if (totalY > 0f) {
            PlayerDragDecision.VERTICAL_DOWN
        } else {
            PlayerDragDecision.REJECTED
        }
    }

    /**
     * Checks whether the swipe-to-minimize gesture should trigger based on total downward
     * displacement, downward release velocity, and state flags.
     */
    fun shouldMinimize(
        totalY: Float,
        velocityY: Float,
        config: PlayerGestureConfig,
        enabled: Boolean,
        startedInProtectedRegion: Boolean
    ): Boolean {
        if (!enabled || startedInProtectedRegion || totalY <= 0f) {
            return false
        }
        val distanceMet = totalY >= config.minimizeDistancePx
        val velocityMet = velocityY >= config.minimizeVelocityPxPerSecond && totalY > 0f
        return distanceMet || velocityMet
    }

    /**
     * Checks whether minimize gestures are allowed in the current screen/playback mode.
     */
    fun isMinimizeGestureAllowed(
        isFullscreen: Boolean,
        isInPip: Boolean,
        minimizeEnabled: Boolean
    ): Boolean {
        return minimizeEnabled && !isFullscreen && !isInPip
    }
}
