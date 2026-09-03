package com.hpre.app.ui.watch

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlaybackProgress
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.player.toProgress
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

    @Test
    fun comments_section_and_long_comment_can_expand_and_collapse() {
        var expanded by androidx.compose.runtime.mutableStateOf(false)
        val comment = com.hpre.app.model.Comment("fold", "A", null, null,
            (1..6).joinToString("\n") { "Comment line $it" }, null, null)
        composeTestRule.setContent {
            HPreTheme {
                WatchMetadataContent(
                    details = testDetails(testKey),
                    commentsState = com.hpre.app.ui.common.AsyncState.Content(com.hpre.app.model.CommentPage(listOf(comment))),
                    commentsExpanded = expanded,
                    onCommentsExpandedChange = { expanded = it }
                )
            }
        }
        composeTestRule.onNodeWithTag("watch_lazy_column").performScrollToNode(hasTestTag("comments_section"))
        composeTestRule.onNodeWithTag("comment_fold").assertDoesNotExist()
        composeTestRule.onNodeWithTag("comments_section").performClick()
        composeTestRule.onNodeWithTag("watch_lazy_column").performScrollToNode(hasTestTag("comment_fold"))
        val collapsed = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule.onNodeWithTag("comment_body_fold").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(collapsed) }
        assertEquals(4, collapsed.single().lineCount)
        composeTestRule.onNodeWithTag("comment_toggle_fold").performClick()
        val opened = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule.onNodeWithTag("comment_body_fold").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(opened) }
        assertEquals(6, opened.single().lineCount)
        composeTestRule.onNodeWithTag("comment_toggle_fold").performClick()
        composeTestRule.onNodeWithTag("watch_lazy_column").performScrollToNode(hasTestTag("comments_section"))
        composeTestRule.onNodeWithTag("comments_section").performClick()
        composeTestRule.onNodeWithTag("comment_fold").assertDoesNotExist()
    }

    private class FakePlayerController(
        private val preparedPlaying: Boolean? = null
    ) : PlayerController {
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
                isPlaying = preparedPlaying ?: playWhenReady,
                playWhenReady = preparedPlaying ?: playWhenReady,
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
        override suspend fun readProgress(): PlaybackProgress = _state.value.toProgress()
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
        composeTestRule.onNodeWithTag("watch_back_button").assertDoesNotExist()
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
    fun phone_width_layout_keeps_primary_content_in_bounds_and_portrait_back_removed() {
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

        composeTestRule.onNodeWithTag("watch_back_button").assertDoesNotExist()

        val screenBounds = composeTestRule.onNodeWithTag("watch_screen").fetchSemanticsNode().boundsInRoot
        listOf(
            "player_container",
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
        val fakePlayer = FakePlayerController(preparedPlaying = false)
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

        // Wait for the asynchronous prepare to publish the controls. The paused
        // fixture keeps them visible without racing the production auto-hide.
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("control_play_pause"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Controls expose localized accessibility descriptions.
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeTestRule.onNodeWithContentDescription(context.getString(com.hpre.app.R.string.action_play)).assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(com.hpre.app.R.string.action_rewind_10)).assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(com.hpre.app.R.string.action_forward_10)).assertExists()

        // Test play/pause toggle dispatch
        composeTestRule.onNodeWithTag("control_play_pause").assertExists()
        composeTestRule.onNodeWithTag("control_play_pause").performClick()
        assertTrue("playPause should be dispatched to controller", fakePlayer.playPauseCalled)

        // Test rewind 10s dispatch
        composeTestRule.onNodeWithTag("control_rewind_10").assertExists()
        composeTestRule.onNodeWithTag("control_rewind_10").performClick()
        assertEquals(-10_000L, fakePlayer.seekDeltaCalled)

        // Test fast forward 10s dispatch
        composeTestRule.onNodeWithTag("control_forward_10").assertExists()
        composeTestRule.onNodeWithTag("control_forward_10").performClick()
        assertEquals(10_000L, fakePlayer.seekDeltaCalled)

        // Test speed menu selection dispatch
        composeTestRule.onNodeWithTag("control_speed_button").assertExists()
        composeTestRule.onNodeWithTag("control_speed_button").performClick()
        composeTestRule.onNodeWithTag("speed_option_1.5").assertExists()
        composeTestRule.onNodeWithTag("speed_option_1.5").performClick()
        assertEquals(1.5f, fakePlayer.speedSelected ?: 0f, 0.01f)

        // Test quality menu selection dispatch
        composeTestRule.onNodeWithTag("control_quality_button").assertExists()
        composeTestRule.onNodeWithTag("control_quality_button").performClick()
        composeTestRule.onNodeWithTag("quality_option_720").assertExists()
        composeTestRule.onNodeWithTag("quality_option_720").performClick()
        assertEquals(720, fakePlayer.qualitySelected?.height)
    }

    @Test
    fun player_controls_overlay_polls_progress_while_visible_and_updates_on_seek() {
        var readCount = 0
        var currentPosition = 5_000L
        val testDuration = 60_000L
        var seekedTo: Long? = null

        composeTestRule.setContent {
            HPreTheme {
                PlayerControlsOverlay(
                    playbackState = PlaybackState(
                        isPlaying = true,
                        durationMs = testDuration
                    ),
                    isFullscreen = false,
                    readProgress = {
                        readCount++
                        PlaybackProgress(positionMs = currentPosition, durationMs = testDuration)
                    },
                    onPlayPause = {},
                    onSeekBy = {},
                    onSeekTo = { pos -> seekedTo = pos },
                    onSpeedSelected = {},
                    onQualitySelected = {},
                    onToggleFullscreen = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals("Initial immediate read", 1, readCount)

        // Advance 250ms -> should not read a second time yet
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        assertEquals("No second read before 500ms", 1, readCount)

        // Advance another 300ms (total 550ms) -> should read second time
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()
        assertEquals("Second read at 500ms", 2, readCount)

        // Perform slider seek action
        composeTestRule.onNodeWithTag("player_progress_slider")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {
                it(30_000f)
            }
        composeTestRule.waitForIdle()
        assertEquals(30_000L, seekedTo)
        composeTestRule.onNodeWithTag("player_time_text")
            .assertTextEquals("0:30 / 1:00")
    }

    @Test
    fun fullscreen_resize_menu_exposes_selected_mode_and_dispatches_zoom() {
        var selectedMode: FullScreenResizeMode? = null
        composeTestRule.setContent {
            HPreTheme {
                PlayerControlsOverlay(
                    playbackState = PlaybackState(),
                    isFullscreen = true,
                    resizeMode = FullScreenResizeMode.FIT,
                    onResizeModeSelected = { selectedMode = it },
                    onPlayPause = {},
                    onSeekBy = {},
                    onSeekTo = {},
                    onSpeedSelected = {},
                    onQualitySelected = {},
                    onToggleFullscreen = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("control_resize_mode_button").performClick()
        composeTestRule.onNodeWithTag("resize_mode_option_fit").assertIsSelected()
        composeTestRule.onNodeWithTag("resize_mode_option_fill").performClick()
        composeTestRule.runOnIdle {
            assertEquals(FullScreenResizeMode.FILL, selectedMode)
        }
    }

    @Test
    fun player_controls_overlay_duration_resolution_disables_slider_when_structural_is_zero() {
        composeTestRule.setContent {
            HPreTheme {
                PlayerControlsOverlay(
                    playbackState = PlaybackState(
                        isPlaying = true,
                        durationMs = 0L
                    ),
                    isFullscreen = false,
                    readProgress = {
                        PlaybackProgress(positionMs = 5_000L, durationMs = 60_000L)
                    },
                    onPlayPause = {},
                    onSeekBy = {},
                    onSeekTo = {},
                    onSpeedSelected = {},
                    onQualitySelected = {},
                    onToggleFullscreen = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        // Slider is disabled when structural duration is 0L
        composeTestRule.onNodeWithTag("player_progress_slider").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("player_time_text").assertTextEquals("0:00 / 0:00")
    }

    @Test
    fun player_controls_overlay_polls_while_paused_and_recovers_from_exceptions() {
        var readCount = 0
        var shouldThrow = false

        composeTestRule.setContent {
            HPreTheme {
                PlayerControlsOverlay(
                    playbackState = PlaybackState(
                        isPlaying = false,
                        durationMs = 60_000L
                    ),
                    isFullscreen = false,
                    readProgress = {
                        readCount++
                        if (shouldThrow) {
                            throw IllegalStateException("Transient read error")
                        }
                        PlaybackProgress(positionMs = 10_000L, durationMs = 60_000L)
                    },
                    onPlayPause = {},
                    onSeekBy = {},
                    onSeekTo = {},
                    onSpeedSelected = {},
                    onQualitySelected = {},
                    onToggleFullscreen = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals("Paused controls still read immediately", 1, readCount)

        // Polling continues while paused and controls visible
        composeTestRule.mainClock.advanceTimeBy(550L)
        composeTestRule.waitForIdle()
        assertEquals("Paused controls poll periodically", 2, readCount)

        // Non-cancellation exception does not terminate polling loop
        shouldThrow = true
        composeTestRule.mainClock.advanceTimeBy(550L)
        composeTestRule.waitForIdle()
        assertEquals("Polling loop attempts read despite exception", 3, readCount)

        // Recovers on next tick
        shouldThrow = false
        composeTestRule.mainClock.advanceTimeBy(550L)
        composeTestRule.waitForIdle()
        assertEquals("Polling loop continues after exception", 4, readCount)
    }

    @Test
    fun player_controls_overlay_polling_stops_when_auto_hidden() {
        var readCount = 0

        composeTestRule.setContent {
            HPreTheme {
                PlayerControlsOverlay(
                    playbackState = PlaybackState(
                        isPlaying = true,
                        durationMs = 60_000L
                    ),
                    isFullscreen = false,
                    readProgress = {
                        readCount++
                        PlaybackProgress(positionMs = 10_000L, durationMs = 60_000L)
                    },
                    onPlayPause = {},
                    onSeekBy = {},
                    onSeekTo = {},
                    onSpeedSelected = {},
                    onQualitySelected = {},
                    onToggleFullscreen = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals("Initial read", 1, readCount)

        // Advance 1000ms -> polling happens while visible
        composeTestRule.mainClock.advanceTimeBy(1000L)
        composeTestRule.waitForIdle()
        val countBeforeAutoHide = readCount
        assertTrue("Polled while visible", countBeforeAutoHide > 1)

        // Advance past AUTO_HIDE_DELAY_MS (3500ms) so controls hide
        composeTestRule.mainClock.advanceTimeBy(PlayerControlsPolicy.AUTO_HIDE_DELAY_MS + 200L)
        composeTestRule.waitForIdle()

        val countAfterAutoHide = readCount
        // Advance > 1500ms while controls auto-hidden -> no more reads
        composeTestRule.mainClock.advanceTimeBy(1500L)
        composeTestRule.waitForIdle()
        assertEquals("No more reads after auto-hide", countAfterAutoHide, readCount)
    }

    @Test
    fun player_controls_overlay_polling_stops_when_disposed_before_auto_hide() {
        var readCount = 0
        var showControls by mutableStateOf(true)

        composeTestRule.setContent {
            HPreTheme {
                if (showControls) {
                    PlayerControlsOverlay(
                        playbackState = PlaybackState(
                            isPlaying = true,
                            durationMs = 60_000L
                        ),
                        isFullscreen = false,
                        readProgress = {
                            readCount++
                            PlaybackProgress(positionMs = 10_000L, durationMs = 60_000L)
                        },
                        onPlayPause = {},
                        onSeekBy = {},
                        onSeekTo = {},
                        onSpeedSelected = {},
                        onQualitySelected = {},
                        onToggleFullscreen = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals("Initial immediate read on compose", 1, readCount)

        // Advance 1000ms (well before AUTO_HIDE_DELAY_MS of 3500ms)
        composeTestRule.mainClock.advanceTimeBy(1000L)
        composeTestRule.waitForIdle()
        val countBeforeDispose = readCount
        assertTrue("Polled while visible before auto-hide", countBeforeDispose > 1)

        // Explicitly dispose before auto-hide timer elapses
        showControls = false
        composeTestRule.waitForIdle()
        val countAtDispose = readCount

        // Advance > 1000ms after disposal -> verify polling stopped completely
        composeTestRule.mainClock.advanceTimeBy(1500L)
        composeTestRule.waitForIdle()
        assertEquals("No additional reads after explicit disposal", countAtDispose, readCount)
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

        // Perform slider seek action using Semantics SetProgress to verify deterministic seek dispatch without reset
        val sliderNode = composeTestRule.onNodeWithTag("player_progress_slider")
        sliderNode.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue(setProgress(75_000f))
        }
        composeTestRule.waitForIdle()

        // Assert exactly one final seek call with target value
        assertEquals("seekTo should be called exactly once upon slider action", 1, fakePlayer.seekCallCount)
        assertNotNull(fakePlayer.seekToPosition)
        assertEquals(75_000L, fakePlayer.seekToPosition)
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

    @Test
    fun lazy_watch_content_emits_100_rows_lazily_and_scrolls_to_item_99() {
        val test100Summaries = (0 until 100).map { i ->
            VideoSummary(
                key = ContentKey(0, "lazy_rel_$i"),
                title = "Lazy Recommendation $i",
                canonicalUrl = "https://hpre.test/watch?v=lazy_rel_$i",
                channelKey = ContentKey(0, "channel_$i"),
                channelName = "Channel $i",
                channelAvatarUrl = null,
                thumbnailUrl = null,
                durationSeconds = 120,
                viewCount = 1000L + i,
                publishedTimestamp = 1600000000L
            )
        }
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = { AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8")) },
            relatedHandler = { AppResult.Success(test100Summaries) }
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

        // Wait for metadata / initial list to be displayed
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Initially item 0 is composed/displayed, but off-screen item 99 does not exist in LazyColumn
        composeTestRule.onNodeWithTag("video_card_lazy_rel_0").assertExists()
        composeTestRule.onNodeWithTag("video_card_lazy_rel_99").assertDoesNotExist()

        // Scroll to item 99
        composeTestRule.onNodeWithTag("watch_lazy_column")
            .performScrollToNode(hasTestTag("video_card_lazy_rel_99"))

        composeTestRule.onNodeWithTag("video_card_lazy_rel_99").assertExists()
        composeTestRule.onNodeWithText("Lazy Recommendation 99").assertIsDisplayed()
    }

    @Test
    fun lazy_watch_content_maintains_key_identity_across_reorder() {
        val batchA = listOf(
            VideoSummary(
                key = ContentKey(0, "item_a"),
                title = "Title Alpha",
                canonicalUrl = "https://hpre.test/a",
                channelKey = null,
                channelName = null,
                channelAvatarUrl = null,
                thumbnailUrl = null,
                durationSeconds = null,
                viewCount = null,
                publishedTimestamp = null
            ),
            VideoSummary(
                key = ContentKey(0, "item_b"),
                title = "Title Beta",
                canonicalUrl = "https://hpre.test/b",
                channelKey = null,
                channelName = null,
                channelAvatarUrl = null,
                thumbnailUrl = null,
                durationSeconds = null,
                viewCount = null,
                publishedTimestamp = null
            )
        )
        val batchBReordered = listOf(
            batchA[1],
            batchA[0]
        )

        var relatedState by androidx.compose.runtime.mutableStateOf(
            RefreshableAsyncState.content(batchA)
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchMetadataContent(
                    commentsExpanded = true,
                    details = testDetails(testKey),
                    relatedState = relatedState
                )
            }
        }

        // Before reorder: item_a is displayed with Title Alpha, item_b with Title Beta
        composeTestRule.onNodeWithTag("video_card_item_a").assertIsDisplayed()
        composeTestRule.onNodeWithText("Title Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_card_item_b").assertIsDisplayed()
        composeTestRule.onNodeWithText("Title Beta").assertIsDisplayed()

        // Mutate/reorder state
        relatedState = RefreshableAsyncState.content(batchBReordered)
        composeTestRule.waitForIdle()

        // After reorder: item_a still has Title Alpha, item_b still has Title Beta
        composeTestRule.onNodeWithTag("video_card_item_a").assertIsDisplayed()
        composeTestRule.onNodeWithText("Title Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_card_item_b").assertIsDisplayed()
        composeTestRule.onNodeWithText("Title Beta").assertIsDisplayed()
    }

    @Test
    fun comments_automatic_sentinel_pagination_triggers_next_pages_without_loop() {
        val page1 = (1..5).map {
            com.hpre.app.model.Comment("comm_$it", "Author $it", null, null, "Body $it", null, null)
        }
        val page2 = (6..10).map {
            com.hpre.app.model.Comment("comm_$it", "Author $it", null, null, "Body $it", null, null)
        }
        val page3 = (11..15).map {
            com.hpre.app.model.Comment("comm_$it", "Author $it", null, null, "Body $it", null, null)
        }

        var commentsState by androidx.compose.runtime.mutableStateOf<com.hpre.app.ui.common.AsyncState<com.hpre.app.model.CommentPage>>(
            com.hpre.app.ui.common.AsyncState.Content(
                com.hpre.app.model.CommentPage(page1, nextPageToken = com.hpre.app.model.PageToken.Id("tok_page_2"))
            )
        )
        var loadMoreCallCount = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchMetadataContent(
                    commentsExpanded = true,
                    details = testDetails(testKey),
                    commentsState = commentsState,
                    onLoadMoreComments = { loadMoreCallCount++ }
                )
            }
        }

        // 1. Initial render -> sentinel is visible or reached via scroll
        composeTestRule.onNodeWithTag("watch_lazy_column")
            .performScrollToNode(hasTestTag("comments_load_more_sentinel"))
        composeTestRule.waitForIdle()

        // Exactly one load-more triggered for tok_page_2
        assertEquals(1, loadMoreCallCount)

        // Wait to verify no infinite loop occurs while token remains tok_page_2
        composeTestRule.waitForIdle()
        assertEquals(1, loadMoreCallCount)

        // 2. ViewModel completes page 2 with a new token. The taller open-row
        // layout may move the sentinel below the viewport, so reach it again.
        commentsState = com.hpre.app.ui.common.AsyncState.Content(
            com.hpre.app.model.CommentPage(page1 + page2, nextPageToken = com.hpre.app.model.PageToken.Id("tok_page_3"))
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("watch_lazy_column")
            .performScrollToNode(hasTestTag("comments_load_more_sentinel"))
        composeTestRule.waitForIdle()

        // Token change must trigger next page (page 3) exactly once
        assertEquals(2, loadMoreCallCount)

        // 3. ViewModel completes page 3 with no next token (end of pagination)
        commentsState = com.hpre.app.ui.common.AsyncState.Content(
            com.hpre.app.model.CommentPage(page1 + page2 + page3, nextPageToken = null)
        )
        composeTestRule.waitForIdle()

        // Sentinel removed, no more loadMore calls
        composeTestRule.onNodeWithTag("comments_load_more_sentinel").assertDoesNotExist()
        assertEquals(2, loadMoreCallCount)
    }

    @Test
    fun comments_open_row_displays_author_age_body_likes_and_replies() {
        val comment = com.hpre.app.model.Comment(
            commentId = "complete",
            authorName = "Minh Anh",
            authorAvatarUrl = "https://example.test/avatar.jpg",
            channelKey = null,
            commentText = "Nội dung bình luận",
            publishedTimestamp = System.currentTimeMillis() - 7_200_000L,
            likeCount = 128L,
            replyCount = 12L
        )
        composeTestRule.setContent {
            HPreTheme {
                WatchMetadataContent(
                    commentsExpanded = true,
                    details = testDetails(testKey),
                    commentsState = com.hpre.app.ui.common.AsyncState.Content(
                        com.hpre.app.model.CommentPage(listOf(comment))
                    )
                )
            }
        }

        composeTestRule.onNodeWithTag("watch_lazy_column")
            .performScrollToNode(hasTestTag("comment_complete"))
        composeTestRule.onNodeWithTag("comment_author_complete").assertTextEquals("Minh Anh")
        composeTestRule.onNodeWithTag("comment_age_complete").assertIsDisplayed()
        composeTestRule.onNodeWithTag("comment_body_complete").assertTextEquals("Nội dung bình luận")
        composeTestRule.onNodeWithTag("comment_likes_complete").assertIsDisplayed()
        composeTestRule.onNodeWithTag("comment_replies_complete").assertIsDisplayed()
        composeTestRule.onNodeWithTag("comment_avatar_complete")
            .assertContentDescriptionContains("Minh Anh", substring = true)
    }

    @Test
    fun comments_open_row_uses_initial_and_omits_missing_metadata() {
        val comment = com.hpre.app.model.Comment(
            commentId = "fallback",
            authorName = "Linh",
            authorAvatarUrl = null,
            channelKey = null,
            commentText = "Bình luận ngắn",
            publishedTimestamp = null,
            likeCount = null,
            replyCount = null
        )
        composeTestRule.setContent {
            HPreTheme {
                WatchMetadataContent(
                    commentsExpanded = true,
                    details = testDetails(testKey),
                    commentsState = com.hpre.app.ui.common.AsyncState.Content(
                        com.hpre.app.model.CommentPage(listOf(comment))
                    )
                )
            }
        }

        composeTestRule.onNodeWithTag("watch_lazy_column")
            .performScrollToNode(hasTestTag("comment_fallback"))
        composeTestRule.onNodeWithTag("comment_avatar_fallback")
            .assertTextEquals("L")
            .assertContentDescriptionContains("Linh", substring = true)
        composeTestRule.onNodeWithTag("comment_age_fallback").assertDoesNotExist()
        composeTestRule.onNodeWithTag("comment_likes_fallback").assertDoesNotExist()
        composeTestRule.onNodeWithTag("comment_replies_fallback").assertDoesNotExist()
    }

    @Test
    fun namespaced_keys_prevent_collisions_between_sections_and_colliding_ids() {
        // Comment ID identical to section constant string
        val collidingCommentId = "section:comments_header"
        val comments = listOf(
            com.hpre.app.model.Comment(
                commentId = collidingCommentId,
                authorName = "Colliding Author",
                authorAvatarUrl = null,
                channelKey = null,
                commentText = "Colliding Body",
                publishedTimestamp = null,
                likeCount = null
            )
        )
        // Video nativeId identical to section constant string
        val collidingVideo = VideoSummary(
            key = ContentKey(0, "section:comments_header"),
            title = "Colliding Video Title",
            canonicalUrl = "https://hpre.test/watch?v=colliding",
            channelKey = null,
            channelName = null,
            channelAvatarUrl = null,
            thumbnailUrl = null,
            durationSeconds = null,
            viewCount = null,
            publishedTimestamp = null
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchMetadataContent(
                    commentsExpanded = true,
                    details = testDetails(testKey),
                    commentsState = com.hpre.app.ui.common.AsyncState.Content(
                        com.hpre.app.model.CommentPage(comments)
                    ),
                    relatedState = RefreshableAsyncState.content(listOf(collidingVideo))
                )
            }
        }

        // Assert all three nodes with colliding IDs render without LazyColumn key collision crashes
        composeTestRule.onNodeWithTag("comments_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("comment_$collidingCommentId").assertIsDisplayed()
        composeTestRule.onNodeWithText("Colliding Body").assertIsDisplayed()
        composeTestRule.onNodeWithTag("video_card_section:comments_header").assertIsDisplayed()
    }

    @Test
    fun comments_pagination_guard_resets_per_video_route() {
        val sharedToken = com.hpre.app.model.PageToken.Id("shared_token_abc")
        val commentsA = (1..5).map {
            com.hpre.app.model.Comment("comm_a_$it", "Author A$it", null, null, "Body A$it", null, null)
        }
        val commentsB = (1..5).map {
            com.hpre.app.model.Comment("comm_b_$it", "Author B$it", null, null, "Body B$it", null, null)
        }

        var loadMoreCallsA = 0
        var loadMoreCallsB = 0

        var currentDetails by androidx.compose.runtime.mutableStateOf(testDetails(ContentKey(0, "video_route_1")))
        var currentComments by androidx.compose.runtime.mutableStateOf<com.hpre.app.ui.common.AsyncState<com.hpre.app.model.CommentPage>>(
            com.hpre.app.ui.common.AsyncState.Content(
                com.hpre.app.model.CommentPage(commentsA, nextPageToken = sharedToken)
            )
        )
        var onLoadMore: () -> Unit by androidx.compose.runtime.mutableStateOf({ loadMoreCallsA++ })

        composeTestRule.setContent {
            HPreTheme {
                WatchMetadataContent(
                    commentsExpanded = true,
                    details = currentDetails,
                    commentsState = currentComments,
                    onLoadMoreComments = onLoadMore
                )
            }
        }

        // 1. Video 1 renders with sharedToken -> scroll to sentinel -> triggers loadMore for Video 1 once
        composeTestRule.onNodeWithTag("watch_lazy_column")
            .performScrollToNode(hasTestTag("comments_load_more_sentinel"))
        composeTestRule.waitForIdle()

        assertEquals(1, loadMoreCallsA)
        assertEquals(0, loadMoreCallsB)

        // 2. Switch to Video 2 with the same token string (sharedToken)
        currentDetails = testDetails(ContentKey(0, "video_route_2"))
        currentComments = com.hpre.app.ui.common.AsyncState.Content(
            com.hpre.app.model.CommentPage(commentsB, nextPageToken = sharedToken)
        )
        onLoadMore = { loadMoreCallsB++ }
        composeTestRule.waitForIdle()

        // 3. Sentinel triggers Video 2's load-more callback once (guard was reset for Video 2's key)
        composeTestRule.onNodeWithTag("watch_lazy_column")
            .performScrollToNode(hasTestTag("comments_load_more_sentinel"))
        composeTestRule.waitForIdle()

        assertEquals(1, loadMoreCallsA)
        assertEquals(1, loadMoreCallsB)
    }

    @Test
    fun swipe_down_on_portrait_player_triggers_minimize_to_home() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    fullscreenHostHandlerFactory = FullscreenHostHandlerFactory {
                        _, _ -> object : FullscreenHostHandler {
                            override fun enterFullscreen() = Unit
                            override fun exitFullscreen() = Unit
                            override fun onConfigurationChange() = Unit
                        }
                    },
                    onMinimizeToHome = { minimizeCalls++ }
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("player_controls_overlay"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Downward swipe on player overlay
        composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
            swipeDown(startY = top + height * 0.2f, endY = bottom - 1f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Swipe down in portrait must dispatch onMinimizeToHome exactly once", 1, minimizeCalls)
    }

    @Test
    fun swipe_down_in_fullscreen_does_not_trigger_minimize() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    fullscreenHostHandlerFactory = FullscreenHostHandlerFactory {
                        _, _ -> object : FullscreenHostHandler {
                            override fun enterFullscreen() = Unit
                            override fun exitFullscreen() = Unit
                            override fun onConfigurationChange() = Unit
                        }
                    },
                    onMinimizeToHome = { minimizeCalls++ }
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Enter fullscreen
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Downward swipe in fullscreen overlay
        composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
            swipeDown(startY = top + height * 0.2f, endY = bottom - 1f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Swipe down in fullscreen must not dispatch onMinimizeToHome", 0, minimizeCalls)
    }

    @Test
    fun swipe_down_in_pip_does_not_trigger_minimize() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onMinimizeToHome = { minimizeCalls++ },
                    isInPip = true
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("player_controls_overlay"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Downward swipe in PiP mode
        composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
            swipeDown(startY = top + height * 0.2f, endY = bottom - 1f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Swipe down while in PiP must not dispatch onMinimizeToHome", 0, minimizeCalls)
    }

    @Test
    fun taps_and_double_taps_still_work_with_single_pointer_coordinator() {
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
            composeTestRule.onAllNodes(hasTestTag("player_controls_overlay"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.mainClock.autoAdvance = false
        try {
            // Ensure controls visible before taps
            composeTestRule.mainClock.advanceTimeByFrame()

            // Double tap right edge to fast forward 10s
            composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
                doubleClick(position = androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.5f))
            }
            composeTestRule.mainClock.advanceTimeBy(100)

            assertEquals(10_000L, fakePlayer.seekDeltaCalled)
        } finally {
            composeTestRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun double_tap_seek_survives_recomposition_between_physical_taps() {
        val fakeService = FakeVideoService(
            videoHandler = { AppResult.Success(testDetails(it)) },
            streamInfoHandler = {
                AppResult.Success(StreamInfo(it, "Title", hlsManifestUrl = "https://manifest.m3u8"))
            }
        )
        val fakePlayer = FakePlayerController().apply {
            _state.value = PlaybackState(
                key = testKey,
                durationMs = 120_000L,
                isReady = true,
                isPlaying = true
            )
        }
        val viewModel = WatchViewModel(
            videoService = fakeService,
            playerController = fakePlayer,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(contentKey = testKey, viewModel = viewModel, onNavigateBack = {})
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasTestTag("player_controls_overlay"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.mainClock.autoAdvance = false
        try {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
                click(androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.5f))
            }
            composeTestRule.mainClock.advanceTimeBy(100) // forces controlsVisible recomposition
            composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
                click(androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.5f))
            }
            composeTestRule.mainClock.advanceTimeBy(100)

            assertEquals(10_000L, fakePlayer.seekDeltaCalled)
        } finally {
            composeTestRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun landscape_non_fullscreen_disables_minimize_gesture() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                val landscapeConfig = android.content.res.Configuration().apply {
                    orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalConfiguration provides landscapeConfig
                ) {
                    WatchScreen(
                        contentKey = testKey,
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onMinimizeToHome = { minimizeCalls++ }
                    )
                }
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("player_controls_overlay"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Downward swipe in landscape non-fullscreen
        composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 300f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Landscape non-fullscreen must disable minimize gesture", 0, minimizeCalls)
    }

    @Test
    fun tap_on_center_play_button_invokes_play_and_does_not_toggle_controls_overlay() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onMinimizeToHome = { minimizeCalls++ }
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("control_play_pause"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tap play/pause button directly
        composeTestRule.onNodeWithTag("control_play_pause").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Play/pause must be called", fakePlayer.playPauseCalled)
        assertEquals("Play click must not invoke seek delta", null, fakePlayer.seekDeltaCalled)
        assertEquals("Play click must not invoke seekTo", null, fakePlayer.seekToPosition)
        assertEquals("Play click must not invoke minimize", 0, minimizeCalls)
        // Controls must remain visible
        composeTestRule.onNodeWithTag("control_play_pause").assertIsDisplayed()
    }

    @Test
    fun slider_horizontal_drag_seeks_and_slider_vertical_swipe_does_not_minimize() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onMinimizeToHome = { minimizeCalls++ }
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("player_progress_slider"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 1. Downward swipe starting on the slider
        val initialSeekCallCount = fakePlayer.seekCallCount
        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 300f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Downward gesture starting on slider must never minimize", 0, minimizeCalls)
        assertEquals("Vertical swipe on slider must not dispatch seekTo", initialSeekCallCount, fakePlayer.seekCallCount)

        // 2. Horizontal slider drag seeks and does not minimize
        composeTestRule.onNodeWithTag("player_progress_slider").performTouchInput {
            swipeRight(startX = width * 0.2f, endX = width * 0.8f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Horizontal seek must not minimize", 0, minimizeCalls)
        assertTrue("Horizontal slider drag must dispatch seekTo", fakePlayer.seekCallCount > initialSeekCallCount)
    }

    @Test
    fun surface_horizontal_swipe_does_not_minimize() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onMinimizeToHome = { minimizeCalls++ }
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("player_controls_overlay"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Horizontal swipe across player surface
        composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
            swipeRight(startX = centerX - 100f, endX = centerX + 100f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Horizontal movement must never trigger minimize", 0, minimizeCalls)
    }

    @Test
    fun fullscreen_enter_and_exit_cleans_stale_top_start_protected_bounds() {
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

        var minimizeCalls = 0

        composeTestRule.setContent {
            HPreTheme {
                WatchScreen(
                    contentKey = testKey,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    fullscreenHostHandlerFactory = FullscreenHostHandlerFactory {
                        _, _ -> object : FullscreenHostHandler {
                            override fun enterFullscreen() = Unit
                            override fun exitFullscreen() = Unit
                            override fun onConfigurationChange() = Unit
                        }
                    },
                    onMinimizeToHome = { minimizeCalls++ }
                )
            }
        }

        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 1. Enter fullscreen -> fullscreen_top_start is registered
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("control_fullscreen_back").assertIsDisplayed()

        // 2. Exit fullscreen -> fullscreen_top_start is disposed/unregistered
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("watch_screen"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Now perform downward swipe starting at the top-left area where fullscreen back used to be
        composeTestRule.onNodeWithTag("player_controls_overlay").performTouchInput {
            swipeDown(startY = top + 20f, endY = top + 350f)
        }
        composeTestRule.waitForIdle()

        assertEquals("Disposed fullscreen control bound must not suppress portrait minimize swipe", 1, minimizeCalls)
    }
}
