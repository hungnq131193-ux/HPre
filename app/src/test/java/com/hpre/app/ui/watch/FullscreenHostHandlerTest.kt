package com.hpre.app.ui.watch

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenHostHandlerTest {

    private class TestActivity(
        private var orientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        private var finishing: Boolean = false,
        private var destroyed: Boolean = false,
        private var changingConfigurations: Boolean = false
    ) : Activity() {
        override fun getRequestedOrientation(): Int = orientation
        override fun setRequestedOrientation(requestedOrientation: Int) {
            this.orientation = requestedOrientation
        }
        override fun isFinishing(): Boolean = finishing
        override fun isDestroyed(): Boolean = destroyed
        override fun isChangingConfigurations(): Boolean = changingConfigurations

        fun setChangingConfigurations(value: Boolean) {
            this.changingConfigurations = value
        }
    }

    private class FakeWindowSystemUiController : WindowSystemUiController {
        var isHidden: Boolean = false
        var hideCalls: Int = 0
        var showCalls: Int = 0

        override fun hideSystemBars() {
            isHidden = true
            hideCalls++
        }

        override fun showSystemBars() {
            isHidden = false
            showCalls++
        }
    }

    @Test
    fun enter_and_exit_fullscreen_captures_and_restores_original_portrait_orientation() {
        val savedState = SavedStateHandle()
        val activity = TestActivity(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val systemUi = FakeWindowSystemUiController()
        val handler = DefaultFullscreenHostHandler(
            activity = activity,
            savedStateHandle = savedState,
            systemUiController = systemUi
        )

        // Enter fullscreen directly calling production handler
        handler.enterFullscreen()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertTrue(systemUi.isHidden)
        assertEquals(1, systemUi.hideCalls)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, savedState.get<Int>(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))

        // Exit fullscreen directly calling production handler
        handler.exitFullscreen()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        assertFalse(systemUi.isHidden)
        assertEquals(1, systemUi.showCalls)
        assertFalse(savedState.contains(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))
    }

    @Test
    fun enter_and_exit_fullscreen_preserves_unspecified_orientation_without_normalizing_to_portrait() {
        val savedState = SavedStateHandle()
        val activity = TestActivity(orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val systemUi = FakeWindowSystemUiController()
        val handler = DefaultFullscreenHostHandler(
            activity = activity,
            savedStateHandle = savedState,
            systemUiController = systemUi
        )

        // Enter fullscreen directly calling production handler
        handler.enterFullscreen()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertTrue(systemUi.isHidden)
        assertEquals(1, systemUi.hideCalls)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, savedState.get<Int>(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))

        // Exit fullscreen directly calling production handler
        handler.exitFullscreen()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        assertFalse(systemUi.isHidden)
        assertEquals(1, systemUi.showCalls)
        assertFalse(savedState.contains(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))
    }

    @Test
    fun recreation_in_fullscreen_restores_saved_original_unspecified_orientation_and_clears_key_on_exit() {
        val savedState = SavedStateHandle()
        val initialActivity = TestActivity(orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val systemUi1 = FakeWindowSystemUiController()
        val handler1 = DefaultFullscreenHostHandler(
            activity = initialActivity,
            savedStateHandle = savedState,
            systemUiController = systemUi1
        )

        // Enter fullscreen in first handler instance
        handler1.enterFullscreen()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, initialActivity.requestedOrientation)
        assertTrue(systemUi1.isHidden)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, savedState.get<Int>(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))

        // Simulate activity recreation: new Activity starts with landscape (as it was recreated in landscape),
        // but shares the same SavedStateHandle containing KEY_ORIG_ORIENTATION = UNSPECIFIED
        val recreatedActivity = TestActivity(orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        val systemUi2 = FakeWindowSystemUiController()
        val handler2 = DefaultFullscreenHostHandler(
            activity = recreatedActivity,
            savedStateHandle = savedState,
            systemUiController = systemUi2
        )

        // Exiting fullscreen on new handler must restore exact UNSPECIFIED and clear saved state key
        handler2.exitFullscreen()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, recreatedActivity.requestedOrientation)
        assertFalse(systemUi2.isHidden)
        assertEquals(1, systemUi2.showCalls)
        assertFalse(savedState.contains(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))
    }

    @Test
    fun non_config_disposal_exits_fullscreen_once() {
        val savedState = SavedStateHandle()
        val activity = TestActivity(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val systemUi = FakeWindowSystemUiController()
        val handler = DefaultFullscreenHostHandler(
            activity = activity,
            savedStateHandle = savedState,
            systemUiController = systemUi
        )

        handler.enterFullscreen()
        assertEquals(1, systemUi.hideCalls)
        assertEquals(0, systemUi.showCalls)

        // Composable destruction/disposal while fullscreen remains active and NOT changing configurations (e.g. navigation pop / app destroy)
        val isFullscreen = true
        val isChangingConfig = activity.isChangingConfigurations
        if (isFullscreen && !isChangingConfig) {
            handler.exitFullscreen()
        }

        assertEquals(1, systemUi.showCalls)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    @Test
    fun config_change_disposal_exits_zero_times_and_preserves_landscape_and_saved_state() {
        val savedState = SavedStateHandle()
        val activity = TestActivity(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, changingConfigurations = true)
        val systemUi = FakeWindowSystemUiController()
        val handler = DefaultFullscreenHostHandler(
            activity = activity,
            savedStateHandle = savedState,
            systemUiController = systemUi
        )

        handler.enterFullscreen()
        assertEquals(1, systemUi.hideCalls)
        assertEquals(0, systemUi.showCalls)

        // Composable disposal during configuration change (activity.isChangingConfigurations == true)
        val isFullscreen = true
        val isChangingConfig = activity.isChangingConfigurations
        if (isFullscreen && !isChangingConfig) {
            handler.exitFullscreen()
        }

        assertEquals("Config change disposal must not call exitFullscreen", 0, systemUi.showCalls)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertTrue(savedState.contains(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, savedState.get<Int>(DefaultFullscreenHostHandler.KEY_ORIG_ORIENTATION))
    }
}


