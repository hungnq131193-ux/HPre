package com.hpre.app.player

/**
 * Startup quality policy: delegates adaptive streaming to Media3 / ExoPlayer ABR.
 * Progressive / Merged AV sources maintain a steady ceiling without automatic rebuilds.
 */
object StartupQualityPolicy {
    const val UNLIMITED_HEIGHT: Int = Int.MAX_VALUE
    const val PROGRESSIVE_TARGET_HEIGHT: Int = 720
}

