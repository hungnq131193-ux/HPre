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
import com.hpre.app.core.performance.VideoOpenEvent
import com.hpre.app.core.performance.VideoOpenMetrics
import com.hpre.app.model.ContentKey
import com.hpre.app.model.CommentPage
import com.hpre.app.model.PageToken
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.player.toStructuralState
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.RecommendationRequest
import com.hpre.app.repository.VideoService
import com.hpre.app.repository.WatchRecommendationSource
import com.hpre.app.repository.WatchStateCache
import com.hpre.app.repository.WatchStateSnapshot
import com.hpre.app.ui.common.AsyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class RefreshableAsyncState<T> private constructor(
    val value: T?,
    val isInitialLoading: Boolean,
    val isRefreshing: Boolean,
    val error: AppError?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RefreshableAsyncState<*>) return false
        if (value != other.value) return false
        if (isInitialLoading != other.isInitialLoading) return false
        if (isRefreshing != other.isRefreshing) return false
        if (error != other.error) return false
        return true
    }

    override fun hashCode(): Int {
        var result = value?.hashCode() ?: 0
        result = 31 * result + isInitialLoading.hashCode()
        result = 31 * result + isRefreshing.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "RefreshableAsyncState(value=$value, isInitialLoading=$isInitialLoading, isRefreshing=$isRefreshing, error=$error)"
    }

    companion object {
        fun <T> initial(): RefreshableAsyncState<T> = RefreshableAsyncState(
            value = null,
            isInitialLoading = true,
            isRefreshing = false,
            error = null
        )

        fun <T> content(value: T): RefreshableAsyncState<T> = RefreshableAsyncState(
            value = value,
            isInitialLoading = false,
            isRefreshing = false,
            error = null
        )

        fun <T> refreshing(currentValue: T?): RefreshableAsyncState<T> = RefreshableAsyncState(
            value = currentValue,
            isInitialLoading = false,
            isRefreshing = true,
            error = null
        )

        fun <T> error(error: AppError, previousValue: T? = null): RefreshableAsyncState<T> = RefreshableAsyncState(
            value = previousValue,
            isInitialLoading = false,
            isRefreshing = false,
            error = error
        )
    }
}

