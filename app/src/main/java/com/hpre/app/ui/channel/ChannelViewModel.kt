package com.hpre.app.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.ContentKey
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ChannelUiState {
    data object Loading : ChannelUiState
    data class Content(val details: ChannelDetails) : ChannelUiState
    data object Empty : ChannelUiState
    data class Error(val error: AppError) : ChannelUiState
}

class ChannelViewModel(
    private val videoService: VideoService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _state = MutableStateFlow<ChannelUiState>(ChannelUiState.Loading)
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()
    private var key: ContentKey? = null
    private var loadJob: Job? = null

    fun load(key: ContentKey) {
        this.key = key
        loadJob?.cancel()
        _state.value = ChannelUiState.Loading
        loadJob = viewModelScope.launch(ioDispatcher) {
            try {
                _state.value = when (val result = videoService.channel(key)) {
                    is AppResult.Success -> if (
                        result.value.channel.name.isBlank() && result.value.videos.isEmpty() && result.value.shorts.isEmpty()
                    ) ChannelUiState.Empty else ChannelUiState.Content(result.value)
                    is AppResult.Failure -> ChannelUiState.Error(result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = ChannelUiState.Error(AppError.Unknown)
            }
        }
    }

    fun retry() = key?.let(::load)

    companion object {
        fun provideFactory(videoService: VideoService): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChannelViewModel(videoService) as T
            }
    }
}
