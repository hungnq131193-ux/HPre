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
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val historyRepository: HistoryRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    val history: StateFlow<List<WatchHistoryItem>> = historyRepository.observeHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val subscriptions: StateFlow<List<LocalSubscription>> = subscriptionRepository.observeSubscriptions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val playlists: StateFlow<List<LocalPlaylist>> = playlistRepository.observePlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _playlistDetail = MutableStateFlow<LocalPlaylistWithEntries?>(null)
    val playlistDetail: StateFlow<LocalPlaylistWithEntries?> = _playlistDetail.asStateFlow()

    private var activeDetailJob: Job? = null

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
