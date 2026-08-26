package com.hpre.app.player

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
    fun shouldContinueInBackground(
        backgroundEnabled: Boolean,
        enteringPip: Boolean = false,
        isChangingConfigurations: Boolean = false
    ): Boolean {
        return backgroundEnabled || enteringPip || isChangingConfigurations
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

