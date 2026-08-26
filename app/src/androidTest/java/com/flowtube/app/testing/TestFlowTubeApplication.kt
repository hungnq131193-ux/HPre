package com.flowtube.app.testing

import com.flowtube.app.FlowTubeApplication
import com.flowtube.app.di.AppContainer
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.player.PlaybackState
import com.flowtube.app.player.PlayerController
import com.flowtube.app.player.QualityOption
import com.flowtube.app.repository.CatalogRepository
import com.flowtube.app.repository.VideoService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TestFlowTubeApplication : FlowTubeApplication() {

    class RecordingPlayerController : PlayerController, com.flowtube.app.player.PlayerIntegrationProbe {
        val _state = MutableStateFlow(
            PlaybackState(
                key = ContentKey(0, "recreation_test_video"),
                isPlaying = true,
                durationMs = 60_000L,
                currentPositionMs = 12_000L
            )
        )
        override val state: StateFlow<PlaybackState> = _state
        var releaseCount = 0
        var attachSurfaceCount = 0
        var detachSurfaceCount = 0
        var lifecycleStartCount = 0
        var lifecycleStopCount = 0

        override suspend fun getTestingSnapshot(): com.flowtube.app.player.PlayerTestingSnapshot {
            val s = _state.value
            return com.flowtube.app.player.PlayerTestingSnapshot(
                mediaOperationGeneration = 1L,
                actualPositionMs = s.currentPositionMs,
                actualDurationMs = s.durationMs,
                playbackState = androidx.media3.common.Player.STATE_READY,
                isPlaying = s.isPlaying,
                playWhenReady = s.playWhenReady,
                selectedQuality = s.selectedQuality,
                streamType = s.streamType,
                error = s.error,
                renderedFirstFrameCount = 1,
                audioDecoderInitializedCount = 1,
                videoDecoderInitializedCount = 1,
                surfaceAttached = true
            )
        }

        override fun attachSurface(playerView: androidx.media3.ui.PlayerView) {
            attachSurfaceCount++
        }

        override fun detachSurface(playerView: androidx.media3.ui.PlayerView) {
            detachSurfaceCount++
        }

        override fun onLifecycleStart() {
            lifecycleStartCount++
        }

        override fun onLifecycleStop() {
            lifecycleStopCount++
        }

        override fun prepare(
            key: ContentKey,
            streamInfo: StreamInfo,
            startPositionMs: Long,
            playWhenReady: Boolean,
            initialQuality: QualityOption?
        ) {
            _state.value = _state.value.copy(
                key = key,
                title = streamInfo.title,
                currentPositionMs = if (startPositionMs > 0L) startPositionMs else 12_000L,
                playWhenReady = playWhenReady,
                selectedQuality = initialQuality
            )
        }
        override fun play() {}
        override fun pause() {}
        override fun playPause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekBy(deltaMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun selectQuality(quality: QualityOption) {}
        override fun release() {
            releaseCount++
        }
    }

    class RecordingWindowSystemUiController : com.flowtube.app.ui.watch.WindowSystemUiController {
        var hideCount = 0
        var showCount = 0

        override fun hideSystemBars() {
            hideCount++
        }

        override fun showSystemBars() {
            showCount++
        }
    }

    class RecordingFullscreenHostHandler(
        val activity: android.app.Activity?,
        val savedStateHandle: androidx.lifecycle.SavedStateHandle?,
        val systemUiController: RecordingWindowSystemUiController
    ) : com.flowtube.app.ui.watch.FullscreenHostHandler {
        var enterCount = 0
        var exitCount = 0
        var configChangeCount = 0

        private val delegate = com.flowtube.app.ui.watch.DefaultFullscreenHostHandler(
            activity = activity,
            savedStateHandle = savedStateHandle,
            systemUiController = systemUiController
        )

        override fun enterFullscreen() {
            enterCount++
            delegate.enterFullscreen()
        }

        override fun exitFullscreen() {
            exitCount++
            delegate.exitFullscreen()
        }

        override fun onConfigurationChange() {
            configChangeCount++
            delegate.onConfigurationChange()
        }
    }

    class RecordingFullscreenHostHandlerFactory : com.flowtube.app.ui.watch.FullscreenHostHandlerFactory {
        var lastCreatedHandler: RecordingFullscreenHostHandler? = null
        var creationCount = 0

        override fun create(
            activity: android.app.Activity?,
            savedStateHandle: androidx.lifecycle.SavedStateHandle?
        ): com.flowtube.app.ui.watch.FullscreenHostHandler {
            creationCount++
            val sysUi = RecordingWindowSystemUiController()
            val handler = RecordingFullscreenHostHandler(activity, savedStateHandle, sysUi)
            lastCreatedHandler = handler
            return handler
        }
    }

    class TestAppContainer(
        val fakeService: VideoService,
        val playerControllerInstance: PlayerController,
        override val fullscreenHostHandlerFactory: RecordingFullscreenHostHandlerFactory = RecordingFullscreenHostHandlerFactory()
    ) : AppContainer {
        override val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override val videoService: VideoService = fakeService
        override val catalogRepository: CatalogRepository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = applicationScope
        )
        override val okHttpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient()
        }
        override val mediaSourceFactory: com.flowtube.app.player.MediaSourceFactory by lazy {
            com.flowtube.app.player.MediaSourceFactory(
                dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()
                )
            )
        }
        override val database: com.flowtube.app.database.FlowTubeDatabase by lazy {
            androidx.room.Room.inMemoryDatabaseBuilder(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                com.flowtube.app.database.FlowTubeDatabase::class.java
            ).allowMainThreadQueries().build()
        }
        override val historyRepository: com.flowtube.app.repository.HistoryRepository by lazy {
            com.flowtube.app.repository.DefaultHistoryRepository(database.historyDao(), playbackPreferences)
        }
        override val subscriptionRepository: com.flowtube.app.repository.SubscriptionRepository by lazy {
            com.flowtube.app.repository.DefaultSubscriptionRepository(database.subscriptionDao())
        }
        override val playlistRepository: com.flowtube.app.repository.PlaylistRepository by lazy {
            com.flowtube.app.repository.DefaultPlaylistRepository(database.playlistDao())
        }
        override val searchHistoryRepository: com.flowtube.app.repository.SearchHistoryRepository by lazy {
            com.flowtube.app.repository.DefaultSearchHistoryRepository(database.searchHistoryDao())
        }
        override val playbackPreferences: com.flowtube.app.settings.PlaybackPreferences by lazy {
            settingsRepository
        }
        override val settingsRepository: com.flowtube.app.settings.SettingsRepository = object : com.flowtube.app.settings.SettingsRepository {
            private val _flow = kotlinx.coroutines.flow.MutableStateFlow(true)
            private val _pipFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
            private val _historyFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
            private val _settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(com.flowtube.app.settings.AppSettings())

            override val settings: kotlinx.coroutines.flow.Flow<com.flowtube.app.settings.AppSettings> = _settingsFlow
            override val isBackgroundPlaybackEnabled: kotlinx.coroutines.flow.Flow<Boolean> = _flow
            override val isPipEnabled: kotlinx.coroutines.flow.Flow<Boolean> = _pipFlow
            override val isHistoryEnabled: kotlinx.coroutines.flow.Flow<Boolean> = _historyFlow

            override suspend fun setTheme(theme: com.flowtube.app.settings.AppTheme) {
                _settingsFlow.value = _settingsFlow.value.copy(theme = theme)
            }
            override suspend fun setWifiQuality(quality: com.flowtube.app.settings.QualityPreferenceSetting) {
                _settingsFlow.value = _settingsFlow.value.copy(wifiQuality = quality)
            }
            override suspend fun setMobileQuality(quality: com.flowtube.app.settings.QualityPreferenceSetting) {
                _settingsFlow.value = _settingsFlow.value.copy(mobileQuality = quality)
            }
            override suspend fun setDefaultPlaybackSpeed(speed: Float) {
                _settingsFlow.value = _settingsFlow.value.copy(defaultPlaybackSpeed = speed)
            }
            override suspend fun setAutoplay(enabled: Boolean) {
                _settingsFlow.value = _settingsFlow.value.copy(autoplay = enabled)
            }
            override suspend fun setBackgroundPlaybackEnabled(enabled: Boolean) {
                _flow.value = enabled
                _settingsFlow.value = _settingsFlow.value.copy(backgroundPlaybackEnabled = enabled)
            }
            override suspend fun setPipEnabled(enabled: Boolean) {
                _pipFlow.value = enabled
                _settingsFlow.value = _settingsFlow.value.copy(pipEnabled = enabled)
            }
            override suspend fun setHistoryEnabled(enabled: Boolean) {
                _historyFlow.value = enabled
                _settingsFlow.value = _settingsFlow.value.copy(historyEnabled = enabled)
            }
        }
        var createPlayerCount = 0
        val uniquePlayerInstanceCount: Int = 1

        override fun createPlayerController(): PlayerController {
            createPlayerCount++
            return playerControllerInstance
        }
    }

    val recordingPlayer = RecordingPlayerController()

    val testKey = ContentKey(0, "recreation_test_video")

    private fun testDetails(key: ContentKey) = VideoDetails(
        key = key,
        title = "Recreation Video",
        canonicalUrl = "https://flowtube.test/watch?v=${key.nativeId}",
        description = "Test description",
        channelKey = ContentKey(key.serviceId, "channel_1"),
        channelName = "Test Channel",
        channelAvatarUrl = null,
        subscriberCountText = "10K",
        thumbnailUrl = null,
        durationSeconds = 60,
        viewCount = 1000,
        likeCount = 50,
        publishedTimestamp = 1600000000L
    )

    private fun summary(key: ContentKey) = VideoSummary(
        key = key,
        title = "Recreation Video",
        canonicalUrl = "https://flowtube.test/watch?v=${key.nativeId}",
        channelKey = ContentKey(key.serviceId, "channel_1"),
        channelName = "Test Channel",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 60,
        viewCount = 1000,
        publishedTimestamp = 1600000000L
    )

    val fakeVideoService = FakeVideoService(
        trendingResponse = com.flowtube.app.core.error.AppResult.Success(listOf(summary(testKey))),
        videoHandler = { com.flowtube.app.core.error.AppResult.Success(testDetails(it)) },
        streamInfoHandler = { com.flowtube.app.core.error.AppResult.Success(StreamInfo(it, "Recreation Video", hlsManifestUrl = "https://manifest.m3u8")) }
    )

    lateinit var testContainer: TestAppContainer

    override fun createContainer(): AppContainer {
        val isLive = FlowTubeTestRunner.isLivePlaybackActive ||
                try {
                    androidx.test.platform.app.InstrumentationRegistry.getArguments()
                        .getString("flowtubeLivePlayback")?.toBoolean() == true
                } catch (_: Throwable) {
                    false
                }

        if (isLive) {
            return com.flowtube.app.di.DefaultAppContainer(this)
        }
        testContainer = TestAppContainer(
            fakeService = fakeVideoService,
            playerControllerInstance = recordingPlayer
        )
        return testContainer
    }
}
