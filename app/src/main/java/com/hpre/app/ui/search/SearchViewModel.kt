package com.hpre.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.LocalSearchHistoryItem
import com.hpre.app.repository.SearchHistoryRepository
import com.hpre.app.repository.TtlLruCache
import com.hpre.app.repository.VideoService
import com.hpre.app.settings.PlaybackPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Content(
        val items: List<SearchResultItem>,
        val nextPageToken: PageToken? = null,
        val isLoadingNextPage: Boolean = false,
        /**
         * A search for a newer query or filter is running while these results stay on screen.
         *
         * Typing past the debounce window used to clear the list and show a spinner, so results
         * visibly vanished and came back on every keystroke burst. Keeping the previous items and
         * flagging them as superseded lets the screen show a thin inline indicator instead.
         */
        val isSearching: Boolean = false,
        val earlierResultsDropped: Boolean = false,
        /**
         * A page-append request failed while earlier results stay on screen. Distinct from the
         * screen-level [Error] state: the loaded pages still answer the committed query, so they
         * remain visible and this error renders as a retryable list footer instead.
         */
        val paginationError: AppError? = null
    ) : SearchUiState
    data object Empty : SearchUiState
    data class Error(val error: AppError) : SearchUiState
}

data class SearchHistoryUiState(
    val items: List<LocalSearchHistoryItem> = emptyList(),
    val isMutationInFlight: Boolean = false,
    val error: AppError? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: CatalogRepository,
    private val videoService: VideoService,
    private val searchHistoryRepository: SearchHistoryRepository? = null,
    private val playbackPreferences: PlaybackPreferences? = null
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _historyMutation = MutableStateFlow(SearchHistoryUiState())
    private val observedHistory = searchHistoryRepository?.observeRecentQueries(20)
        ?: flowOf(emptyList())
    val historyState: StateFlow<SearchHistoryUiState> = combine(
        observedHistory,
        _historyMutation
    ) { items, mutation -> mutation.copy(items = items) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchHistoryUiState())
    val recentQueries: StateFlow<List<String>> = historyState
        .map { state -> state.items.map(LocalSearchHistoryItem::query) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Search suggestions flow with debouncing
    val suggestions: StateFlow<List<String>> = _query
        .debounce(200)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            val normalized = repository.normalizeQuery(q)
            if (normalized.isBlank() || !videoService.supportsSearchSuggestions) {
                flowOf(emptyList())
            } else {
                flow {
                    when (val res = videoService.suggestions(normalized)) {
                        is AppResult.Success -> emit(res.value)
                        is AppResult.Failure -> emit(emptyList())
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private var activeSearchJob: Job? = null
    private var activePaginationJob: Job? = null
    private val paginationMutex = Mutex()

    private var currentGeneration: Long = 0L
    private var activeRequestKey: String = ""
    private val _committedSearch = MutableStateFlow<Pair<String, SearchFilter>?>(null)

    /**
     * Normalized query of the last committed (successful or cache-served) search. The UI uses it to
     * tell "results being viewed" apart from "text being typed" and to reset scroll position only
     * when the committed query changes — not while the user is still editing the field.
     */
    val committedQuery: StateFlow<String?> = _committedSearch
        .map { it?.first }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var lastSearchedQuery: String? = null
    private var lastSearchedFilter: SearchFilter? = null
    private var interruptedSearch: Pair<String, SearchFilter>? = null

    /**
     * First page of recent searches, keyed by filter and normalized query.
     *
     * The dominant repeat is back-navigation: open a result from Watch, come back, and the same
     * query was re-issued from scratch. Caching only the first page keeps this small while covering
     * that path; pagination state beyond page one is cheap to rebuild by scrolling.
     */
    private val resultCache = TtlLruCache<String, CachedSearchPage>(
        ttlMs = SEARCH_CACHE_TTL_MS,
        maxEntries = SEARCH_CACHE_MAX_ENTRIES
    )

    private data class CachedSearchPage(
        val items: List<SearchResultItem>,
        val nextPageToken: PageToken?,
        val earlierResultsDropped: Boolean = false
    )

    init {
        // Observe debounced queries for real-time search typing (400ms debounce)
        viewModelScope.launch {
            _query
                .debounce(400)
                .distinctUntilChanged()
                .collect { debouncedQuery ->
                    val normalized = repository.normalizeQuery(debouncedQuery)
                    if (normalized.isNotBlank()) {
                        if (normalized != lastSearchedQuery || _filter.value != lastSearchedFilter) {
                            performSearch(normalized, _filter.value, isExplicit = false)
                        }
                    } else {
                        cancelAllSearches()
                        lastSearchedQuery = null
                        lastSearchedFilter = null
                        _uiState.value = SearchUiState.Idle
                    }
                }
        }
    }

    private fun cancelAllSearches() {
        activeSearchJob?.cancel()
        activePaginationJob?.cancel()
        currentGeneration++
    }

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        val normalized = repository.normalizeQuery(newQuery)
        if (normalized.isBlank()) {
            cancelAllSearches()
            lastSearchedQuery = null
            lastSearchedFilter = null
            _uiState.value = SearchUiState.Idle
        }
    }

    fun onQuerySubmitted(submittedQuery: String) {
        val normalized = repository.normalizeQuery(submittedQuery)
        if (normalized.isBlank()) return

        _query.value = normalized
        recordRecentQuery(normalized)
        // The typing debounce may already be fetching this exact search; restarting it would only
        // discard the in-flight request.
        if (normalized == lastSearchedQuery && _filter.value == lastSearchedFilter &&
            activeSearchJob?.isActive == true
        ) return
        performSearch(normalized, _filter.value, isExplicit = true)
    }

    /** Re-runs a search that [onVideoSelected] interrupted, so results match the query box again. */
    fun resumeInterruptedSearch() {
        val (query, filter) = interruptedSearch ?: return
        performSearch(query, filter, isExplicit = false)
    }

    fun onFilterChanged(newFilter: SearchFilter) {
        if (_filter.value == newFilter) return
        _filter.value = newFilter
        val normalized = repository.normalizeQuery(_query.value)
        if (normalized.isNotBlank()) {
            performSearch(normalized, newFilter, isExplicit = true)
        }
    }

    private fun performSearch(query: String, filter: SearchFilter, isExplicit: Boolean) {
        cancelAllSearches()
        interruptedSearch = null
        val generation = currentGeneration
        val requestKey = "${filter.name}:$query"
        activeRequestKey = requestKey

        lastSearchedQuery = query
        lastSearchedFilter = filter

        // An explicit action (submit, filter change, retry) means the user wants fresh results, so it
        // skips the cache. Debounced typing is happy to reuse a recent identical search.
        val cached = if (isExplicit) null else resultCache.get(requestKey)
        if (cached != null) {
            _committedSearch.value = query to filter
            _uiState.value = SearchUiState.Content(
                items = cached.items,
                nextPageToken = cached.nextPageToken,
                isLoadingNextPage = false,
                isSearching = false,
                earlierResultsDropped = cached.earlierResultsDropped
            )
            return
        }

        // Preserve whatever results are showing rather than clearing to a spinner. Only an empty
        // screen falls back to Loading.
        val visible = _uiState.value as? SearchUiState.Content
        _uiState.value = if (visible != null && visible.items.isNotEmpty()) {
            visible.copy(isSearching = true, isLoadingNextPage = false, paginationError = null)
        } else {
            SearchUiState.Loading
        }

        activeSearchJob = viewModelScope.launch {
            val result = try {
                repository.search(query, filter, pageToken = null, forceRefresh = isExplicit)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }

            // Generation and request key check guard
            if (generation == currentGeneration && activeRequestKey == requestKey) {
                when (result) {
                    is AppResult.Success -> {
                        _committedSearch.value = query to filter
                        val page = result.value
                        if (page.items.isEmpty()) {
                            resultCache.remove(requestKey)
                            _uiState.value = SearchUiState.Empty
                        } else {
                            val deduplicatedItems = deduplicateItems(page.items)
                            resultCache.put(
                                requestKey,
                                CachedSearchPage(deduplicatedItems.takeLast(MAX_RETAINED_RESULTS), page.nextPageToken,
                                    deduplicatedItems.size > MAX_RETAINED_RESULTS)
                            )
                            _uiState.value = SearchUiState.Content(
                                items = deduplicatedItems.takeLast(MAX_RETAINED_RESULTS),
                                nextPageToken = page.nextPageToken,
                                isLoadingNextPage = false,
                                isSearching = false,
                                earlierResultsDropped = deduplicatedItems.size > MAX_RETAINED_RESULTS
                            )
                        }
                    }
                    is AppResult.Failure -> {
                        // Unlike Home, a failed search shows the error screen even when stale results
                        // are on screen: those results belong to a different query, so leaving them up
                        // would misrepresent them as results for what was just typed.
                        _uiState.value = SearchUiState.Error(result.error)
                    }
                }
            }
        }
    }

    fun retry() {
        val normalized = repository.normalizeQuery(_query.value)
        if (normalized.isNotBlank()) {
            performSearch(normalized, _filter.value, isExplicit = true)
        }
    }

    fun loadNextPage() {
        val currentState = _uiState.value
        if (currentState !is SearchUiState.Content) return
        val nextToken = currentState.nextPageToken ?: return
        if (currentState.isLoadingNextPage || currentState.isSearching) return
        val (currentQuery, currentFilter) = _committedSearch.value ?: return

        if (!paginationMutex.tryLock()) return

        val generation = currentGeneration
        val requestKey = activeRequestKey

        _uiState.value = currentState.copy(isLoadingNextPage = true, paginationError = null)

        activePaginationJob = viewModelScope.launch {
            try {
                val result = try {
                    repository.search(
                        query = currentQuery,
                        filter = currentFilter,
                        pageToken = nextToken,
                        forceRefresh = false
                    )
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    AppResult.Failure(AppError.Unknown)
                }

                // Verify request generation, key, query, filter, and page token
                if (generation == currentGeneration && activeRequestKey == requestKey) {
                    val latestState = _uiState.value
                    if (latestState is SearchUiState.Content && latestState.nextPageToken == nextToken) {
                        when (result) {
                            is AppResult.Success -> {
                                val newPage = result.value
                                val combinedItems = deduplicateItems(latestState.items + newPage.items)
                                _uiState.value = SearchUiState.Content(
                                    items = combinedItems.takeLast(MAX_RETAINED_RESULTS),
                                    nextPageToken = newPage.nextPageToken.takeUnless { it == nextToken },
                                    isLoadingNextPage = false,
                                    earlierResultsDropped = latestState.earlierResultsDropped || combinedItems.size > MAX_RETAINED_RESULTS
                                )
                            }
                            is AppResult.Failure -> {
                                // Keep loaded pages; the failed append becomes a retryable footer
                                // error instead of dropping to a full-screen error.
                                _uiState.value = latestState.copy(
                                    isLoadingNextPage = false,
                                    paginationError = result.error
                                )
                            }
                        }
                    }
                }
            } finally {
                paginationMutex.unlock()
            }
        }
    }

    fun onVideoSelected() {
        val query = lastSearchedQuery
        val filter = lastSearchedFilter
        if (activeSearchJob?.isActive == true && query != null && filter != null) {
            interruptedSearch = query to filter
        }
        cancelAllSearches()
        activeSearchJob = null
        activePaginationJob = null
        _uiState.update { state ->
            if (state is SearchUiState.Content) {
                state.copy(isSearching = false, isLoadingNextPage = false, paginationError = null)
            } else state
        }
    }

    private fun deduplicateItems(items: List<SearchResultItem>): List<SearchResultItem> {
        val seen = mutableSetOf<ContentKey>()
        val result = mutableListOf<SearchResultItem>()
        for (item in items) {
            val key = when (item) {
                is SearchResultItem.VideoItem -> item.summary.key
                is SearchResultItem.ChannelItem -> item.channel.key
                is SearchResultItem.PlaylistItem -> item.playlist.key
            }
            if (seen.add(key)) {
                result.add(item)
            }
        }
        return result
    }

    private fun recordRecentQuery(query: String) {
        val history = searchHistoryRepository ?: return
        val preferences = playbackPreferences ?: return
        viewModelScope.launch {
            if (!preferences.isHistoryEnabled.first()) return@launch
            try {
                when (val result = history.recordQuery(query)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> _historyMutation.update { it.copy(error = result.error) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _historyMutation.update { it.copy(error = AppError.Unknown) }
            }
        }
    }

    fun removeRecentQuery(query: String) {
        mutateHistory { it.deleteQuery(query) }
    }

    fun clearRecentQueries() {
        mutateHistory(SearchHistoryRepository::clearHistory)
    }

    fun consumeHistoryError() {
        _historyMutation.update { it.copy(error = null) }
    }

    private fun mutateHistory(operation: suspend (SearchHistoryRepository) -> AppResult<Unit>) {
        val history = searchHistoryRepository ?: return
        if (_historyMutation.value.isMutationInFlight) return
        _historyMutation.update { it.copy(isMutationInFlight = true, error = null) }
        viewModelScope.launch {
            var error: AppError? = null
            try {
                error = (operation(history) as? AppResult.Failure)?.error
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                error = AppError.Unknown
            } finally {
                _historyMutation.update {
                    it.copy(isMutationInFlight = false, error = error)
                }
            }
        }
    }

    companion object {
        internal const val MAX_RETAINED_RESULTS = 300
        /**
         * Short enough that results still reflect current content, long enough to cover a browsing
         * loop of opening several videos from one result list.
         */
        private const val SEARCH_CACHE_TTL_MS = 180_000L

        /** Covers a typical session's worth of distinct queries without holding much memory. */
        private const val SEARCH_CACHE_MAX_ENTRIES = 24

        fun provideFactory(
            repository: CatalogRepository,
            videoService: VideoService,
            searchHistoryRepository: SearchHistoryRepository,
            playbackPreferences: PlaybackPreferences
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SearchViewModel(
                    repository,
                    videoService,
                    searchHistoryRepository,
                    playbackPreferences
                ) as T
            }
        }
    }
}