enum class FullScreenResizeMode {
    FIT,
    FILL,
    ZOOM;

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun toMedia3ResizeMode(): Int = when (this) {
        FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
}

data class WatchUiState(
    val key: ContentKey? = null,
    val isLoading: Boolean = false,
    val details: VideoDetails? = null,
    val error: AppError? = null,
    val isFullscreen: Boolean = false,
    val fullScreenResizeMode: FullScreenResizeMode = FullScreenResizeMode.FIT,
    val commentsExpanded: Boolean = false,
    val isPlayerLoading: Boolean = false,
    val thumbnailUrl: String? = null
)

data class CommentsPaginationState(
    val isLoading: Boolean = false,
    val error: AppError? = null,
    val earlierCommentsDropped: Boolean = false
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
    private val watchStateCache: WatchStateCache? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val videoOpenMetrics: VideoOpenMetrics = VideoOpenMetrics.Default
) : ViewModel() {

    companion object {
        const val KEY_IS_FULLSCREEN = "watch_is_fullscreen"
        const val KEY_FULLSCREEN_RESIZE_MODE = "watch_fullscreen_resize_mode"
        internal const val RESUME_LOOKUP_TIMEOUT_MS = 1_500L
        internal const val COMMENTS_READY_FALLBACK_MS = 2_000L
        internal const val MAX_RETAINED_COMMENTS = 200
        internal const val MAX_CACHED_COMMENTS = 60

        fun provideFactory(
            videoService: VideoService,
            playerControllerFactory: () -> PlayerController,
            catalogRepository: CatalogRepository? = null,
            historyRepository: com.hpre.app.repository.HistoryRepository? = null,
            subscriptionRepository: com.hpre.app.repository.SubscriptionRepository? = null,
            playlistRepository: com.hpre.app.repository.PlaylistRepository? = null,
            watchRecommendationSource: WatchRecommendationSource? = null,
            watchStateCache: WatchStateCache? = null,
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
                    watchStateCache = watchStateCache,
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
                    watchStateCache = watchStateCache,
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
            watchStateCache: WatchStateCache? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = provideFactory(
            videoService = videoService,
            playerControllerFactory = { playerController },
            catalogRepository = catalogRepository,
            historyRepository = historyRepository,
            subscriptionRepository = subscriptionRepository,
            playlistRepository = playlistRepository,
            watchRecommendationSource = watchRecommendationSource,
            watchStateCache = watchStateCache,
            ioDispatcher = ioDispatcher
        )
    }

    private val initialFullscreen: Boolean = savedStateHandle.get<Boolean>(KEY_IS_FULLSCREEN) ?: false
    private val initialFullScreenResizeMode: FullScreenResizeMode = savedStateHandle.get<String>(KEY_FULLSCREEN_RESIZE_MODE)?.let {
        try { FullScreenResizeMode.valueOf(it) } catch (_: Exception) { FullScreenResizeMode.FIT }
    } ?: FullScreenResizeMode.FIT

    private val _uiState = MutableStateFlow(
        WatchUiState(
            isFullscreen = initialFullscreen,
            fullScreenResizeMode = initialFullScreenResizeMode
        )
    )
    val uiState: StateFlow<WatchUiState> = _uiState.asStateFlow()

    private val _relatedState = MutableStateFlow<RefreshableAsyncState<List<VideoSummary>>>(RefreshableAsyncState.initial())
    val relatedState: StateFlow<RefreshableAsyncState<List<VideoSummary>>> = _relatedState.asStateFlow()
    private val _commentsState = MutableStateFlow<AsyncState<CommentPage>>(AsyncState.Loading)
    val commentsState: StateFlow<AsyncState<CommentPage>> = _commentsState.asStateFlow()
    private val _commentsPagination = MutableStateFlow(CommentsPaginationState())
    val commentsPagination: StateFlow<CommentsPaginationState> = _commentsPagination.asStateFlow()

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
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    val localPlaylists: StateFlow<List<com.hpre.app.repository.LocalPlaylist>> =
        playlistRepository?.observePlaylists()?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ) ?: MutableStateFlow(emptyList<com.hpre.app.repository.LocalPlaylist>()).asStateFlow()

    val playbackState: StateFlow<PlaybackState> = playerController.state
    val structuralPlaybackState: StateFlow<PlaybackState> = playerController.state
        .map(PlaybackState::toStructuralState)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), playerController.state.value.toStructuralState())

    @Volatile
    private var currentKey: ContentKey? = null
    @Volatile
    private var currentGeneration: Long = 0L
    @Volatile
    private var relatedGeneration: Long = 0L
    private val sessionGuard = Any()
    private var loadJob: Job? = null
    private var relatedJob: Job? = null
    private var commentsJob: Job? = null
    private var commentsGateJob: Job? = null
    private var playbackReadyJob: Job? = null
    private var retryingPlayback = false
    @Volatile
    private var cleared = false
    private var commentsInFlight = false
    private var commentsRequestGeneration = 0L
    private var firstCommentsPage: CommentPage? = null

    private suspend fun loadResumePosition(key: ContentKey): Long {
        val repository = historyRepository ?: return 0L
        return try {
            withTimeoutOrNull(RESUME_LOOKUP_TIMEOUT_MS) {
                val item = (repository.getHistoryItem(key) as? AppResult.Success)?.value
                item?.takeIf {
                    HistoryRepository.shouldOfferResume(
                        positionMs = it.playbackPositionMs,
                        durationSeconds = it.durationSeconds
                    )
                }?.playbackPositionMs ?: 0L
            } ?: 0L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional history storage must not cancel a valid stream request.
            0L
        }
    }

    fun load(key: ContentKey, forceRefresh: Boolean = false, initialThumbnailUrl: String? = null) {
        val jobToStart = synchronized(sessionGuard) {
            if (cleared) return
            val activePlayback = playbackState.value
            if (!forceRefresh && currentKey == key && _uiState.value.error == null &&
                activePlayback.error == null
            ) {
                // A lazy job is already admitted even before the IO dispatcher starts it.
                val requestPending = loadJob?.let { !it.isCompleted && !it.isCancelled } == true
                if (requestPending || (activePlayback.key == key && _uiState.value.details != null)) return
            }

            val keyChanged = currentKey != key
            currentKey = key
            val generation = ++currentGeneration
            relatedGeneration++
            cancelLoadRequests()

            // Invalidate the old generation before queuing a stop, so late IO cannot prepare it.
            if (activePlayback.key?.let { it != key } == true) {
                playerController.stopForTransition()
            }

            val cachedSnapshot = if (!forceRefresh) watchStateCache?.get(key) else null
            val reuseActivePlayer = activePlayback.key == key && activePlayback.error == null
            val metricsSession = videoOpenMetrics.start(key)
            firstCommentsPage = cachedSnapshot?.comments
            _commentsPagination.value = CommentsPaginationState()
            _uiState.update {
                it.copy(
                    key = key,
                    isLoading = cachedSnapshot == null,
                    details = cachedSnapshot?.details ?: it.details?.takeIf { details -> details.key == key },
                    error = null,
                    commentsExpanded = if (keyChanged) false else it.commentsExpanded,
                    isPlayerLoading = !reuseActivePlayer || activePlayback.isLoading ||
                        activePlayback.isBuffering ||
                        (!activePlayback.isReady && !activePlayback.hasRenderedFirstFrame),
                    thumbnailUrl = if (keyChanged) initialThumbnailUrl else it.thumbnailUrl
                )
            }
            _relatedState.value = cachedSnapshot?.relatedVideos?.let {
                RefreshableAsyncState.content(it)
            } ?: RefreshableAsyncState.initial()
            _commentsState.value = cachedSnapshot?.comments?.let {
                if (it.comments.isEmpty()) AsyncState.Empty else AsyncState.Content(it)
            } ?: if (videoService.supportsComments) AsyncState.Loading else AsyncState.Empty
            if (cachedSnapshot != null) {
                videoOpenMetrics.mark(metricsSession, VideoOpenEvent.DETAILS_READY)
            }

            viewModelScope.launch(ioDispatcher, start = CoroutineStart.LAZY) loadRequest@ {
                try {
                    val prepareGate = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val detailsJob = if (cachedSnapshot == null) launch details@ {
                        val result = try {
                            if (forceRefresh && !reuseActivePlayer) {
                                // Let the forced stream refresh replace the extractor cache first.
                                // Otherwise metadata can start a second, non-refresh extraction.
                                prepareGate.await()
                            }
                            catalogRepository?.video(key, forceRefresh = forceRefresh)
                                ?: videoService.video(key)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            AppResult.Failure(AppError.Unknown)
                        }
                        coroutineContext.ensureActive()
                        when (result) {
                            is AppResult.Success -> {
                                synchronized(sessionGuard) {
                                    if (!isCurrentRequest(key, generation)) return@details
                                    _uiState.update { state ->
                                        state.copy(
                                            isLoading = false,
                                            details = result.value,
                                            thumbnailUrl = state.thumbnailUrl ?: result.value.thumbnailUrl
                                        )
                                    }
                                    videoOpenMetrics.mark(metricsSession, VideoOpenEvent.DETAILS_READY)
                                    watchStateCache?.put(key, WatchStateSnapshot(result.value, null, null))
                                }
                                recordDetailsInHistory(result.value)
                                if (watchRecommendationSource != null) {
                                    prepareGate.await()
                                    synchronized(sessionGuard) {
                                        if (isCurrentRequest(key, generation)) {
                                            executeRelated(key, result.value, forceRefresh, isRefresh = false)
                                        }
                                    }
                                }
                            }
                            is AppResult.Failure -> synchronized(sessionGuard) {
                                if (isCurrentRequest(key, generation)) {
                                    // Metadata failure must not stop an otherwise valid video pipeline.
                                    _uiState.update { it.copy(isLoading = false, error = result.error) }
                                }
                            }
                        }
                    } else null

                    if (!reuseActivePlayer) {
                        val resumeDeferred = async { loadResumePosition(key) }
                        val streamResult = if (forceRefresh) videoService.refreshStreamInfo(key)
                            else videoService.streamInfo(key)
                        coroutineContext.ensureActive()
                        if (streamResult is AppResult.Failure) {
                            resumeDeferred.cancel()
                            detailsJob?.cancel()
                            publishLoadFailure(key, generation, streamResult.error)
                            return@loadRequest
                        }
                        videoOpenMetrics.mark(metricsSession, VideoOpenEvent.STREAM_INFO_READY)
                        val resumePositionMs = resumeDeferred.await()
                        synchronized(sessionGuard) {
                            if (!isCurrentRequest(key, generation)) return@loadRequest
                            videoOpenMetrics.mark(metricsSession, VideoOpenEvent.PLAYER_PREPARE)
                            playerController.prepare(
                                key,
                                (streamResult as AppResult.Success).value,
                                startPositionMs = resumePositionMs
                            )
                        }
                    }

                    observePlayerReadiness(key, generation)
                    prepareGate.complete(Unit)
                    synchronized(sessionGuard) {
                        if (!isCurrentRequest(key, generation)) return@loadRequest
                        if (cachedSnapshot != null && cachedSnapshot.relatedVideos == null) {
                            executeRelated(key, cachedSnapshot.details, forceRefresh = false, isRefresh = false)
                        } else if (cachedSnapshot == null && watchRecommendationSource == null) {
                            executeRelated(key, null, forceRefresh, isRefresh = false)
                        }
                        if (cachedSnapshot?.comments == null) {
                            scheduleInitialComments(key, generation)
                        }
                    }
                    detailsJob?.join()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A thrown stream/prepare error must also stop children waiting on prepareGate.
                    coroutineContext.cancelChildren()
                    publishLoadFailure(key, generation, AppError.Unknown)
                }
            }.also { loadJob = it }
        }
        jobToStart.start()
    }

    private fun isCurrentRequest(key: ContentKey, generation: Long): Boolean =
        !cleared && currentKey == key && currentGeneration == generation

    /** Cancel before navigation; the outgoing entry may stay alive during its exit animation. */
    fun cancelPendingLoads() {
        synchronized(sessionGuard) {
            currentKey = null
            currentGeneration++
            relatedGeneration++
            cancelLoadRequests()
        }
    }

    /** Called under sessionGuard; the shared player/session deliberately stays alive. */
    private fun cancelLoadRequests() {
        retryingPlayback = false
        loadJob?.cancel()
        loadJob = null
        relatedJob?.cancel()
        relatedJob = null
        playbackReadyJob?.cancel()
        playbackReadyJob = null
        cancelCommentsRequests()
    }

    private fun publishLoadFailure(key: ContentKey, generation: Long, error: AppError) {
        synchronized(sessionGuard) {
            if (!isCurrentRequest(key, generation)) return
            playbackReadyJob?.cancel()
            _uiState.update {
                it.copy(isLoading = false, isPlayerLoading = false, thumbnailUrl = null, error = error)
            }
        }
    }

    private enum class StartupReadiness { WAITING, FINISHED }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observePlayerReadiness(key: ContentKey, generation: Long) {
        val jobToStart = synchronized(sessionGuard) {
            if (!isCurrentRequest(key, generation)) return
            playbackReadyJob?.cancel()
            viewModelScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                playerController.state
                    .map { state ->
                        when {
                            state.key != key -> StartupReadiness.WAITING
                            state.error != null || state.isEnded || state.hasRenderedFirstFrame ->
                                StartupReadiness.FINISHED
                            state.isReady && state.streamType == com.hpre.app.player.PlaybackStreamType.AUDIO_ONLY ->
                                StartupReadiness.FINISHED
                            else -> StartupReadiness.WAITING
                        }
                    }
                    .distinctUntilChanged()
                    .first { it == StartupReadiness.FINISHED }
                synchronized(sessionGuard) {
                    if (isCurrentRequest(key, generation)) {
                        _uiState.update { it.copy(isPlayerLoading = false, thumbnailUrl = null) }
                    }
                }
            }.also { playbackReadyJob = it }
        }
        jobToStart.start()
    }

    private suspend fun recordDetailsInHistory(details: VideoDetails) {
        val repository = historyRepository ?: return
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
        try {
            val progress = playerController.readProgress()
            repository.recordHistory(summary, progress.positionMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Metadata/history failures must not cancel the sibling player pipeline.
        }
    }

    fun retryRelated() {
        val key = synchronized(sessionGuard) { currentKey } ?: return
        val hasExistingContent = _relatedState.value.value != null
        executeRelated(
            key = key,
            details = _uiState.value.details,
            forceRefresh = true,
            isRefresh = hasExistingContent
        )
    }

    fun refreshRelated() {
        val key = synchronized(sessionGuard) { currentKey } ?: return
        val currentState = _relatedState.value
        // If there is no loaded batch or initial loading is still in-progress, or already refreshing: no-op
        if (currentState.value == null || currentState.isInitialLoading || currentState.isRefreshing) {
            return
        }
        executeRelated(
            key = key,
            details = _uiState.value.details,
            forceRefresh = true,
            isRefresh = true
        )
    }

    private fun executeRelated(
        key: ContentKey,
        details: VideoDetails?,
        forceRefresh: Boolean,
        isRefresh: Boolean
    ) {
        val (admittedGeneration, admittedRelGeneration, admittedExcludedKeys, previousValue, jobToStart) = synchronized(sessionGuard) {
            if (cleared || currentKey != key) return
            relatedJob?.cancel()
            val relGen = ++relatedGeneration
            val prev = _relatedState.value.value
            val excluded = if (isRefresh) prev?.map { it.key }?.toSet().orEmpty() else emptySet()
            if (isRefresh) {
                _relatedState.value = RefreshableAsyncState.refreshing(prev)
            } else {
                _relatedState.value = RefreshableAsyncState.initial()
            }
            val currentGlobalGen = currentGeneration

            val newJob = viewModelScope.launch(ioDispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val req = RecommendationRequest(
                        forceRefresh = forceRefresh,
                        excludedKeys = excluded
                    )
                    val result = if (watchRecommendationSource != null && details != null) {
                        if (!isRefresh) {
                            // Do not compete with the initial media fetch/decoder startup.
                            withTimeoutOrNull(COMMENTS_READY_FALLBACK_MS) {
                                playerController.state.first { it.key == key && it.isReady }
                            }
                        }
                        watchRecommendationSource.recommendations(key, details, req)
                    } else {
                        when (val rel = videoService.related(key)) {
                            is AppResult.Success -> {
                                val filtered = rel.value.filter { it.key !in excluded }
                                AppResult.Success(filtered)
                            }
                            is AppResult.Failure -> rel
                        }
                    }

                    synchronized(sessionGuard) {
                        if (currentGeneration == currentGlobalGen &&
                            currentKey == key &&
                            relatedGeneration == relGen
                        ) {
                            when (result) {
                                is AppResult.Success -> {
                                    _relatedState.value = RefreshableAsyncState.content(result.value)
                                    _uiState.value.details?.let { details ->
                                        watchStateCache?.updateRelated(key, result.value)
                                    }
                                }
                                is AppResult.Failure -> {
                                    _relatedState.value = if (isRefresh) {
                                        RefreshableAsyncState.error(result.error, prev)
                                    } else {
                                        RefreshableAsyncState.error(result.error, null)
                                    }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    synchronized(sessionGuard) {
                        if (currentGeneration == currentGlobalGen &&
                            currentKey == key &&
                            relatedGeneration == relGen
                        ) {
                            _relatedState.value = if (isRefresh) {
                                RefreshableAsyncState.error(AppError.Unknown, prev)
                            } else {
                                RefreshableAsyncState.error(AppError.Unknown, null)
                            }
                        }
                    }
                }
            }

            relatedJob = newJob
            Tuple5(currentGlobalGen, relGen, excluded, prev, newJob)
        }

        jobToStart.start()
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private fun scheduleInitialComments(key: ContentKey, generation: Long) {
        val jobToStart = synchronized(sessionGuard) {
            if (!isCurrentRequest(key, generation) || !_uiState.value.commentsExpanded ||
                firstCommentsPage != null || _commentsState.value is AsyncState.Content || commentsInFlight
            ) return
            commentsGateJob?.cancel()
            viewModelScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                playerController.state.first { it.key == key }
                withTimeoutOrNull(COMMENTS_READY_FALLBACK_MS) {
                    playerController.state.first { state -> state.key == key && state.isReady }
                }
                loadComments(key, generation, null, append = false)
            }.also { commentsGateJob = it }
        }
        jobToStart.start()
    }

    fun setCommentsExpanded(expanded: Boolean) {
        val request = synchronized(sessionGuard) {
            if (cleared) return
            _uiState.update { it.copy(commentsExpanded = expanded) }
            if (!expanded) {
                cancelCommentsRequests()
                _commentsPagination.value = CommentsPaginationState()
                _commentsState.value = firstCommentsPage?.let { AsyncState.Content(it) } ?: AsyncState.Empty
            }
            currentKey?.let { it to currentGeneration }
        }
        if (expanded) request?.let { (key, generation) -> scheduleInitialComments(key, generation) }
    }

    private fun cancelCommentsRequests() {
        commentsRequestGeneration++
        commentsGateJob?.cancel()
        commentsGateJob = null
        commentsJob?.cancel()
        commentsJob = null
        commentsInFlight = false
    }

    fun retryComments() = currentKey?.let { key ->
        if (_uiState.value.commentsExpanded) loadComments(key, currentGeneration, null, append = false)
    }

    fun restartComments() {
        val key = currentKey ?: return
        synchronized(sessionGuard) {
            cancelCommentsRequests()
            _commentsPagination.value = CommentsPaginationState()
            _commentsState.value = firstCommentsPage?.let { AsyncState.Content(it) } ?: AsyncState.Empty
        }
        if (firstCommentsPage == null && _uiState.value.commentsExpanded) {
            loadComments(key, currentGeneration, null, append = false)
        }
    }

    fun loadMoreComments() {
        if (!_uiState.value.commentsExpanded) return
        val key = currentKey ?: return
        val page = (_commentsState.value as? AsyncState.Content)?.value ?: return
        val token = page.nextPageToken ?: return
        loadComments(key, currentGeneration, token, append = true)
    }

    private fun loadComments(
        key: ContentKey,
        generation: Long,
        token: PageToken?,
        append: Boolean
    ) {
        val jobToStart = synchronized(sessionGuard) {
            if (!isCurrentRequest(key, generation) || commentsInFlight || !_uiState.value.commentsExpanded) return
            if (!videoService.supportsComments) {
                _commentsState.value = AsyncState.Empty
                return
            }
            commentsInFlight = true
            if (!append) _commentsState.value = AsyncState.Loading
            _commentsPagination.update { it.copy(isLoading = append, error = null) }
            val requestGeneration = ++commentsRequestGeneration
            viewModelScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    val result = videoService.comments(key, token)
                    coroutineContext.ensureActive()
                    synchronized(sessionGuard) {
                        if (!isCurrentRequest(key, generation) || requestGeneration != commentsRequestGeneration) return@launch
                        when (result) {
                            is AppResult.Success -> {
                                val prior = if (append) (_commentsState.value as? AsyncState.Content)?.value?.comments.orEmpty() else emptyList()
                                val commentsById = LinkedHashMap<String, com.hpre.app.model.Comment>()
                                prior.forEach { commentsById[it.commentId] = it }
                                result.value.comments.forEach { commentsById.putIfAbsent(it.commentId, it) }
                                val dropped = commentsById.size > MAX_RETAINED_COMMENTS
                                val page = result.value.copy(
                                    comments = commentsById.values.toList().takeLast(MAX_RETAINED_COMMENTS),
                                    // A repeated continuation must not turn a visible sentinel into a request loop.
                                    nextPageToken = result.value.nextPageToken.takeUnless { append && it == token }
                                )
                                _commentsState.value = if (page.comments.isEmpty()) AsyncState.Empty else AsyncState.Content(page)
                                _commentsPagination.update {
                                    CommentsPaginationState(earlierCommentsDropped = (append && it.earlierCommentsDropped) || dropped)
                                }
                                if (!append) {
                                    firstCommentsPage = page.takeIf { !dropped && it.comments.size <= MAX_CACHED_COMMENTS }
                                    watchStateCache?.updateComments(key, firstCommentsPage)
                                }
                            }
                            is AppResult.Failure -> publishCommentsError(result.error, append)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    synchronized(sessionGuard) {
                        if (isCurrentRequest(key, generation) && requestGeneration == commentsRequestGeneration) {
                            publishCommentsError(AppError.Unknown, append)
                        }
                    }
                } finally {
                    synchronized(sessionGuard) {
                        if (isCurrentRequest(key, generation) && requestGeneration == commentsRequestGeneration) {
                            commentsInFlight = false
                            _commentsPagination.update { it.copy(isLoading = false) }
                        }
                    }
                }
            }.also { commentsJob = it }
        }
        jobToStart.start()
    }

    private fun publishCommentsError(error: AppError, append: Boolean) {
        if (append) {
            _commentsPagination.update { it.copy(isLoading = false, error = error) }
        } else {
            _commentsState.value = AsyncState.Error(error)
            _commentsPagination.value = CommentsPaginationState()
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
        val jobToStart = synchronized(sessionGuard) {
            val key = currentKey ?: return
            if (cleared) return
            val requestPending = loadJob?.let { !it.isCompleted && !it.isCancelled } == true
            val currentPlayback = playbackState.value
            if (requestPending && (retryingPlayback ||
                    (_uiState.value.error == null && currentPlayback.error == null))
            ) return

            val snapshot = currentPlayback.retrySnapshot
            if (currentPlayback.error == null || _uiState.value.details == null ||
                (snapshot != null && snapshot.key != key)
            ) {
                load(key, forceRefresh = true)
                return
            }

            val playWhenReady = snapshot?.userRequestedPlay
                ?: (currentPlayback.isPlaying || currentPlayback.playWhenReady)
            val quality = snapshot?.selectedQuality ?: currentPlayback.selectedQuality
            val generation = ++currentGeneration
            relatedGeneration++
            cancelLoadRequests()
            retryingPlayback = true
            val metricsSession = videoOpenMetrics.start(key)
            _uiState.update { it.copy(isLoading = true, isPlayerLoading = true, error = null) }
            _commentsPagination.update { it.copy(isLoading = false) }
            if (_relatedState.value.isRefreshing) {
                _relatedState.value.value?.let { _relatedState.value = RefreshableAsyncState.content(it) }
            }

            viewModelScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    val position = snapshot?.positionMs ?: playerController.readProgress().positionMs
                    val result = videoService.refreshStreamInfo(key)
                    coroutineContext.ensureActive()
                    synchronized(sessionGuard) {
                        if (!isCurrentRequest(key, generation)) return@launch
                        when (result) {
                            is AppResult.Success -> {
                                videoOpenMetrics.mark(metricsSession, VideoOpenEvent.STREAM_INFO_READY)
                                videoOpenMetrics.mark(metricsSession, VideoOpenEvent.PLAYER_PREPARE)
                                playerController.prepare(
                                    key = key,
                                    streamInfo = result.value,
                                    startPositionMs = position,
                                    playWhenReady = playWhenReady,
                                    initialQuality = quality
                                )
                                _uiState.update { it.copy(isLoading = false) }
                                observePlayerReadiness(key, generation)
                                if (_relatedState.value.value == null) {
                                    executeRelated(key, _uiState.value.details, forceRefresh = false, isRefresh = false)
                                }
                                scheduleInitialComments(key, generation)
                            }
                            is AppResult.Failure -> publishLoadFailure(key, generation, result.error)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    publishLoadFailure(key, generation, AppError.Unknown)
                } finally {
                    synchronized(sessionGuard) {
                        if (isCurrentRequest(key, generation)) retryingPlayback = false
                    }
                }
            }.also { loadJob = it }
        }
        jobToStart.start()
    }

    fun setFullscreen(isFullscreen: Boolean) {
        savedStateHandle[KEY_IS_FULLSCREEN] = isFullscreen
        _uiState.update { it.copy(isFullscreen = isFullscreen) }
    }

    fun setFullScreenResizeMode(mode: FullScreenResizeMode) {
        savedStateHandle[KEY_FULLSCREEN_RESIZE_MODE] = mode.name
        _uiState.update { it.copy(fullScreenResizeMode = mode) }
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
        synchronized(sessionGuard) {
            cleared = true
            currentGeneration++
            relatedGeneration++
            cancelLoadRequests()
        }
        super.onCleared()
        // Note: ViewModel does not tear down service player on normal unbind
    }
}
