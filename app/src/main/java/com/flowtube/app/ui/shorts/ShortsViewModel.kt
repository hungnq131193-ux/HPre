package com.flowtube.app.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.repository.VideoService
import com.flowtube.app.player.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ShortsUiState {
    data object Loading : ShortsUiState
    data object Unavailable : ShortsUiState
    data object Empty : ShortsUiState
    data class Content(val videos: List<VideoSummary>) : ShortsUiState
    data class Error(val error: AppError) : ShortsUiState
}

class ShortsViewModel(
    private val videoService: VideoService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playerController: PlayerController? = null
) : ViewModel() {
    private val _state = MutableStateFlow<ShortsUiState>(ShortsUiState.Loading)
    val state: StateFlow<ShortsUiState> = _state.asStateFlow()

    fun load() {
        if (!videoService.supportsShorts) {
            _state.value = ShortsUiState.Unavailable
            return
        }
        _state.value = ShortsUiState.Loading
        viewModelScope.launch(ioDispatcher) {
            try {
                _state.value = when (val result = videoService.trending()) {
                    is AppResult.Success -> {
                        val shorts = result.value.filter(VideoSummary::isShort)
                        if (shorts.isEmpty()) ShortsUiState.Empty else ShortsUiState.Content(shorts)
                    }
                    is AppResult.Failure -> ShortsUiState.Error(result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = ShortsUiState.Error(AppError.Unknown)
            }
        }
    }

    fun retry() = load()

    fun activate(video: VideoSummary) {
        val controller = playerController ?: return
        viewModelScope.launch(ioDispatcher) {
            when (val result = videoService.streamInfo(video.key)) {
                is AppResult.Success -> controller.prepare(video.key, result.value)
                is AppResult.Failure -> _state.value = ShortsUiState.Error(result.error)
            }
        }
    }

    companion object {
        fun provideFactory(videoService: VideoService, playerController: PlayerController? = null): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShortsViewModel(videoService, playerController = playerController) as T
            }
    }
}
