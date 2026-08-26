package com.flowtube.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUiState(
    val watchVisible: Boolean = false,
    val pipEnabled: Boolean = true,
    val backgroundPlaybackEnabled: Boolean = true,
    val isInPip: Boolean = false
)

class PlaybackUiCoordinator {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    fun setWatchVisible(value: Boolean) {
        _state.value = _state.value.copy(watchVisible = value)
    }

    fun setInPip(value: Boolean) {
        _state.value = _state.value.copy(isInPip = value)
    }

    fun setPipEnabled(value: Boolean) {
        _state.value = _state.value.copy(pipEnabled = value)
    }

    fun setBackgroundPlaybackEnabled(value: Boolean) {
        _state.value = _state.value.copy(backgroundPlaybackEnabled = value)
    }
}
