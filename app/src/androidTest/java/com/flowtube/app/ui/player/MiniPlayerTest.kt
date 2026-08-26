package com.flowtube.app.ui.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.core.designsystem.FlowTubeTheme
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.player.PlaybackState
import com.flowtube.app.player.PlayerController
import com.flowtube.app.player.QualityOption
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
    }

    @Test
    fun mini_player_not_rendered_when_no_active_media_item() {
        val controller = FakeMiniPlayerController()
        controller._state.value = PlaybackState(key = null)

        composeTestRule.setContent {
            FlowTubeTheme {
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
            FlowTubeTheme {
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
}
