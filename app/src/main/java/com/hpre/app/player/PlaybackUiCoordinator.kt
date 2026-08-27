package com.hpre.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayerPresentation {
    WATCH,
    MINIMIZING,
    MINI_PLAYER,
    SYSTEM_PIP
}

enum class SurfaceOwner {
    NONE,
    WATCH,
    MINI_PLAYER,
    SYSTEM_PIP
}

data class SurfaceLease(
    val owner: SurfaceOwner,
    val generation: Long
)

data class PlaybackUiState(
    val watchVisible: Boolean = false,
    val pipEnabled: Boolean = true,
    val backgroundPlaybackEnabled: Boolean = true,
    val isInPip: Boolean = false,
    val presentation: PlayerPresentation = PlayerPresentation.WATCH
)

class PlaybackUiCoordinator {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private var surfaceGeneration = 0L
    private var pendingSurfaceLease: SurfaceLease? = null
    private var activeSurfaceLease: SurfaceLease = SurfaceLease(SurfaceOwner.NONE, 0L)
    private var presentationBeforePip: PlayerPresentation = PlayerPresentation.WATCH

    @Synchronized
    fun beginSurfaceHandoff(target: SurfaceOwner): SurfaceLease {
        val lease = SurfaceLease(target, ++surfaceGeneration)
        pendingSurfaceLease = lease
        return lease
    }

    @Synchronized
    fun confirmSurfaceAttached(lease: SurfaceLease): Boolean {
        if (pendingSurfaceLease != lease || lease.generation < activeSurfaceLease.generation) return false
        activeSurfaceLease = lease
        pendingSurfaceLease = null
        return true
    }

    @Synchronized
    fun rejectSurfaceAttach(lease: SurfaceLease): Boolean {
        if (pendingSurfaceLease != lease) return false
        pendingSurfaceLease = null
        return true
    }

    @Synchronized
    fun currentSurfaceLease(): SurfaceLease = activeSurfaceLease

    @Synchronized
    fun isCurrentSurfaceLease(lease: SurfaceLease): Boolean = activeSurfaceLease == lease

    fun setWatchVisible(value: Boolean) {
        val current = _state.value
        val newPresentation = when {
            current.isInPip -> PlayerPresentation.SYSTEM_PIP
            value -> PlayerPresentation.WATCH
            current.presentation == PlayerPresentation.MINIMIZING -> PlayerPresentation.MINI_PLAYER
            else -> current.presentation
        }
        _state.value = current.copy(
            watchVisible = value,
            presentation = newPresentation
        )
    }

    fun setInPip(value: Boolean) {
        val current = _state.value
        val newPresentation = if (value) {
            presentationBeforePip = current.presentation
            PlayerPresentation.SYSTEM_PIP
        } else {
            if (current.watchVisible) {
                PlayerPresentation.WATCH
            } else if (presentationBeforePip == PlayerPresentation.MINI_PLAYER ||
                presentationBeforePip == PlayerPresentation.MINIMIZING
            ) {
                PlayerPresentation.MINI_PLAYER
            } else {
                PlayerPresentation.WATCH
            }
        }
        _state.value = current.copy(
            isInPip = value,
            presentation = newPresentation
        )
    }

    fun setPipEnabled(value: Boolean) {
        _state.value = _state.value.copy(pipEnabled = value)
    }

    fun setBackgroundPlaybackEnabled(value: Boolean) {
        _state.value = _state.value.copy(backgroundPlaybackEnabled = value)
    }

    fun requestMinimizeToHome() {
        val current = _state.value
        if (!current.watchVisible || current.isInPip || current.presentation != PlayerPresentation.WATCH) {
            return
        }
        _state.value = current.copy(
            presentation = PlayerPresentation.MINIMIZING
        )
    }
}
