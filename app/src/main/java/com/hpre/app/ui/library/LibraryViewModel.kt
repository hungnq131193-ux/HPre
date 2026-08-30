package com.hpre.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.repository.LocalPlaylistWithEntries
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.repository.SubscriptionRepository
import com.hpre.app.repository.WatchHistoryItem
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

    private val _playlistDetail = MutableStateFlow<LocalPlaylistWithEntries?>(null)
    val playlistDetail: StateFlow<LocalPlaylistWithEntries?> = _playlistDetail.asStateFlow()

    private var activeDetailJob: Job? = null

    fun previousHistoryPage() {
        requestedHistoryPage.value = (historyPage.value - 1).coerceAtLeast(0)
    }

    fun nextHistoryPage() {
        val lastPage = (historyCount.value - 1).coerceAtLeast(0) / HISTORY_PAGE_SIZE
        requestedHistoryPage.value = (historyPage.value + 1).coerceAtMost(lastPage)
    }

    fun loadPlaylistDetail(playlistId: Long) {
        activeDetailJob?.cancel()
        activeDetailJob = viewModelScope.launch {
            playlistRepository.observePlaylistWithEntries(playlistId).collect { detail ->
                _playlistDetail.value = detail
            }
        }
    }

    fun deleteHistoryItem(key: ContentKey) {
        viewModelScope.launch {
            historyRepository.deleteHistoryItem(key)
        }
    }

    fun clearHistory() {
        requestedHistoryPage.value = 0
        viewModelScope.launch {
            historyRepository.clearHistory()
        }
    }

    fun unsubscribe(channelKey: ContentKey) {
        viewModelScope.launch {
            subscriptionRepository.unsubscribe(channelKey)
        }
    }

    fun createPlaylist(title: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(title)
            if (result is com.hpre.app.core.error.AppResult.Success) {
                onCreated?.invoke(result.value)
            }
        }
    }

    fun renamePlaylist(playlistId: Long, newTitle: String) {
        viewModelScope.launch {
            playlistRepository.renamePlaylist(playlistId, newTitle)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
        }
    }

    fun addVideoToPlaylist(playlistId: Long, video: VideoSummary) {
        viewModelScope.launch {
            playlistRepository.addEntry(playlistId, video)
        }
    }

    fun removeVideoFromPlaylist(playlistId: Long, videoKey: ContentKey) {
        viewModelScope.launch {
            playlistRepository.removeEntry(playlistId, videoKey)
        }
    }

    fun reorderPlaylistEntries(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            playlistRepository.reorderEntries(playlistId, fromIndex, toIndex)
        }
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
