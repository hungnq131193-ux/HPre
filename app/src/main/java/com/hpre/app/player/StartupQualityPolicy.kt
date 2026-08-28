package com.hpre.app.player

/**
 * Startup quality policy: begin playback at the smallest usable rendition so the first frame and the
 * surrounding page (recommendations, comments) appear quickly, then raise the ceiling once playback
 * is actually running.
 *
 * Two escalation shapes exist because the cost of raising quality differs per source:
 *  - Adaptive sources (HLS/DASH) escalate by lifting the track-selector height cap in steps. ExoPlayer's
 *    ABR switches renditions on segment boundaries, so this is seamless and can be done repeatedly.
 *  - Progressive/merged sources have no ABR; raising quality requires rebuilding the media source and
 *    re-buffering. That is done at most once, straight to the target height, to avoid repeated stalls.
 */
object StartupQualityPolicy {

    const val UNLIMITED_HEIGHT: Int = Int.MAX_VALUE

    /**
     * Progressive/merged startup floor. Playback begins at the lowest rendition at or above this
     * height; anything lower is usually too degraded to be worth the saved bytes.
     */
    const val FAST_START_MIN_HEIGHT: Int = 240

    /** Height cap applied to adaptive sources for the first seconds of playback. */
    const val FAST_START_ADAPTIVE_CAP: Int = 360

    /** Steady-state ceiling for progressive/merged startup, matching pre-fast-start behaviour. */
    const val PROGRESSIVE_TARGET_HEIGHT: Int = 720

    /** How long to wait after playback is running before raising the ceiling one step. */
    const val ESCALATION_STEP_DELAY_MS: Long = 2_000L

    /** Give up waiting for playback to reach READY after this long; the plan is then abandoned. */
    const val READY_TIMEOUT_MS: Long = 20_000L

    /** Polling interval while waiting for the player to reach READY. */
    const val READY_POLL_INTERVAL_MS: Long = 200L

    private val ADAPTIVE_LADDER = listOf(480, 720, 1080)

    /**
     * Initial adaptive cap, never above an existing user/policy ceiling.
     */
    fun adaptiveStartCap(policyMaxHeight: Int?): Int =
        minOf(FAST_START_ADAPTIVE_CAP, policyMaxHeight ?: UNLIMITED_HEIGHT)

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
            if (steps.isEmpty()) null else FastStartPlan(
                isAdaptive = true,
                startCapHeight = startCap,
                heightSteps = steps,
                escalationOption = null
            )
        }

        PlaybackStreamType.PROGRESSIVE, PlaybackStreamType.MERGED_AV -> {
            val ceiling = minOf(PROGRESSIVE_TARGET_HEIGHT, policyMaxHeight ?: UNLIMITED_HEIGHT)
            val target = progressiveEscalationTarget(
                available = available,
                streamType = streamType,
                startHeight = startHeight,
                targetHeight = ceiling
            )
            if (target == null) null else FastStartPlan(
                isAdaptive = false,
                startCapHeight = null,
                heightSteps = listOf(target.height),
                escalationOption = target
            )
        }

        PlaybackStreamType.AUDIO_ONLY -> null
    }
}

/**
 * A resolved startup escalation plan bound to one playback session.
 */
data class FastStartPlan(
    val isAdaptive: Boolean,
    val startCapHeight: Int?,
    val heightSteps: List<Int>,
    val escalationOption: QualityOption?,
    val key: com.hpre.app.model.ContentKey? = null,
    val sessionGeneration: Long = 0L
)
