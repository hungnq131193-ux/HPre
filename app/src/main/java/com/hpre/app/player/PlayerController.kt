package com.hpre.app.player

import androidx.media3.ui.PlayerView
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import kotlinx.coroutines.flow.StateFlow

interface PlayerController {
    val state: StateFlow<PlaybackState>

    fun attachSurface(playerView: PlayerView)
    fun detachSurface(playerView: PlayerView)
    fun attachSurface(playerView: PlayerView, lease: SurfaceLease): Boolean {
        attachSurface(playerView)
        return true
    }
    fun detachSurface(playerView: PlayerView, lease: SurfaceLease): Boolean {
        detachSurface(playerView)
        return true
    }
    fun onLifecycleStart()
    fun onLifecycleStop()

    fun prepare(
        key: ContentKey,
        streamInfo: StreamInfo,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        initialQuality: QualityOption? = null
    )
    fun play()
    fun pause()
    fun playPause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun selectQuality(quality: QualityOption)
    fun setQualityPolicy(policy: UserQualityPolicy) = Unit
    fun clearMedia() = Unit
    fun release()
}

/**
 * Controller-owned testing and integration probe interface.
 * Exposes actual player snapshot on main dispatcher for testing/integration verification.
 */
interface PlayerIntegrationProbe {
    suspend fun getTestingSnapshot(): PlayerTestingSnapshot
}
