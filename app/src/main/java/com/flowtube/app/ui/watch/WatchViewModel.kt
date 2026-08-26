package com.flowtube.app.ui.watch

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.savedstate.SavedStateRegistryOwner
import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.CommentPage
import com.flowtube.app.model.PageToken
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.player.PlaybackState
import com.flowtube.app.player.PlayerController
import com.flowtube.app.player.QualityOption
import com.flowtube.app.repository.CatalogRepository
import com.flowtube.app.repository.VideoService
import com.flowtube.app.ui.common.AsyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val historyRepository: com.flowtube.app.repository.HistoryRepository? = null,
    private val subscriptionRepository: com.flowtube.app.repository.SubscriptionRepository? = null,
    private val playlistRepository: com.flowtube.app.repository.PlaylistRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        const val KEY_IS_FULLSCREEN = "watch_is_fullscreen"

        fun provideFactory(
            videoService: VideoService,
            playerControllerFactory: () -> PlayerController,
            catalogRepository: CatalogRepository? = null,
            historyRepository: com.flowtube.app.repository.HistoryRepository? = null,
            subscriptionRepository: com.flowtube.app.repository.SubscriptionRepository? = null,
            playlistRepository: com.flowtube.app.repository.PlaylistRepository? = null,
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
                    ioDispatcher = ioDispatcher
                ) as T
            }
        }

        fun provideFactory(
            videoService: VideoService,
            playerController: PlayerController,
            catalogRepository: CatalogRepository? = null,
            historyRepository: com.flowtube.app.repository.HistoryRepository? = null,
            subscriptionRepository: com.flowtube.app.repository.SubscriptionRepository? = null,
            playlistRepository: com.flowtube.app.repository.PlaylistRepository? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = provideFactory(
            videoService = videoService,
            playerControllerFactory = { playerController },
            catalogRepository = catalogRepository,
            historyRepository = historyRepository,
            subscriptionRepository = subscriptionRepository,
            playlistRepository = playlistRepository,
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

    val localPlaylists: StateFlow<List<com.flowtube.app.repository.LocalPlaylist>> =
        playlistRepository?.observePlaylists()?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ) ?: MutableStateFlow(emptyList<com.flowtube.app.repository.LocalPlaylist>()).asStateFlow()

    val playbackState: StateFlow<PlaybackState> = playerController.state

    private var currentKey: ContentKey? = null
    private var currentGeneration: Long = 0L
    private var loadJob: Job? = null
    private var relatedJob: Job? = null
    private var commentsJob: Job? = null
    private var commentsInFlight = false

    fun load(key: ContentKey, forceRefresh: Boolean = false) {
        if (!forceRefresh && currentKey == key && _uiState.value.details != null && _uiState.value.error == null && playbackState.value.error == null) {
            return
        }

        loadJob?.cancel()
        val generation = ++currentGeneration
        currentKey = key

        _uiState.update {
            it.copy(
                key = key,
                isLoading = true,
                error = null
            )
        }

        loadJob = viewModelScope.launch(ioDispatcher) {
            try {
                val detailsResult = catalogRepository?.video(key, forceRefresh = forceRefresh) ?: videoService.video(key)
                val streamResult = videoService.streamInfo(key)

                if (generation != currentGeneration || currentKey != key) return@launch

                when {
                    streamResult is AppResult.Failure -> {
                        if (generation != currentGeneration || currentKey != key) return@launch
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = streamResult.error
                            )
                        }
                    }
                    detailsResult is AppResult.Failure -> {
                        if (generation != currentGeneration || currentKey != key) return@launch
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = detailsResult.error
                            )
                        }
                    }
                    streamResult is AppResult.Success && detailsResult is AppResult.Success -> {
                        if (generation != currentGeneration || currentKey != key) return@launch
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                details = detailsResult.value,
                                error = null
                            )
                        }

                        val resumePos = historyRepository?.let { repo ->
                            val historyItemResult = repo.getHistoryItem(key)
                            if (historyItemResult is AppResult.Success && historyItemResult.value != null) {
                                val item = historyItemResult.value!!
                                if (com.flowtube.app.repository.HistoryRepository.shouldOfferResume(
                                        item.playbackPositionMs,
                                        detailsResult.value.durationSeconds
                                    )
                                ) {
                                    item.playbackPositionMs
                                } else 0L
                            } else 0L
                        } ?: 0L

                        playerController.prepare(key, streamResult.value, startPositionMs = resumePos)
                        loadRelated(key)
                        loadComments(key, null, append = false)
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

    fun retryRelated() = currentKey?.let(::loadRelated)

    fun retryComments() = currentKey?.let { loadComments(it, null, append = false) }

    fun loadMoreComments() {
        val key = currentKey ?: return
        val page = (_commentsState.value as? AsyncState.Content)?.value ?: return
        val token = page.nextPageToken ?: return
        loadComments(key, token, append = true)
    }

    private fun loadRelated(key: ContentKey) {
        relatedJob?.cancel()
        _relatedState.value = AsyncState.Loading
        relatedJob = viewModelScope.launch(ioDispatcher) {
            try {
                _relatedState.value = when (val result = videoService.related(key)) {
                    is AppResult.Success -> if (result.value.isEmpty()) AsyncState.Empty else AsyncState.Content(result.value)
                    is AppResult.Failure -> AsyncState.Error(result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _relatedState.value = AsyncState.Error(AppError.Unknown)
            }
        }
    }

    private fun loadComments(key: ContentKey, token: PageToken?, append: Boolean) {
        if (!videoService.supportsComments) {
            _commentsState.value = AsyncState.Empty
            return
        }
        if (commentsInFlight) return
        commentsInFlight = true
        if (!append) _commentsState.value = AsyncState.Loading
        commentsJob = viewModelScope.launch(ioDispatcher) {
            try {
                _commentsState.value = when (val result = videoService.comments(key, token)) {
                    is AppResult.Success -> {
                        val prior = if (append) (_commentsState.value as? AsyncState.Content)?.value?.comments.orEmpty() else emptyList()
                        val page = result.value.copy(comments = (prior + result.value.comments).distinctBy { it.commentId })
                        if (page.comments.isEmpty()) AsyncState.Empty else AsyncState.Content(page)
                    }
                    is AppResult.Failure -> AsyncState.Error(result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _commentsState.value = AsyncState.Error(AppError.Unknown)
            } finally {
                commentsInFlight = false
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
                val channel = com.flowtube.app.model.Channel(
                    key = channelKey,
                    name = details.channelName ?: "Channel",
                    canonicalUrl = "https://flowtube.test/channel/${channelKey.nativeId}",
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
        val key = currentKey ?: return

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
            loadJob?.cancel()
            val generation = ++currentGeneration

            _uiState.update { it.copy(isLoading = true, error = null) }

            loadJob = viewModelScope.launch(ioDispatcher) {
                try {
                    val freshStreamResult = videoService.streamInfo(key)
                    if (generation != currentGeneration || currentKey != key) return@launch

                    when (freshStreamResult) {
                        is AppResult.Success -> {
                            _uiState.update { it.copy(isLoading = false, error = null) }
                            playerController.prepare(
                                key = key,
                                streamInfo = freshStreamResult.value,
                                startPositionMs = snapshotPosition,
                                playWhenReady = snapshotPlayWhenReady,
                                initialQuality = snapshotQuality
                            )
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
