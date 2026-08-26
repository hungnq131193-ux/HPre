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

data class HomeChip(val label: String, val query: String?)

data class HomeChipsState(
    val chips: List<HomeChip>,
    val selectedIndex: Int = 0
)

class HomeViewModel(
    private val repository: HomeRecommendationSource,
    private val topicFeedSource: TopicFeedSource
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _chipsState = MutableStateFlow(HomeChipsState(DEFAULT_CHIPS))
    val chipsState: StateFlow<HomeChipsState> = _chipsState.asStateFlow()

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
            val selectedChip = _chipsState.value.chips[_chipsState.value.selectedIndex]
            val result = try {
                selectedChip.query?.let { query ->
                    topicFeedSource.videos(query, forceRefresh)
                } ?: repository.home(forceRefresh = forceRefresh)
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

    fun selectChip(index: Int) {
        if (index !in _chipsState.value.chips.indices) return
        if (_chipsState.value.selectedIndex == index) return
        _chipsState.value = _chipsState.value.copy(selectedIndex = index)
        load(forceRefresh = false)
    }

    fun retry() {
        load(forceRefresh = true)
    }

    companion object {
        val DEFAULT_CHIPS = listOf(
            HomeChip("Tất cả", null),
            HomeChip("Âm nhạc", "âm nhạc"),
            HomeChip("Trò chơi", "trò chơi"),
            HomeChip("Phim ảnh", "phim ảnh"),
            HomeChip("Thể thao", "thể thao"),
            HomeChip("Tin tức", "tin tức"),
            HomeChip("Học tập", "học tập"),
            HomeChip("Ẩm thực", "ẩm thực")
        )

        fun provideFactory(
            repository: HomeRecommendationSource,
            topicFeedSource: TopicFeedSource
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository, topicFeedSource) as T
                }
            }
    }
}
