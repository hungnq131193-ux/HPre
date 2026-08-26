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
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Content(
        val items: List<SearchResultItem>,
        val nextPageToken: PageToken? = null,
        val isLoadingNextPage: Boolean = false
    ) : SearchUiState
    data object Empty : SearchUiState
    data class Error(val error: AppError) : SearchUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: CatalogRepository,
    private val videoService: VideoService
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // Local in-memory recent search queries
    private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
    val recentQueries: StateFlow<List<String>> = _recentQueries.asStateFlow()

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

    private var lastSearchedQuery: String? = null
    private var lastSearchedFilter: SearchFilter? = null

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
        performSearch(normalized, _filter.value, isExplicit = true)
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
        val generation = currentGeneration
        val requestKey = "${filter.name}:$query"
        activeRequestKey = requestKey

        lastSearchedQuery = query
        lastSearchedFilter = filter
        _uiState.value = SearchUiState.Loading

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
                        val page = result.value
                        if (page.items.isEmpty()) {
                            _uiState.value = SearchUiState.Empty
                        } else {
                            val deduplicatedItems = deduplicateItems(page.items)
                            _uiState.value = SearchUiState.Content(
                                items = deduplicatedItems,
                                nextPageToken = page.nextPageToken,
                                isLoadingNextPage = false
                            )
                        }
                    }
                    is AppResult.Failure -> {
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
        if (currentState.isLoadingNextPage) return

        if (!paginationMutex.tryLock()) return

        val generation = currentGeneration
        val requestKey = activeRequestKey
        val currentQuery = repository.normalizeQuery(_query.value)
        val currentFilter = _filter.value

        _uiState.value = currentState.copy(isLoadingNextPage = true)

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
                                    items = combinedItems,
                                    nextPageToken = newPage.nextPageToken,
                                    isLoadingNextPage = false
                                )
                            }
                            is AppResult.Failure -> {
                                // Keep current items, reset in-flight state
                                _uiState.value = latestState.copy(isLoadingNextPage = false)
                            }
                        }
                    }
                }
            } finally {
                paginationMutex.unlock()
            }
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

    private fun recordRecentQuery(q: String) {
        val current = _recentQueries.value.toMutableList()
        current.remove(q)
        current.add(0, q)
        if (current.size > 20) {
            current.removeAt(current.size - 1)
        }
        _recentQueries.value = current
    }

    fun removeRecentQuery(q: String) {
        _recentQueries.value = _recentQueries.value.filter { it != q }
    }

    fun clearRecentQueries() {
        _recentQueries.value = emptyList()
    }

    companion object {
        fun provideFactory(
            repository: CatalogRepository,
            videoService: VideoService
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SearchViewModel(repository, videoService) as T
            }
        }
    }
}
