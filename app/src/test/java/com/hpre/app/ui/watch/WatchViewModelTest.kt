package com.hpre.app.ui.watch

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.Comment
import com.hpre.app.model.CommentPage
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlaybackProgress
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.player.toProgress
import com.hpre.app.repository.RecommendationRequest
import com.hpre.app.repository.VideoService
import com.hpre.app.repository.WatchRecommendationSource
import com.hpre.app.testing.FakeVideoService
import com.hpre.app.ui.common.AsyncState
import com.hpre.app.ui.watch.RefreshableAsyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testKey = ContentKey(0, "watch_test_video")

    private fun summary(id: String) = VideoSummary(
        key = ContentKey(0, id),
        title = "Title $id",
        canonicalUrl = "https://example.test/watch?v=$id",
        channelKey = null,
        channelName = "Channel",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 120,
        viewCount = null,
        publishedTimestamp = null
    )

    private class FakePlayerController : PlayerController {
        val _state = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = _state

        var preparedKey: ContentKey? = null
        var prepareCount = 0
        var preparedStreamInfo: StreamInfo? = null
        var startPositionMs: Long? = null
        var playWhenReady: Boolean? = null
        var initialQuality: QualityOption? = null
        var isReleased = false
        var playPauseCalled = false
        var seekByDelta: Long? = null
        val seekToPositions = mutableListOf<Long>()
        var selectedSpeed: Float? = null
        var selectedQualityOption: QualityOption? = null
        var attachedViewCount = 0
        var clearMediaCount = 0
        var transitionCount = 0
        var onPrepare: (() -> Unit)? = null

        override fun attachSurface(playerView: androidx.media3.ui.PlayerView) {
            attachedViewCount++
        }

        override fun detachSurface(playerView: androidx.media3.ui.PlayerView) {
            attachedViewCount = (attachedViewCount - 1).coerceAtLeast(0)
        }

        override fun onLifecycleStart() {}

        override fun onLifecycleStop() {}

        override fun prepare(
            key: ContentKey,
            streamInfo: StreamInfo,
            startPositionMs: Long,
            playWhenReady: Boolean,
            initialQuality: QualityOption?
        ) {
            onPrepare?.invoke()
            prepareCount++
            preparedKey = key
            preparedStreamInfo = streamInfo
            this.startPositionMs = startPositionMs
            this.playWhenReady = playWhenReady
            this.initialQuality = initialQuality
            _state.value = PlaybackState(
                key = key,
                isPlaying = playWhenReady,
                playWhenReady = playWhenReady,
                currentPositionMs = startPositionMs,
                selectedQuality = initialQuality
            )
        }

        override fun play() {
            _state.value = _state.value.copy(isPlaying = true)
        }

        override fun pause() {
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun playPause() {
            playPauseCalled = true
            _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
        }

        override fun seekTo(positionMs: Long) {
            seekToPositions += positionMs
            _state.value = _state.value.copy(currentPositionMs = positionMs)
        }

        override fun seekBy(deltaMs: Long) {
            seekByDelta = deltaMs
        }

        override fun setPlaybackSpeed(speed: Float) {
            selectedSpeed = speed
            _state.value = _state.value.copy(playbackSpeed = speed)
        }

        override fun selectQuality(quality: QualityOption) {
            selectedQualityOption = quality
            _state.value = _state.value.copy(selectedQuality = quality)
        }

        override fun release() {
            isReleased = true
            _state.value = PlaybackState()
        }

        var stubbedProgress: PlaybackProgress? = null

        override suspend fun readProgress(): PlaybackProgress {
            return stubbedProgress ?: _state.value.toProgress()
        }

        override fun clearMedia() {
            clearMediaCount++
            _state.value = PlaybackState()
        }

        override fun stopForTransition() {
            transitionCount++
            _state.value = PlaybackState()
        }

        fun setPlayerErrorForTest(error: AppError) {
            _state.value = _state.value.copy(error = error, isPlaying = false, isLoading = false)
        }

        fun markReady(key: ContentKey) {
            _state.value = _state.value.copy(key = key, isReady = true, isLoading = false)
        }
    }

    @Test
    fun related_starts_after_prepare_but_comments_wait_for_player_ready() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val streamGate = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = {
                events += "details"
                AppResult.Success(testDetails(it))
            },
            streamInfoHandler = {
                events += "stream"
                streamGate.await()
            },
            relatedHandler = {
                events += "related"
                AppResult.Success(emptyList())
            },
            commentsHandler = { _, _ ->
                events += "comments"
                AppResult.Success(CommentPage(emptyList()))
            }
        )
        val player = FakePlayerController().apply { onPrepare = { events += "prepare" } }
        val model = WatchViewModel(
            videoService = service,
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        model.setCommentsExpanded(true)
        runCurrent()

        assertTrue("stream must start immediately", "stream" in events)
        assertFalse("related must not compete before prepare: $events", "related" in events)
        assertFalse("comments must not compete before prepare: $events", "comments" in events)

        streamGate.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()

        assertTrue(events.indexOf("prepare") < events.indexOf("related"))
        assertFalse("comments must wait for player ready: $events", "comments" in events)

        player.markReady(testKey)
        runCurrent()

        assertEquals(1, events.count { it == "comments" })
        assertTrue(events.indexOf("prepare") < events.indexOf("comments"))
    }

    @Test
    fun comments_start_once_after_two_second_ready_fallback() = runTest(testDispatcher) {
        var commentsCalls = 0
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(emptyList()) },
            commentsHandler = { _, _ ->
                commentsCalls++
                AppResult.Success(CommentPage(emptyList()))
            }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        model.setCommentsExpanded(true)
        runCurrent()
        assertEquals(0, commentsCalls)
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(0, commentsCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, commentsCalls)
    }

    @Test
    fun ready_and_fallback_race_starts_comments_once() = runTest(testDispatcher) {
        var commentsCalls = 0
        val player = FakePlayerController()
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(emptyList()) },
            commentsHandler = { _, _ ->
                commentsCalls++
                AppResult.Success(CommentPage(emptyList()))
            }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        model.setCommentsExpanded(true)
        runCurrent()
        advanceTimeBy(2_000L)
        player.markReady(testKey)
        runCurrent()

        assertEquals(1, commentsCalls)
    }

    @Test
    fun changing_video_invalidates_previous_comments_gate() = runTest(testDispatcher) {
        val requestedKeys = mutableListOf<ContentKey>()
        val first = ContentKey(0, "first")
        val second = ContentKey(0, "second")
        val player = FakePlayerController()
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(emptyList()) },
            commentsHandler = { key, _ ->
                requestedKeys += key
                AppResult.Success(CommentPage(emptyList()))
            }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        model.load(first)
        model.setCommentsExpanded(true)
        runCurrent()
        model.load(second)
        model.setCommentsExpanded(true)
        runCurrent()
        player.markReady(first)
        runCurrent()
        assertTrue(requestedKeys.isEmpty())
        player.markReady(second)
        runCurrent()

        assertEquals(listOf(second), requestedKeys)
    }

    @Test
    fun cached_comments_bypass_ready_gate_without_refetch() = runTest(testDispatcher) {
        var commentsCalls = 0
        val cachedPage = CommentPage(
            listOf(Comment("cached", "Author", null, null, "Cached body", null, null))
        )
        val cache = com.hpre.app.repository.WatchStateCache().apply {
            put(
                testKey,
                com.hpre.app.repository.WatchStateSnapshot(
                    details = testDetails(testKey),
                    relatedVideos = emptyList(),
                    comments = cachedPage
                )
            )
        }
        val player = FakePlayerController()
        val service = FakeVideoService(
            supportsComments = true,
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            commentsHandler = { _, _ ->
                commentsCalls++
                AppResult.Success(CommentPage(emptyList()))
            }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchStateCache = cache,
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        model.setCommentsExpanded(true)
        runCurrent()
        assertEquals(cachedPage, (model.commentsState.value as AsyncState.Content).value)
        assertEquals(0, commentsCalls)

        player.markReady(testKey)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, commentsCalls)
    }

    @Test
    fun cached_details_render_immediately_while_resume_and_stream_start_concurrently() = runTest(testDispatcher) {
        val resumeGate = CompletableDeferred<AppResult<com.hpre.app.repository.WatchHistoryItem?>>()
        var resumeStarted = false
        var streamStarted = false
        val history = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey): AppResult<com.hpre.app.repository.WatchHistoryItem?> {
                resumeStarted = true
                return resumeGate.await()
            }
            override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val service = FakeVideoService(
            supportsComments = false,
            streamInfoHandler = {
                streamStarted = true
                AppResult.Success(testStreamInfo(it))
            },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val cache = com.hpre.app.repository.WatchStateCache().apply {
            put(testKey, com.hpre.app.repository.WatchStateSnapshot(testDetails(testKey), null, null))
        }
        val player = FakePlayerController()
        val model = WatchViewModel(
            videoService = service,
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            historyRepository = history,
            watchStateCache = cache,
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        runCurrent()

        assertEquals(testDetails(testKey), model.uiState.value.details)
        assertFalse(model.uiState.value.isLoading)
        assertTrue(resumeStarted)
        assertTrue("stream must not wait for resume lookup", streamStarted)

        resumeGate.complete(AppResult.Success(null))
        advanceUntilIdle()
        assertEquals(1, player.prepareCount)
    }

    @Test
    fun cached_load_does_not_prepare_stale_key_after_navigation_during_resume() = runTest(testDispatcher) {
        val keyA = ContentKey(0, "cached_stale_a")
        val keyB = ContentKey(0, "cached_stale_b")
        val resumeA = CompletableDeferred<AppResult<com.hpre.app.repository.WatchHistoryItem?>>()
        val history = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey) =
                if (key == keyA) withContext(NonCancellable) { resumeA.await() } else AppResult.Success(null)
            override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val cache = com.hpre.app.repository.WatchStateCache().apply {
            put(keyA, com.hpre.app.repository.WatchStateSnapshot(testDetails(keyA), emptyList(), CommentPage(emptyList())))
            put(keyB, com.hpre.app.repository.WatchStateSnapshot(testDetails(keyB), emptyList(), CommentPage(emptyList())))
        }
        val player = FakePlayerController()
        val model = WatchViewModel(
            videoService = FakeVideoService(
                streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
            ),
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            historyRepository = history,
            watchStateCache = cache,
            ioDispatcher = testDispatcher
        )

        model.load(keyA)
        runCurrent()
        model.load(keyB)
        runCurrent()
        resumeA.complete(AppResult.Success(null))
        advanceUntilIdle()

        assertEquals(keyB, player.preparedKey)
        assertEquals(1, player.prepareCount)
    }

    @Test
    fun cached_stream_failure_exposes_error_instead_of_silently_stopping() = runTest(testDispatcher) {
        val cache = com.hpre.app.repository.WatchStateCache().apply {
            put(testKey, com.hpre.app.repository.WatchStateSnapshot(testDetails(testKey), emptyList(), CommentPage(emptyList())))
        }
        val model = WatchViewModel(
            videoService = FakeVideoService(
                streamInfoHandler = { AppResult.Failure(AppError.ContentUnavailable) }
            ),
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchStateCache = cache,
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        advanceUntilIdle()

        assertFalse(model.uiState.value.isLoading)
        assertEquals(testDetails(testKey), model.uiState.value.details)
        assertEquals(AppError.ContentUnavailable, model.uiState.value.error)
    }

    @Test
    fun new_key_resets_previous_sections_before_prepare() = runTest(testDispatcher) {
        val keyA = ContentKey(0, "sections_a")
        val keyB = ContentKey(0, "sections_b")
        val oldRelated = listOf(summary("old_related"))
        val oldComments = CommentPage(listOf(Comment("old", "Old", null, null, "Old", null, null)))
        val streamB = CompletableDeferred<AppResult<StreamInfo>>()
        val cache = com.hpre.app.repository.WatchStateCache().apply {
            put(keyA, com.hpre.app.repository.WatchStateSnapshot(testDetails(keyA), oldRelated, oldComments))
            put(keyB, com.hpre.app.repository.WatchStateSnapshot(testDetails(keyB), null, null))
        }
        val model = WatchViewModel(
            videoService = FakeVideoService(
                supportsComments = true,
                streamInfoHandler = { key ->
                    if (key == keyB) streamB.await() else AppResult.Success(testStreamInfo(key))
                },
                relatedHandler = { AppResult.Success(emptyList()) },
                commentsHandler = { _, _ -> AppResult.Success(CommentPage(emptyList())) }
            ),
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchStateCache = cache,
            ioDispatcher = testDispatcher
        )

        model.load(keyA)
        advanceUntilIdle()
        assertEquals(oldRelated, model.relatedState.value.value)

        model.load(keyB)
        runCurrent()

        assertNull(model.relatedState.value.value)
        assertTrue(model.relatedState.value.isInitialLoading)
        assertEquals(AsyncState.Loading, model.commentsState.value)
        streamB.complete(AppResult.Success(testStreamInfo(keyB)))
        advanceUntilIdle()
    }

    @Test
    fun new_key_clears_old_media_before_new_stream_is_ready() = runTest(testDispatcher) {
        val keyA = ContentKey(0, "old_video")
        val keyB = ContentKey(0, "new_video")
        val streamB = CompletableDeferred<AppResult<StreamInfo>>()
        val player = FakePlayerController().apply {
            _state.value = PlaybackState(key = keyA, isReady = true, isPlaying = true)
        }
        val model = WatchViewModel(
            videoService = FakeVideoService(
                videoHandler = { AppResult.Success(testDetails(it)) },
                streamInfoHandler = { streamB.await() }
            ),
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        model.load(keyB)
        runCurrent()

        assertEquals(0, player.clearMediaCount)
        assertEquals(1, player.transitionCount)
        assertNull(player.state.value.key)
        streamB.complete(AppResult.Success(testStreamInfo(keyB)))
        advanceUntilIdle()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testDetails(key: ContentKey) = VideoDetails(
        key = key,
        title = "Test Video Details",
        canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
        description = "Test description",
        channelKey = ContentKey(key.serviceId, "channel_1"),
        channelName = "Test Channel",
        channelAvatarUrl = null,
        subscriberCountText = "10K",
        thumbnailUrl = "https://thumb.test/img.jpg",
        durationSeconds = 120,
        viewCount = 1000,
        likeCount = 50,
        publishedTimestamp = 1600000000L
    )

    private fun testStreamInfo(key: ContentKey) = StreamInfo(
        key = key,
        title = "Test Stream Info",
        hlsManifestUrl = "https://manifest.m3u8"
    )

    @Test
    fun repeated_load_resolves_once_and_keeps_player_loading_until_first_frame() = runTest(testDispatcher) {
        var detailsCalls = 0
        val stream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            videoHandler = { detailsCalls++; AppResult.Success(testDetails(it)) },
            streamInfoHandler = { stream.await() },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)

        model.load(testKey)
        model.load(testKey) // Also deduplicate before the lazy job reaches its dispatcher.
        model.setFullscreen(true)
        runCurrent()

        assertEquals(testDetails(testKey), model.uiState.value.details)
        assertFalse(model.uiState.value.isLoading)
        assertTrue(model.uiState.value.isPlayerLoading)
        assertEquals(0, player.prepareCount)

        model.load(testKey)
        model.setFullscreen(false)
        model.load(testKey)
        runCurrent()
        assertEquals(1, detailsCalls)
        assertEquals(1, service.streamInfoCallCount)

        stream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()
        assertEquals(1, player.prepareCount)
        assertTrue(model.uiState.value.isPlayerLoading)

        player.markReady(testKey)
        runCurrent()
        advanceTimeBy(WatchViewModel.FIRST_FRAME_READY_FALLBACK_MS - 1)
        runCurrent()
        assertTrue("READY alone must not hide loading before the first-frame grace period", model.uiState.value.isPlayerLoading)

        player._state.value = player._state.value.copy(hasRenderedFirstFrame = true)
        runCurrent()
        assertFalse(model.uiState.value.isPlayerLoading)
        model.load(testKey)
        runCurrent()
        assertEquals(1, service.streamInfoCallCount)
        assertEquals(1, player.prepareCount)
    }

    @Test
    fun ready_fallback_restarts_after_buffering_but_not_after_position_ticks() = runTest(testDispatcher) {
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        player.markReady(testKey)
        runCurrent()
        advanceTimeBy(200)
        player._state.value = player._state.value.copy(isReady = false, isBuffering = true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(model.uiState.value.isPlayerLoading)

        player._state.value = player._state.value.copy(isReady = true, isBuffering = false)
        runCurrent()
        advanceTimeBy(100)
        player._state.value = player._state.value.copy(currentPositionMs = 100)
        runCurrent()
        advanceTimeBy(WatchViewModel.FIRST_FRAME_READY_FALLBACK_MS - 101)
        runCurrent()
        assertTrue(model.uiState.value.isPlayerLoading)
        advanceTimeBy(1)
        runCurrent()
        assertFalse("Progress updates must not extend the READY fallback", model.uiState.value.isPlayerLoading)
    }

    @Test
    fun audio_only_ready_and_player_error_do_not_wait_for_a_video_frame() = runTest(testDispatcher) {
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        player._state.value = player._state.value.copy(
            isReady = true,
            streamType = com.hpre.app.player.PlaybackStreamType.AUDIO_ONLY
        )
        runCurrent()
        assertFalse(model.uiState.value.isPlayerLoading)

        model.load(ContentKey(0, "startup_error"))
        runCurrent()
        assertTrue(model.uiState.value.isPlayerLoading)
        player.setPlayerErrorForTest(AppError.NetworkError)
        runCurrent()
        assertFalse(model.uiState.value.isPlayerLoading)
        assertEquals(AppError.NetworkError, model.playbackState.value.error)
    }

    @Test
    fun late_stream_from_first_A_cannot_prepare_after_A_B_A() = runTest(testDispatcher) {
        val keyB = ContentKey(0, "pipeline_B")
        val oldStream = CompletableDeferred<AppResult<StreamInfo>>()
        var aCalls = 0
        val freshStream = testStreamInfo(testKey).copy(hlsManifestUrl = "https://fresh.test/A.m3u8")
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { key ->
                if (key == testKey && ++aCalls == 1) withContext(NonCancellable) { oldStream.await() }
                else AppResult.Success(if (key == testKey) freshStream else testStreamInfo(key))
            },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        model.load(keyB)
        runCurrent()
        model.load(testKey)
        runCurrent()
        assertEquals(2, player.prepareCount)
        assertEquals(freshStream, player.preparedStreamInfo)

        oldStream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()
        assertEquals(2, aCalls)
        assertEquals(2, player.prepareCount)
        assertEquals(testKey, player.preparedKey)
        assertEquals(freshStream, player.preparedStreamInfo)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun cleared_view_model_rejects_late_details_and_stream_without_releasing_shared_player() = runTest(testDispatcher) {
        val details = CompletableDeferred<AppResult<VideoDetails>>()
        val stream = CompletableDeferred<AppResult<StreamInfo>>()
        var relatedCalls = 0
        val service = FakeVideoService(
            videoHandler = { withContext(NonCancellable) { details.await() } },
            streamInfoHandler = { withContext(NonCancellable) { stream.await() } },
            relatedHandler = { relatedCalls++; AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        val store = androidx.lifecycle.ViewModelStore().apply { put("watch", model) }
        model.load(testKey)
        runCurrent()
        val stateAtExit = model.uiState.value
        store.clear()

        details.complete(AppResult.Success(testDetails(testKey)))
        stream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()
        model.load(ContentKey(0, "must_not_open_after_clear"))
        runCurrent()

        assertEquals(stateAtExit, model.uiState.value)
        assertEquals(0, player.prepareCount)
        assertEquals(0, relatedCalls)
        assertEquals(1, service.streamInfoCallCount)
        assertFalse(player.isReleased)
    }

    @Test
    fun navigation_cancels_old_entry_before_its_view_model_is_cleared() = runTest(testDispatcher) {
        val nextKey = ContentKey(0, "next_watch_entry")
        val oldStream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { key ->
                if (key == testKey) withContext(NonCancellable) { oldStream.await() }
                else AppResult.Success(testStreamInfo(key))
            },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val sharedPlayer = FakePlayerController()
        val outgoing = WatchViewModel(service, sharedPlayer, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        val incoming = WatchViewModel(service, sharedPlayer, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        val store = androidx.lifecycle.ViewModelStore().apply {
            put("outgoing", outgoing)
            put("incoming", incoming)
        }
        outgoing.load(testKey)
        runCurrent()
        outgoing.cancelPendingLoads()
        incoming.load(nextKey)
        runCurrent()

        // The old route still exists during NavHost's exit animation.
        oldStream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()
        assertEquals(1, sharedPlayer.prepareCount)
        assertEquals(nextKey, sharedPlayer.preparedKey)
        assertFalse(sharedPlayer.isReleased)
        store.clear()
    }

    @Test
    fun switching_video_cancels_in_flight_related_and_comments_requests() = runTest(testDispatcher) {
        var relatedCancelled = false
        var commentsCancelled = false
        var commentsStarted = false
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { key ->
                if (key == testKey) {
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { relatedCancelled = true }
                } else AppResult.Success(emptyList())
            },
            commentsHandler = { _, _ ->
                commentsStarted = true
                try { kotlinx.coroutines.awaitCancellation() }
                finally { commentsCancelled = true }
            }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        model.setCommentsExpanded(true)
        runCurrent()
        player.markReady(testKey)
        runCurrent()
        assertTrue(commentsStarted)

        val nextKey = ContentKey(0, "cancel_previous_sections")
        model.load(nextKey)
        runCurrent()
        assertTrue(relatedCancelled)
        assertTrue(commentsCancelled)
        assertEquals(nextKey, player.preparedKey)
        assertFalse(model.uiState.value.commentsExpanded)
    }

    @Test
    fun clearing_watch_cancels_the_comments_readiness_gate() = runTest(testDispatcher) {
        var commentsCalls = 0
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(emptyList()) },
            commentsHandler = { _, _ -> commentsCalls++; AppResult.Success(CommentPage(emptyList())) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        val store = androidx.lifecycle.ViewModelStore().apply { put("watch", model) }
        model.load(testKey)
        model.setCommentsExpanded(true)
        runCurrent()
        assertEquals(0, commentsCalls)

        store.clear()
        player.markReady(testKey)
        advanceTimeBy(WatchViewModel.COMMENTS_READY_FALLBACK_MS)
        runCurrent()
        assertEquals(0, commentsCalls)
        assertFalse(player.isReleased)
    }

    @Test
    fun stream_failure_is_not_overwritten_by_late_metadata() = runTest(testDispatcher) {
        val details = CompletableDeferred<AppResult<VideoDetails>>()
        val stream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            videoHandler = { withContext(NonCancellable) { details.await() } },
            streamInfoHandler = { stream.await() }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        stream.complete(AppResult.Failure(AppError.NetworkError))
        runCurrent()
        assertFalse(model.uiState.value.isPlayerLoading)
        assertEquals(AppError.NetworkError, model.uiState.value.error)

        details.complete(AppResult.Success(testDetails(testKey)))
        runCurrent()
        assertEquals(AppError.NetworkError, model.uiState.value.error)
        assertFalse(model.uiState.value.isLoading)
        assertFalse(model.uiState.value.isPlayerLoading)
        assertEquals(0, player.prepareCount)
    }

    @Test
    fun repeated_playback_retry_resolves_once_and_preserves_playback_snapshot() = runTest(testDispatcher) {
        var streamCalls = 0
        val retryStream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = {
                if (++streamCalls == 1) AppResult.Success(testStreamInfo(it)) else retryStream.await()
            },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        val quality = QualityOption(720, "720p", true)
        player._state.value = player._state.value.copy(
            isPlaying = false, playWhenReady = false, currentPositionMs = 42_000, selectedQuality = quality
        )
        player.setPlayerErrorForTest(AppError.StreamExpired)

        model.retry()
        model.retry()
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(2, streamCalls)
        assertEquals(1, player.prepareCount)
        assertTrue(model.uiState.value.isPlayerLoading)

        val fresh = testStreamInfo(testKey).copy(hlsManifestUrl = "https://fresh.test/retry.m3u8")
        retryStream.complete(AppResult.Success(fresh))
        runCurrent()
        assertEquals(2, player.prepareCount)
        assertEquals(fresh, player.preparedStreamInfo)
        assertEquals(42_000L, player.startPositionMs)
        assertEquals(false, player.playWhenReady)
        assertEquals(quality, player.initialQuality)
        assertTrue(model.uiState.value.isPlayerLoading)
        player._state.value = player._state.value.copy(isReady = true, hasRenderedFirstFrame = true)
        runCurrent()
        assertFalse(model.uiState.value.isPlayerLoading)
    }

    @Test
    fun repeated_retry_after_initial_stream_failure_deduplicates_fresh_load() = runTest(testDispatcher) {
        var streamCalls = 0
        val retryStream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = {
                if (++streamCalls == 1) AppResult.Failure(AppError.NetworkError) else retryStream.await()
            },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        model.retry()
        model.retry()
        runCurrent()
        model.retry()
        runCurrent()
        assertEquals(2, streamCalls)

        retryStream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()
        assertEquals(1, player.prepareCount)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun forced_reload_prepares_fresh_stream_before_requesting_metadata() = runTest(testDispatcher) {
        val events = mutableListOf<String>()
        val stream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            videoHandler = { events += "details"; AppResult.Success(testDetails(it)) },
            streamInfoHandler = { events += "stream"; stream.await() },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController().apply { onPrepare = { events += "prepare" } }
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey, forceRefresh = true)
        runCurrent()
        assertEquals(listOf("stream"), events)
        assertTrue(model.uiState.value.isPlayerLoading)

        stream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()
        assertEquals(listOf("stream", "prepare", "details"), events)
        assertEquals(1, player.prepareCount)
        assertEquals(testDetails(testKey), model.uiState.value.details)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun history_storage_exceptions_do_not_cancel_stream_resolution() = runTest(testDispatcher) {
        val history = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey): AppResult<com.hpre.app.repository.WatchHistoryItem?> =
                throw java.io.IOException("History read unavailable")
            override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long): AppResult<Unit> =
                throw java.io.IOException("History write unavailable")
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val stream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { stream.await() },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val player = FakePlayerController()
        val model = WatchViewModel(
            service, player, androidx.lifecycle.SavedStateHandle(),
            historyRepository = history, ioDispatcher = testDispatcher
        )
        model.load(testKey)
        runCurrent()
        assertEquals(testDetails(testKey), model.uiState.value.details)
        assertTrue(model.uiState.value.isPlayerLoading)
        stream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()
        assertEquals(1, player.prepareCount)
        assertEquals(0L, player.startPositionMs)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun load_success_prepares_player_and_loads_details() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val repository = com.hpre.app.repository.CatalogRepository(
            videoService = fakeService,
            repositoryScope = this
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            catalogRepository = repository,
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertNotNull(state.details)
        assertEquals("Test Video Details", state.details?.title)
        assertEquals(testKey, fakePlayer.preparedKey)
    }

    @Test
    fun loaded_details_are_recorded_with_thumbnail_metadata() = runTest(testDispatcher) {
        var recordedSummary: VideoSummary? = null
        val history = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey) = AppResult.Success(null)
            override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long): AppResult<Unit> {
                recordedSummary = summary
                return AppResult.Success(Unit)
            }
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            historyRepository = history,
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        advanceUntilIdle()

        assertEquals(testDetails(testKey).thumbnailUrl, recordedSummary?.thumbnailUrl)
        assertEquals(testDetails(testKey).channelName, recordedSummary?.channelName)
    }

    @Test
    fun load_active_key_skips_stream_extraction_prepare_and_seek() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController().apply {
            _state.value = PlaybackState(
                key = testKey,
                isPlaying = true,
                isReady = true,
                currentPositionMs = 42_000L,
                durationMs = 120_000L
            )
        }
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        assertEquals(0, fakeService.streamInfoCallCount)
        assertEquals(0, fakePlayer.prepareCount)
        assertTrue(fakePlayer.seekToPositions.isEmpty())
        assertEquals(42_000L, fakePlayer.state.value.currentPositionMs)
        assertEquals(testDetails(testKey), viewModel.uiState.value.details)
    }

    @Test
    fun load_prepares_player_before_video_details_finish() = runTest(testDispatcher) {
        val details = CompletableDeferred<AppResult<VideoDetails>>()
        val stream = CompletableDeferred<AppResult<StreamInfo>>()
        val fakeService = FakeVideoService(
            videoHandler = { details.await() },
            streamInfoHandler = { stream.await() },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        runCurrent()
        assertNull(fakePlayer.preparedKey)

        stream.complete(AppResult.Success(testStreamInfo(testKey)))
        runCurrent()

        assertEquals(testKey, fakePlayer.preparedKey)
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.details)

        details.complete(AppResult.Success(testDetails(testKey)))
        advanceUntilIdle()
        assertEquals(testDetails(testKey), viewModel.uiState.value.details)
    }

    @Test
    fun load_falls_back_to_zero_when_history_lookup_times_out() = runTest(testDispatcher) {
        val historyResult = CompletableDeferred<AppResult<com.hpre.app.repository.WatchHistoryItem?>>()
        val historyRepo = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey) = historyResult.await()
            override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val player = FakePlayerController()
        val model = WatchViewModel(
            videoService = FakeVideoService(
                videoHandler = { AppResult.Success(testDetails(it)) },
                streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
                relatedHandler = { AppResult.Success(emptyList()) }
            ),
            playerController = player,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            historyRepository = historyRepo,
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        runCurrent()
        assertNull(player.preparedKey)

        advanceTimeBy(WatchViewModel.RESUME_LOOKUP_TIMEOUT_MS)
        runCurrent()

        assertEquals(testKey, player.preparedKey)
        assertEquals(0L, player.startPositionMs)
        assertTrue(player.seekToPositions.isEmpty())
    }

    @Test
    fun details_failure_after_stream_success_keeps_prepared_playback() = runTest(testDispatcher) {
        val details = CompletableDeferred<AppResult<VideoDetails>>()
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = FakeVideoService(
                videoHandler = { details.await() },
                streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
                relatedHandler = { AppResult.Success(emptyList()) }
            ),
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        runCurrent()
        assertEquals(testKey, fakePlayer.preparedKey)

        details.complete(AppResult.Failure(AppError.ExtractionFailed))
        advanceUntilIdle()

        assertEquals(testKey, fakePlayer.preparedKey)
        assertEquals(AppError.ExtractionFailed, viewModel.uiState.value.error)
    }

    @Test
    fun load_fetches_related_and_comments_as_independent_sections() = runTest(testDispatcher) {
        val relatedVideo = VideoSummary(
            key = ContentKey(0, "related"), title = "Related", canonicalUrl = "https://example.test/related",
            channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        )
        val comment = Comment("comment", "Author", null, null, "Text", null, null)
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(listOf(relatedVideo)) },
            commentsHandler = { _, _ -> AppResult.Success(CommentPage(listOf(comment))) }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        model.setCommentsExpanded(true)
        advanceUntilIdle()

        assertEquals(listOf(relatedVideo), model.relatedState.value.value)
        assertEquals(false, model.relatedState.value.isRefreshing)
        assertEquals(false, model.relatedState.value.isInitialLoading)
        assertEquals(listOf(comment), (model.commentsState.value as AsyncState.Content<CommentPage>).value.comments)
    }

    @Test
    fun injected_recommendation_source_expands_related_after_details_load() = runTest(testDispatcher) {
        val expanded = VideoSummary(
            key = ContentKey(0, "expanded"), title = "Expanded",
            canonicalUrl = "https://example.test/expanded", channelKey = null,
            channelName = "New Channel", channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        )
        var receivedDetails: VideoDetails? = null
        val recommendations = WatchRecommendationSource { _, details, _ ->
            receivedDetails = details
            AppResult.Success(listOf(expanded))
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        advanceUntilIdle()

        assertEquals(testDetails(testKey), receivedDetails)
        assertEquals(listOf(expanded), model.relatedState.value.value)
    }

    @Test
    fun load_uses_catalog_repository_video_cache() = runTest(testDispatcher) {
        var videoCallCount = 0
        val fakeService = FakeVideoService(
            videoHandler = {
                videoCallCount++
                AppResult.Success(testDetails(it))
            },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val repository = com.hpre.app.repository.CatalogRepository(
            videoService = fakeService,
            repositoryScope = this
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            catalogRepository = repository,
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()
        assertEquals(1, videoCallCount)

        // Loading again with forceRefresh = false hits repository videoCache
        viewModel.load(testKey, forceRefresh = false)
        advanceUntilIdle()
        assertEquals(1, videoCallCount)
    }

    @Test
    fun stream_failure_exposes_mapped_error_and_retry_event() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Failure(AppError.ContentUnavailable) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(AppError.ContentUnavailable, state.error)
        assertNull(fakePlayer.preparedKey)
    }

    @Test
    fun fullscreen_toggle_modifies_ui_state() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        assertFalse(viewModel.uiState.value.isFullscreen)
        viewModel.setFullscreen(true)
        assertTrue(viewModel.uiState.value.isFullscreen)
        viewModel.setFullscreen(false)
        assertFalse(viewModel.uiState.value.isFullscreen)
    }

    @Test
    fun fullScreenResizeMode_defaults_to_fit_and_updates_state_and_savedStateHandle() {
        val savedState = androidx.lifecycle.SavedStateHandle()
        val viewModel = WatchViewModel(
            videoService = FakeVideoService(),
            playerController = FakePlayerController(),
            savedStateHandle = savedState,
            ioDispatcher = testDispatcher
        )

        assertEquals(FullScreenResizeMode.FIT, viewModel.uiState.value.fullScreenResizeMode)

        viewModel.setFullScreenResizeMode(FullScreenResizeMode.ZOOM)
        assertEquals(FullScreenResizeMode.ZOOM, viewModel.uiState.value.fullScreenResizeMode)
        assertEquals("ZOOM", savedState.get<String>(WatchViewModel.KEY_FULLSCREEN_RESIZE_MODE))

        viewModel.setFullScreenResizeMode(FullScreenResizeMode.FILL)
        assertEquals(FullScreenResizeMode.FILL, viewModel.uiState.value.fullScreenResizeMode)
        assertEquals("FILL", savedState.get<String>(WatchViewModel.KEY_FULLSCREEN_RESIZE_MODE))
    }

    @Test
    fun fullScreenResizeMode_restores_from_savedStateHandle() {
        val savedState = androidx.lifecycle.SavedStateHandle(mapOf(WatchViewModel.KEY_FULLSCREEN_RESIZE_MODE to "ZOOM"))
        val viewModel = WatchViewModel(
            videoService = FakeVideoService(),
            playerController = FakePlayerController(),
            savedStateHandle = savedState,
            ioDispatcher = testDispatcher
        )

        assertEquals(FullScreenResizeMode.ZOOM, viewModel.uiState.value.fullScreenResizeMode)
    }

    @Test
    fun retry_reloads_both_details_and_stream() = runTest(testDispatcher) {
        var failFirst = true
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = {
                if (failFirst) {
                    AppResult.Failure(AppError.NetworkError)
                } else {
                    AppResult.Success(testStreamInfo(it))
                }
            }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()
        assertEquals(AppError.NetworkError, viewModel.uiState.value.error)

        failFirst = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.error)
        assertEquals(testKey, fakePlayer.preparedKey)
    }

    @Test
    fun onCleared_cancels_in_flight_load_job() = runTest(testDispatcher) {
        val fakeService = FakeVideoService()
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        val store = androidx.lifecycle.ViewModelStore()
        store.put("test_vm", viewModel)

        store.clear()
        // ViewModel is cleared without releasing global session player
        assertFalse(fakePlayer.isReleased)
    }

    @Test
    fun factory_invocation_creates_fresh_controller_via_factory() {
        val fakeService = FakeVideoService()
        var factoryCallCount = 0
        val fakePlayer = FakePlayerController()

        val factory = WatchViewModel.provideFactory(
            videoService = fakeService,
            playerControllerFactory = {
                factoryCallCount++
                fakePlayer
            },
            ioDispatcher = testDispatcher
        )

        val viewModel = factory.create(WatchViewModel::class.java)
        assertEquals(1, factoryCallCount)
        assertEquals(fakePlayer, viewModel.playerController)
    }

    @Test
    fun monotonic_generation_ignores_stale_video_A_when_video_B_is_loaded() = runTest(testDispatcher) {
        val keyA = ContentKey(0, "video_A")
        val keyB = ContentKey(0, "video_B")

        var delayA = true
        val fakeService = FakeVideoService(
            videoHandler = { key ->
                if (key == keyA && delayA) {
                    kotlinx.coroutines.delay(1000)
                }
                AppResult.Success(testDetails(key))
            },
            streamInfoHandler = { key ->
                if (key == keyA && delayA) {
                    kotlinx.coroutines.delay(1000)
                }
                AppResult.Success(testStreamInfo(key))
            }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        // Load A
        viewModel.load(keyA)
        // Before A finishes, Load B immediately
        viewModel.load(keyB)
        advanceUntilIdle()

        // State and controller must be B, not overwritten by stale A
        assertEquals(keyB, viewModel.uiState.value.key)
        assertEquals("Test Video Details", viewModel.uiState.value.details?.title)
        assertEquals(keyB, viewModel.uiState.value.details?.key)
        assertEquals(keyB, fakePlayer.preparedKey)
    }

    @Test
    fun stale_related_and_comments_from_previous_video_do_not_overwrite_current_sections() = runTest(testDispatcher) {
        val keyA = ContentKey(0, "video_A_sections")
        val keyB = ContentKey(0, "video_B_sections")
        val staleRelated = CompletableDeferred<AppResult<List<VideoSummary>>>()
        val staleComments = CompletableDeferred<AppResult<CommentPage>>()
        val relatedB = VideoSummary(
            key = ContentKey(0, "related_B"), title = "Related B",
            canonicalUrl = "https://example.test/related-b", channelKey = null,
            channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        )
        val commentB = Comment("comment_B", "Author B", null, null, "B", null, null)
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { key ->
                if (key == keyA) withContext(NonCancellable) { staleRelated.await() }
                else AppResult.Success(listOf(relatedB))
            },
            commentsHandler = { key, _ ->
                if (key == keyA) withContext(NonCancellable) { staleComments.await() }
                else AppResult.Success(CommentPage(listOf(commentB)))
            }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(keyA)
        viewModel.setCommentsExpanded(true)
        runCurrent()
        viewModel.load(keyB)
        viewModel.setCommentsExpanded(true)
        runCurrent()

        staleRelated.complete(AppResult.Success(emptyList()))
        staleComments.complete(AppResult.Success(CommentPage(emptyList())))
        advanceUntilIdle()

        assertEquals(listOf(relatedB), viewModel.relatedState.value.value)
        assertEquals(listOf(commentB), (viewModel.commentsState.value as AsyncState.Content).value.comments)
    }

    @Test
    fun refreshRelated_keeps_batch_A_visible_and_excludes_all_A_keys() = runTest(testDispatcher) {
        val key = ContentKey(0, "watch_refresh_test")
        val batchA = List(100) { VideoSummary(
            key = ContentKey(0, "rel_a_$it"), title = "A $it", canonicalUrl = "https://example.test/a$it",
            channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        ) }
        val batchB = List(15) { VideoSummary(
            key = ContentKey(0, "rel_b_$it"), title = "B $it", canonicalUrl = "https://example.test/b$it",
            channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        ) }

        var callCount = 0
        var capturedRequest: RecommendationRequest? = null
        val bDeferred = CompletableDeferred<AppResult<List<VideoSummary>>>()

        val recommendations = WatchRecommendationSource { _, _, req ->
            callCount++
            if (callCount == 1) {
                AppResult.Success(batchA)
            } else {
                capturedRequest = req
                bDeferred.await()
            }
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(key)
        advanceUntilIdle()

        assertEquals(batchA, viewModel.relatedState.value.value)
        assertEquals(false, viewModel.relatedState.value.isRefreshing)

        // Trigger refreshRelated
        viewModel.refreshRelated()
        runCurrent()

        // Batch A stays visible while refreshing
        assertEquals(batchA, viewModel.relatedState.value.value)
        assertTrue(viewModel.relatedState.value.isRefreshing)
        assertEquals(null, viewModel.relatedState.value.error)

        assertNotNull(capturedRequest)
        assertEquals(true, capturedRequest?.forceRefresh)
        assertEquals(batchA.map { it.key }.toSet(), capturedRequest?.excludedKeys)

        // Complete B
        bDeferred.complete(AppResult.Success(batchB))
        advanceUntilIdle()

        assertEquals(batchB, viewModel.relatedState.value.value)
        assertEquals(false, viewModel.relatedState.value.isRefreshing)
    }

    @Test
    fun refreshRelated_repeated_A_to_B_to_A_eligibility() = runTest(testDispatcher) {
        val key = ContentKey(0, "watch_refresh_cycle")
        val batchA = List(50) { VideoSummary(
            key = ContentKey(0, "rel_a_$it"), title = "A $it", canonicalUrl = "https://example.test/a$it",
            channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        ) }
        val batchB = List(20) { VideoSummary(
            key = ContentKey(0, "rel_b_$it"), title = "B $it", canonicalUrl = "https://example.test/b$it",
            channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        ) }

        val requests = mutableListOf<RecommendationRequest>()
        var callCount = 0
        val recommendations = WatchRecommendationSource { _, _, req ->
            callCount++
            requests += req
            when (callCount) {
                1 -> AppResult.Success(batchA)
                2 -> AppResult.Success(batchB)
                else -> AppResult.Success(batchA)
            }
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(key)
        advanceUntilIdle()

        // Refresh 1: A -> B
        viewModel.refreshRelated()
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertEquals(batchA.map { it.key }.toSet(), requests[1].excludedKeys)

        // Refresh 2: B -> A (excludes B, A is re-eligible)
        viewModel.refreshRelated()
        advanceUntilIdle()
        assertEquals(3, requests.size)
        assertEquals(batchB.map { it.key }.toSet(), requests[2].excludedKeys)
        assertEquals(batchA, viewModel.relatedState.value.value)
    }

    @Test
    fun refreshRelated_error_preserves_batch_and_exposes_error() = runTest(testDispatcher) {
        val key = ContentKey(0, "watch_refresh_err")
        val batch = listOf(
            VideoSummary(
                key = ContentKey(0, "init_rel"), title = "Init", canonicalUrl = "https://example.test/init",
                channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                durationSeconds = null, viewCount = null, publishedTimestamp = null
            )
        )
        var callCount = 0
        val recommendations = WatchRecommendationSource { _, _, _ ->
            callCount++
            if (callCount == 1) AppResult.Success(batch) else AppResult.Failure(AppError.NetworkError)
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(key)
        advanceUntilIdle()

        viewModel.refreshRelated()
        advanceUntilIdle()

        val state = viewModel.relatedState.value
        assertEquals(batch, state.value)
        assertEquals(false, state.isRefreshing)
        assertEquals(AppError.NetworkError, state.error)
    }

    @Test
    fun refreshRelated_clean_empty_publishes_empty_success_not_error() = runTest(testDispatcher) {
        val key = ContentKey(0, "watch_refresh_empty")
        val batch = listOf(
            VideoSummary(
                key = ContentKey(0, "init_rel"), title = "Init", canonicalUrl = "https://example.test/init",
                channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                durationSeconds = null, viewCount = null, publishedTimestamp = null
            )
        )
        var callCount = 0
        val recommendations = WatchRecommendationSource { _, _, _ ->
            callCount++
            if (callCount == 1) AppResult.Success(batch) else AppResult.Success(emptyList())
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(key)
        advanceUntilIdle()

        viewModel.refreshRelated()
        advanceUntilIdle()

        val state = viewModel.relatedState.value
        assertEquals(emptyList<VideoSummary>(), state.value)
        assertEquals(false, state.isRefreshing)
        assertEquals(null, state.error)
    }

    @Test
    fun video_route_change_invalidates_all_previous_recommendation_refreshes() = runTest(testDispatcher) {
        val keyA = ContentKey(0, "video_route_A")
        val keyB = ContentKey(0, "video_route_B")
        val slowRefreshA = CompletableDeferred<AppResult<List<VideoSummary>>>()

        val recommendations = WatchRecommendationSource { key, _, req ->
            if (key == keyA && req.forceRefresh) {
                slowRefreshA.await()
            } else if (key == keyA) {
                AppResult.Success(listOf(VideoSummary(
                    key = ContentKey(0, "rel_A_initial"), title = "A", canonicalUrl = "",
                    channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                    durationSeconds = null, viewCount = null, publishedTimestamp = null
                )))
            } else {
                AppResult.Success(listOf(VideoSummary(
                    key = ContentKey(0, "rel_B_initial"), title = "B", canonicalUrl = "",
                    channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                    durationSeconds = null, viewCount = null, publishedTimestamp = null
                )))
            }
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        // Load A
        viewModel.load(keyA)
        advanceUntilIdle()
        assertEquals("rel_A_initial", viewModel.relatedState.value.value?.first()?.key?.nativeId)

        // Start refresh on A (slow)
        viewModel.refreshRelated()
        runCurrent()

        // Navigate to B
        viewModel.load(keyB)
        advanceUntilIdle()
        assertEquals("rel_B_initial", viewModel.relatedState.value.value?.first()?.key?.nativeId)

        // Slow refresh for A finishes now
        slowRefreshA.complete(AppResult.Success(listOf(VideoSummary(
            key = ContentKey(0, "rel_A_stale_refresh"), title = "Stale A", canonicalUrl = "",
            channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
            durationSeconds = null, viewCount = null, publishedTimestamp = null
        ))))
        advanceUntilIdle()

        // Must still show B, not overwritten by stale refresh of A
        assertEquals("rel_B_initial", viewModel.relatedState.value.value?.first()?.key?.nativeId)
    }

    @Test
    fun retryRelated_when_content_present_runs_as_refresh_retry_preserving_batch_and_exclusion() = runTest(testDispatcher) {
        val key = ContentKey(0, "retry_related_with_content")
        val batchA = listOf(
            VideoSummary(
                key = ContentKey(0, "rel_1"), title = "R1", canonicalUrl = "",
                channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                durationSeconds = null, viewCount = null, publishedTimestamp = null
            )
        )
        val batchB = listOf(
            VideoSummary(
                key = ContentKey(0, "rel_2"), title = "R2", canonicalUrl = "",
                channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                durationSeconds = null, viewCount = null, publishedTimestamp = null
            )
        )
        var callCount = 0
        var capturedExcludedKeys: Set<ContentKey>? = null
        val retryGate = CompletableDeferred<Unit>()
        val recommendations = WatchRecommendationSource { _, _, req ->
            callCount++
            capturedExcludedKeys = req.excludedKeys
            when (callCount) {
                1 -> AppResult.Success(batchA)
                2 -> AppResult.Failure(AppError.NetworkError)
                else -> {
                    retryGate.await()
                    AppResult.Success(batchB)
                }
            }
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(key)
        advanceUntilIdle()
        assertEquals(batchA, viewModel.relatedState.value.value)

        // Trigger refreshRelated -> fails
        viewModel.refreshRelated()
        advanceUntilIdle()
        assertEquals(batchA, viewModel.relatedState.value.value)
        assertEquals(AppError.NetworkError, viewModel.relatedState.value.error)

        // Trigger retryRelated -> with batchA present, must run as refresh retry preserving batchA while refreshing
        viewModel.retryRelated()
        runCurrent()
        assertTrue(viewModel.relatedState.value.isRefreshing)
        assertEquals(batchA, viewModel.relatedState.value.value)
        assertEquals(batchA.map { it.key }.toSet(), capturedExcludedKeys)

        retryGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(batchB, viewModel.relatedState.value.value)
        assertFalse(viewModel.relatedState.value.isRefreshing)
        assertNull(viewModel.relatedState.value.error)
    }

    @Test
    fun interleaved_admit_A_then_admit_B_before_A_starts_guarantees_B_handle_cancellable_and_A_cannot_publish() = runTest(testDispatcher) {
        val key = ContentKey(0, "interleaved_key")
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        var callCount = 0

        val recommendations = WatchRecommendationSource { _, _, req ->
            callCount++
            val cur = callCount
            if (cur == 1) {
                // Initial load
                AppResult.Success(listOf(VideoSummary(
                    key = ContentKey(0, "rel_initial"), title = "Initial", canonicalUrl = "",
                    channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                    durationSeconds = null, viewCount = null, publishedTimestamp = null
                )))
            } else if (cur == 2) {
                // Request A (refresh 1)
                gateA.await()
                AppResult.Success(listOf(VideoSummary(
                    key = ContentKey(0, "rel_from_A"), title = "A", canonicalUrl = "",
                    channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                    durationSeconds = null, viewCount = null, publishedTimestamp = null
                )))
            } else {
                // Request B (refresh 2)
                gateB.await()
                AppResult.Success(listOf(VideoSummary(
                    key = ContentKey(0, "rel_from_B"), title = "B", canonicalUrl = "",
                    channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                    durationSeconds = null, viewCount = null, publishedTimestamp = null
                )))
            }
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(key)
        advanceUntilIdle()
        assertEquals("rel_initial", viewModel.relatedState.value.value?.first()?.key?.nativeId)

        // Admit A
        viewModel.refreshRelated()
        runCurrent()

        // A repeated refresh while A is active is intentionally ignored to prevent request loops.
        viewModel.refreshRelated()
        runCurrent()

        // Let A finish
        gateA.complete(Unit)
        runCurrent()
        // A is the only admitted request and publishes once complete; B was never started.
        advanceUntilIdle()
        assertEquals(2, callCount)
        assertEquals("rel_from_A", viewModel.relatedState.value.value?.first()?.key?.nativeId)
        assertFalse(viewModel.relatedState.value.isRefreshing)
    }

    @Test
    fun check_and_publish_critical_section_prevents_TOCTOU_during_route_change() = runTest(testDispatcher) {
        val keyA = ContentKey(0, "toctou_video_A")
        val keyB = ContentKey(0, "toctou_video_B")

        val resumePublishA = CompletableDeferred<Unit>()
        val recommendations = WatchRecommendationSource { key, _, _ ->
            if (key == keyA) {
                withContext(NonCancellable) {
                    resumePublishA.await()
                    AppResult.Success(listOf(VideoSummary(
                        key = ContentKey(0, "rel_A"), title = "A", canonicalUrl = "",
                        channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                        durationSeconds = null, viewCount = null, publishedTimestamp = null
                    )))
                }
            } else {
                AppResult.Success(listOf(VideoSummary(
                    key = ContentKey(0, "rel_B"), title = "B", canonicalUrl = "",
                    channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                    durationSeconds = null, viewCount = null, publishedTimestamp = null
                )))
            }
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(keyA)
        runCurrent()

        // While A is waiting right before publish under NonCancellable, route immediately to B
        viewModel.load(keyB)
        advanceUntilIdle()
        assertEquals("rel_B", viewModel.relatedState.value.value?.first()?.key?.nativeId)

        // Unblock A
        resumePublishA.complete(Unit)
        advanceUntilIdle()

        // State remains B and was not corrupted by A
        assertEquals("rel_B", viewModel.relatedState.value.value?.first()?.key?.nativeId)
    }

    @Test
    fun direct_video_service_related_fallback_applies_exclusion_and_handles_initial_and_refresh() = runTest(testDispatcher) {
        val key = ContentKey(0, "fallback_test_key")
        val serviceRelatedList = listOf(
            VideoSummary(
                key = ContentKey(0, "item_1"), title = "Item 1", canonicalUrl = "",
                channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                durationSeconds = null, viewCount = null, publishedTimestamp = null
            ),
            VideoSummary(
                key = ContentKey(0, "item_2"), title = "Item 2", canonicalUrl = "",
                channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                durationSeconds = null, viewCount = null, publishedTimestamp = null
            )
        )
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            relatedHandler = { AppResult.Success(serviceRelatedList) }
        )
        // No watchRecommendationSource injected -> falls back to videoService.related
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = null,
            ioDispatcher = testDispatcher
        )

        viewModel.load(key)
        advanceUntilIdle()

        // Initial load: shows all 2 items
        assertEquals(serviceRelatedList, viewModel.relatedState.value.value)
        assertFalse(viewModel.relatedState.value.isRefreshing)

        // Refresh: snapshots item_1 and item_2 as excluded. Since service returns same 2 items, local filter excludes both -> empty list
        viewModel.refreshRelated()
        advanceUntilIdle()

        assertEquals(emptyList<VideoSummary>(), viewModel.relatedState.value.value)
        assertFalse(viewModel.relatedState.value.isRefreshing)
        assertNull(viewModel.relatedState.value.error)
    }

    @Test
    fun refreshable_async_state_helper_construct_and_transition_invariants() {
        val initial: RefreshableAsyncState<List<VideoSummary>> = RefreshableAsyncState.initial()
        assertNull(initial.value)
        assertTrue(initial.isInitialLoading)
        assertFalse(initial.isRefreshing)
        assertNull(initial.error)

        val batch = listOf(
            VideoSummary(
                key = ContentKey(0, "test"), title = "Test", canonicalUrl = "",
                channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
                durationSeconds = null, viewCount = null, publishedTimestamp = null
            )
        )
        val content = RefreshableAsyncState.content(batch)
        assertEquals(batch, content.value)
        assertFalse(content.isInitialLoading)
        assertFalse(content.isRefreshing)
        assertNull(content.error)

        val refreshing = RefreshableAsyncState.refreshing(content.value)
        assertEquals(batch, refreshing.value)
        assertFalse(refreshing.isInitialLoading)
        assertTrue(refreshing.isRefreshing)
        assertNull(refreshing.error)

        val error = RefreshableAsyncState.error(AppError.NetworkError, refreshing.value)
        assertEquals(batch, error.value)
        assertFalse(error.isInitialLoading)
        assertFalse(error.isRefreshing)
        assertEquals(AppError.NetworkError, error.error)
    }

    @Test
    fun refreshRelated_noops_when_value_is_null_or_initial_loading_active() = runTest(testDispatcher) {
        var recommendationCalls = 0
        val gate = CompletableDeferred<AppResult<List<VideoSummary>>>()
        val recommendations = WatchRecommendationSource { _, _, _ ->
            recommendationCalls++
            gate.await()
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        runCurrent()

        // State is currently in initial loading
        assertTrue(viewModel.relatedState.value.isInitialLoading)
        assertNull(viewModel.relatedState.value.value)
        assertEquals(0, recommendationCalls)

        // The initial request waits for playback; refresh must not bypass this gate.
        viewModel.refreshRelated()
        runCurrent()
        assertEquals(0, recommendationCalls)
        advanceTimeBy(WatchViewModel.COMMENTS_READY_FALLBACK_MS)
        runCurrent()
        assertEquals(1, recommendationCalls)

        // refreshRelated while initial is active should be a NO-OP
        viewModel.refreshRelated()
        runCurrent()
        assertEquals(1, recommendationCalls)

        // Complete initial
        gate.complete(AppResult.Success(listOf(summary("item_1"))))
        advanceUntilIdle()
        assertEquals(1, recommendationCalls)
        assertEquals(listOf(summary("item_1")), viewModel.relatedState.value.value)
    }

    @Test
    fun refreshRelated_repeated_invocation_while_refreshing_noops_and_prevents_request_loop() = runTest(testDispatcher) {
        var recommendationCalls = 0
        val refreshGate = CompletableDeferred<AppResult<List<VideoSummary>>>()
        val recommendations = WatchRecommendationSource { _, _, req ->
            recommendationCalls++
            if (req.forceRefresh) {
                refreshGate.await()
            } else {
                AppResult.Success(listOf(summary("init_item")))
            }
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val viewModel = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            watchRecommendationSource = recommendations,
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()
        assertEquals(1, recommendationCalls)
        assertFalse(viewModel.relatedState.value.isRefreshing)

        // First refresh call
        viewModel.refreshRelated()
        runCurrent()
        assertEquals(2, recommendationCalls)
        assertTrue(viewModel.relatedState.value.isRefreshing)

        // Second and third rapid refresh calls while isRefreshing == true must NO-OP
        viewModel.refreshRelated()
        viewModel.refreshRelated()
        runCurrent()
        assertEquals(2, recommendationCalls)

        // Complete refresh
        refreshGate.complete(AppResult.Success(listOf(summary("refreshed_item"))))
        advanceUntilIdle()
        assertEquals(2, recommendationCalls)
        assertFalse(viewModel.relatedState.value.isRefreshing)
        assertEquals(listOf(summary("refreshed_item")), viewModel.relatedState.value.value)
    }

    @Test
    fun retry_on_playback_error_fetches_fresh_stream_and_prepares_current_key() = runTest(testDispatcher) {
        var streamFetchCount = 0
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = {
                streamFetchCount++
                AppResult.Success(testStreamInfo(it))
            }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()
        assertEquals(1, streamFetchCount)
        assertEquals(testKey, fakePlayer.preparedKey)

        // Simulate playback error (e.g. StreamExpired)
        fakePlayer.setPlayerErrorForTest(AppError.StreamExpired)
        assertEquals(AppError.StreamExpired, viewModel.playbackState.value.error)

        // Retry on playback error
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, streamFetchCount)
        assertEquals(testKey, fakePlayer.preparedKey)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun retry_on_playback_error_uses_authoritative_progress_when_snapshot_position_absent() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        fakePlayer.stubbedProgress = PlaybackProgress(positionMs = 77_000L, durationMs = 120_000L)
        fakePlayer._state.value = fakePlayer._state.value.copy(
            error = AppError.NetworkError,
            retrySnapshot = null
        )

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(testKey, fakePlayer.preparedKey)
        assertEquals(77_000L, fakePlayer.startPositionMs)
    }

    @Test
    fun loaded_details_records_history_using_authoritative_read_progress() = runTest(testDispatcher) {
        var recordedPositionMs: Long? = null
        val history = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey) = AppResult.Success(null)
            override suspend fun recordHistory(summary: VideoSummary, positionMs: Long, watchedTimestamp: Long): AppResult<Unit> {
                recordedPositionMs = positionMs
                return AppResult.Success(Unit)
            }
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        var readProgressCallCount = 0
        val fakePlayer = FakePlayerController().apply {
            stubbedProgress = PlaybackProgress(positionMs = 19_000L, durationMs = 100_000L)
        }
        val customPlayer = object : PlayerController by fakePlayer {
            override suspend fun readProgress(): PlaybackProgress {
                readProgressCallCount++
                return fakePlayer.readProgress()
            }
        }
        val model = WatchViewModel(
            videoService = service,
            playerController = customPlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            historyRepository = history,
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        advanceUntilIdle()

        assertEquals(19_000L, recordedPositionMs)
        assertEquals(1, readProgressCallCount)
    }

    // Fix 3 & 4: Retry snapshot preserves paused/playing state, position, and selected quality
    @Test
    fun retry_on_playback_error_preserves_paused_nonzero_position_and_quality() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        val customQuality = QualityOption(1080, "1080p", false)
        // Set player state to paused at 42_000ms with customQuality
        fakePlayer._state.value = fakePlayer._state.value.copy(
            isPlaying = false,
            playWhenReady = false,
            currentPositionMs = 42_000L,
            selectedQuality = customQuality
        )

        // Error occurs during paused playback
        fakePlayer.setPlayerErrorForTest(AppError.StreamExpired)
        assertEquals(AppError.StreamExpired, viewModel.playbackState.value.error)

        viewModel.retry()
        advanceUntilIdle()

        // Assert prepare was called with exact snapshot values
        assertEquals(testKey, fakePlayer.preparedKey)
        assertEquals(42_000L, fakePlayer.startPositionMs)
        assertEquals(false, fakePlayer.playWhenReady)
        assertEquals(customQuality, fakePlayer.initialQuality)
    }

    @Test
    fun retry_on_playback_error_preserves_playing_nonzero_position_and_quality() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        val customQuality = QualityOption(720, "720p", true)
        // Set player state to playing at 25_000ms
        fakePlayer._state.value = fakePlayer._state.value.copy(
            isPlaying = true,
            playWhenReady = true,
            currentPositionMs = 25_000L,
            selectedQuality = customQuality
        )

        fakePlayer.setPlayerErrorForTest(AppError.NetworkError)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(testKey, fakePlayer.preparedKey)
        assertEquals(25_000L, fakePlayer.startPositionMs)
        assertEquals(true, fakePlayer.playWhenReady)
        assertEquals(customQuality, fakePlayer.initialQuality)
    }

    @Test
    fun load_passes_resumable_history_position_into_prepare_without_post_seek() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController()
        val historyRepo = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey): AppResult<com.hpre.app.repository.WatchHistoryItem?> =
                AppResult.Success(
                    com.hpre.app.repository.WatchHistoryItem(
                        key = key,
                        canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
                        title = "Test Video Details",
                        channelKey = null,
                        channelName = null,
                        thumbnailUrl = null,
                        durationSeconds = 100L,
                        playbackPositionMs = 50000L, // 50s / 100s = 50% < 95% -> should resume
                        watchedTimestamp = 1000L
                    )
                )
            override suspend fun recordHistory(summary: com.hpre.app.model.VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }

        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            historyRepository = historyRepo,
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        assertEquals(50_000L, fakePlayer.startPositionMs)
        assertEquals(1, fakePlayer.prepareCount)
        assertTrue(fakePlayer.seekToPositions.isEmpty())
    }

    @Test
    fun load_reads_history_and_does_not_seek_if_over_95_percent() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it).copy(durationSeconds = 100L)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController()
        val historyRepo = object : com.hpre.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.hpre.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey): AppResult<com.hpre.app.repository.WatchHistoryItem?> =
                AppResult.Success(
                    com.hpre.app.repository.WatchHistoryItem(
                        key = key,
                        canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
                        title = "Test Video Details",
                        channelKey = null,
                        channelName = null,
                        thumbnailUrl = null,
                        durationSeconds = 100L,
                        playbackPositionMs = 96000L, // 96s / 100s = 96% >= 95% -> do not resume
                        watchedTimestamp = 1000L
                    )
                )
            override suspend fun recordHistory(summary: com.hpre.app.model.VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
            override suspend fun deleteHistoryItem(key: ContentKey) = AppResult.Success(Unit)
            override suspend fun clearHistory() = AppResult.Success(Unit)
        }

        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            historyRepository = historyRepo,
            ioDispatcher = testDispatcher
        )

        viewModel.load(testKey)
        advanceUntilIdle()

        assertEquals(0L, fakePlayer.startPositionMs)
    }

    @Test
    fun initial_page_duplicate_comment_ids_produces_unique_list() = runTest(testDispatcher) {
        val duplicateComments = listOf(
            Comment("comm_dup", "Author 1", null, null, "Comment body 1", null, null),
            Comment("comm_dup", "Author 1 Duplicate", null, null, "Comment body duplicate", null, null),
            Comment("comm_unique", "Author 2", null, null, "Comment body 2", null, null)
        )
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            commentsHandler = { _, _ -> AppResult.Success(CommentPage(duplicateComments)) }
        )
        val model = WatchViewModel(
            videoService = service,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
            ioDispatcher = testDispatcher
        )

        model.load(testKey)
        model.setCommentsExpanded(true)
        advanceUntilIdle()

        val commentsContent = (model.commentsState.value as AsyncState.Content<CommentPage>).value.comments
        assertEquals(2, commentsContent.size)
        assertEquals("comm_dup", commentsContent[0].commentId)
        assertEquals("Comment body 1", commentsContent[0].commentText)
        assertEquals("comm_unique", commentsContent[1].commentId)
    }

    @Test
    fun comments_are_idle_until_open_and_collapse_rejects_a_late_response() = runTest(testDispatcher) {
        var calls = 0
        val gate = CompletableDeferred<AppResult<CommentPage>>()
        val player = FakePlayerController()
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            commentsHandler = { _, _ -> calls++; withContext(NonCancellable) { gate.await() } }
        )
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        player.markReady(testKey)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, calls)
        assertFalse(model.uiState.value.commentsExpanded)

        model.setCommentsExpanded(true)
        runCurrent()
        assertEquals(1, calls)
        model.setCommentsExpanded(false)
        gate.complete(AppResult.Success(CommentPage(listOf(Comment("late", "A", null, null, "Late", null, null)))))
        runCurrent()
        assertEquals(AsyncState.Empty, model.commentsState.value)
        assertFalse(model.uiState.value.commentsExpanded)
    }

    @Test
    fun failed_comment_continuation_keeps_previous_page_and_retries_the_same_token() = runTest(testDispatcher) {
        val first = Comment("first", "A", null, null, "First", null, null)
        val second = first.copy(commentId = "second")
        val token = com.hpre.app.model.PageToken.Id("next")
        var attempts = 0
        val player = FakePlayerController()
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            commentsHandler = { _, pageToken ->
                if (pageToken == null) AppResult.Success(CommentPage(listOf(first), token))
                else {
                    assertEquals(token, pageToken)
                    attempts++
                    if (attempts == 1) AppResult.Failure(AppError.NetworkError)
                    else AppResult.Success(CommentPage(listOf(second)))
                }
            }
        )
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        player.markReady(testKey)
        model.setCommentsExpanded(true)
        runCurrent()
        model.loadMoreComments()
        runCurrent()
        assertEquals(listOf(first), (model.commentsState.value as AsyncState.Content).value.comments)
        assertEquals(AppError.NetworkError, model.commentsPagination.value.error)
        model.loadMoreComments()
        runCurrent()
        assertEquals(listOf(first, second), (model.commentsState.value as AsyncState.Content).value.comments)
        assertNull(model.commentsPagination.value.error)
        assertEquals(2, attempts)
    }

    @Test
    fun comment_memory_window_is_bounded_and_reopening_uses_the_first_page_cursor() = runTest(testDispatcher) {
        val player = FakePlayerController()
        val cache = com.hpre.app.repository.WatchStateCache()
        var calls = 0
        val service = FakeVideoService(
            supportsComments = true,
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) },
            commentsHandler = { _, token ->
                calls++
                val page = (token as? com.hpre.app.model.PageToken.Id)?.id?.toInt() ?: 0
                AppResult.Success(CommentPage(
                    (page * 50 until (page + 1) * 50).map { Comment("$it", "A", null, null, "Body $it", null, null) },
                    com.hpre.app.model.PageToken.Id("${page + 1}")
                ))
            }
        )
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(),
            watchStateCache = cache, ioDispatcher = testDispatcher)
        model.load(testKey)
        runCurrent()
        player.markReady(testKey)
        model.setCommentsExpanded(true)
        runCurrent()
        repeat(6) { model.loadMoreComments(); runCurrent() }
        val visible = (model.commentsState.value as AsyncState.Content).value
        assertEquals(200, visible.comments.size)
        assertTrue(model.commentsPagination.value.earlierCommentsDropped)
        assertEquals("150", visible.comments.first().commentId)
        assertEquals(50, cache.get(testKey)?.comments?.comments?.size)
        model.setCommentsExpanded(false)
        model.setCommentsExpanded(true)
        runCurrent()
        assertEquals(7, calls)
        val restored = (model.commentsState.value as AsyncState.Content).value
        assertEquals("0", restored.comments.first().commentId)
        assertEquals(com.hpre.app.model.PageToken.Id("1"), restored.nextPageToken)
        assertFalse(model.commentsPagination.value.earlierCommentsDropped)
    }

    @Test
    fun returning_to_previous_details_reprepares_when_shared_player_is_on_another_video() = runTest(testDispatcher) {
        val player = FakePlayerController()
        val service = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val model = WatchViewModel(service, player, androidx.lifecycle.SavedStateHandle(), ioDispatcher = testDispatcher)
        model.load(testKey)
        advanceUntilIdle()
        val another = ContentKey(0, "another")
        player.prepare(another, testStreamInfo(another))
        model.load(testKey)
        advanceUntilIdle()
        assertEquals(testKey, player.state.value.key)
        assertEquals(testKey, model.uiState.value.details?.key)
        assertEquals(0, player.clearMediaCount)
        assertEquals(1, player.transitionCount)
    }
}
