package com.hpre.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.HomeRecommendationSource
import com.hpre.app.repository.RecommendationRequest
import com.hpre.app.repository.TtlLruCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class HomeContent(
    val videos: List<VideoSummary>,
    val isRefreshing: Boolean = false,
    val refreshError: AppError? = null,
    /**
     * A load for a different chip is in flight while these videos stay on screen.
     *
     * Distinct from [isRefreshing], which drives the pull-to-refresh indicator. This one is for the
     * quieter case of switching chips: the previous list stays visible so content never disappears,
     * and the screen shows a subtle inline indicator instead of replacing everything with a spinner.
     */
    val isLoadingSelection: Boolean = false
)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val content: HomeContent) : HomeUiState {
        // Backwards compatibility convenience
        val videos: List<VideoSummary> get() = content.videos
    }
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
    private val topicFeedSource: TopicFeedSource,
    private val feedStore: HomeFeedStore? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _chipsState = MutableStateFlow(HomeChipsState(DEFAULT_CHIPS))
    val chipsState: StateFlow<HomeChipsState> = _chipsState.asStateFlow()

    private var activeLoadJob: Job? = null
    private var loadGeneration: Long = 0L

    /**
     * Last successful feed per chip, so returning to a chip renders instantly.
     *
     * Keyed by chip query (null for "Tất cả"). Sized to hold every default chip, because the common
     * pattern is bouncing between two or three of them; TTL is short enough that a chip left alone
     * for minutes revalidates instead of showing yesterday's feed.
     */
    private val chipCache = TtlLruCache<String, List<VideoSummary>>(
        ttlMs = CHIP_CACHE_TTL_MS,
        maxEntries = CHIP_CACHE_MAX_ENTRIES
    )

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean = false) {
        activeLoadJob?.cancel()
        val generation = ++loadGeneration
        val selectedChip = _chipsState.value.chips[_chipsState.value.selectedIndex]
        val cacheKey = selectedChip.query ?: ALL_CHIP_CACHE_KEY

        // Cached feed for this chip goes on screen before the request starts. A forced refresh skips
        // the cache because the user explicitly asked for new content.
        val memoryCached = if (forceRefresh) null else chipCache.getStale(cacheKey)
        val diskCached = if (memoryCached == null && !forceRefresh) feedStore?.load(cacheKey) else null
        if (diskCached != null) chipCache.put(cacheKey, diskCached)
        val cached = memoryCached ?: diskCached?.let { TtlLruCache.StaleEntry(it, isStale = true) }
        if (cached != null && cached.value.isNotEmpty()) {
            _uiState.value = HomeUiState.Content(
                HomeContent(videos = cached.value, isLoadingSelection = cached.isStale)
            )
            // Fresh cache is good enough on its own; no request, no spinner.
            if (!cached.isStale) return
        } else {
            // Keep whatever is on screen and mark it as being replaced, so switching chips never
            // blanks the list. Only a genuinely empty screen falls back to full-screen loading.
            val current = (_uiState.value as? HomeUiState.Content)?.content
            _uiState.value = if (current != null && current.videos.isNotEmpty()) {
                HomeUiState.Content(
                    current.copy(isLoadingSelection = true, isRefreshing = false, refreshError = null)
                )
            } else {
                HomeUiState.Loading
            }
        }

        activeLoadJob = viewModelScope.launch {
            val result = try {
                withTimeoutOrNull(INITIAL_LOAD_TIMEOUT_MS) {
                    selectedChip.query?.let { query ->
                        topicFeedSource.videos(query, RecommendationRequest(forceRefresh = forceRefresh))
                    } ?: repository.home(RecommendationRequest(forceRefresh = forceRefresh))
                } ?: AppResult.Failure(AppError.NetworkError)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }
            if (generation == loadGeneration) {
                when (result) {
                    is AppResult.Success -> {
                        if (result.value.isEmpty()) {
                            chipCache.remove(cacheKey)
                            feedStore?.remove(cacheKey)
                            _uiState.value = HomeUiState.Empty
                        } else {
                            chipCache.put(cacheKey, result.value)
                            feedStore?.save(cacheKey, result.value)
                            _uiState.value = HomeUiState.Content(HomeContent(result.value))
                        }
                    }
                    is AppResult.Failure -> {
                        // Stale content beats an error screen: keep the list and surface the failure
                        // inline. Only report a hard error when there is nothing to show.
                        val visible = (_uiState.value as? HomeUiState.Content)?.content
                        _uiState.value = if (visible != null && visible.videos.isNotEmpty()) {
                            HomeUiState.Content(
                                visible.copy(
                                    isLoadingSelection = false,
                                    isRefreshing = false,
                                    refreshError = result.error
                                )
                            )
                        } else {
                            HomeUiState.Error(result.error)
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        val currentContent = (_uiState.value as? HomeUiState.Content)?.content
        if (currentContent == null) {
            load(forceRefresh = true)
            return
        }

        activeLoadJob?.cancel()
        val generation = ++loadGeneration
        val excludedKeysSnapshot = currentContent.videos.map { it.key }.toSet()

        _uiState.value = HomeUiState.Content(
            currentContent.copy(isRefreshing = true, refreshError = null)
        )

        activeLoadJob = viewModelScope.launch {
            val selectedChip = _chipsState.value.chips[_chipsState.value.selectedIndex]
            val cacheKey = selectedChip.query ?: ALL_CHIP_CACHE_KEY
            val result = try {
                withTimeoutOrNull(INITIAL_LOAD_TIMEOUT_MS) {
                    selectedChip.query?.let { query ->
                        topicFeedSource.videos(
                            query,
                            RecommendationRequest(
                                forceRefresh = true,
                                excludedKeys = excludedKeysSnapshot
                            )
                        )
                    } ?: repository.home(
                        RecommendationRequest(
                            forceRefresh = true,
                            excludedKeys = excludedKeysSnapshot
                        )
                    )
                } ?: AppResult.Failure(AppError.NetworkError)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }

            if (generation == loadGeneration) {
                when (result) {
                    is AppResult.Success -> {
                        if (result.value.isEmpty()) {
                            chipCache.remove(cacheKey)
                            feedStore?.remove(cacheKey)
                            _uiState.value = HomeUiState.Empty
                        } else {
                            // Overwrite the cache so leaving and returning to this chip shows what
                            // the user just pulled, not the pre-refresh list.
                            chipCache.put(cacheKey, result.value)
                            feedStore?.save(cacheKey, result.value)
                            _uiState.value = HomeUiState.Content(
                                HomeContent(videos = result.value, isRefreshing = false, refreshError = null)
                            )
                        }
                    }
                    is AppResult.Failure -> {
                        _uiState.value = HomeUiState.Content(
                            currentContent.copy(isRefreshing = false, refreshError = result.error)
                        )
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
        internal const val INITIAL_LOAD_TIMEOUT_MS = 2_000L

        /** Cache key for the "Tất cả" chip, which has no query of its own. */
        private const val ALL_CHIP_CACHE_KEY = "__all__"

        /**
         * Long enough to make chip switching feel instant within a browsing session, short enough
         * that a feed reopened later still revalidates.
         */
        private const val CHIP_CACHE_TTL_MS = 900_000L

        /** Holds every default chip so a full sweep through them never evicts an earlier one. */
        private const val CHIP_CACHE_MAX_ENTRIES = 8

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
            topicFeedSource: TopicFeedSource,
            feedStore: HomeFeedStore? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository, topicFeedSource, feedStore) as T
                }
            }
    }
}
