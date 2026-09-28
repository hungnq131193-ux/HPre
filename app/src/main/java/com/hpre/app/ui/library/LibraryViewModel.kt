package com.hpre.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.repository.LocalPlaylistWithEntries
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.repository.SubscriptionRepository
import com.hpre.app.repository.WatchHistoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Result of the most recent library write operation. Only one mutation runs at a
 * time; the UI disables write actions while [inFlight] is true. Not persisted —
 * a process death simply loses the in-flight marker, never replays a mutation.
 */
data class LibraryMutationState(
    val operation: String? = null,
    val inFlight: Boolean = false,
    val error: AppError? = null,
    val completed: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val historyRepository: HistoryRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val requestedHistoryPage = MutableStateFlow(0)
    val historyCount: StateFlow<Int> = historyRepository.observeHistoryCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val historyPage: StateFlow<Int> = combine(requestedHistoryPage, historyCount) { requested, count ->
        requested.coerceIn(0, ((count - 1).coerceAtLeast(0) / HISTORY_PAGE_SIZE))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val recentHistory: StateFlow<List<WatchHistoryItem>> = historyRepository.observeRecentHistory(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<WatchHistoryItem>> = historyPage
        .flatMapLatest { page -> historyRepository.observeHistoryPage(HISTORY_PAGE_SIZE, page * HISTORY_PAGE_SIZE) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val subscriptions: StateFlow<List<LocalSubscription>> = subscriptionRepository.observeSubscriptions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val playlists: StateFlow<List<LocalPlaylist>> = playlistRepository.observePlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _mutationState = MutableStateFlow(LibraryMutationState())
    val mutationState: StateFlow<LibraryMutationState> = _mutationState.asStateFlow()

    private val selectedPlaylistId = MutableStateFlow<Long?>(null)
    val playlistDetail: StateFlow<LocalPlaylistWithEntries?> = selectedPlaylistId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(null)
            else playlistRepository.observePlaylistWithEntries(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun previousHistoryPage() {
        requestedHistoryPage.value = (historyPage.value - 1).coerceAtLeast(0)
    }

    fun nextHistoryPage() {
        val lastPage = (historyCount.value - 1).coerceAtLeast(0) / HISTORY_PAGE_SIZE
        requestedHistoryPage.value = (historyPage.value + 1).coerceAtMost(lastPage)
    }

    fun loadPlaylistDetail(playlistId: Long) {
        selectedPlaylistId.value = playlistId
    }

    /** Clear a rendered mutation outcome so it is not shown again on recomposition. */
    fun consumeMutationResult() {
        if (!_mutationState.value.inFlight) {
            _mutationState.value = LibraryMutationState()
        }
    }

    private val mutationMutex = kotlinx.coroutines.sync.Mutex()
    private val inFlightMutationKeys = mutableSetOf<String>()

    /**
     * Runs one library mutation at a time. Identical in-flight calls (same [key],
     * e.g. a double-tap) are dropped; distinct mutations queue behind the mutex so
     * back-to-back writes (two adds, then a reorder) still complete in order.
     */
    private fun runMutation(
        operation: String,
        key: String = operation,
        onSuccess: suspend (Any?) -> Unit = {},
        block: suspend () -> AppResult<*>
    ) {
        if (!inFlightMutationKeys.add(key)) return
        _mutationState.value = LibraryMutationState(operation = operation, inFlight = true)
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    when (val result = block()) {
                        is AppResult.Success -> {
                            _mutationState.value = LibraryMutationState(operation, completed = true)
                            onSuccess(result.value)
                        }
                        is AppResult.Failure ->
                            _mutationState.value = LibraryMutationState(operation, error = result.error)
                    }
                }
            } catch (e: CancellationException) {
                _mutationState.value = LibraryMutationState()
                throw e
            } finally {
                inFlightMutationKeys.remove(key)
            }
        }
    }

    fun deleteHistoryItem(key: ContentKey) = runMutation("deleteHistoryItem", "deleteHistoryItem:$key") {
        historyRepository.deleteHistoryItem(key)
    }

    fun clearHistory() = runMutation("clearHistory") {
        requestedHistoryPage.value = 0
        historyRepository.clearHistory()
    }

    fun unsubscribe(channelKey: ContentKey) = runMutation("unsubscribe", "unsubscribe:$channelKey") {
        subscriptionRepository.unsubscribe(channelKey)
    }

    fun createPlaylist(title: String, onCreated: ((Long) -> Unit)? = null) {
        runMutation(
            operation = "createPlaylist",
            key = "createPlaylist:$title",
            onSuccess = { id -> (id as? Long)?.let { onCreated?.invoke(it) } }
        ) { playlistRepository.createPlaylist(title) }
    }

    fun renamePlaylist(playlistId: Long, newTitle: String) = runMutation("renamePlaylist", "renamePlaylist:$playlistId") {
        playlistRepository.renamePlaylist(playlistId, newTitle)
    }

    fun deletePlaylist(playlistId: Long) = runMutation("deletePlaylist", "deletePlaylist:$playlistId") {
        playlistRepository.deletePlaylist(playlistId)
    }

    fun addVideoToPlaylist(playlistId: Long, video: VideoSummary) = runMutation("addEntry", "addEntry:$playlistId:${video.key}") {
        playlistRepository.addEntry(playlistId, video)
    }

    fun removeVideoFromPlaylist(playlistId: Long, videoKey: ContentKey) = runMutation("removeEntry", "removeEntry:$playlistId:$videoKey") {
        playlistRepository.removeEntry(playlistId, videoKey)
    }

    fun reorderPlaylistEntries(playlistId: Long, fromIndex: Int, toIndex: Int) = runMutation("reorder", "reorder:$playlistId:$fromIndex:$toIndex") {
        playlistRepository.reorderEntries(playlistId, fromIndex, toIndex)
    }

    companion object {
        const val HISTORY_PAGE_SIZE = 50
        fun provideFactory(
            historyRepository: HistoryRepository,
            subscriptionRepository: SubscriptionRepository,
            playlistRepository: PlaylistRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LibraryViewModel(
                    historyRepository = historyRepository,
                    subscriptionRepository = subscriptionRepository,
                    playlistRepository = playlistRepository
                ) as T
            }
        }
    }
}
