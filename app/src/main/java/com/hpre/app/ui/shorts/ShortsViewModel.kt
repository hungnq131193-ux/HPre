package com.hpre.app.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Comment
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.MediaSourceFactory
import com.hpre.app.player.PlayerController
import com.hpre.app.player.cache.MediaCacheManager
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.InterestTopic
import com.hpre.app.repository.LocalInterestSignals
import com.hpre.app.repository.LocalSearchHistoryItem
import com.hpre.app.repository.RecommendationContext
import com.hpre.app.repository.RecommendationRanker
import com.hpre.app.repository.SearchHistoryRepository
import com.hpre.app.repository.TopicExtractor
import com.hpre.app.repository.VideoService
import com.hpre.app.repository.WatchHistoryItem
import com.hpre.app.settings.PlaybackPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Holds an optional initial Short when the user taps a Short card from Home/Search/Subscriptions
 * so that navigating to [com.hpre.app.navigation.Screen.Shorts] starts on that exact Short.
 */
object ShortsSeedStore {
    @Volatile
    private var pendingSeed: VideoSummary? = null

    fun seed(video: VideoSummary) {
        pendingSeed = video.copy(isShort = true)
    }

    fun consume(): VideoSummary? {
        val current = pendingSeed
        pendingSeed = null
        return current
    }
}

sealed interface ShortsUiState {
    data object Loading : ShortsUiState
    data object Empty : ShortsUiState
    data class Error(val error: AppError) : ShortsUiState
    data class Content(
        val items: List<VideoSummary>,
        val currentIndex: Int = 0,
        val isStreamLoading: Boolean = false,
        val streamError: AppError? = null,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false
    ) : ShortsUiState
}

data class ShortsCommentsSheetState(
    val videoKey: ContentKey? = null,
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val comments: List<Comment> = emptyList(),
    val error: AppError? = null,
    val nextPageToken: PageToken? = null,
    val isAppending: Boolean = false
)

