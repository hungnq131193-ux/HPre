package com.flowtube.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.core.designsystem.FlowTubeTheme
import com.flowtube.app.di.AppContainer
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.repository.CatalogRepository
import com.flowtube.app.repository.VideoService
import com.flowtube.app.testing.FakeVideoService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    class TestContainer(val fakeService: FakeVideoService) : AppContainer {
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
        private val playerController = object : com.flowtube.app.player.PlayerController {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow(com.flowtube.app.player.PlaybackState())
            override val state = _state
            override fun attachSurface(playerView: androidx.media3.ui.PlayerView) {}
            override fun detachSurface(playerView: androidx.media3.ui.PlayerView) {}
            override fun onLifecycleStart() {}
            override fun onLifecycleStop() {}
            override fun prepare(
                key: ContentKey,
                streamInfo: com.flowtube.app.model.StreamInfo,
                startPositionMs: Long,
                playWhenReady: Boolean,
                initialQuality: com.flowtube.app.player.QualityOption?
            ) {
                _state.value = com.flowtube.app.player.PlaybackState(
                    key = key,
                    title = streamInfo.title,
                    isPlaying = playWhenReady,
                    playWhenReady = playWhenReady,
                    currentPositionMs = startPositionMs
                )
            }
            override fun play() {}
            override fun pause() {}
            override fun playPause() {}
            override fun seekTo(positionMs: Long) {}
            override fun seekBy(deltaMs: Long) {}
            override fun setPlaybackSpeed(speed: Float) {}
            override fun selectQuality(quality: com.flowtube.app.player.QualityOption) {}
            override fun release() {}
        }
        override fun createPlayerController(): com.flowtube.app.player.PlayerController = playerController
    }

    @Test
    fun bottom_nav_switches_between_home_and_tabs() {
        val fakeService = FakeVideoService()
        val container = TestContainer(fakeService)

        composeTestRule.setContent {
            FlowTubeTheme {
                RootScaffold(container = container)
            }
        }

        // Home screen is visible by default
        composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()

        // Switch to Shorts tab
        composeTestRule.onNodeWithTag("bottom_nav_shorts").performClick()
        composeTestRule.onNodeWithTag("shorts_unavailable").assertIsDisplayed()

        // Switch to Subscriptions tab
        composeTestRule.onNodeWithTag("bottom_nav_subscriptions").performClick()
        composeTestRule.onNodeWithTag("subscriptions_screen").assertIsDisplayed()

        // Switch to Library tab
        composeTestRule.onNodeWithTag("bottom_nav_library").performClick()
        composeTestRule.onNodeWithTag("library_screen").assertIsDisplayed()

        // Switch back to Home
        composeTestRule.onNodeWithTag("bottom_nav_home").performClick()
        composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test
    fun top_bar_actions_navigate_to_search_and_settings() {
        val fakeService = FakeVideoService()
        val container = TestContainer(fakeService)

        composeTestRule.setContent {
            FlowTubeTheme {
                RootScaffold(container = container)
            }
        }

        // Click search icon in TopBar
        composeTestRule.onNodeWithTag("top_bar_search_button").performClick()
        composeTestRule.onNodeWithTag("search_screen").assertIsDisplayed()

        // Press back from Search
        composeTestRule.onNodeWithTag("search_back_button").performClick()
        composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()

        // Click settings icon
        composeTestRule.onNodeWithTag("top_bar_settings_button").performClick()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
    }

    @Test
    fun watch_navigation_encodes_and_decodes_complex_keys_and_handles_invalid_ids() {
        val fakeService = FakeVideoService()
        val container = TestContainer(fakeService)

        var navController: androidx.navigation.NavHostController? = null

        composeTestRule.setContent {
            FlowTubeTheme {
                val hostNavController = androidx.navigation.compose.rememberNavController()
                navController = hostNavController
                FlowTubeNavHost(navController = hostNavController, container = container)
            }
        }

        // Navigate with special characters nativeId
        val specialKey = ContentKey(0, "video_with_query_and_space")
        composeTestRule.runOnUiThread {
            navController?.navigate(Screen.Watch.createRoute(specialKey))
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("watch_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mock Video video_with_query_and_space").assertIsDisplayed()

        // Navigate directly with blank nativeId
        composeTestRule.runOnUiThread {
            navController?.navigate("watch/0/%20")
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("invalid_watch_screen").assertIsDisplayed()
    }

    @Test
    fun watch_navigation_roundtrip_cases_with_slash_literal_percent_plus_hash_and_unicode() {
        val fakeService = FakeVideoService()
        val container = TestContainer(fakeService)

        var navController: androidx.navigation.NavHostController? = null

        composeTestRule.setContent {
            FlowTubeTheme {
                val hostNavController = androidx.navigation.compose.rememberNavController()
                navController = hostNavController
                FlowTubeNavHost(navController = hostNavController, container = container)
            }
        }

        val testCases = listOf(
            "id/with/slashes",
            "100%real%",
            "search+query#tag",
            "video_tiếng_việt_日本語_🎉"
        )

        for (nativeId in testCases) {
            val key = ContentKey(0, nativeId)
            composeTestRule.runOnUiThread {
                navController?.navigate(Screen.Watch.createRoute(key))
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("watch_screen").assertIsDisplayed()
            composeTestRule.onNodeWithText("Mock Video $nativeId").assertIsDisplayed()

            composeTestRule.onNodeWithTag("watch_back_button").performClick()
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun malformed_routes_are_rejected_or_display_unavailable() {
        val fakeService = FakeVideoService()
        val container = TestContainer(fakeService)

        var navController: androidx.navigation.NavHostController? = null

        composeTestRule.setContent {
            FlowTubeTheme {
                val hostNavController = androidx.navigation.compose.rememberNavController()
                navController = hostNavController
                FlowTubeNavHost(navController = hostNavController, container = container)
            }
        }

        // Malformed percent '%ZZ' or blank is rejected or shows invalid/unavailable
        composeTestRule.runOnUiThread {
            navController?.navigate("watch/0/%20")
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("invalid_watch_screen").assertIsDisplayed()
    }

    @Test
    fun channel_and_playlist_unavailable_routes_display_truthful_unavailable_pane() {
        val fakeService = FakeVideoService()
        val container = TestContainer(fakeService)

        var navController: androidx.navigation.NavHostController? = null

        composeTestRule.setContent {
            FlowTubeTheme {
                val hostNavController = androidx.navigation.compose.rememberNavController()
                navController = hostNavController
                FlowTubeNavHost(navController = hostNavController, container = container)
            }
        }

        val chanKey = ContentKey(0, "chan_abc/123")
        composeTestRule.runOnUiThread {
            navController?.navigate(Screen.ChannelUnavailable.createRoute(chanKey))
        }

        composeTestRule.onNodeWithTag("channel_unavailable_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Channel (chan_abc/123) Unavailable").assertIsDisplayed()

        val playKey = ContentKey(0, "playlist_xyz")
        composeTestRule.runOnUiThread {
            navController?.navigate(Screen.PlaylistUnavailable.createRoute(playKey))
        }

        composeTestRule.onNodeWithTag("playlist_unavailable_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Playlist (playlist_xyz) Unavailable").assertIsDisplayed()
    }
}
