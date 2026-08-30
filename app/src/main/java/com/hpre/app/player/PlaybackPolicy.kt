package com.hpre.app.player

import com.hpre.app.model.ContentKey
import kotlinx.coroutines.flow.StateFlow

data class PipEligibility(
    val supported: Boolean,
    val enabled: Boolean,
    val watchVisible: Boolean,
    val alreadyInPip: Boolean,
    val hasVideo: Boolean,
    val isPlaying: Boolean,
    val isReady: Boolean
)

interface PlaybackPolicyBridge {
    val backgroundPlaybackEnabled: Boolean
    val isPipActiveOrEntering: Boolean
    fun setBackgroundPlaybackEnabled(enabled: Boolean)
    fun setPipActiveOrEntering(activeOrEntering: Boolean)
}

object PlaybackPolicy {
    /**
     * A live stream must start from Media3's default live position. Reusing a history or retry
     * position can otherwise land the viewer near the beginning of the DVR window.
     */
    fun resolveStartPosition(isLive: Boolean, requestedPositionMs: Long): Long {
        return if (isLive) 0L else requestedPositionMs.coerceAtLeast(0L)
    }

    fun prepareSnapshotPosition(
        existing: PlaybackSnapshot?,
        key: ContentKey,
        requestedPositionMs: Long
    ): Long {
        val requested = requestedPositionMs.coerceAtLeast(0L)
        return if (requested == 0L && existing?.key == key && existing.positionMs > 0L) {
            existing.positionMs
        } else {
            requested
        }
    }

    fun shouldContinueInBackground(
        backgroundEnabled: Boolean,
        enteringPip: Boolean = false,
        isChangingConfigurations: Boolean = false
    ): Boolean {
        return backgroundEnabled || enteringPip || isChangingConfigurations
    }

    fun shouldTrackUiProgress(isLifecycleStarted: Boolean, isInPip: Boolean): Boolean =
        isLifecycleStarted || isInPip

    /**
     * Keep video decoding only while pixels can be visible. Audio continues in the background when
     * enabled, while PiP and configuration changes retain the video renderer without interruption.
     */
    fun shouldEnableVideoTrack(
        lifecycleStarted: Boolean,
        pipActiveOrEntering: Boolean,
        isChangingConfigurations: Boolean = false
    ): Boolean {
        return lifecycleStarted || pipActiveOrEntering || isChangingConfigurations
    }

    fun canEnterPip(value: PipEligibility): Boolean {
        return value.supported && value.enabled && value.watchVisible && !value.alreadyInPip &&
            value.hasVideo && value.isPlaying && value.isReady
    }

    fun calculatePipEligibility(
        isPipSupported: Boolean,
        uiState: PlaybackUiState,
        playbackState: PlaybackState
    ): PipEligibility {
        return PipEligibility(
            supported = isPipSupported,
            enabled = uiState.pipEnabled,
            watchVisible = uiState.watchVisible,
            alreadyInPip = uiState.isInPip,
            hasVideo = playbackState.key != null && playbackState.streamType != null &&
                playbackState.streamType != PlaybackStreamType.AUDIO_ONLY,
            isPlaying = playbackState.isPlaying,
            isReady = playbackState.isReady
        )
    }

    fun isControllerAuthorized(
        expectedPackageName: String,
        expectedUid: Int,
        controllerPackage: String,
        controllerUid: Int
    ): Boolean {
        if (controllerPackage.isBlank()) return false
        val sameUid = controllerUid == expectedUid
        val samePackage = controllerPackage == expectedPackageName
        return (samePackage && sameUid) || sameUid
    }
}
