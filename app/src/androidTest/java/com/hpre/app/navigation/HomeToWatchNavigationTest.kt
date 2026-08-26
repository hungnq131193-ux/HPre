package com.hpre.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.di.AppContainer
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlayerController
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.VideoService
import com.hpre.app.testing.FakeVideoService
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
        override val mediaSourceFactory: com.hpre.app.player.MediaSourceFactory by lazy {
            com.hpre.app.player.MediaSourceFactory(
                dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext()
                )
            )
        }
        override val fullscreenHostHandlerFactory: com.hpre.app.ui.watch.FullscreenHostHandlerFactory =
            com.hpre.app.ui.watch.FullscreenHostHandlerFactory { act, savedState ->
                com.hpre.app.ui.watch.DefaultFullscreenHostHandler(act, savedState)
            }
        override val database: com.hpre.app.database.HPreDatabase by lazy {
            androidx.room.Room.inMemoryDatabaseBuilder(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                com.hpre.app.database.HPreDatabase::class.java
            ).allowMainThreadQueries().build()
        }
        override val historyRepository: com.hpre.app.repository.HistoryRepository by lazy {
            com.hpre.app.repository.DefaultHistoryRepository(database.historyDao(), playbackPreferences)
        }
        override val subscriptionRepository: com.hpre.app.repository.SubscriptionRepository by lazy {
            com.hpre.app.repository.DefaultSubscriptionRepository(database.subscriptionDao())
        }
        override val playlistRepository: com.hpre.app.repository.PlaylistRepository by lazy {
            com.hpre.app.repository.DefaultPlaylistRepository(database.playlistDao())
        }
        override val searchHistoryRepository: com.hpre.app.repository.SearchHistoryRepository by lazy {
            com.hpre.app.repository.DefaultSearchHistoryRepository(database.searchHistoryDao())
        }
        override val playbackPreferences: com.hpre.app.settings.PlaybackPreferences by lazy {
            settingsRepository
        }
        override val settingsRepository: com.hpre.app.settings.SettingsRepository = object : com.hpre.app.settings.SettingsRepository {
            private val _flow = kotlinx.coroutines.flow.MutableStateFlow(true)
            private val _pipFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
            private val _historyFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
            private val _settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(com.hpre.app.settings.AppSettings())

            override val settings: kotlinx.coroutines.flow.Flow<com.hpre.app.settings.AppSettings> = _settingsFlow
            override val isBackgroundPlaybackEnabled: kotlinx.coroutines.flow.Flow<Boolean> = _flow
            override val isPipEnabled: kotlinx.coroutines.flow.Flow<Boolean> = _pipFlow
            override val isHistoryEnabled: kotlinx.coroutines.flow.Flow<Boolean> = _historyFlow

            override suspend fun setTheme(theme: com.hpre.app.settings.AppTheme) {
                _settingsFlow.value = _settingsFlow.value.copy(theme = theme)
            }
            override suspend fun setWifiQuality(quality: com.hpre.app.settings.QualityPreferenceSetting) {
                _settingsFlow.value = _settingsFlow.value.copy(wifiQuality = quality)
            }
            override suspend fun setMobileQuality(quality: com.hpre.app.settings.QualityPreferenceSetting) {
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
            private val _state = kotlinx.coroutines.flow.MutableStateFlow(com.hpre.app.player.PlaybackState())
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
                initialQuality: com.hpre.app.player.QualityOption?
            ) {}
            override fun play() {}
            override fun pause() {}
            override fun playPause() {}
            override fun seekTo(positionMs: Long) {}
            override fun seekBy(deltaMs: Long) {}
            override fun setPlaybackSpeed(speed: Float) {}
            override fun selectQuality(quality: com.hpre.app.player.QualityOption) {}
            override fun release() {}
        }
    }

    @Test
    fun clicking_home_video_card_navigates_to_watch_screen_with_content_key() {
        val fakeService = FakeVideoService(
            trendingResponse = com.hpre.app.core.error.AppResult.Success(listOf(summary("item999"))),
            videoHandler = { com.hpre.app.core.error.AppResult.Success(details(it.nativeId)) },
            streamInfoHandler = { com.hpre.app.core.error.AppResult.Success(StreamInfo(it, "Title")) }
        )
        val container = TestContainer(fakeService)

        composeTestRule.setContent {
            HPreTheme {
                RootScaffold(container = container)
            }
        }

        // Wait for trending card
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_item999"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("video_card_item999").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_filter_chips").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_filter_chip_0").assertIsSelected()
        composeTestRule.onNodeWithText("Tất cả").assertIsDisplayed()
        composeTestRule.onNodeWithText("Âm nhạc").assertIsDisplayed()
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

    @Test
    fun selecting_home_topic_keeps_chips_visible_and_loads_real_results() {
        val topicPage = kotlinx.coroutines.CompletableDeferred<SearchPage>()
        val fakeService = FakeVideoService(
            trendingResponse = com.hpre.app.core.error.AppResult.Success(listOf(summary("all")))
        )
        fakeService.searchHandler = { query, _, _ ->
            if (query == "âm nhạc") {
                com.hpre.app.core.error.AppResult.Success(topicPage.await())
            } else {
                com.hpre.app.core.error.AppResult.Success(SearchPage(emptyList()))
            }
        }
        val container = TestContainer(fakeService)

        composeTestRule.setContent {
            HPreTheme {
                RootScaffold(container = container)
            }
        }

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_all"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("home_filter_chip_1").performClick()
        composeTestRule.onNodeWithTag("home_loading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_filter_chips").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_filter_chip_7").performScrollTo().assertIsDisplayed()

        topicPage.complete(
            SearchPage(items = listOf(SearchResultItem.VideoItem(summary("music_topic"))))
        )
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_music_topic"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("home_filter_chips").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_filter_chip_1").assertIsSelected()
        composeTestRule.onNodeWithTag("video_card_music_topic").assertIsDisplayed()
    }
}

