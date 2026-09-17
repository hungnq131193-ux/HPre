package com.hpre.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.SubscriptionFeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SubscriptionFeedUiState {
    data object Loading : SubscriptionFeedUiState
    data object Empty : SubscriptionFeedUiState
    data class Content(val videos: List<VideoSummary>, val failedChannels: List<ContentKey>) : SubscriptionFeedUiState
    data class Error(val error: AppError) : SubscriptionFeedUiState
}

class SubscriptionFeedViewModel(
    private val repository: SubscriptionFeedRepository
) : ViewModel() {
    private val _state = MutableStateFlow<SubscriptionFeedUiState>(SubscriptionFeedUiState.Loading)
    val state: StateFlow<SubscriptionFeedUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = SubscriptionFeedUiState.Loading
        viewModelScope.launch {
            try {
                val feed = repository.refreshAll(forceRefresh = true)
                _state.value = when {
                    feed.videos.isNotEmpty() -> SubscriptionFeedUiState.Content(feed.videos, feed.failedChannels)
                    feed.failedChannels.isNotEmpty() -> SubscriptionFeedUiState.Error(AppError.NetworkError)
                    else -> SubscriptionFeedUiState.Empty
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = SubscriptionFeedUiState.Error(AppError.Unknown)
            }
        }
    }

    companion object {
        fun provideFactory(repository: SubscriptionFeedRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SubscriptionFeedViewModel(repository) as T
            }
    }
}
