package com.hpre.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
        class CountingPlayerController : PlayerController {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow(com.hpre.app.player.PlaybackState())
            override val state = _state
            var prepareCount = 0

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
            ) {
                prepareCount++
                _state.value = com.hpre.app.player.PlaybackState(
                    key = key,
                    title = streamInfo.title,
                    isPlaying = playWhenReady,
                    playWhenReady = playWhenReady,
                    durationMs = 120_000L,
                    currentPositionMs = startPositionMs,
                    isReady = true
                )
            }
            override fun play() {
                _state.value = _state.value.copy(isPlaying = true)
            }
            override fun pause() {
                _state.value = _state.value.copy(isPlaying = false)
            }
            override fun playPause() {
                _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
            }
            override fun seekTo(positionMs: Long) {
                _state.value = _state.value.copy(currentPositionMs = positionMs)
            }
            override fun seekBy(deltaMs: Long) {
                _state.value = _state.value.copy(currentPositionMs = _state.value.currentPositionMs + deltaMs)
            }
            override fun setPlaybackSpeed(speed: Float) {
                _state.value = _state.value.copy(playbackSpeed = speed)
            }
            override fun selectQuality(quality: com.hpre.app.player.QualityOption) {
                _state.value = _state.value.copy(selectedQuality = quality)
            }
            override fun release() {}

            fun advancePositionForTest(positionMs: Long) {
                _state.value = _state.value.copy(currentPositionMs = positionMs)
            }
        }

        val countingPlayer = CountingPlayerController()
        override fun createPlayerController(): PlayerController = countingPlayer
    }

    @Test
    fun clicking_home_video_card_navigates_to_watch_screen_with_content_key() {
        var streamInfoCallCount = 0
        val fakeService = FakeVideoService(
            trendingResponse = com.hpre.app.core.error.AppResult.Success(listOf(summary("item999"))),
            videoHandler = { com.hpre.app.core.error.AppResult.Success(details(it.nativeId)) },
            streamInfoHandler = {
                streamInfoCallCount++
                com.hpre.app.core.error.AppResult.Success(StreamInfo(it, "Title"))
            }
        )
        val container = TestContainer(fakeService)

        var hostNavController: androidx.navigation.NavHostController? = null

        composeTestRule.setContent {
            HPreTheme {
                val nav = androidx.navigation.compose.rememberNavController()
                hostNavController = nav
                RootScaffold(container = container, navController = nav)
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

        val initialPrepareCount = container.countingPlayer.prepareCount
        val initialStreamInfoCount = streamInfoCallCount
        container.countingPlayer.advancePositionForTest(42_000L)

        repeat(3) {
            composeTestRule.runOnUiThread {
                hostNavController?.popBackStack()
            }
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("home_screen"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("mini-player", useUnmergedTree = true).assertIsDisplayed()
            val watchCountAfterBack = hostNavController?.currentBackStack?.value.orEmpty()
                .count { it.destination.route == Screen.Watch.route }
            org.junit.Assert.assertEquals(0, watchCountAfterBack)

            composeTestRule.onNodeWithTag("mini-player", useUnmergedTree = true).performClick()
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen"))
                    .fetchSemanticsNodes().isNotEmpty()
            }

            org.junit.Assert.assertEquals(initialPrepareCount, container.countingPlayer.prepareCount)
            org.junit.Assert.assertEquals(initialStreamInfoCount, streamInfoCallCount)
            org.junit.Assert.assertEquals(42_000L, container.countingPlayer.state.value.currentPositionMs)
            val watchCountAfterExpand = hostNavController?.currentBackStack?.value.orEmpty()
                .count { it.destination.route == Screen.Watch.route }
            org.junit.Assert.assertEquals(1, watchCountAfterExpand)
        }
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

    @Test
    fun swipe_down_minimize_from_home_search_channel_library_subscriptions_and_related_watch_all_reach_single_home() {
        val testVideo = summary("vid_1")
        val relatedVideo = summary("vid_related")
        val fakeService = FakeVideoService(
            trendingResponse = com.hpre.app.core.error.AppResult.Success(listOf(testVideo)),
            videoHandler = { key -> com.hpre.app.core.error.AppResult.Success(details(key.nativeId)) },
            streamInfoHandler = { key -> com.hpre.app.core.error.AppResult.Success(StreamInfo(key, "Title ${key.nativeId}")) },
            relatedHandler = { com.hpre.app.core.error.AppResult.Success(listOf(relatedVideo)) },
            searchHandler = { _, _, _ -> com.hpre.app.core.error.AppResult.Success(SearchPage(listOf(SearchResultItem.VideoItem(testVideo)))) },
            channelHandler = { key ->
                com.hpre.app.core.error.AppResult.Success(
                    com.hpre.app.model.ChannelDetails(
                        channel = com.hpre.app.model.Channel(
                            key = key,
                            name = "Test Channel",
                            canonicalUrl = "https://example.test/channel/${key.nativeId}",
                            avatarUrl = null,
                            bannerUrl = null,
                            subscriberCountText = "10K",
                            description = "desc"
                        ),
                        videos = listOf(testVideo)
                    )
                )
            }
        )
        val container = TestContainer(fakeService)
        val testCoordinator = com.hpre.app.player.PlaybackUiCoordinator()
        var hostNavController: androidx.navigation.NavHostController? = null

        composeTestRule.setContent {
            HPreTheme {
                val nav = androidx.navigation.compose.rememberNavController()
                hostNavController = nav
                RootScaffold(container = container, navController = nav, coordinator = testCoordinator)
            }
        }

        fun assertSingleHomeDestination() {
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()
            // Verify exactly one Home destination in backstack
            val backQueue = hostNavController?.currentBackStack?.value ?: emptyList()
            val homeCount = backQueue.count { it.destination.route == Screen.Home.route }
            org.junit.Assert.assertEquals(1, homeCount)
            org.junit.Assert.assertEquals(com.hpre.app.player.PlayerPresentation.MINI_PLAYER, testCoordinator.state.value.presentation)
        }

        fun performSwipeMinimize() {
            composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
                swipeDown(startY = centerY - 50, endY = centerY + 300)
            }
            composeTestRule.waitForIdle()
        }

        // 1. Origin: Home -> Watch -> Swipe Minimize -> Home (Canonical Flow asserting MiniPlayer and active state)
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("video_card_vid_1")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("video_card_vid_1").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(com.hpre.app.player.PlayerPresentation.WATCH, testCoordinator.state.value.presentation)
        val initialPrepareCount = container.countingPlayer.prepareCount
        val initialIsPlaying = container.countingPlayer.state.value.isPlaying
        val initialPosition = container.countingPlayer.state.value.currentPositionMs
        org.junit.Assert.assertTrue(initialPrepareCount > 0)
        org.junit.Assert.assertTrue(initialIsPlaying)

        performSwipeMinimize()
        assertSingleHomeDestination()

        // Assert mini player is displayed on Home, active media unchanged without re-prepare
        composeTestRule.onNodeWithTag("mini-player", useUnmergedTree = true).assertIsDisplayed()
        org.junit.Assert.assertEquals(initialPrepareCount, container.countingPlayer.prepareCount)
        org.junit.Assert.assertEquals(testVideo.key, container.countingPlayer.state.value.key)
        org.junit.Assert.assertEquals(initialIsPlaying, container.countingPlayer.state.value.isPlaying)
        org.junit.Assert.assertEquals(initialPosition, container.countingPlayer.state.value.currentPositionMs)

        // 2. Stack-origin integration fixture: Search -> Watch -> Swipe Minimize -> Home
        composeTestRule.onNodeWithTag("top_bar_search_button").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("search_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.runOnUiThread {
            hostNavController?.navigate(Screen.Watch.createRoute(testVideo.key))
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(com.hpre.app.player.PlayerPresentation.WATCH, testCoordinator.state.value.presentation)
        performSwipeMinimize()
        assertSingleHomeDestination()

        // 3. Stack-origin integration fixture: Channel -> Watch -> Swipe Minimize -> Home
        composeTestRule.runOnUiThread {
            hostNavController?.navigate(Screen.Channel.createRoute(ContentKey(0, "c_vid_1")))
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("channel_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.runOnUiThread {
            hostNavController?.navigate(Screen.Watch.createRoute(testVideo.key))
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(com.hpre.app.player.PlayerPresentation.WATCH, testCoordinator.state.value.presentation)
        performSwipeMinimize()
        assertSingleHomeDestination()

        // 4. Stack-origin integration fixture: Library -> Watch -> Swipe Minimize -> Home
        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("library_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.runOnUiThread {
            hostNavController?.navigate(Screen.Watch.createRoute(testVideo.key))
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(com.hpre.app.player.PlayerPresentation.WATCH, testCoordinator.state.value.presentation)
        performSwipeMinimize()
        assertSingleHomeDestination()

        // 5. Stack-origin integration fixture: Subscriptions -> Watch -> Swipe Minimize -> Home
        composeTestRule.onNodeWithTag("bottom_nav_subscriptions").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("subscriptions_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.runOnUiThread {
            hostNavController?.navigate(Screen.Watch.createRoute(testVideo.key))
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(com.hpre.app.player.PlayerPresentation.WATCH, testCoordinator.state.value.presentation)
        performSwipeMinimize()
        assertSingleHomeDestination()

        // 6. Stack-origin integration fixture: Watch -> Related Watch -> Swipe Minimize -> Home
        composeTestRule.runOnUiThread {
            hostNavController?.navigate(Screen.Watch.createRoute(testVideo.key))
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.runOnUiThread {
            hostNavController?.navigate(Screen.Watch.createRoute(relatedVideo.key))
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(com.hpre.app.player.PlayerPresentation.WATCH, testCoordinator.state.value.presentation)
        performSwipeMinimize()
        assertSingleHomeDestination()
    }
}

