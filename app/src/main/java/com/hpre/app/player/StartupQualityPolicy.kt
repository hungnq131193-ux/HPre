package com.hpre.app.player

/**
 * Startup quality policy: delegates adaptive streaming to Media3 / ExoPlayer ABR.
 * Progressive / Merged AV sources maintain a steady ceiling without automatic rebuilds.
 */
object StartupQualityPolicy {
    const val UNLIMITED_HEIGHT: Int = Int.MAX_VALUE
    const val PROGRESSIVE_TARGET_HEIGHT: Int = 720
    const val AUTO_STARTUP_MAX_HEIGHT: Int = 360
}

internal fun startupAutoCeiling(policy: UserQualityPolicy, streamType: PlaybackStreamType?): Int? =
    if (policy is UserQualityPolicy.Auto &&
        (streamType == PlaybackStreamType.HLS || streamType == PlaybackStreamType.DASH)
    ) {
        minOf(policy.maxHeight ?: Int.MAX_VALUE, StartupQualityPolicy.AUTO_STARTUP_MAX_HEIGHT)
    } else {
        null
    }

internal fun shouldReleaseStartupCeiling(
    boundToken: Long,
    activeToken: Long,
    policy: UserQualityPolicy,
    ceilingApplied: Boolean
): Boolean = ceilingApplied && boundToken == activeToken && policy is UserQualityPolicy.Auto

