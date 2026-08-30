package com.hpre.app.ui.watch

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.MainActivity
import com.hpre.app.testing.TestHPreApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchRecreationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun full_e2e_main_activity_navigation_fullscreen_recreation_back_and_state_persistence() {
        val app = ApplicationProvider.getApplicationContext<TestHPreApplication>()
        val sharedPlayer = app.recordingPlayer
        val testContainer = app.testContainer
        val recordingFactory = testContainer.fullscreenHostHandlerFactory
        sharedPlayer._state.value = com.hpre.app.player.PlaybackState(
            key = app.testKey,
            isPlaying = true,
            playWhenReady = true,
            durationMs = 60_000L,
            currentPositionMs = 12_000L
        )

        // 1. Wait for Home screen video card and click it to navigate to Watch route
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(hasTestTag("video_card_recreation_test_video"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("video_card_recreation_test_video").performClick()

        // Verify Watch screen loaded
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(hasTestTag("watch_video_title"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("watch_video_title").fetchSemanticsNode()

        // Verify recording factory created host handler via DI seam
        assertNotNull("Recording factory should have created a host handler", recordingFactory.lastCreatedHandler)
        val initialHandler = recordingFactory.lastCreatedHandler!!

        // Multiple consumers may ask for the app-scoped controller, but there is one session instance.
        assertEquals(1, testContainer.uniquePlayerInstanceCount)
        assertEquals(0, sharedPlayer.releaseCount)

        val scenario = composeTestRule.activityRule.scenario

        // Verify initial orientation is PORTRAIT or UNSPECIFIED
        var origOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario.onActivity { act ->
            origOrientation = act.requestedOrientation
        }

        // 2. Click actual `control_fullscreen_toggle` UI tag
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(hasTestTag("control_fullscreen_toggle"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("control_fullscreen_toggle").performClick()

        // Verify fullscreen container displayed
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("watch_screen_fullscreen").assertIsDisplayed()

        // Assert enterFullscreen called and recording systemUiController hid system bars (exact counts: 1 enter, 1 hide, 0 exit, 0 show)
        assertEquals(1, initialHandler.enterCount)
        assertEquals(0, initialHandler.exitCount)
        assertEquals(1, initialHandler.systemUiController.hideCount)
        assertEquals(0, initialHandler.systemUiController.showCount)

        // Assert real MainActivity requestedOrientation changes landscape
        scenario.onActivity { act ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, act.requestedOrientation)
        }

        // 3. Call scenario.recreate() during fullscreen via compose rule
        val attachesBeforeRecreate = sharedPlayer.attachSurfaceCount
        val detachesBeforeRecreate = sharedPlayer.detachSurfaceCount
        val startCountBeforeRecreate = sharedPlayer.lifecycleStartCount
        val stopCountBeforeRecreate = sharedPlayer.lifecycleStopCount
        val factoryCountBeforeRecreate = recordingFactory.creationCount

        scenario.recreate()

        // Wait for recreated screen in fullscreen
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(hasTestTag("watch_screen_fullscreen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("watch_screen_fullscreen").assertIsDisplayed()

        // Assert new handler created for recreated activity
        assertEquals(1, recordingFactory.creationCount - factoryCountBeforeRecreate)
        val handlerInFullscreen = recordingFactory.lastCreatedHandler
        assertNotNull(handlerInFullscreen)

        // Configuration change disposal of old handler must not invoke exitFullscreen or showSystemBars
        assertEquals(0, initialHandler.exitCount)
        assertEquals(0, initialHandler.systemUiController.showCount)

        // Recreated handler starts with 0 enter/exit/hide/show until transitions occur
        assertEquals(0, handlerInFullscreen!!.enterCount)
        assertEquals(0, handlerInFullscreen.exitCount)
        assertEquals(0, handlerInFullscreen.systemUiController.hideCount)
        assertEquals(0, handlerInFullscreen.systemUiController.showCount)

        // Assert the app-scoped session instance remains unique, no release.
        assertEquals(1, testContainer.uniquePlayerInstanceCount)
        assertEquals(0, sharedPlayer.releaseCount)
        assertEquals(12_000L, sharedPlayer.state.value.currentPositionMs)
        assertEquals(app.testKey, sharedPlayer.state.value.key)

        // Assert surface/lifecycle behavior: exactly one detach and one reattach delta, no stop since config change
        assertEquals(1, sharedPlayer.detachSurfaceCount - detachesBeforeRecreate)
        assertEquals(1, sharedPlayer.attachSurfaceCount - attachesBeforeRecreate)
        assertEquals(0, sharedPlayer.lifecycleStopCount - stopCountBeforeRecreate)
        assertEquals(1, sharedPlayer.lifecycleStartCount - startCountBeforeRecreate)

        // Assert orientation is still landscape
        scenario.onActivity { act ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, act.requestedOrientation)
        }

        // 4. Press production back (first back exits fullscreen but remains Watch)
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        // Fullscreen exits, NavHost remains Watch (portrait watch_screen)
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(hasTestTag("watch_screen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("watch_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("watch_video_title").fetchSemanticsNode()

        // Assert exitFullscreen called and systemUiController showSystemBars invoked exactly once (no duplicate exit)
        assertEquals(0, handlerInFullscreen.enterCount)
        assertEquals(1, handlerInFullscreen.exitCount)
        assertEquals(0, handlerInFullscreen.systemUiController.hideCount)
        assertEquals(1, handlerInFullscreen.systemUiController.showCount)

        // Assert orientation restores original orientation
        scenario.onActivity { act ->
            assertEquals(origOrientation, act.requestedOrientation)
        }

        assertEquals(1, testContainer.uniquePlayerInstanceCount)
        assertEquals(0, sharedPlayer.releaseCount)

        // 5. Press production back again to pop from Watch back to Home
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(hasTestTag("home_screen"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()

        // Navigating away from Watch shows mini-player and keeps session active without release
        assertEquals(0, sharedPlayer.releaseCount)
    }
}
