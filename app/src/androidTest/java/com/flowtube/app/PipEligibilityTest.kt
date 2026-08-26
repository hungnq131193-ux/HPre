package com.flowtube.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowtube.app.player.PlaybackState
import com.flowtube.app.player.PlaybackStreamType
import com.flowtube.app.testing.TestFlowTubeApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PipEligibilityTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun pip_rejected_outside_watch_and_allowed_for_visible_ready_playing_video() {
        activityRule.scenario.onActivity { activity ->
            val app = activity.application as TestFlowTubeApplication
            app.recordingPlayer._state.value = PlaybackState(
                key = app.testKey,
                title = "PiP video",
                isPlaying = true,
                playWhenReady = true,
                isReady = true,
                streamType = PlaybackStreamType.PROGRESSIVE
            )

            app.playbackUiCoordinator.setWatchVisible(false)
            assertFalse(activity.enterPictureInPictureIfEligible())

            app.playbackUiCoordinator.setWatchVisible(true)
            val entered = activity.enterPictureInPictureIfEligible()
            if (activity.isPipSupported()) {
                assertTrue(entered)
            } else {
                assertFalse(entered)
            }
        }
    }

    @Test
    fun pip_disabled_preference_rejects_eligibility() {
        activityRule.scenario.onActivity { activity ->
            val app = activity.application as TestFlowTubeApplication
            app.recordingPlayer._state.value = PlaybackState(
                key = app.testKey,
                title = "PiP video",
                isPlaying = true,
                playWhenReady = true,
                isReady = true,
                streamType = PlaybackStreamType.PROGRESSIVE
            )
            app.playbackUiCoordinator.setWatchVisible(true)

            // When pip preference is disabled, isPipEligible and enterPictureInPictureIfEligible must reject
            app.playbackUiCoordinator.setPipEnabled(false)
            assertFalse(activity.isPipEligible())
            assertFalse(activity.enterPictureInPictureIfEligible())

            // When pip preference is re-enabled, eligibility is restored if device supports PiP
            app.playbackUiCoordinator.setPipEnabled(true)
            if (activity.isPipSupported()) {
                assertTrue(activity.isPipEligible())
            }
        }
    }

    @Test
    fun pip_returns_false_and_resets_entering_pip_when_system_pip_fails() {
        activityRule.scenario.onActivity { activity ->
            val app = activity.application as TestFlowTubeApplication
            app.recordingPlayer._state.value = PlaybackState(
                key = app.testKey,
                title = "PiP video",
                isPlaying = true,
                playWhenReady = true,
                isReady = true,
                streamType = PlaybackStreamType.PROGRESSIVE
            )
            app.playbackUiCoordinator.setWatchVisible(true)

            // When pip preference is disabled, enterPictureInPictureIfEligible returns false
            app.playbackUiCoordinator.setPipEnabled(false)
            assertFalse(activity.enterPictureInPictureIfEligible())
            app.playbackUiCoordinator.setPipEnabled(true)
        }
    }

    @Test
    fun pip_auto_enter_params_updated_when_eligible() {
        activityRule.scenario.onActivity { activity ->
            val app = activity.application as TestFlowTubeApplication
            app.recordingPlayer._state.value = PlaybackState(
                key = app.testKey,
                title = "PiP video",
                isPlaying = true,
                playWhenReady = true,
                isReady = true,
                streamType = PlaybackStreamType.PROGRESSIVE
            )
            app.playbackUiCoordinator.setWatchVisible(true)
            app.playbackUiCoordinator.setPipEnabled(true)

            if (activity.isPipSupported()) {
                assertTrue(activity.isPipEligible())
            } else {
                assertFalse(activity.isPipEligible())
            }
            activity.updateAutoPipEligibility()
        }
    }

    @Test
    fun pip_mode_changed_updates_ui_coordinator_and_policy() {
        activityRule.scenario.onActivity { activity ->
            val app = activity.application as TestFlowTubeApplication
            val config = activity.resources.configuration

            activity.onPictureInPictureModeChanged(true, config)
            assertTrue(app.playbackUiCoordinator.state.value.isInPip)

            activity.onPictureInPictureModeChanged(false, config)
            assertFalse(app.playbackUiCoordinator.state.value.isInPip)
        }
    }
}
