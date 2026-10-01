package com.hpre.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.SubscriptionFeedRepository
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SubscriptionFeedUiState {
    data object Loading : SubscriptionFeedUiState
    data object Empty : SubscriptionFeedUiState
    data class Content(
        val videos: List<VideoSummary>,
        val failedChannels: List<ContentKey>,
        val isRefreshing: Boolean = false,
        val refreshError: AppError? = null
    ) : SubscriptionFeedUiState
    data class Error(val error: AppError) : SubscriptionFeedUiState
}

class SubscriptionFeedViewModel(
    private val repository: SubscriptionFeedRepository,
    private val videoService: VideoService? = null
) : ViewModel() {
    private val _state = MutableStateFlow<SubscriptionFeedUiState>(SubscriptionFeedUiState.Loading)
    val state: StateFlow<SubscriptionFeedUiState> = _state.asStateFlow()

    private var refreshJob: kotlinx.coroutines.Job? = null
    private var refreshGeneration = 0L

    init { viewModelScope.launch { repository.subscriptionKeys.collect { refresh() } } }

    fun refresh() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        // Keep loaded videos on screen during a refresh; only a first load with
        // nothing to show gets the Loading state.
        val current = _state.value
        _state.value = if (current is SubscriptionFeedUiState.Content) {
            current.copy(isRefreshing = true, refreshError = null)
        } else {
            SubscriptionFeedUiState.Loading
        }
        refreshJob = viewModelScope.launch {
            try {
                val feed = repository.refreshAll(forceRefresh = true)
                if (generation != refreshGeneration) return@launch
                val existing = _state.value as? SubscriptionFeedUiState.Content
                if (feed.videos.isNotEmpty()) prefetchTop(feed.videos)
                _state.value = when {
                    feed.videos.isNotEmpty() -> SubscriptionFeedUiState.Content(
                        feed.videos, feed.failedChannels
                    )
                    feed.failedChannels.isNotEmpty() -> existing?.copy(
                        isRefreshing = false,
                        refreshError = AppError.NetworkError
                    ) ?: SubscriptionFeedUiState.Error(AppError.NetworkError)
                    else -> SubscriptionFeedUiState.Empty
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation != refreshGeneration) return@launch
                val existing = _state.value as? SubscriptionFeedUiState.Content
                _state.value = existing?.copy(
                    isRefreshing = false,
                    refreshError = AppError.Unknown
                ) ?: SubscriptionFeedUiState.Error(AppError.Unknown)
            }
        }
    }

    /**
     * Warms the shared extraction cache for the first few visible items so a tap on them skips
     * the cold network round-trip. Bounded inside [VideoService.prefetch]; failures are ignored.
     */
    private fun prefetchTop(videos: List<VideoSummary>) {
        val service = videoService ?: return
        viewModelScope.launch {
            try {
                service.prefetch(videos.take(3).map(VideoSummary::key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun provideFactory(
            repository: SubscriptionFeedRepository,
            videoService: VideoService? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SubscriptionFeedViewModel(repository, videoService) as T
            }
    }
}
