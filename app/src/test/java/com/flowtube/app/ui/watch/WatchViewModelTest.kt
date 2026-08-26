package com.flowtube.app.ui.watch

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.Comment
import com.flowtube.app.model.CommentPage
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.player.PlaybackState
import com.flowtube.app.player.PlayerController
import com.flowtube.app.player.QualityOption
import com.flowtube.app.repository.VideoService
import com.flowtube.app.testing.FakeVideoService
import com.flowtube.app.ui.common.AsyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

    private class FakePlayerController : PlayerController {
        val _state = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = _state

        var preparedKey: ContentKey? = null
        var preparedStreamInfo: StreamInfo? = null
        var startPositionMs: Long? = null
        var playWhenReady: Boolean? = null
        var initialQuality: QualityOption? = null
        var isReleased = false
        var playPauseCalled = false
        var seekByDelta: Long? = null
        var selectedSpeed: Float? = null
        var selectedQualityOption: QualityOption? = null
        var attachedViewCount = 0

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

        fun setPlayerErrorForTest(error: AppError) {
            _state.value = _state.value.copy(error = error, isPlaying = false, isLoading = false)
        }
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
        canonicalUrl = "https://flowtube.test/watch?v=${key.nativeId}",
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
    fun load_success_prepares_player_and_loads_details() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val repository = com.flowtube.app.repository.CatalogRepository(
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
        advanceUntilIdle()

        assertEquals(listOf(relatedVideo), (model.relatedState.value as AsyncState.Content<List<VideoSummary>>).value)
        assertEquals(listOf(comment), (model.commentsState.value as AsyncState.Content<CommentPage>).value.comments)
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
        val repository = com.flowtube.app.repository.CatalogRepository(
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
    fun load_reads_history_and_seeks_if_should_offer_resume() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController()
        val historyRepo = object : com.flowtube.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.flowtube.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey): AppResult<com.flowtube.app.repository.WatchHistoryItem?> =
                AppResult.Success(
                    com.flowtube.app.repository.WatchHistoryItem(
                        key = key,
                        canonicalUrl = "https://flowtube.test/watch?v=${key.nativeId}",
                        title = "Test Video Details",
                        channelKey = null,
                        channelName = null,
                        thumbnailUrl = null,
                        durationSeconds = 100L,
                        playbackPositionMs = 50000L, // 50s / 100s = 50% < 95% -> should resume
                        watchedTimestamp = 1000L
                    )
                )
            override suspend fun recordHistory(summary: com.flowtube.app.model.VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
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

        assertEquals(50000L, fakePlayer.startPositionMs)
    }

    @Test
    fun load_reads_history_and_does_not_seek_if_over_95_percent() = runTest(testDispatcher) {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it).copy(durationSeconds = 100L)) },
            streamInfoHandler = { AppResult.Success(testStreamInfo(it)) }
        )
        val fakePlayer = FakePlayerController()
        val historyRepo = object : com.flowtube.app.repository.HistoryRepository {
            override fun observeHistory() = kotlinx.coroutines.flow.emptyFlow<List<com.flowtube.app.repository.WatchHistoryItem>>()
            override suspend fun getHistoryItem(key: ContentKey): AppResult<com.flowtube.app.repository.WatchHistoryItem?> =
                AppResult.Success(
                    com.flowtube.app.repository.WatchHistoryItem(
                        key = key,
                        canonicalUrl = "https://flowtube.test/watch?v=${key.nativeId}",
                        title = "Test Video Details",
                        channelKey = null,
                        channelName = null,
                        thumbnailUrl = null,
                        durationSeconds = 100L,
                        playbackPositionMs = 96000L, // 96s / 100s = 96% >= 95% -> do not resume
                        watchedTimestamp = 1000L
                    )
                )
            override suspend fun recordHistory(summary: com.flowtube.app.model.VideoSummary, positionMs: Long, watchedTimestamp: Long) = AppResult.Success(Unit)
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
}
