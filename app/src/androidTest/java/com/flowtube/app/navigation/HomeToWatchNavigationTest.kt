package com.flowtube.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.core.designsystem.FlowTubeTheme
import com.flowtube.app.di.AppContainer
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.player.PlayerController
import com.flowtube.app.repository.CatalogRepository
import com.flowtube.app.repository.VideoService
import com.flowtube.app.testing.FakeVideoService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeToWatchNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun summary(id: String) = VideoSummary(
        key = ContentKey(0, id),
        title = "Trending $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 120,
        viewCount = 500,
        publishedTimestamp = 2000L
    )

    private fun details(id: String) = VideoDetails(
        key = ContentKey(0, id),
        title = "Video Details $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        description = "Description for $id",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = null,
        subscriberCountText = "10K",
        thumbnailUrl = null,
        durationSeconds = 120,
        viewCount = 500,
        likeCount = 10,
        publishedTimestamp = 2000L
    )

    private class TestContainer(val fakeService: FakeVideoService) : AppContainer {
        override val applicationScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        override val videoService: VideoService = fakeService
        override val catalogRepository: CatalogRepository = CatalogRepository(
            videoService = fakeService,
            repositoryScope = applicationScope
        )
        override val okHttpClient: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }
        override val mediaSourceFactory: com.flowtube.app.player.MediaSourceFactory by lazy {
            com.flowtube.app.player.MediaSourceFactory(
                dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()
                )
            )
        }
        override val fullscreenHostHandlerFactory: com.flowtube.app.ui.watch.FullscreenHostHandlerFactory =
            com.flowtube.app.ui.watch.FullscreenHostHandlerFactory { act, savedState ->
                com.flowtube.app.ui.watch.DefaultFullscreenHostHandler(act, savedState)
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
        override fun createPlayerController(): PlayerController = object : PlayerController {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow(com.flowtube.app.player.PlaybackState())
            override val state = _state
            override fun attachSurface(playerView: androidx.media3.ui.PlayerView) {}
            override fun detachSurface(playerView: androidx.media3.ui.PlayerView) {}
            override fun onLifecycleStart() {}
            override fun onLifecycleStop() {}
            override fun prepare(
                key: ContentKey,
                streamInfo: StreamInfo,
                startPositionMs: Long,
                playWhenReady: Boolean,
                initialQuality: com.flowtube.app.player.QualityOption?
            ) {}
            override fun play() {}
            override fun pause() {}
            override fun playPause() {}
            override fun seekTo(positionMs: Long) {}
            override fun seekBy(deltaMs: Long) {}
            override fun setPlaybackSpeed(speed: Float) {}
            override fun selectQuality(quality: com.flowtube.app.player.QualityOption) {}
            override fun release() {}
        }
    }

    @Test
    fun clicking_home_video_card_navigates_to_watch_screen_with_content_key() {
        val fakeService = FakeVideoService(
            trendingResponse = com.flowtube.app.core.error.AppResult.Success(listOf(summary("item999"))),
            videoHandler = { com.flowtube.app.core.error.AppResult.Success(details(it.nativeId)) },
            streamInfoHandler = { com.flowtube.app.core.error.AppResult.Success(StreamInfo(it, "Title")) }
        )
        val container = TestContainer(fakeService)

        composeTestRule.setContent {
            FlowTubeTheme {
                RootScaffold(container = container)
            }
        }

        // Wait for trending card
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_item999"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("video_card_item999").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_card_item999").performClick()

        // Verify Watch screen is shown
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("watch_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_video_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Video Details item999").assertIsDisplayed()

        // Press back
        composeTestRule.onNodeWithTag("watch_back_button").performClick()
        composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()
    }
}

