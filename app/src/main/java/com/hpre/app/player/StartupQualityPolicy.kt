package com.hpre.app.player

import kotlinx.coroutines.CompletableDeferred

internal class PlaybackReadinessTracker {
    @Volatile
    private var activeSessionGeneration: Long = -1L
    @Volatile
    private var activeMediaId: String? = null
    @Volatile
    private var readyDeferred: CompletableDeferred<Boolean>? = null

    @Synchronized
    fun registerSession(sessionGen: Long, mediaId: String? = null): CompletableDeferred<Boolean> {
        readyDeferred?.complete(false)
        activeSessionGeneration = sessionGen
        activeMediaId = mediaId
        val deferred = CompletableDeferred<Boolean>()
        readyDeferred = deferred
        return deferred
    }

    @Synchronized
    fun onPlaybackStateChanged(sessionGen: Long, currentMediaId: String? = null, playbackState: Int) {
        val active = activeMediaId
        if (sessionGen == activeSessionGeneration && active != null && currentMediaId != null && active == currentMediaId) {
            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                readyDeferred?.complete(true)
            }
        }
    }

    @Synchronized
    fun onError(sessionGen: Long, currentMediaId: String? = null) {
        val active = activeMediaId
        if (sessionGen == activeSessionGeneration && active != null && currentMediaId != null && active == currentMediaId) {
            readyDeferred?.complete(false)
        }
    }

    @Synchronized
    fun cancel(sessionGen: Long? = null) {
        if (sessionGen == null || sessionGen == activeSessionGeneration) {
            readyDeferred?.complete(false)
            if (sessionGen == activeSessionGeneration) {
                activeSessionGeneration = -1L
                activeMediaId = null
            }
        }
    }
}

/**
 * Adaptive sources start with a conservative cap and then let ABR switch on segment boundaries.
 * Progressive/merged sources keep their initial rendition: an automatic source rebuild would
 * discard the buffer and introduce a second startup stall.
 */
object StartupQualityPolicy {

    const val UNLIMITED_HEIGHT: Int = Int.MAX_VALUE

    /** Height cap applied to adaptive sources for the first seconds of playback. */
    const val FAST_START_ADAPTIVE_CAP: Int = 360

    /** Steady-state ceiling for progressive/merged startup, matching pre-fast-start behaviour. */
    const val PROGRESSIVE_TARGET_HEIGHT: Int = 720

    /** How long to wait after playback is running before raising the ceiling one step. */
    const val ESCALATION_STEP_DELAY_MS: Long = 2_000L

    /** Give up waiting for playback to reach READY after this long; the plan is then abandoned. */
    const val READY_TIMEOUT_MS: Long = 20_000L

    private val ADAPTIVE_LADDER = listOf(480, 720, 1080)

    /**
     * Initial adaptive cap, never above an existing user/policy ceiling.
     */
    fun adaptiveStartCap(policyMaxHeight: Int?): Int =
        minOf(FAST_START_ADAPTIVE_CAP, policyMaxHeight ?: UNLIMITED_HEIGHT)

    fun constraintsAfterManualSelection(
        currentCapHeight: Int?,
        currentlyForcingLowestBitrate: Boolean
    ): FastStartConstraints {
        @Suppress("UNUSED_VARIABLE")
        val previousConstraints = currentCapHeight to currentlyForcingLowestBitrate
        return FastStartConstraints(capHeight = null, forceLowestBitrate = false)
    }

    /**
     * Ascending height caps to apply after [startCap], ending at [targetHeight].
     * [UNLIMITED_HEIGHT] as the final step removes the cap entirely.
     */
    fun adaptiveSteps(startCap: Int, targetHeight: Int): List<Int> {
        if (targetHeight <= startCap) return emptyList()
        val intermediate = ADAPTIVE_LADDER.filter { it > startCap && it < targetHeight }
        return (intermediate + targetHeight).distinct().sorted()
    }

    /**
     * Highest rendition of the same source family that is above [startHeight] and within
     * [targetHeight], or null when startup already picked the best available option.
     */
    fun progressiveEscalationTarget(
        available: List<QualityOption>,
        streamType: PlaybackStreamType,
        startHeight: Int,
        targetHeight: Int = PROGRESSIVE_TARGET_HEIGHT
    ): QualityOption? = available
        .filter { it.streamType == streamType }
        .filter { it.height > startHeight && it.height <= targetHeight }
        .maxByOrNull { it.height }

    /**
     * Builds the escalation plan for a freshly prepared stream, or null when the source cannot or
     * need not be escalated (audio-only, or already at the ceiling).
     */
    fun planFor(
        streamType: PlaybackStreamType,
        startHeight: Int,
        available: List<QualityOption>,
        policyMaxHeight: Int?
    ): FastStartPlan? = when (streamType) {
        PlaybackStreamType.HLS, PlaybackStreamType.DASH -> {
            val startCap = adaptiveStartCap(policyMaxHeight)
            val steps = adaptiveSteps(startCap, policyMaxHeight ?: UNLIMITED_HEIGHT)
            FastStartPlan(
                isAdaptive = true,
                startCapHeight = startCap,
                heightSteps = steps,
                escalationOption = null,
                forceLowestBitrate = true
            )
        }

        // A progressive/merged change discards buffered data and prepares another source. Pick a
        // usable rendition once at startup; only an explicit user choice may rebuild that source.
        PlaybackStreamType.PROGRESSIVE, PlaybackStreamType.MERGED_AV -> null

        PlaybackStreamType.AUDIO_ONLY -> null
    }
}

data class FastStartConstraints(
    val capHeight: Int?,
    val forceLowestBitrate: Boolean
)

/**
 * A resolved startup escalation plan bound to one playback session.
 */
data class FastStartPlan(
    val isAdaptive: Boolean,
    val startCapHeight: Int?,
    val heightSteps: List<Int>,
    val escalationOption: QualityOption?,
    val forceLowestBitrate: Boolean,
    val key: com.hpre.app.model.ContentKey? = null,
    val sessionGeneration: Long = 0L
)