class ShortsViewModel(
    private val catalogRepository: CatalogRepository,
    private val videoService: VideoService,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val historyRepository: HistoryRepository,
    private val playbackPreferences: PlaybackPreferences,
    val playerController: PlayerController,
    private val mediaSourceFactory: MediaSourceFactory? = null,
    private val mediaCacheManager: MediaCacheManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<ShortsUiState>(ShortsUiState.Loading)
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    private val _commentsSheet = MutableStateFlow(ShortsCommentsSheetState())
    val commentsSheet: StateFlow<ShortsCommentsSheetState> = _commentsSheet.asStateFlow()

    private val seenKeys = linkedSetOf<ContentKey>()
    private val seenTitleKeys = linkedSetOf<String>()
    private val watchedHistoryKeys = linkedSetOf<ContentKey>()
    private val watchedHistoryTitleKeys = linkedSetOf<String>()
    private val queryQueue = ArrayDeque<String>()
    private val continuationTokens = mutableMapOf<String, PageToken>()
    private val videoTopicBucketMap = ConcurrentHashMap<ContentKey, String>()
    private var anchorRotationIndex: Int = 0
    private var sessionFreshnessRound: Int = ((System.currentTimeMillis() / 1000L) % 997L).toInt().coerceAtLeast(1)
    private var lastBackgroundedAtMs: Long = 0L

    // Instant in-memory stream cache + pre-cached byte tracker so swiping is 0ms
    private val resolvedStreams = ConcurrentHashMap<ContentKey, StreamInfo>()
    private val preCachedMediaKeys = ConcurrentHashMap.newKeySet<ContentKey>()
    private val preCacheSemaphore = Semaphore(3)

    // Real-time retention tracking ("giữ chân người xem")
    private var activeWatchedVideo: VideoSummary? = null
    private var activeWatchStartMs: Long = 0L
    private val sessionEngagedChannels = ConcurrentHashMap<String, Int>()
    private val sessionEngagedTopics = ArrayDeque<String>()

    private var activePrepareJob: Job? = null
    private var activeLoadMoreJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mediaCacheManager?.initialize() }
        }
        // Always force a fresh discovery feed on cold app launch so reopening the app
        // never serves stale cached pages or already-watched videos.
        loadInitialFeed(forceRefresh = true)
    }

    @Synchronized
    private fun syncWatchedHistoryIntoSeen(
        watchHistory: List<WatchHistoryItem>,
        keepKey: ContentKey? = null
    ) {
        for (item in watchHistory) {
            if (item.key == keepKey) continue
            watchedHistoryKeys.add(item.key)
            seenKeys.add(item.key)
            val titleKey = normalizeTitleKey(item.title)
            if (titleKey.length >= 6) {
                watchedHistoryTitleKeys.add(titleKey)
                seenTitleKeys.add(titleKey)
            }
        }
    }

    @Synchronized
    private fun markSeenIfUnique(video: VideoSummary): Boolean {
        if (video.key in seenKeys || video.key in watchedHistoryKeys) return false
        val titleKey = normalizeTitleKey(video.title)
        if (titleKey.length >= 6 && (titleKey in seenTitleKeys || titleKey in watchedHistoryTitleKeys)) {
            return false
        }
        seenKeys.add(video.key)
        if (titleKey.length >= 6) seenTitleKeys.add(titleKey)
        return true
    }

    @Synchronized
    private fun isAlreadySeen(video: VideoSummary): Boolean {
        if (video.key in seenKeys || video.key in watchedHistoryKeys) return true
        val titleKey = normalizeTitleKey(video.title)
        return titleKey.length >= 6 && (titleKey in seenTitleKeys || titleKey in watchedHistoryTitleKeys)
    }

    private fun takeNextDistinctQueries(count: Int): List<String> {
        val picked = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        val maxScans = queryQueue.size
        var scanned = 0
        while (picked.size < count && scanned < maxScans && queryQueue.isNotEmpty()) {
            val candidate = queryQueue.removeFirst()
            scanned++
            if (picked.none { hasOverlappingRootTopic(it, candidate) }) {
                picked.add(candidate)
                queryQueue.addLast(candidate)
            } else {
                deferred.add(candidate)
            }
        }
        deferred.forEach { queryQueue.addLast(it) }
        while (picked.size < count && queryQueue.isNotEmpty()) {
            val q = queryQueue.removeFirst()
            queryQueue.addLast(q)
            if (q !in picked) picked.add(q) else break
        }
        return picked
    }

    fun consumePendingSeedIfPresent() {
        val seed = ShortsSeedStore.consume() ?: return
        val current = _uiState.value
        if (current is ShortsUiState.Content) {
            val existingIdx = current.items.indexOfFirst { it.key == seed.key }
            if (existingIdx >= 0) {
                onPageSettled(existingIdx)
            } else {
                synchronized(this) {
                    seenKeys.add(seed.key)
                }
                val updated = listOf(seed) + current.items
                _uiState.value = current.copy(items = updated, currentIndex = 0)
                onPageSettled(0, forceReloadStream = true)
            }
        } else {
            loadInitialFeed(forceRefresh = true, explicitSeed = seed)
        }
    }

    fun onScreenDisposed() {
        flushActiveWatchRetention(currentPageIndex = -1, triggerBoost = false)
    }

    fun onAppBackgrounded() {
        lastBackgroundedAtMs = System.currentTimeMillis()
        flushActiveWatchRetention(currentPageIndex = -1, triggerBoost = false)
    }

    fun onAppResumed() {
        val bgAt = lastBackgroundedAtMs
        if (bgAt <= 0L) return
        lastBackgroundedAtMs = 0L
        val elapsedMs = System.currentTimeMillis() - bgAt
        val current = _uiState.value as? ShortsUiState.Content
        if (elapsedMs >= 5_000L || (current != null && current.currentIndex > 0 && elapsedMs >= 1_500L)) {
            refresh()
        }
    }

    private var discoveryRotationOffset: Int =
        ((System.currentTimeMillis() / 1000L) % DEFAULT_SHORTS_QUERIES.size).toInt().coerceAtLeast(0)

    fun refresh() {
        loadInitialFeed(forceRefresh = true)
    }

    fun retry() {
        val current = _uiState.value
        if (current is ShortsUiState.Content && current.streamError != null) {
            onPageSettled(current.currentIndex, forceReloadStream = true)
        } else {
            loadInitialFeed(forceRefresh = true)
        }
    }

    private fun loadInitialFeed(forceRefresh: Boolean, explicitSeed: VideoSummary? = null) {
        activePrepareJob?.cancel()
        activeLoadMoreJob?.cancel()
        if (forceRefresh) {
            runCatching { playerController.pause() }
        }
        viewModelScope.launch {
            val seed = explicitSeed ?: ShortsSeedStore.consume()
            // Always show Loading state on refresh so VerticalPager resets cleanly to index 0
            // and the user sees immediate visual feedback.
            _uiState.value = ShortsUiState.Loading

            try {
                sessionFreshnessRound++
                discoveryRotationOffset =
                    (discoveryRotationOffset + 5 + (sessionFreshnessRound % 7)) % DEFAULT_SHORTS_QUERIES.size
                if (forceRefresh) {
                    synchronized(sessionEngagedTopics) {
                        sessionEngagedTopics.clear()
                    }
                    synchronized(this@ShortsViewModel) {
                        // Never forget watchedHistoryKeys! Keep seenKeys bounded while preserving all watched history.
                        if (seenKeys.size > 500) {
                            val recentSessionKeys = seenKeys.toList().takeLast(250)
                            seenKeys.clear()
                            seenKeys.addAll(watchedHistoryKeys)
                            seenKeys.addAll(recentSessionKeys)
                            seenTitleKeys.clear()
                            seenTitleKeys.addAll(watchedHistoryTitleKeys)
                        }
                    }
                    continuationTokens.clear()
                    queryQueue.clear()
                }

                val discoveryPlan = buildDiscoveryPlan(
                    forceExplore = forceRefresh,
                    keepSeedKey = seed?.key
                )
                queryQueue.clear()
                discoveryPlan.queries.forEach { queryQueue.addLast(it) }

                val initialBatch = mutableListOf<VideoSummary>()
                if (seed != null) {
                    synchronized(this@ShortsViewModel) {
                        seenKeys.add(seed.key)
                    }
                    initialBatch.add(seed)
                }

                // Pull 5 distinct topic queries in parallel so every batch has >= 5 distinct topics
                val initialQueries = takeNextDistinctQueries(5)

                // Pick up to 2 distinct watched anchors for YouTube native related recommendations
                val anchorsPool = discoveryPlan.deepWatchedAnchors
                val anchorKeys = if (anchorsPool.isEmpty()) {
                    emptyList()
                } else {
                    buildList {
                        repeat(minOf(2, anchorsPool.size)) {
                            val anchor = anchorsPool[anchorRotationIndex % anchorsPool.size]
                            anchorRotationIndex++
                            add(anchor.key)
                        }
                    }.distinct()
                }

                val fetched = fetchAndRankShortsBatch(
                    queries = initialQueries,
                    anchorKeys = anchorKeys,
                    signals = discoveryPlan.signals,
                    forceRefresh = true,
                    existingTail = initialBatch.takeLast(4)
                )
                for (video in fetched) {
                    if (markSeenIfUnique(video)) {
                        initialBatch.add(video)
                    }
                }

                if (initialBatch.size < 8) {
                    val backupQueries = takeNextDistinctQueries(5)
                    val backupFetched = fetchAndRankShortsBatch(
                        queries = backupQueries,
                        anchorKeys = emptyList(),
                        signals = discoveryPlan.signals,
                        forceRefresh = true,
                        existingTail = initialBatch.takeLast(4)
                    )
                    for (video in backupFetched) {
                        if (markSeenIfUnique(video)) {
                            initialBatch.add(video)
                        }
                    }
                }

                // Final pass to guarantee strict 5-video topic window rule across initialBatch
                val finalBatch = enforceTopicWindowAcrossFeed(
                    items = initialBatch,
                    existingTail = emptyList()
                ).ifEmpty { initialBatch }

                if (finalBatch.isEmpty()) {
                    _uiState.value = ShortsUiState.Empty
                } else {
                    _uiState.value = ShortsUiState.Content(
                        items = finalBatch,
                        currentIndex = 0,
                        isStreamLoading = true
                    )
                    // Immediately start playing index 0 and pre-caching 1, 2, 3
                    onPageSettled(0, forceReloadStream = true)
                    // Load additional Shorts in the background without blocking initial playback
                    if (finalBatch.size < 15) {
                        loadMore(seedRelatedFrom = null)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _uiState.value = ShortsUiState.Error(AppError.NetworkError)
            }
        }
    }

    fun onChannelRequested(video: VideoSummary, onNavigate: (ContentKey) -> Unit) {
        val directKey = video.channelKey
        if (directKey != null) {
            onNavigate(directKey)
            return
        }
        viewModelScope.launch {
            val details = (videoService.video(video.key) as? AppResult.Success)?.value
            val resolvedKey = details?.channelKey
            if (resolvedKey != null) {
                enrichShortWithDetails(video.key)
                onNavigate(resolvedKey)
            }
        }
    }

    private fun enrichShortWithDetails(key: ContentKey) {
        viewModelScope.launch(Dispatchers.IO) {
            val details = (videoService.video(key) as? AppResult.Success)?.value ?: return@launch
            val current = _uiState.value as? ShortsUiState.Content ?: return@launch
            val targetItem = current.items.firstOrNull { it.key == key } ?: return@launch

            val newChannelKey = details.channelKey ?: targetItem.channelKey
            val newChannelName = details.channelName?.trim()?.takeIf { it.isNotEmpty() } ?: targetItem.channelName
            val newAvatar = details.channelAvatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: targetItem.channelAvatarUrl
            val newViews = targetItem.viewCount ?: details.viewCount
            val newDuration = targetItem.durationSeconds ?: details.durationSeconds

            if (newChannelKey == targetItem.channelKey &&
                newChannelName == targetItem.channelName &&
                newAvatar == targetItem.channelAvatarUrl &&
                newViews == targetItem.viewCount &&
                newDuration == targetItem.durationSeconds
            ) {
                return@launch
            }

            val enrichedItem = targetItem.copy(
                channelKey = newChannelKey,
                channelName = newChannelName,
                channelAvatarUrl = newAvatar,
                viewCount = newViews,
                durationSeconds = newDuration
            )

            val latest = _uiState.value as? ShortsUiState.Content
            if (latest != null) {
                _uiState.value = latest.copy(
                    items = latest.items.map { item ->
                        if (item.key == key) enrichedItem else item
                    }
                )
            }
            if (activeWatchedVideo?.key == key) {
                activeWatchedVideo = enrichedItem
                runCatching {
                    historyRepository.recordHistory(enrichedItem.copy(isShort = true), positionMs = 1_000L)
                }
            }
        }
    }

    fun onPageSettled(index: Int, forceReloadStream: Boolean = false) {
        val current = _uiState.value as? ShortsUiState.Content ?: return
        if (index !in current.items.indices) return

        val targetVideo = current.items[index]
        if (activeWatchedVideo?.key != targetVideo.key) {
            flushActiveWatchRetention(currentPageIndex = index, triggerBoost = true)
            activeWatchedVideo = targetVideo
            activeWatchStartMs = System.currentTimeMillis()
        }

        // Immediately mark as watched in memory and persist to SQLite history so even if the user
        // closes/kills the app right away, this Short is never shown again on reopen.
        synchronized(this) {
            watchedHistoryKeys.add(targetVideo.key)
            seenKeys.add(targetVideo.key)
            val titleKey = normalizeTitleKey(targetVideo.title)
            if (titleKey.length >= 6) {
                watchedHistoryTitleKeys.add(titleKey)
                seenTitleKeys.add(titleKey)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { historyRepository.recordHistory(targetVideo.copy(isShort = true), positionMs = 1_000L) }
        }

        // Pre-extract and pre-cache media bytes for the next 3 Shorts immediately
        val upcoming = listOfNotNull(
            current.items.getOrNull(index + 1),
            current.items.getOrNull(index + 2),
            current.items.getOrNull(index + 3)
        )
        warmUpcomingShorts(upcoming)

        // Load more Shorts before reaching the end of the feed
        if (index >= current.items.size - 6) {
            loadMore(seedRelatedFrom = targetVideo.key)
        }

        val currentPlayingKey = playerController.state.value.key
        val alreadyPrepared = !forceReloadStream &&
            currentPlayingKey == targetVideo.key &&
            current.streamError == null

        if (alreadyPrepared) {
            _uiState.value = current.copy(
                currentIndex = index,
                isStreamLoading = false,
                streamError = null
            )
            playerController.play()
            if (targetVideo.channelName.isNullOrBlank() || targetVideo.channelKey == null) {
                enrichShortWithDetails(targetVideo.key)
            }
            return
        }

        // Fast path: if StreamInfo is already in RAM from prefetch, prepare synchronously in 0ms!
        val cachedStream = if (!forceReloadStream) resolvedStreams[targetVideo.key] else null
        if (cachedStream != null) {
            activePrepareJob?.cancel()
            _uiState.value = current.copy(
                currentIndex = index,
                isStreamLoading = false,
                streamError = null
            )
            val fastQuality = mediaSourceFactory?.selectFastStartQuality(cachedStream)
            playerController.prepare(
                key = targetVideo.key,
                streamInfo = cachedStream,
                startPositionMs = 0L,
                playWhenReady = true,
                initialQuality = fastQuality
            )
            if (targetVideo.channelName.isNullOrBlank() || targetVideo.channelKey == null) {
                enrichShortWithDetails(targetVideo.key)
            }
            return
        }

        _uiState.value = current.copy(
            currentIndex = index,
            isStreamLoading = true,
            streamError = null
        )

        activePrepareJob?.cancel()
        activePrepareJob = viewModelScope.launch {
            val streamResult = when (val firstAttempt = videoService.streamInfo(targetVideo.key)) {
                is AppResult.Success -> firstAttempt
                is AppResult.Failure -> videoService.refreshStreamInfo(targetVideo.key)
            }

            val latest = _uiState.value as? ShortsUiState.Content ?: return@launch
            if (latest.currentIndex != index) return@launch

            when (streamResult) {
                is AppResult.Success -> {
                    resolvedStreams[targetVideo.key] = streamResult.value
                    _uiState.value = latest.copy(
                        isStreamLoading = false,
                        streamError = null
                    )
                    val fastQuality = mediaSourceFactory?.selectFastStartQuality(streamResult.value)
                    playerController.prepare(
                        key = targetVideo.key,
                        streamInfo = streamResult.value,
                        startPositionMs = 0L,
                        playWhenReady = true,
                        initialQuality = fastQuality
                    )
                    enrichShortWithDetails(targetVideo.key)
                }
                is AppResult.Failure -> {
                    // If this Short cannot be extracted even after refresh and other Shorts exist,
                    // automatically prune it and play the next Short seamlessly.
                    val remaining = latest.items.filterNot { it.key == targetVideo.key }
                    if (remaining.isNotEmpty()) {
                        val nextIdx = index.coerceAtMost(remaining.lastIndex)
                        _uiState.value = latest.copy(
                            items = remaining,
                            currentIndex = nextIdx,
                            isStreamLoading = true,
                            streamError = null
                        )
                        onPageSettled(nextIdx, forceReloadStream = true)
                    } else {
                        _uiState.value = latest.copy(
                            isStreamLoading = false,
                            streamError = streamResult.error
                        )
                    }
                }
            }
        }
    }

    /**
     * Pre-extracts StreamInfo AND pre-downloads the first 512 KB of 720p/1080p H.264 video/audio
     * into SimpleCache so swiping to any of the next 3 Shorts starts playing in 0ms without lag.
     * Also enriches channelName, channelAvatarUrl, and channelKey from the cached bundle.
     * Unplayable Shorts are pruned from the upcoming queue before the user ever reaches them.
     */
    private fun warmUpcomingShorts(upcoming: List<VideoSummary>) {
        if (upcoming.isEmpty()) return
        for (video in upcoming) {
            viewModelScope.launch(Dispatchers.IO) {
                preCacheSemaphore.withPermit {
                    val streamInfo = resolvedStreams[video.key] ?: run {
                        val first = videoService.streamInfo(video.key)
                        val resolved = if (first is AppResult.Success) {
                            first
                        } else {
                            videoService.refreshStreamInfo(video.key)
                        }
                        when (resolved) {
                            is AppResult.Success -> {
                                resolvedStreams[video.key] = resolved.value
                                resolved.value
                            }
                            is AppResult.Failure -> {
                                pruneUnplayableUpcomingShort(video.key)
                                null
                            }
                        }
                    } ?: return@withPermit

                    if (video.channelName.isNullOrBlank() || video.channelKey == null) {
                        enrichShortWithDetails(video.key)
                    }

                    if (preCachedMediaKeys.add(video.key)) {
                        val fastQuality = mediaSourceFactory?.selectFastStartQuality(streamInfo)
                        mediaSourceFactory?.preCacheFirstBytes(
                            streamInfo = streamInfo,
                            preferredQuality = fastQuality,
                            maxBytes = 524_288L
                        )
                    }
                }
            }
        }
    }

    private fun pruneUnplayableUpcomingShort(unplayableKey: ContentKey) {
        val current = _uiState.value as? ShortsUiState.Content ?: return
        val activeKey = current.items.getOrNull(current.currentIndex)?.key
        if (unplayableKey == activeKey) return
        val idx = current.items.indexOfFirst { it.key == unplayableKey }
        if (idx <= current.currentIndex) return
        val updated = current.items.filterNot { it.key == unplayableKey }
        if (updated.isNotEmpty() && updated.size != current.items.size) {
            _uiState.value = current.copy(items = updated)
        }
    }

    /**
     * Evaluates how long the user watched the previous Short ("giữ chân người xem"):
     * - Persists the real watched duration into [historyRepository].
     * - If the user watched >= 16s or >= 85% of the Short's duration, boosts that topic & channel
     *   and injects a related Short into the upcoming feed while respecting the 5-video topic window.
     */
    private fun flushActiveWatchRetention(currentPageIndex: Int, triggerBoost: Boolean) {
        val prevVideo = activeWatchedVideo ?: return
        val startedAt = activeWatchStartMs
        if (startedAt <= 0L) return
        val watchedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        activeWatchedVideo = null
        activeWatchStartMs = 0L

        if (watchedMs < 800L) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                historyRepository.recordHistory(prevVideo.copy(isShort = true), positionMs = watchedMs)
            }
        }

        val durationMs = (prevVideo.durationSeconds ?: 30L).coerceAtLeast(10L) * 1000L
        val completionRatio = watchedMs.toDouble() / durationMs.toDouble()
        val isHighRetention = watchedMs >= 16_000L || completionRatio >= 0.85

        if (isHighRetention) {
            prevVideo.channelName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let { ch ->
                sessionEngagedChannels[ch] = (sessionEngagedChannels[ch] ?: 0) + 2
            }
            val titleTopic = TopicExtractor.keywords(prevVideo.title).take(2).joinToString(" ")
            if (titleTopic.isNotBlank()) {
                synchronized(sessionEngagedTopics) {
                    sessionEngagedTopics.remove(titleTopic)
                    sessionEngagedTopics.addFirst(titleTopic)
                    while (sessionEngagedTopics.size > 4) sessionEngagedTopics.removeLast()
                }
            }
            if (triggerBoost && currentPageIndex >= 0) {
                injectRetentionRecommendations(prevVideo, insertAfterIndex = currentPageIndex)
            }
        }
    }

    private fun injectRetentionRecommendations(engagedVideo: VideoSummary, insertAfterIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val relatedShorts = when (val rel = videoService.related(engagedVideo.key)) {
                    is AppResult.Success -> filterToShorts(rel.value)
                    is AppResult.Failure -> emptyList()
                }
                val engagedTopic = rootTopicIdForVideo(engagedVideo)
                val candidates = relatedShorts
                    .filterNot { isAlreadySeen(it) }
                    .distinctBy { it.key }
                if (candidates.isEmpty()) return@launch

                val latest = _uiState.value as? ShortsUiState.Content ?: return@launch
                val insertPos = (maxOf(latest.currentIndex, insertAfterIndex) + 4)
                    .coerceAtMost(latest.items.size)
                val surrounding = latest.items.subList(
                    (insertPos - 4).coerceAtLeast(0),
                    (insertPos + 4).coerceAtMost(latest.items.size)
                )

                val chosen = candidates.firstOrNull { candidate ->
                    videoTopicBucketMap[candidate.key] = engagedTopic
                    surrounding.none { isSameTopic(it, candidate) } && markSeenIfUnique(candidate)
                } ?: return@launch

                val updatedItems = buildList {
                    addAll(latest.items.subList(0, insertPos))
                    add(chosen)
                    addAll(latest.items.subList(insertPos, latest.items.size))
                }
                _uiState.value = latest.copy(items = updatedItems)
                warmUpcomingShorts(listOf(chosen))
            } catch (_: Throwable) {
            }
        }
    }

    fun loadMore(seedRelatedFrom: ContentKey? = null) {
        val current = _uiState.value as? ShortsUiState.Content ?: return
        if (current.isLoadingMore || activeLoadMoreJob?.isActive == true) return

        _uiState.value = current.copy(isLoadingMore = true)
        activeLoadMoreJob = viewModelScope.launch {
            try {
                val plan = buildDiscoveryPlan(forceExplore = false)
                if (queryQueue.size < 6) {
                    plan.queries.forEach { q ->
                        if (q !in queryQueue) queryQueue.addLast(q)
                    }
                }

                val nextQueries = takeNextDistinctQueries(5).toMutableList()
                synchronized(sessionEngagedTopics) {
                    sessionEngagedTopics.firstOrNull()?.let { topic ->
                        val formatted = formatShortsQuery(topic, sessionFreshnessRound)
                        if (nextQueries.none { hasOverlappingRootTopic(it, formatted) }) {
                            if (nextQueries.size >= 5) nextQueries.removeAt(nextQueries.lastIndex)
                            nextQueries.add(formatted)
                        }
                    }
                }

                val anchors = buildList {
                    seedRelatedFrom?.let(::add)
                    val pool = plan.deepWatchedAnchors
                    if (pool.isNotEmpty()) {
                        val nextAnchor = pool[anchorRotationIndex % pool.size].key
                        anchorRotationIndex++
                        if (nextAnchor !in this) add(nextAnchor)
                    }
                }.take(2)

                val existingItems = (_uiState.value as? ShortsUiState.Content)?.items.orEmpty()
                val batch = fetchAndRankShortsBatch(
                    queries = nextQueries,
                    anchorKeys = anchors,
                    signals = plan.signals,
                    forceRefresh = false,
                    existingTail = existingItems.takeLast(4)
                )
                val newItems = batch.filter { markSeenIfUnique(it) }

                val latest = _uiState.value as? ShortsUiState.Content ?: return@launch
                val diversifiedNew = enforceTopicWindowAcrossFeed(
                    items = newItems,
                    existingTail = latest.items.takeLast(4)
                )
                _uiState.value = latest.copy(
                    items = if (diversifiedNew.isNotEmpty()) latest.items + diversifiedNew else latest.items,
                    isLoadingMore = false
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                val latest = _uiState.value as? ShortsUiState.Content ?: return@launch
                _uiState.value = latest.copy(isLoadingMore = false)
            }
        }
    }

    fun openComments(key: ContentKey) {
        _commentsSheet.value = ShortsCommentsSheetState(
            videoKey = key,
            isVisible = true,
            isLoading = true
        )
        viewModelScope.launch {
            when (val res = videoService.comments(key, null)) {
                is AppResult.Success -> {
                    if (_commentsSheet.value.videoKey == key) {
                        _commentsSheet.value = _commentsSheet.value.copy(
                            isLoading = false,
                            comments = res.value.comments,
                            nextPageToken = res.value.nextPageToken,
                            error = null
                        )
                    }
                }
                is AppResult.Failure -> {
                    if (_commentsSheet.value.videoKey == key) {
                        _commentsSheet.value = _commentsSheet.value.copy(
                            isLoading = false,
                            error = res.error
                        )
                    }
                }
            }
        }
    }

    fun loadMoreComments() {
        val sheet = _commentsSheet.value
        val key = sheet.videoKey ?: return
        val nextToken = sheet.nextPageToken ?: return
        if (sheet.isLoading || sheet.isAppending) return

        _commentsSheet.value = sheet.copy(isAppending = true)
        viewModelScope.launch {
            when (val res = videoService.comments(key, nextToken)) {
                is AppResult.Success -> {
                    val latest = _commentsSheet.value
                    if (latest.videoKey == key) {
                        _commentsSheet.value = latest.copy(
                            isAppending = false,
                            comments = (latest.comments + res.value.comments).distinctBy { it.commentId },
                            nextPageToken = res.value.nextPageToken
                        )
                    }
                }
                is AppResult.Failure -> {
                    val latest = _commentsSheet.value
                    if (latest.videoKey == key) {
                        _commentsSheet.value = latest.copy(isAppending = false)
                    }
                }
            }
        }
    }

    fun dismissComments() {
        _commentsSheet.value = ShortsCommentsSheetState(isVisible = false)
    }

    private data class DiscoveryPlan(
        val queries: List<String>,
        val deepWatchedAnchors: List<WatchHistoryItem>,
        val signals: LocalInterestSignals
    )

    private fun engagementWeight(item: WatchHistoryItem): Int {
        val watchedSec = (item.playbackPositionMs / 1000L).coerceAtLeast(0L)
        if (watchedSec == 0L) return 1
        val durationSec = item.durationSeconds ?: 0L
        val ratio = if (durationSec > 0L) watchedSec.toDouble() / durationSec.toDouble() else 0.0
        return when {
            watchedSec >= 180L || ratio >= 0.70 -> 4
            watchedSec >= 45L || ratio >= 0.45 -> 3
            watchedSec >= 10L || ratio >= 0.25 -> 2
            watchedSec in 1L..3L && ratio < 0.12 -> 0
            else -> 1
        }
    }

    private fun primaryTopicTokens(text: String): Set<String> {
        val stripped = text
            .replace(SHORTS_TAG_REGEX, " ")
            .replace(MODIFIER_WORDS_REGEX, " ")
        return TopicExtractor.keywords(stripped).take(2).toSet()
    }

    private fun rootTopicIdForQuery(query: String): String {
        val tokens = primaryTopicTokens(query).sorted()
        return if (tokens.isNotEmpty()) {
            "topic:" + tokens.joinToString("_")
        } else {
            "topic:" + query.lowercase(Locale.ROOT).trim()
        }
    }

    private fun rootTopicIdForVideo(video: VideoSummary): String {
        videoTopicBucketMap[video.key]?.let { return it }
        val tokens = primaryTopicTokens(video.title).sorted()
        if (tokens.isNotEmpty()) {
            return "topic:" + tokens.joinToString("_")
        }
        val ch = video.channelName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
        return if (ch != null) "channel:$ch" else "video:${video.key.nativeId}"
    }

    private fun hasOverlappingRootTopic(queryA: String, queryB: String): Boolean {
        val tokensA = primaryTopicTokens(queryA)
        val tokensB = primaryTopicTokens(queryB)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return queryA.equals(queryB, ignoreCase = true)
        return tokensA.any { it in tokensB }
    }

    private fun formatShortsQuery(rawTopic: String, variantSeed: Int): String {
        val base = rawTopic
            .replace(SHORTS_TAG_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val modifier = QUERY_FRESHNESS_MODIFIERS[
            (variantSeed.coerceAtLeast(0)) % QUERY_FRESHNESS_MODIFIERS.size
        ]
        return "$base $modifier".replace(WHITESPACE_REGEX, " ").trim()
    }

    /**
     * Builds a diversified discovery plan mixing:
     * 1. YouTube recommendations (Trending + Related anchors from up to 200 watched videos)
     * 2. Search history (from the 200 most recent search entries)
     * 3. Watch history (from the 200 most recent watched videos + session retention)
     * 4. Multi-topic discovery pool (25 diverse topics rotated/shuffled per session)
     */
    private suspend fun buildDiscoveryPlan(
        forceExplore: Boolean = false,
        keepSeedKey: ContentKey? = null
    ): DiscoveryPlan {
        val totalDefaults = DEFAULT_SHORTS_QUERIES.size
        val rotatedDefaults = buildList {
            for (i in 0 until totalDefaults) {
                add(DEFAULT_SHORTS_QUERIES[(discoveryRotationOffset + i) % totalDefaults])
            }
        }.let { list ->
            if (forceExplore) {
                list.shuffled(java.util.Random(System.nanoTime() xor sessionFreshnessRound.toLong()))
            } else {
                list
            }
        }

        val historyEnabled = runCatching { playbackPreferences.isHistoryEnabled.first() }.getOrDefault(true)
        if (!historyEnabled) {
            return DiscoveryPlan(
                queries = rotatedDefaults,
                deepWatchedAnchors = emptyList(),
                signals = LocalInterestSignals(emptyList(), emptyMap(), emptySet())
            )
        }

        // Pull from the 200 most recent search history entries and 200 most recent watch history entries
        val recentQueries = runCatching {
            searchHistoryRepository.observeRecentQueries(HISTORY_SIGNAL_LIMIT)
                .first()
                .map(LocalSearchHistoryItem::query)
        }.getOrDefault(emptyList())

        val watchHistory = runCatching {
            historyRepository.observeRecentHistory(HISTORY_SIGNAL_LIMIT).first()
        }.getOrDefault(emptyList())

        // Always sync all 200 watched history items into seenKeys/watchedHistoryKeys so
        // reopening the app never replays already-watched Shorts.
        syncWatchedHistoryIntoSeen(watchHistory, keepKey = keepSeedKey)

        val weightedChannels = linkedMapOf<String, Int>()
        sessionEngagedChannels.forEach { (ch, w) -> weightedChannels[ch] = w }
        for (item in watchHistory) {
            val ch = item.channelName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: continue
            val w = engagementWeight(item)
            if (w > 0) {
                weightedChannels[ch] = (weightedChannels[ch] ?: 0) + w
            }
        }

        val engagedHistory = watchHistory
            .withIndex()
            .filter { engagementWeight(it.value) > 0 }
            .sortedWith(
                compareByDescending<IndexedValue<WatchHistoryItem>> { engagementWeight(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }

        // Extract diverse topics across all 200 watch history items
        val extractedWatchTopics = TopicExtractor.topicsFromHistory(
            recentTitles = engagedHistory.map(WatchHistoryItem::title),
            channelFrequency = weightedChannels,
            maxTitleTopics = 12,
            maxChannelTopics = 6
        ).map(InterestTopic::query)

        val sessionTopics = synchronized(sessionEngagedTopics) { sessionEngagedTopics.toList() }

        // 1. Build distinct Search History topic pool (from up to 200 search history items)
        val searchTopicPool = mutableListOf<String>()
        for ((idx, raw) in recentQueries.withIndex()) {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            val formatted = formatShortsQuery(trimmed, sessionFreshnessRound + idx)
            if (searchTopicPool.none { hasOverlappingRootTopic(it, formatted) } &&
                rotatedDefaults.none { hasOverlappingRootTopic(it, formatted) }
            ) {
                searchTopicPool.add(formatted)
                if (searchTopicPool.size >= 12) break
            }
        }
        if (searchTopicPool.size > 2 && forceExplore) {
            searchTopicPool.shuffle(java.util.Random(sessionFreshnessRound.toLong() xor 0x5A5AL))
        }

        // 2. Build distinct Watch History topic pool (from up to 200 watched videos + session retention)
        val watchTopicPool = mutableListOf<String>()
        for ((idx, raw) in (sessionTopics + extractedWatchTopics).withIndex()) {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            val formatted = formatShortsQuery(trimmed, sessionFreshnessRound + idx + 3)
            if (watchTopicPool.none { hasOverlappingRootTopic(it, formatted) } &&
                searchTopicPool.none { hasOverlappingRootTopic(it, formatted) } &&
                rotatedDefaults.none { hasOverlappingRootTopic(it, formatted) }
            ) {
                watchTopicPool.add(formatted)
                if (watchTopicPool.size >= 12) break
            }
        }
        if (watchTopicPool.size > 2 && forceExplore) {
            watchTopicPool.shuffle(java.util.Random(sessionFreshnessRound.toLong() xor 0xA5A5L))
        }

        // 3. Interleave Discovery + Search History + Watch History so every 5 queries span 5 distinct topics
        val mixedQueries = buildList {
            var dIdx = 0
            var sIdx = 0
            var wIdx = 0

            fun addIfDistinctFromTail(candidate: String?) {
                if (candidate == null) return
                if (this.takeLast(4).none { hasOverlappingRootTopic(it, candidate) }) {
                    add(candidate)
                }
            }

            while (dIdx < rotatedDefaults.size || sIdx < searchTopicPool.size || wIdx < watchTopicPool.size) {
                addIfDistinctFromTail(rotatedDefaults.getOrNull(dIdx++))
                addIfDistinctFromTail(searchTopicPool.getOrNull(sIdx++))
                addIfDistinctFromTail(rotatedDefaults.getOrNull(dIdx++))
                addIfDistinctFromTail(watchTopicPool.getOrNull(wIdx++))
                addIfDistinctFromTail(rotatedDefaults.getOrNull(dIdx++))
            }
        }.distinct()

        // 4. Select diverse watched anchors from the 200 watch history items for YouTube Related Shorts
        val distinctAnchors = mutableListOf<WatchHistoryItem>()
        for (item in engagedHistory) {
            val itemTokens = primaryTopicTokens(item.title)
            val itemChannel = item.channelName?.trim()?.lowercase(Locale.ROOT)
            val overlapsExisting = distinctAnchors.any { existing ->
                val existingTokens = primaryTopicTokens(existing.title)
                val existingChannel = existing.channelName?.trim()?.lowercase(Locale.ROOT)
                (!itemChannel.isNullOrBlank() && itemChannel == existingChannel) ||
                    (itemTokens.isNotEmpty() && existingTokens.isNotEmpty() && itemTokens.any { it in existingTokens })
            }
            if (!overlapsExisting) {
                distinctAnchors.add(item)
                if (distinctAnchors.size >= 10) break
            }
        }

        val signals = LocalInterestSignals(
            recentQueries = (searchTopicPool.take(2) + watchTopicPool.take(2)).distinct(),
            watchedChannelFrequency = weightedChannels,
            recentlyWatched = watchHistory.map(WatchHistoryItem::key).toSet()
        )

        return DiscoveryPlan(
            queries = mixedQueries.ifEmpty { rotatedDefaults },
            deepWatchedAnchors = distinctAnchors,
            signals = signals
        )
    }

    private suspend fun fetchAndRankShortsBatch(
        queries: List<String>,
        anchorKeys: List<ContentKey>,
        signals: LocalInterestSignals,
        forceRefresh: Boolean,
        existingTail: List<VideoSummary> = emptyList()
    ): List<VideoSummary> = coroutineScope {
        val deferredSearches = queries.map { query ->
            async {
                val bucketTopicId = rootTopicIdForQuery(query)
                val pageToken = if (forceRefresh) null else continuationTokens[query]
                when (
                    val res = catalogRepository.search(
                        query = query,
                        filter = SearchFilter.VIDEOS,
                        pageToken = pageToken,
                        forceRefresh = forceRefresh && pageToken == null
                    )
                ) {
                    is AppResult.Success -> {
                        res.value.nextPageToken?.let { continuationTokens[query] = it }
                        val videos = res.value.items.mapNotNull { item ->
                            (item as? SearchResultItem.VideoItem)?.summary
                        }
                        val unseenShorts = filterToShorts(videos).filterNot { isAlreadySeen(it) }
                        unseenShorts.forEach { videoTopicBucketMap[it.key] = bucketTopicId }
                        bucketTopicId to unseenShorts
                    }
                    is AppResult.Failure -> bucketTopicId to emptyList()
                }
            }
        }

        val deferredRelated = anchorKeys.map { anchorKey ->
            async {
                val bucketTopicId = "yt_related:${anchorKey.nativeId}"
                when (val res = videoService.related(anchorKey)) {
                    is AppResult.Success -> {
                        val unseenShorts = filterToShorts(res.value).filterNot { isAlreadySeen(it) }
                        unseenShorts.forEach { video ->
                            videoTopicBucketMap.putIfAbsent(video.key, rootTopicIdForVideo(video))
                        }
                        bucketTopicId to unseenShorts
                    }
                    is AppResult.Failure -> bucketTopicId to emptyList()
                }
            }
        }

        val trendingDeferred = async {
            when (val res = catalogRepository.getTrending(forceRefresh = forceRefresh)) {
                is AppResult.Success -> {
                    val unseenTrending = res.value
                        .filter { it.isShort && !isAlreadySeen(it) }
                    unseenTrending.forEach { video ->
                        videoTopicBucketMap.putIfAbsent(video.key, rootTopicIdForVideo(video))
                    }
                    unseenTrending
                }
                is AppResult.Failure -> emptyList()
            }
        }

        val searchBuckets = deferredSearches.awaitAll()
        val relatedBuckets = deferredRelated.awaitAll()
        val trendingShorts = trendingDeferred.await()

        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        val neutralSignals = LocalInterestSignals(
            recentQueries = emptyList(),
            watchedChannelFrequency = signals.watchedChannelFrequency,
            recentlyWatched = signals.recentlyWatched
        )

        // Rank each topic bucket independently so no single searched topic can dominate the score
        val rankedBuckets = mutableListOf<List<VideoSummary>>()
        for ((topicId, bucket) in searchBuckets) {
            if (bucket.isNotEmpty()) {
                val ranked = RecommendationRanker.rank(
                    candidates = bucket,
                    signals = neutralSignals,
                    context = RecommendationContext(nowEpochSeconds = nowEpochSeconds),
                    limit = bucket.size.coerceAtMost(15)
                )
                ranked.forEach { videoTopicBucketMap[it.key] = topicId }
                rankedBuckets.add(ranked)
            }
        }
        if (trendingShorts.isNotEmpty()) {
            rankedBuckets.add(trendingShorts.shuffled(java.util.Random(sessionFreshnessRound.toLong())))
        }
        for ((_, relBucket) in relatedBuckets) {
            if (relBucket.isNotEmpty()) {
                rankedBuckets.add(relBucket.take(8))
            }
        }

        // Round-robin interleave across all topic & YouTube recommendation buckets
        val interleaved = mutableListOf<VideoSummary>()
        val maxLen = rankedBuckets.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (bucket in rankedBuckets) {
                bucket.getOrNull(i)?.let { interleaved.add(it) }
            }
        }

        val batchSeenTitles = HashSet<String>()
        val uniqueCandidates = interleaved
            .distinctBy { it.key }
            .filter { video ->
                val titleKey = normalizeTitleKey(video.title)
                titleKey.length < 6 || batchSeenTitles.add(titleKey)
            }
        if (uniqueCandidates.isEmpty()) return@coroutineScope emptyList()

        enforceTopicWindowAcrossFeed(
            items = uniqueCandidates,
            existingTail = existingTail,
            limit = uniqueCandidates.size.coerceAtMost(60)
        )
    }

    private fun isSameTopic(a: VideoSummary, b: VideoSummary): Boolean {
        if (a.key == b.key) return true
        val bucketA = rootTopicIdForVideo(a)
        val bucketB = rootTopicIdForVideo(b)
        if (bucketA == bucketB) return true

        val channelA = a.channelName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
        val channelB = b.channelName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
        if (channelA != null && channelA == channelB) return true

        val tokensA = primaryTopicTokens(a.title)
        val tokensB = primaryTopicTokens(b.title)
        return tokensA.isNotEmpty() && tokensB.isNotEmpty() && tokensA.any { it in tokensB }
    }

    /**
     * Enforces strict topic diversification across any window of 5 consecutive videos:
     * - Prefer candidates whose topic bucket, channel, and title root keywords do not appear
     *   in the previous 4 videos (so all 5 consecutive videos come from 5 different topics).
     * - Never allow adjacent videos to share a topic, and never allow 5 consecutive videos
     *   to share a topic (excess single-topic tail items are dropped).
     */
    private fun enforceTopicWindowAcrossFeed(
        items: List<VideoSummary>,
        existingTail: List<VideoSummary>,
        limit: Int = items.size
    ): List<VideoSummary> {
        if (items.isEmpty()) return emptyList()
        val remaining = items.toMutableList()
        val result = mutableListOf<VideoSummary>()
        val recentWindow = ArrayDeque<VideoSummary>()
        existingTail.takeLast(4).forEach { recentWindow.addLast(it) }

        while (remaining.isNotEmpty() && result.size < limit) {
            // Pass 1: Candidate's topic does not appear anywhere in the last 4 videos
            // -> guarantees all 5 consecutive videos have 5 distinct topics!
            var chosenIdx = remaining.indexOfFirst { candidate ->
                recentWindow.none { prev -> isSameTopic(prev, candidate) }
            }

            // Pass 2: Candidate's topic does not appear in the last 2 videos and appears at most once in the last 4
            if (chosenIdx < 0) {
                val lastTwo = recentWindow.takeLast(2)
                chosenIdx = remaining.indexOfFirst { candidate ->
                    lastTwo.none { prev -> isSameTopic(prev, candidate) } &&
                        recentWindow.count { prev -> isSameTopic(prev, candidate) } <= 1
                }
            }

            // Pass 3: Candidate's topic is different from the immediately preceding video
            // and appears < 2 times in the last 4 videos
            if (chosenIdx < 0) {
                val prevVideo = recentWindow.lastOrNull()
                chosenIdx = remaining.indexOfFirst { candidate ->
                    (prevVideo == null || !isSameTopic(prevVideo, candidate)) &&
                        recentWindow.count { prev -> isSameTopic(prev, candidate) } < 2
                }
            }

            // If all remaining items belong to the same single topic as recent videos, stop and
            // drop the excess single-topic tail so the feed never repeats the same topic consecutively.
            if (chosenIdx < 0) {
                break
            }

            val chosen = remaining.removeAt(chosenIdx)
            result.add(chosen)
            recentWindow.addLast(chosen)
            while (recentWindow.size > 4) {
                recentWindow.removeFirst()
            }
        }
        return result
    }

    private fun filterToShorts(videos: List<VideoSummary>): List<VideoSummary> {
        val flaggedShorts = videos.filter { video ->
            video.isShort &&
                !video.isLive &&
                (video.viewCount == null || video.viewCount >= MIN_SHORTS_VIEWS)
        }
        if (flaggedShorts.isNotEmpty()) {
            return deduplicateByTitle(flaggedShorts)
        }
        val shortDurationVideos = videos.filter { video ->
            !video.isShort &&
                !video.isLive &&
                video.durationSeconds != null &&
                video.durationSeconds in 1L..60L &&
                (video.viewCount == null || video.viewCount >= 5_000L)
        }.map { it.copy(isShort = true) }
        return deduplicateByTitle(shortDurationVideos)
    }

    private fun deduplicateByTitle(videos: List<VideoSummary>): List<VideoSummary> {
        val seenIds = HashSet<ContentKey>()
        val seenTitles = HashSet<String>()
        return videos.filter { video ->
            if (!seenIds.add(video.key)) return@filter false
            val titleKey = normalizeTitleKey(video.title)
            if (titleKey.length >= 6 && !seenTitles.add(titleKey)) return@filter false
            true
        }
    }

    private fun normalizeTitleKey(title: String): String {
        val stripped = title
            .lowercase(Locale.ROOT)
            .replace(SHORTS_TAG_REGEX, " ")
            .replace(PART_TAG_REGEX, " ")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        return stripped.split(' ').filter { it.isNotBlank() }.take(7).joinToString(" ")
    }

    companion object {
        internal const val HISTORY_SIGNAL_LIMIT = 200
        private const val MIN_SHORTS_VIEWS = 1_500L
        private val SHORTS_TAG_REGEX = Regex("(?i)#?shorts?\\b")
        private val MODIFIER_WORDS_REGEX =
            Regex("(?i)\\b(mới|nhất|xu|hướng|thịnh|hành|triệu|view|hay|hot|viral|top)\\b")
        private val PART_TAG_REGEX = Regex("(?i)\\b(p|part|phần|tập|ep)\\s*\\d+\\b|#\\d+")
        private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private val QUERY_FRESHNESS_MODIFIERS = listOf(
            "#shorts",
            "mới nhất #shorts",
            "xu hướng #shorts",
            "thịnh hành #shorts",
            "triệu view #shorts",
            "hay nhất #shorts"
        )

        private val DEFAULT_SHORTS_QUERIES = listOf(
            "xu hướng việt nam triệu view #shorts",
            "hài hước vui nhộn triệu view #shorts",
            "nhạc trẻ remix thịnh hành #shorts",
            "mẹo hay công nghệ điện thoại #shorts",
            "ẩm thực đường phố nấu ăn ngon #shorts",
            "bóng đá thể thao kịch tính #shorts",
            "khám phá khoa học vũ trụ #shorts",
            "review phim hay tóm tắt phim #shorts",
            "thú cưng chó mèo đáng yêu #shorts",
            "du lịch khám phá thiên nhiên #shorts",
            "siêu xe độ xe tốc độ #shorts",
            "kiến thức thú vị lịch sử #shorts",
            "ảo thuật tài năng đường phố #shorts",
            "game thủ highlight hài hước #shorts",
            "sáng tạo tự làm diy độc đáo #shorts",
            "chữa lành chill phong cảnh đẹp #shorts",
            "văn hóa lễ hội độc lạ #shorts",
            "nhảy đẹp dance cover triệu view #shorts",
            "bí ẩn thế giới khám phá #shorts",
            "câu chuyện cảm động ý nghĩa #shorts",
            "thời trang phong cách làm đẹp #shorts",
            "nhiếp ảnh quay phim nghệ thuật #shorts",
            "thể hình gym workout động lực #shorts",
            "kiến trúc nhà đẹp nội thất #shorts",
            "thiên nhiên hoang dã kỳ thú #shorts"
        )

        fun provideFactory(
            catalogRepository: CatalogRepository,
            videoService: VideoService,
            searchHistoryRepository: SearchHistoryRepository,
            historyRepository: HistoryRepository,
            playbackPreferences: PlaybackPreferences,
            playerController: PlayerController,
            mediaSourceFactory: MediaSourceFactory? = null,
            mediaCacheManager: MediaCacheManager? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ShortsViewModel(
                    catalogRepository = catalogRepository,
                    videoService = videoService,
                    searchHistoryRepository = searchHistoryRepository,
                    historyRepository = historyRepository,
                    playbackPreferences = playbackPreferences,
                    playerController = playerController,
                    mediaSourceFactory = mediaSourceFactory,
                    mediaCacheManager = mediaCacheManager
                ) as T
            }
        }
    }
}
