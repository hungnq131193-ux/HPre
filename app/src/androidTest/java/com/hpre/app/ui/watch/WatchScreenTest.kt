package com.hpre.app.ui.watch

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testKey = ContentKey(0, "watch_ui_test_video")

    private class FakePlayerController : PlayerController {
        val _state = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = _state

        var playPauseCalled = false
        var seekDeltaCalled: Long? = null
        var seekToPosition: Long? = null
        var seekCallCount = 0
        var speedSelected: Float? = null
        var qualitySelected: QualityOption? = null
        val attachedSurfaces = mutableListOf<androidx.media3.ui.PlayerView>()

        override fun attachSurface(playerView: androidx.media3.ui.PlayerView) {
            attachedSurfaces += playerView
        }

        override fun detachSurface(playerView: androidx.media3.ui.PlayerView) {
            attachedSurfaces -= playerView
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
            _state.value = PlaybackState(
                key = key,
                isPlaying = playWhenReady,
                playWhenReady = playWhenReady,
                durationMs = 60_000L,
                currentPositionMs = 5_000L,
                availableQualities = listOf(
                    QualityOption(1080, "1080p", false),
                    QualityOption(720, "720p", true)
                )
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
            seekCallCount++
            seekToPosition = positionMs
            _state.value = _state.value.copy(currentPositionMs = positionMs)
        }

        override fun seekBy(deltaMs: Long) {
            seekDeltaCalled = deltaMs
        }

        override fun setPlaybackSpeed(speed: Float) {
            speedSelected = speed
        }

        override fun selectQuality(quality: QualityOption) {
            qualitySelected = quality
        }

        override fun release() {}
    }

    private fun testDetails(key: ContentKey) = VideoDetails(
        key = key,
        title = "HPre Video Title",
        canonicalUrl = "https://hpre.test/watch?v=${key.nativeId}",
        description = "This is a detailed description of the video content.",
        channelKey = ContentKey(key.serviceId, "channel_1"),
        channelName = "HPre Creator",
        channelAvatarUrl = null,
        subscriberCountText = "120K subscribers",
        thumbnailUrl = null,
        durationSeconds = 120,
        viewCount = 15000,
        likeCount = 340,
        publishedTimestamp = 1600000000L
    )

    @Test
    fun watch_screen_displays_player_container_and_metadata_when_loaded() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        // Wait for metadata
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("player_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_back_button").assertIsDisplayed()
        composeTestRule.runOnIdle {
            val playerView = fakePlayer.attachedSurfaces.lastOrNull()
            assertNotNull("PlayerSurface must attach a real PlayerView", playerView)
            assertTrue("Attached PlayerView must be in the activity window", playerView!!.isAttachedToWindow)
            assertTrue("Attached PlayerView must have a measured width", playerView.width > 0)
            assertTrue("Attached PlayerView must have a measured height", playerView.height > 0)
            assertTrue("Attached PlayerView must be visible to the user", playerView.isShown)
        }
        composeTestRule.onNodeWithTag("watch_video_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("HPre Video Title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_channel_name").assertIsDisplayed()
        composeTestRule.onNodeWithText("HPre Creator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_action_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_channel_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_share_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_description_container").assertIsDisplayed()
    }

    @Test
    fun phone_width_layout_keeps_primary_content_in_bounds_and_back_works() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = {
                AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8"))
            },
            relatedHandler = { AppResult.Success(emptyList()) }
        )
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = FakePlayerController(),
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )
        var backClicked = false

        composeTestRule.setContent {
            HPreTheme {
                Box(Modifier.width(360.dp)) {
                    WatchScreen(
                        contentKey = testKey,
                        viewModel = viewModel,
                        onNavigateBack = { backClicked = true }
                    )
                }
            }
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("watch_channel_card"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("watch_back_button").performClick()
        composeTestRule.runOnIdle { assertTrue(backClicked) }

        val screenBounds = composeTestRule.onNodeWithTag("watch_screen").fetchSemanticsNode().boundsInRoot
        listOf(
            "player_container",
            "watch_back_button",
            "watch_action_row",
            "watch_channel_card",
            "comments_section",
            "related_videos_section"
        ).forEach { tag ->
            val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag starts outside the phone width: $bounds", bounds.left >= screenBounds.left)
            assertTrue("$tag ends outside the phone width: $bounds", bounds.right <= screenBounds.right)
        }

        val commentsTop = composeTestRule.onNodeWithTag("comments_section")
            .fetchSemanticsNode().boundsInRoot.top
        val relatedTop = composeTestRule.onNodeWithTag("related_videos_section")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("Comments must appear before related videos", commentsTop < relatedTop)
    }

    @Test
    fun watch_screen_shows_error_pane_and_retries_on_stream_failure() {
        var failStream = true
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = {
                if (failStream) {
                    AppResult.Failure(AppError.NetworkError)
                } else {
                    AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8"))
                }
            }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        // Wait for Error Pane
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("error_pane"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("error_pane").assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_retry_button").assertIsDisplayed()

        // Click retry
        failStream = false
        composeTestRule.onNodeWithTag("error_retry_button").performClick()

        // Wait for success metadata
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("HPre Video Title").assertIsDisplayed()
    }

    @Test
    fun watch_screen_controls_dispatch_play_pause_and_seek() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        // Wait for player container to be displayed
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_container"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Controls expose localized accessibility descriptions.
        composeTestRule.onNodeWithContentDescription("Tạm dừng").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tua lùi 10 giây").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tua tới 10 giây").assertIsDisplayed()

        // Test play/pause toggle dispatch
        composeTestRule.onNodeWithTag("control_play_pause").assertIsDisplayed()
        composeTestRule.onNodeWithTag("control_play_pause").performClick()
        assertTrue("playPause should be dispatched to controller", fakePlayer.playPauseCalled)

        // Test rewind 10s dispatch
        composeTestRule.onNodeWithTag("control_rewind_10").assertIsDisplayed()
        composeTestRule.onNodeWithTag("control_rewind_10").performClick()
        assertEquals(-10_000L, fakePlayer.seekDeltaCalled)

        // Test fast forward 10s dispatch
        composeTestRule.onNodeWithTag("control_forward_10").assertIsDisplayed()
        composeTestRule.onNodeWithTag("control_forward_10").performClick()
        assertEquals(10_000L, fakePlayer.seekDeltaCalled)

        // Test speed menu selection dispatch
        composeTestRule.onNodeWithTag("control_speed_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("control_speed_button").performClick()
        composeTestRule.onNodeWithTag("speed_option_1.5").assertIsDisplayed()
        composeTestRule.onNodeWithTag("speed_option_1.5").performClick()
        assertEquals(1.5f, fakePlayer.speedSelected ?: 0f, 0.01f)

        // Test quality menu selection dispatch
        composeTestRule.onNodeWithTag("control_quality_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("control_quality_button").performClick()
        composeTestRule.onNodeWithTag("quality_option_720").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quality_option_720").performClick()
        assertEquals(720, fakePlayer.qualitySelected?.height)
    }

    @Test
    fun watch_screen_shows_real_catalog_sections_without_fake_action_buttons() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        // Wait for metadata
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Task 10 replaces the old placeholder with independently stateful sections.
        composeTestRule.onNodeWithTag("related_videos_section").fetchSemanticsNode()
        composeTestRule.onNodeWithTag("comments_section").fetchSemanticsNode()

        // Verify there are no fake subscribe/like/download buttons
        composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("Subscribe", substring = true)).assertCountEquals(0)
        composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("Download", substring = true)).assertCountEquals(0)
        composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("Like", substring = true)).assertCountEquals(0)
    }

    @Test
    fun watch_screen_hides_share_button_when_canonical_url_is_invalid() {
        val invalidDetails = testDetails(testKey).copy(canonicalUrl = "file:///sdcard/malicious")
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(invalidDetails) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Share button must be hidden for invalid URL schemes
        composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_share_button"))
            .assertCountEquals(0)
    }

    @Test
    fun watch_screen_fullscreen_toggle_renders_fullscreen_layout() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        var enterFullscreenCount = 0
        var exitFullscreenCount = 0
        val fakeHostHandler = object : FullscreenHostHandler {
            override fun enterFullscreen() {
                enterFullscreenCount++
            }

            override fun exitFullscreen() {
                exitFullscreenCount++
            }

            override fun onConfigurationChange() {}
        }

        val fakeFactory = FullscreenHostHandlerFactory { _, _ -> fakeHostHandler }

        composeTestRule.setContent {
            HPreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalFullscreenHostHandlerFactory provides fakeFactory
                ) {
                    WatchScreen(
                        contentKey = testKey,
                        viewModel = viewModel,
                        onNavigateBack = {}
                    )
                }
            }
        }

        // Wait for metadata to be displayed (initial portrait load)
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Initial portrait state should have exact 0 enter and 0 exit calls
        assertEquals("Initial state should not enter fullscreen", 0, enterFullscreenCount)
        assertEquals("Initial state should not exit fullscreen", 0, exitFullscreenCount)

        // Click fullscreen toggle button
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()

        // Verify fullscreen container is displayed
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("watch_screen_fullscreen").assertIsDisplayed()
        assertEquals("enterFullscreen should have been called exactly once", 1, enterFullscreenCount)
        assertEquals("exitFullscreen should not have been called on enter", 0, exitFullscreenCount)

        // Click exit fullscreen toggle button
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()

        // Verify portrait container is restored
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("watch_screen").assertIsDisplayed()
        assertEquals("enterFullscreen count should remain exactly 1", 1, enterFullscreenCount)
        assertEquals("exitFullscreen should have been called exactly once (no duplicate exit)", 1, exitFullscreenCount)
    }

    @Test
    fun watch_screen_back_press_in_fullscreen_exits_fullscreen_once() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        var enterFullscreenCount = 0
        var exitFullscreenCount = 0
        val fakeHostHandler = object : FullscreenHostHandler {
            override fun enterFullscreen() {
                enterFullscreenCount++
            }

            override fun exitFullscreen() {
                exitFullscreenCount++
            }

            override fun onConfigurationChange() {}
        }

        val fakeFactory = FullscreenHostHandlerFactory { _, _ -> fakeHostHandler }

        composeTestRule.setContent {
            HPreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalFullscreenHostHandlerFactory provides fakeFactory
                ) {
                    WatchScreen(
                        contentKey = testKey,
                        viewModel = viewModel,
                        onNavigateBack = {}
                    )
                }
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Enter fullscreen
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("enterFullscreen should be called exactly once", 1, enterFullscreenCount)
        assertEquals("exitFullscreen should not be called yet", 0, exitFullscreenCount)

        // Simulate back via viewModel.setFullscreen(false) as BackHandler does
        viewModel.setFullscreen(false)
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("enterFullscreen should remain 1", 1, enterFullscreenCount)
        assertEquals("exitFullscreen should be called exactly once on back exit (no duplicate exit)", 1, exitFullscreenCount)
    }

    @Test
    fun watch_screen_explicit_exit_does_not_trigger_duplicate_exit_on_subsequent_dispose() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        var enterFullscreenCount = 0
        var exitFullscreenCount = 0
        val fakeHostHandler = object : FullscreenHostHandler {
            override fun enterFullscreen() {
                enterFullscreenCount++
            }

            override fun exitFullscreen() {
                exitFullscreenCount++
            }

            override fun onConfigurationChange() {}
        }

        val fakeFactory = FullscreenHostHandlerFactory { _, _ -> fakeHostHandler }
        val showWatchScreen = androidx.compose.runtime.mutableStateOf(true)

        composeTestRule.setContent {
            HPreTheme {
                if (showWatchScreen.value) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalFullscreenHostHandlerFactory provides fakeFactory
                    ) {
                        WatchScreen(
                            contentKey = testKey,
                            viewModel = viewModel,
                            onNavigateBack = {}
                        )
                    }
                }
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 1. Enter fullscreen
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, enterFullscreenCount)
        assertEquals(0, exitFullscreenCount)

        // 2. Explicitly exit fullscreen via toggle button
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, enterFullscreenCount)
        assertEquals(1, exitFullscreenCount)

        // 3. Now dispose WatchScreen entirely (e.g., navigating away / removing from composition)
        showWatchScreen.value = false
        composeTestRule.waitForIdle()

        // Disposal must not trigger a duplicate exitFullscreen call because fullscreen was already explicitly exited
        assertEquals("enterFullscreen count should remain 1", 1, enterFullscreenCount)
        assertEquals("exitFullscreen must not run again on dispose after explicit exit", 1, exitFullscreenCount)
    }

    @Test
    fun watch_screen_destruction_while_fullscreen_active_triggers_exit_on_dispose() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        var enterFullscreenCount = 0
        var exitFullscreenCount = 0
        val fakeHostHandler = object : FullscreenHostHandler {
            override fun enterFullscreen() {
                enterFullscreenCount++
            }

            override fun exitFullscreen() {
                exitFullscreenCount++
            }

            override fun onConfigurationChange() {}
        }

        val fakeFactory = FullscreenHostHandlerFactory { _, _ -> fakeHostHandler }
        val showWatchScreen = androidx.compose.runtime.mutableStateOf(true)

        composeTestRule.setContent {
            HPreTheme {
                if (showWatchScreen.value) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalFullscreenHostHandlerFactory provides fakeFactory
                    ) {
                        WatchScreen(
                            contentKey = testKey,
                            viewModel = viewModel,
                            onNavigateBack = {}
                        )
                    }
                }
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Enter fullscreen
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, enterFullscreenCount)
        assertEquals(0, exitFullscreenCount)

        // Dispose WatchScreen while fullscreen remains active (non-config destruction, e.g. pop/remove)
        showWatchScreen.value = false
        composeTestRule.waitForIdle()

        // onDispose should clean up destruction and trigger exitFullscreen exactly once
        assertEquals("enterFullscreen count should remain 1", 1, enterFullscreenCount)
        assertEquals("exitFullscreen should be called exactly once during destruction while fullscreen is active", 1, exitFullscreenCount)
    }

    @Test
    fun seek_slider_disabled_when_duration_zero() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_progress_slider"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Set duration to 0
        fakePlayer._state.value = fakePlayer._state.value.copy(durationMs = 0L)
        composeTestRule.waitForIdle()

        // Verify slider exists and is not enabled when duration <= 0
        composeTestRule.onNodeWithTag("player_progress_slider").assertIsNotEnabled()

        // Set duration to positive 60s
        fakePlayer._state.value = fakePlayer._state.value.copy(durationMs = 60_000L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_progress_slider").assertIsEnabled()
        composeTestRule.onNodeWithTag("player_progress_slider").assertHeightIsAtLeast(48.dp)

        fakePlayer.seekToPosition = null
        composeTestRule.onNodeWithTag("player_progress_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(30_000f))
            }
        assertEquals(30_000L, fakePlayer.seekToPosition)
    }

    @Test
    fun slider_drag_updates_display_without_resetting_and_seeks_on_finish() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_progress_slider"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        fakePlayer.seekToPosition = null
        fakePlayer.seekCallCount = 0
        fakePlayer._state.value = fakePlayer._state.value.copy(
            durationMs = 100_000L,
            currentPositionMs = 10_000L
        )
        composeTestRule.waitForIdle()

        // Perform actual touch input gesture (down -> move -> up) across slider bounds
        val sliderNode = composeTestRule.onNodeWithTag("player_progress_slider")
        sliderNode.performTouchInput {
            // Drag from left (approx 10%) to right (approx 75% of slider width)
            down(centerLeft + androidx.compose.ui.geometry.Offset(width * 0.1f, 0f))
            moveTo(centerLeft + androidx.compose.ui.geometry.Offset(width * 0.75f, 0f))
        }

        // Simulate progress tick during active drag to verify drag value isn't reset by incoming playback state
        fakePlayer._state.value = fakePlayer._state.value.copy(currentPositionMs = 15_000L)
        composeTestRule.waitForIdle()

        // Verify seekTo was not called while still holding / dragging
        assertEquals("seekTo must not be dispatched before touch release", 0, fakePlayer.seekCallCount)

        // Complete the touch gesture (up)
        sliderNode.performTouchInput {
            up()
        }
        composeTestRule.waitForIdle()

        // Assert exactly one final seek call with tolerance
        assertEquals("seekTo should be called exactly once upon slider drag release", 1, fakePlayer.seekCallCount)
        assertNotNull(fakePlayer.seekToPosition)
        val requestedMs = fakePlayer.seekToPosition ?: 0L
        val expectedTargetMs = 75_000L // 75% of 100_000L
        assertTrue(
            "Final requested ms ($requestedMs) must be close to dragged endpoint (~$expectedTargetMs ms, tolerance +-5000ms)",
            kotlin.math.abs(requestedMs - expectedTargetMs) <= 5000L
        )
    }

    @Test
    fun default_share_launcher_handles_intent_launcher_abstraction_and_asserts_exact_action_and_text() {
        var capturedIntent: Intent? = null
        val testIntentLauncher = IntentLauncher { intent ->
            capturedIntent = intent
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaultShareLauncher = DefaultShareLauncher(context, intentLauncher = testIntentLauncher)

        val testTitle = "Sample Video Title"
        val testUrl = "https://hpre.test/watch?v=sample123"

        defaultShareLauncher.launchShare(testTitle, testUrl)

        assertNotNull("Intent should have been launched", capturedIntent)
        assertEquals(Intent.ACTION_CHOOSER, capturedIntent?.action)

        val targetIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            capturedIntent?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            capturedIntent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }
        assertNotNull("Target send intent should be present in chooser", targetIntent)
        assertEquals(Intent.ACTION_SEND, targetIntent?.action)
        assertEquals("text/plain", targetIntent?.type)
        assertEquals(testTitle, targetIntent?.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(testUrl, targetIntent?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun default_share_launcher_with_invalid_url_produces_zero_intent_launches() {
        var intentLaunchCount = 0
        val testIntentLauncher = IntentLauncher {
            intentLaunchCount++
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaultShareLauncher = DefaultShareLauncher(context, intentLauncher = testIntentLauncher)

        defaultShareLauncher.launchShare("Malicious", "javascript:alert(1)")
        defaultShareLauncher.launchShare("File URL", "file:///data/data/com.hpre.app/databases/app.db")
        defaultShareLauncher.launchShare("Empty", "")

        assertEquals("Invalid canonical URLs must produce zero intent launches", 0, intentLaunchCount)
    }

    @Test
    fun share_launcher_end_to_end_test_valid() {
        var shareInvocationCount = 0
        var sharedTitle: String? = null
        var sharedUrl: String? = null
        val testShareLauncher = ShareLauncher { title, canonicalUrl ->
            shareInvocationCount++
            sharedTitle = title
            sharedUrl = canonicalUrl
        }

        val validDetails = testDetails(testKey)
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(validDetails) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalShareLauncher provides testShareLauncher
                ) {
                    WatchScreen(
                        contentKey = testKey,
                        viewModel = viewModel,
                        onNavigateBack = {}
                    )
                }
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_share_button"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Valid share button click invokes injected shareLauncher with canonical url and title
        composeTestRule.onNodeWithTag("watch_share_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Valid share click must invoke ShareLauncher exactly once", 1, shareInvocationCount)
        assertEquals(validDetails.title, sharedTitle)
        assertEquals(validDetails.canonicalUrl, sharedUrl)
    }

    @Test
    fun share_launcher_end_to_end_test_invalid_url_records_zero_invocations() {
        var invalidShareCount = 0
        val invalidTrackingLauncher = ShareLauncher { _, _ ->
            invalidShareCount++
        }
        val invalidDetails = testDetails(testKey).copy(canonicalUrl = "javascript:alert(1)")
        val invalidFakeService = FakeVideoService(
            videoHandler = { AppResult.Success(invalidDetails) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) }
        )
        val fakePlayer = FakePlayerController()
        val invalidViewModel = WatchViewModel(
            videoService = invalidFakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalShareLauncher provides invalidTrackingLauncher
                ) {
                    WatchScreen(
                        contentKey = testKey,
                        viewModel = invalidViewModel,
                        onNavigateBack = {}
                    )
                }
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Assert share action node is absent for invalid URL
        composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("watch_share_button"))
            .assertCountEquals(0)

        // Assert injected launcher invocation count is zero
        assertEquals(
            "Invalid canonical URL must produce zero ShareLauncher invocations",
            0,
            invalidShareCount
        )
    }
}
