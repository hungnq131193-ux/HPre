package com.hpre.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.player.PlaybackProgress
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.player.toProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniPlayerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeMiniPlayerController : PlayerController {
        val _state = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = _state
        var playPauseCalled = false
        var seekCalledWith: Long? = null

        override fun attachSurface(playerView: androidx.media3.ui.PlayerView) {}
        override fun detachSurface(playerView: androidx.media3.ui.PlayerView) {}
        override fun onLifecycleStart() {}
        override fun onLifecycleStop() {}
        override fun prepare(
            key: ContentKey,
            streamInfo: StreamInfo,
            startPositionMs: Long,
            playWhenReady: Boolean,
            initialQuality: QualityOption?
        ) {}
        override fun play() {}
        override fun pause() {}
        override fun playPause() {
            playPauseCalled = true
            _state.value = _state.value.copy(isPlaying = !_state.value.isPlaying)
        }
        override fun seekTo(positionMs: Long) {
            seekCalledWith = positionMs
        }
        override fun seekBy(deltaMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun selectQuality(quality: QualityOption) {}
        override fun release() {}
        override suspend fun readProgress(): PlaybackProgress = _state.value.toProgress()
    }

    @Test
    fun mini_player_not_rendered_when_no_active_media_item() {
        val controller = FakeMiniPlayerController()
        controller._state.value = PlaybackState(key = null)

        composeTestRule.setContent {
            HPreTheme {
                MiniPlayer(
                    playerController = controller,
                    onExpandWatch = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("mini_player_container").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mini-player", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun mini_player_renders_and_interacts_when_active_item_exists() {
        val controller = FakeMiniPlayerController()
        val key = ContentKey(0, "mini_test_vid")
        controller._state.value = PlaybackState(
            key = key,
            title = "Mini player title",
            isPlaying = true,
            currentPositionMs = 15_000L,
            durationMs = 60_000L
        )

        var expandedKey: ContentKey? = null
        var dismissed = false

        composeTestRule.setContent {
            HPreTheme {
                androidx.compose.material3.Surface {
                    MiniPlayer(
                        playerController = controller,
                        onExpandWatch = { expandedKey = it },
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mini_player_container").assertExists()
        composeTestRule.onNodeWithTag("mini_player_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mini-player", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("mini-player", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Mini player title").assertIsDisplayed()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeTestRule.onNodeWithText(context.getString(com.hpre.app.R.string.status_playing)).assertIsDisplayed()

        // Play/Pause interaction
        composeTestRule.onNodeWithTag("mini_player_play_pause_button").performClick()
        assertTrue(controller.playPauseCalled)

        // Close interaction
        composeTestRule.onNodeWithTag("mini_player_close_button").performClick()
        assertTrue(dismissed)

        // Expand interaction
        composeTestRule.onNodeWithTag("mini_player_container").performClick()
        assertEquals(key, expandedKey)
    }

    @Test
    fun mini_player_progress_polls_without_state_flow_emission() {
        var readCount = 0
        var currentPos = 10_000L
        val fakeState = MutableStateFlow(
            PlaybackState(
                key = ContentKey(0, "poll_test"),
                title = "Polling Test",
                isPlaying = true,
                durationMs = 100_000L
            )
        )
        val controller = object : PlayerController by FakeMiniPlayerController() {
            override val state: StateFlow<PlaybackState> = fakeState

            override suspend fun readProgress(): PlaybackProgress {
                readCount++
                return PlaybackProgress(positionMs = currentPos, durationMs = 100_000L)
            }
        }

        var showMiniPlayer by androidx.compose.runtime.mutableStateOf(true)

        composeTestRule.setContent {
            HPreTheme {
                if (showMiniPlayer) {
                    MiniPlayer(
                        playerController = controller,
                        onExpandWatch = {},
                        onDismiss = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals("Initial read on composition", 1, readCount)

        // Advance Compose main clock by 250ms -> no second read yet
        composeTestRule.mainClock.advanceTimeBy(250L)
        composeTestRule.waitForIdle()
        assertEquals("No second read before 500ms", 1, readCount)

        // Advance by 300ms -> polled at 500ms
        currentPos = 50_000L
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()
        assertEquals("Polled at 500ms", 2, readCount)

        // Assert state flow emissions count did not change (controller.state remains untouched)
        assertEquals(1, fakeState.replayCache.size)

        // Progress bar exists and is displayed
        composeTestRule.onNodeWithTag("mini_player_progress").assertExists().assertIsDisplayed()

        // Dispose MiniPlayer -> polling stops
        showMiniPlayer = false
        composeTestRule.waitForIdle()
        val countAfterDispose = readCount

        composeTestRule.mainClock.advanceTimeBy(1000L)
        composeTestRule.waitForIdle()
        assertEquals("Polling stopped after dispose", countAfterDispose, readCount)
    }
}
