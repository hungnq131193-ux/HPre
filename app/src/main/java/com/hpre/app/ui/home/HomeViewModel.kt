package com.hpre.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.HomeRecommendationSource
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
    private val repository: HomeRecommendationSource
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var activeLoadJob: Job? = null
    private var loadGeneration: Long = 0L

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean = false) {
        activeLoadJob?.cancel()
        val generation = ++loadGeneration
        _uiState.value = HomeUiState.Loading

        activeLoadJob = viewModelScope.launch {
            val result = try {
                repository.home(forceRefresh = forceRefresh)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }
            if (generation == loadGeneration) {
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
        load(forceRefresh = true)
    }

    companion object {
        fun provideFactory(repository: HomeRecommendationSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository) as T
                }
            }
    }
}
