package com.hpre.app.ui.watch

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.savedstate.SavedStateRegistryOwner
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.CommentPage
import com.hpre.app.model.PageToken
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.VideoService
import com.hpre.app.repository.WatchRecommendationSource
import com.hpre.app.ui.common.AsyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchUiState(
    val key: ContentKey? = null,
    val isLoading: Boolean = false,
    val details: VideoDetails? = null,
    val error: AppError? = null,
    val isFullscreen: Boolean = false
)

class WatchViewModel(
    private val videoService: VideoService,
    val playerController: PlayerController,
    val savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository? = null,
    private val historyRepository: com.hpre.app.repository.HistoryRepository? = null,
    private val subscriptionRepository: com.hpre.app.repository.SubscriptionRepository? = null,
    private val playlistRepository: com.hpre.app.repository.PlaylistRepository? = null,
    private val watchRecommendationSource: WatchRecommendationSource? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        const val KEY_IS_FULLSCREEN = "watch_is_fullscreen"

        fun provideFactory(
            videoService: VideoService,
            playerControllerFactory: () -> PlayerController,
            catalogRepository: CatalogRepository? = null,
            historyRepository: com.hpre.app.repository.HistoryRepository? = null,
            subscriptionRepository: com.hpre.app.repository.SubscriptionRepository? = null,
            playlistRepository: com.hpre.app.repository.PlaylistRepository? = null,
            watchRecommendationSource: WatchRecommendationSource? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val handle = extras.createSavedStateHandle()
                return WatchViewModel(
                    videoService = videoService,
                    playerController = playerControllerFactory(),
                    savedStateHandle = handle,
                    catalogRepository = catalogRepository,
                    historyRepository = historyRepository,
                    subscriptionRepository = subscriptionRepository,
                    playlistRepository = playlistRepository,
                    watchRecommendationSource = watchRecommendationSource,
                    ioDispatcher = ioDispatcher
                ) as T
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WatchViewModel(
                    videoService = videoService,
                    playerController = playerControllerFactory(),
                    savedStateHandle = SavedStateHandle(),
                    catalogRepository = catalogRepository,
                    historyRepository = historyRepository,
                    subscriptionRepository = subscriptionRepository,
                    playlistRepository = playlistRepository,
                    watchRecommendationSource = watchRecommendationSource,
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }

        fun provideFactory(
            videoService: VideoService,
            playerController: PlayerController,
            catalogRepository: CatalogRepository? = null,
            historyRepository: com.hpre.app.repository.HistoryRepository? = null,
            subscriptionRepository: com.hpre.app.repository.SubscriptionRepository? = null,
            playlistRepository: com.hpre.app.repository.PlaylistRepository? = null,
            watchRecommendationSource: WatchRecommendationSource? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = provideFactory(
            videoService = videoService,
            playerControllerFactory = { playerController },
            catalogRepository = catalogRepository,
            historyRepository = historyRepository,
            subscriptionRepository = subscriptionRepository,
            playlistRepository = playlistRepository,
            watchRecommendationSource = watchRecommendationSource,
            ioDispatcher = ioDispatcher
        )
    }

    private val initialFullscreen: Boolean = savedStateHandle.get<Boolean>(KEY_IS_FULLSCREEN) ?: false

    private val _uiState = MutableStateFlow(WatchUiState(isFullscreen = initialFullscreen))
    val uiState: StateFlow<WatchUiState> = _uiState.asStateFlow()

    private val _relatedState = MutableStateFlow<AsyncState<List<VideoSummary>>>(AsyncState.Loading)
    val relatedState: StateFlow<AsyncState<List<VideoSummary>>> = _relatedState.asStateFlow()
    private val _commentsState = MutableStateFlow<AsyncState<CommentPage>>(AsyncState.Loading)
    val commentsState: StateFlow<AsyncState<CommentPage>> = _commentsState.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isSubscribed: StateFlow<Boolean> = _uiState.flatMapLatest { state ->
        val cKey = state.details?.channelKey
        if (cKey != null && subscriptionRepository != null) {
            subscriptionRepository.observeIsSubscribed(cKey)
        } else {
            flowOf(false)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    val localPlaylists: StateFlow<List<com.hpre.app.repository.LocalPlaylist>> =
        playlistRepository?.observePlaylists()?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ) ?: MutableStateFlow(emptyList<com.hpre.app.repository.LocalPlaylist>()).asStateFlow()

    val playbackState: StateFlow<PlaybackState> = playerController.state

    @Volatile
    private var currentKey: ContentKey? = null
    @Volatile
    private var currentGeneration: Long = 0L
    private val sessionGuard = Any()
    private var loadJob: Job? = null
    private var relatedJob: Job? = null
    private var commentsJob: Job? = null
    private var commentsInFlight = false

    fun load(key: ContentKey, forceRefresh: Boolean = false) {
        if (!forceRefresh && currentKey == key && _uiState.value.details != null && _uiState.value.error == null && playbackState.value.error == null) {
            return
        }

        val generation = synchronized(sessionGuard) {
            loadJob?.cancel()
            relatedJob?.cancel()
            commentsJob?.cancel()
            commentsInFlight = false
            currentKey = key
            ++currentGeneration
        }

        _uiState.update {
            it.copy(
                key = key,
                isLoading = true,
                error = null
            )
        }

        loadJob = viewModelScope.launch(ioDispatcher) {
            try {
                val detailsDeferred = async {
                    catalogRepository?.video(key, forceRefresh = forceRefresh) ?: videoService.video(key)
                }
                val streamDeferred = async { videoService.streamInfo(key) }

                val streamResult = streamDeferred.await()

                if (generation != currentGeneration || currentKey != key) return@launch

                if (streamResult is AppResult.Failure) {
                    detailsDeferred.cancel()
                    _uiState.update { it.copy(isLoading = false, error = streamResult.error) }
                    return@launch
                }

                val preparedCurrentSession = synchronized(sessionGuard) {
                    if (generation != currentGeneration || currentKey != key) {
                        false
                    } else {
                        playerController.prepare(
                            key,
                            (streamResult as AppResult.Success).value,
                            startPositionMs = 0L
                        )
                        true
                    }
                }
                if (!preparedCurrentSession) return@launch
                if (watchRecommendationSource == null) {
                    loadRelated(key, null, generation, forceRefresh)
                }
                loadComments(key, generation, null, append = false)

                launch {
                    val historyResult = historyRepository?.getHistoryItem(key)
                    if (generation != currentGeneration || currentKey != key) return@launch
                    val historyItem = (historyResult as? AppResult.Success)?.value
                    if (historyItem != null &&
                        com.hpre.app.repository.HistoryRepository.shouldOfferResume(
                            historyItem.playbackPositionMs,
                            historyItem.durationSeconds
                        )
                    ) {
                        synchronized(sessionGuard) {
                            if (generation == currentGeneration && currentKey == key) {
                                playerController.seekTo(historyItem.playbackPositionMs)
                            }
                        }
                    }
                }

                when (val detailsResult = detailsDeferred.await()) {
                    is AppResult.Success -> {
                        if (generation != currentGeneration || currentKey != key) return@launch
                        _uiState.update {
                            it.copy(isLoading = false, details = detailsResult.value, error = null)
                        }
                        if (watchRecommendationSource != null) {
                            loadRelated(key, detailsResult.value, generation, forceRefresh)
                        }
                    }
                    is AppResult.Failure -> {
                        if (generation != currentGeneration || currentKey != key) return@launch
                        _uiState.update {
                            it.copy(isLoading = false, error = detailsResult.error)
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (generation != currentGeneration || currentKey != key) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = AppError.Unknown
                    )
                }
            }
        }
    }

    fun retryRelated() {
        val key = currentKey ?: return
        loadRelated(key, _uiState.value.details, currentGeneration, forceRefresh = true)
    }

    fun retryComments() = currentKey?.let {
        loadComments(it, currentGeneration, null, append = false)
    }

    fun loadMoreComments() {
        val key = currentKey ?: return
        val page = (_commentsState.value as? AsyncState.Content)?.value ?: return
        val token = page.nextPageToken ?: return
        loadComments(key, currentGeneration, token, append = true)
    }

    private fun loadRelated(
        key: ContentKey,
        details: VideoDetails?,
        generation: Long,
        forceRefresh: Boolean
    ) {
        relatedJob?.cancel()
        _relatedState.value = AsyncState.Loading
        relatedJob = viewModelScope.launch(ioDispatcher) {
            try {
                val result = if (watchRecommendationSource != null && details != null) {
                    watchRecommendationSource.recommendations(key, details, forceRefresh)
                } else {
                    videoService.related(key)
                }
                val nextState = when (result) {
                    is AppResult.Success -> if (result.value.isEmpty()) AsyncState.Empty else AsyncState.Content(result.value)
                    is AppResult.Failure -> AsyncState.Error(result.error)
                }
                if (generation == currentGeneration && currentKey == key) {
                    _relatedState.value = nextState
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (generation == currentGeneration && currentKey == key) {
                    _relatedState.value = AsyncState.Error(AppError.Unknown)
                }
            }
        }
    }

    private fun loadComments(
        key: ContentKey,
        generation: Long,
        token: PageToken?,
        append: Boolean
    ) {
        if (!videoService.supportsComments) {
            if (generation == currentGeneration && currentKey == key) {
                _commentsState.value = AsyncState.Empty
            }
            return
        }
        val admitted = synchronized(sessionGuard) {
            if (generation != currentGeneration || currentKey != key || commentsInFlight) {
                false
            } else {
                commentsInFlight = true
                if (!append) _commentsState.value = AsyncState.Loading
                true
            }
        }
        if (!admitted) return
        commentsJob = viewModelScope.launch(ioDispatcher) {
            try {
                val nextState = when (val result = videoService.comments(key, token)) {
                    is AppResult.Success -> {
                        val prior = if (append) (_commentsState.value as? AsyncState.Content)?.value?.comments.orEmpty() else emptyList()
                        val page = result.value.copy(comments = (prior + result.value.comments).distinctBy { it.commentId })
                        if (page.comments.isEmpty()) AsyncState.Empty else AsyncState.Content(page)
                    }
                    is AppResult.Failure -> AsyncState.Error(result.error)
                }
                if (generation == currentGeneration && currentKey == key) {
                    _commentsState.value = nextState
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (generation == currentGeneration && currentKey == key) {
                    _commentsState.value = AsyncState.Error(AppError.Unknown)
                }
            } finally {
                synchronized(sessionGuard) {
                    if (generation == currentGeneration && currentKey == key) {
                        commentsInFlight = false
                    }
                }
            }
        }
    }

    fun toggleSubscription() {
        val details = _uiState.value.details ?: return
        val channelKey = details.channelKey ?: return
        val subRepo = subscriptionRepository ?: return
        val currentlySubscribed = isSubscribed.value

        viewModelScope.launch(ioDispatcher) {
            if (currentlySubscribed) {
                subRepo.unsubscribe(channelKey)
            } else {
                val channel = com.hpre.app.model.Channel(
                    key = channelKey,
                    name = details.channelName ?: "Channel",
                    canonicalUrl = "https://hpre.test/channel/${channelKey.nativeId}",
                    avatarUrl = details.channelAvatarUrl,
                    bannerUrl = null,
                    subscriberCountText = details.subscriberCountText,
                    description = null
                )
                subRepo.subscribe(channel)
            }
        }
    }

    fun addVideoToPlaylist(playlistId: Long) {
        val details = _uiState.value.details ?: return
        val playlistRepo = playlistRepository ?: return
        val summary = VideoSummary(
            key = details.key,
            title = details.title,
            canonicalUrl = details.canonicalUrl,
            channelKey = details.channelKey,
            channelName = details.channelName,
            channelAvatarUrl = details.channelAvatarUrl,
            thumbnailUrl = details.thumbnailUrl,
            durationSeconds = details.durationSeconds,
            viewCount = details.viewCount,
            publishedTimestamp = details.publishedTimestamp,
            isLive = details.isLive,
            isShort = details.isShort
        )
        viewModelScope.launch(ioDispatcher) {
            playlistRepo.addEntry(playlistId, summary)
        }
    }

    fun createPlaylistAndAddVideo(title: String) {
        val details = _uiState.value.details ?: return
        val playlistRepo = playlistRepository ?: return
        val summary = VideoSummary(
            key = details.key,
            title = details.title,
            canonicalUrl = details.canonicalUrl,
            channelKey = details.channelKey,
            channelName = details.channelName,
            channelAvatarUrl = details.channelAvatarUrl,
            thumbnailUrl = details.thumbnailUrl,
            durationSeconds = details.durationSeconds,
            viewCount = details.viewCount,
            publishedTimestamp = details.publishedTimestamp,
            isLive = details.isLive,
            isShort = details.isShort
        )
        viewModelScope.launch(ioDispatcher) {
            val res = playlistRepo.createPlaylist(title)
            if (res is AppResult.Success) {
                playlistRepo.addEntry(res.value, summary)
            }
        }
    }

    fun retry() {
        val key = synchronized(sessionGuard) { currentKey } ?: return

        // Distinguish metadata/initial load error vs playback state error
        if (playbackState.value.error != null && _uiState.value.details != null) {
            val currentPlayback = playbackState.value
            val snapshot = currentPlayback.retrySnapshot
            val retryKey = snapshot?.key ?: key
            if (retryKey != key) {
                // Key mismatch -> full reload
                _uiState.update { it.copy(error = null, isLoading = true) }
                load(key, forceRefresh = true)
                return
            }

            val snapshotPosition = snapshot?.positionMs ?: currentPlayback.currentPositionMs
            val snapshotPlayWhenReady = snapshot?.userRequestedPlay ?: (currentPlayback.isPlaying || currentPlayback.playWhenReady)
            val snapshotQuality = snapshot?.selectedQuality ?: currentPlayback.selectedQuality

            // Playback error: resolve fresh stream and prepare current key without clearing details
            val generation = synchronized(sessionGuard) {
                loadJob?.cancel()
                ++currentGeneration
            }

            _uiState.update { it.copy(isLoading = true, error = null) }

            loadJob = viewModelScope.launch(ioDispatcher) {
                try {
                    val freshStreamResult = videoService.streamInfo(key)
                    if (generation != currentGeneration || currentKey != key) return@launch

                    when (freshStreamResult) {
                        is AppResult.Success -> {
                            val preparedCurrentSession = synchronized(sessionGuard) {
                                if (generation != currentGeneration || currentKey != key) {
                                    false
                                } else {
                                    playerController.prepare(
                                        key = key,
                                        streamInfo = freshStreamResult.value,
                                        startPositionMs = snapshotPosition,
                                        playWhenReady = snapshotPlayWhenReady,
                                        initialQuality = snapshotQuality
                                    )
                                    true
                                }
                            }
                            if (!preparedCurrentSession) return@launch
                            _uiState.update { it.copy(isLoading = false, error = null) }
                        }
                        is AppResult.Failure -> {
                            _uiState.update { it.copy(isLoading = false, error = freshStreamResult.error) }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    if (generation != currentGeneration || currentKey != key) return@launch
                    _uiState.update { it.copy(isLoading = false, error = AppError.Unknown) }
                }
            }
        } else {
            // Metadata or general error: full reload with refresh
            _uiState.update { it.copy(error = null, isLoading = true) }
            load(key, forceRefresh = true)
        }
    }

    fun setFullscreen(isFullscreen: Boolean) {
        savedStateHandle[KEY_IS_FULLSCREEN] = isFullscreen
        _uiState.update { it.copy(isFullscreen = isFullscreen) }
    }

    fun playPause() {
        playerController.playPause()
    }

    fun seekBy(deltaMs: Long) {
        playerController.seekBy(deltaMs)
    }

    fun seekTo(positionMs: Long) {
        playerController.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        playerController.setPlaybackSpeed(speed)
    }

    fun selectQuality(quality: QualityOption) {
        playerController.selectQuality(quality)
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        relatedJob?.cancel()
        commentsJob?.cancel()
        // Note: ViewModel does not tear down service player on normal unbind
    }
}
