package com.flowtube.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.repository.CatalogRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val videos: List<VideoSummary>) : HomeUiState
    data object Empty : HomeUiState
    data class Error(val error: AppError) : HomeUiState
}

class HomeViewModel(
    private val repository: CatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var activeTrendingJob: Job? = null
    private var trendingGeneration: Long = 0L

    init {
        loadTrending(forceRefresh = false)
    }

    fun loadTrending(forceRefresh: Boolean = false) {
        activeTrendingJob?.cancel()
        val generation = ++trendingGeneration
        _uiState.value = HomeUiState.Loading

        activeTrendingJob = viewModelScope.launch {
            val result = try {
                repository.getTrending(forceRefresh = forceRefresh)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }
            if (generation == trendingGeneration) {
                when (result) {
                    is AppResult.Success -> {
                        if (result.value.isEmpty()) {
                            _uiState.value = HomeUiState.Empty
                        } else {
                            _uiState.value = HomeUiState.Content(result.value)
                        }
                    }
                    is AppResult.Failure -> {
                        _uiState.value = HomeUiState.Error(result.error)
                    }
                }
            }
        }
    }

    fun retry() {
        loadTrending(forceRefresh = true)
    }

    companion object {
        fun provideFactory(repository: CatalogRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository) as T
                }
            }
    }
}
