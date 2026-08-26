package com.hpre.app.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlayerController
import com.hpre.app.repository.ShortsFeedSource
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ShortsUiState {
    data object Loading : ShortsUiState
    data object Unavailable : ShortsUiState
    data object Empty : ShortsUiState
    data class Content(val videos: List<VideoSummary>) : ShortsUiState
    data class Error(val error: AppError) : ShortsUiState
}

class ShortsViewModel(
    private val feedRepository: ShortsFeedSource,
    private val videoService: VideoService,
    val playerController: PlayerController,
    private val playlistRepository: PlaylistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _state = MutableStateFlow<ShortsUiState>(ShortsUiState.Loading)
    val state: StateFlow<ShortsUiState> = _state.asStateFlow()
    private val _saveErrors = MutableStateFlow<Map<com.hpre.app.model.ContentKey, AppError>>(emptyMap())
    val saveErrors: StateFlow<Map<com.hpre.app.model.ContentKey, AppError>> = _saveErrors.asStateFlow()
    private var loadJob: Job? = null
    private var activationJob: Job? = null
    private var activationGeneration = 0L
    private val saveMutex = Mutex()

    fun load(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        _state.value = ShortsUiState.Loading
        loadJob = viewModelScope.launch(ioDispatcher) {
            try {
                _state.value = when (val result = feedRepository.load(forceRefresh)) {
                    is AppResult.Success -> if (result.value.isEmpty()) ShortsUiState.Empty else ShortsUiState.Content(result.value)
                    is AppResult.Failure -> if (result.error == AppError.UnsupportedFormat) {
                        ShortsUiState.Unavailable
                    } else {
                        ShortsUiState.Error(result.error)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.value = ShortsUiState.Error(AppError.Unknown)
            }
        }
    }

    fun retry() = load(forceRefresh = true)

    fun activate(video: VideoSummary) {
        activationJob?.cancel()
        val generation = ++activationGeneration
        activationJob = viewModelScope.launch(ioDispatcher) {
            try {
                when (val result = videoService.streamInfo(video.key)) {
                    is AppResult.Success -> if (generation == activationGeneration) {
                        playerController.prepare(video.key, result.value, playWhenReady = true)
                    }
                    is AppResult.Failure -> if (generation == activationGeneration) {
                        _state.value = if (result.error == AppError.UnsupportedFormat) {
                            ShortsUiState.Unavailable
                        } else {
                            ShortsUiState.Error(result.error)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (generation == activationGeneration) {
                    _state.value = ShortsUiState.Error(AppError.Unknown)
                }
            }
        }
    }

    fun save(video: VideoSummary) {
        viewModelScope.launch(ioDispatcher) {
            saveMutex.withLock {
                _saveErrors.value = _saveErrors.value - video.key
                val existing = playlistRepository.observePlaylists().first()
                    .firstOrNull { it.title == SHORTS_PLAYLIST_TITLE }
                val playlistId = existing?.playlistId ?: when (
                    val created = playlistRepository.createPlaylist(SHORTS_PLAYLIST_TITLE)
                ) {
                    is AppResult.Success -> created.value
                    is AppResult.Failure -> {
                        _saveErrors.value = _saveErrors.value + (video.key to created.error)
                        return@withLock
                    }
                }
                when (val added = playlistRepository.addEntry(playlistId, video)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> {
                        _saveErrors.value = _saveErrors.value + (video.key to added.error)
                    }
                }
            }
        }
    }

    fun playPause() = playerController.playPause()

    override fun onCleared() {
        loadJob?.cancel()
        activationJob?.cancel()
    }

    companion object {
        fun provideFactory(
            feedRepository: ShortsFeedSource,
            videoService: VideoService,
            playerController: PlayerController,
            playlistRepository: PlaylistRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShortsViewModel(feedRepository, videoService, playerController, playlistRepository) as T
        }

        private const val SHORTS_PLAYLIST_TITLE = "Shorts"
    }
}
