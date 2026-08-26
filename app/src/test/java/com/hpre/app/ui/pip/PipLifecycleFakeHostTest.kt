package com.hpre.app.ui.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePipHost(
    private val pipSupported: Boolean = true,
    var isInPip: Boolean = false
) {
    var enterPipCount = 0
    var refreshPipParamsCount = 0

    fun enterPip(): Boolean {
        if (!pipSupported) return false
        isInPip = true
        enterPipCount++
        return true
    }

    fun exitPip() {
        isInPip = false
    }

    fun refreshPipParams() {
        refreshPipParamsCount++
    }

    fun onLifecycleStart() {
        refreshPipParams()
    }

    fun onLifecycleResume() {
        refreshPipParams()
    }

    fun onLifecycleConfigurationChanged() {
        refreshPipParams()
    }

    fun onLifecyclePipModeChanged(inPip: Boolean) {
        isInPip = inPip
        refreshPipParams()
    }
}

class PipLifecycleFakeHostTest {

    @Test
    fun when_pip_is_supported_and_active_enter_pip_succeeds_and_tracks_state() {
        val fakeHost = FakePipHost(pipSupported = true)
        assertFalse(fakeHost.isInPip)

        val entered = fakeHost.enterPip()
        assertTrue(entered)
        assertTrue(fakeHost.isInPip)
        org.junit.Assert.assertEquals(1, fakeHost.enterPipCount)

        fakeHost.exitPip()
        assertFalse(fakeHost.isInPip)
    }

    @Test
    fun when_pip_unsupported_enter_pip_returns_false_and_does_not_mutate_state() {
        val fakeHost = FakePipHost(pipSupported = false)
        assertFalse(fakeHost.isInPip)

        val entered = fakeHost.enterPip()
        assertFalse(entered)
        assertFalse(fakeHost.isInPip)
        org.junit.Assert.assertEquals(0, fakeHost.enterPipCount)
    }

    @Test
    fun pip_params_refreshed_on_start_resume_config_changed_and_pip_mode_changed() {
        val fakeHost = FakePipHost(pipSupported = true)
        org.junit.Assert.assertEquals(0, fakeHost.refreshPipParamsCount)

        fakeHost.onLifecycleStart()
        org.junit.Assert.assertEquals(1, fakeHost.refreshPipParamsCount)

        fakeHost.onLifecycleResume()
        org.junit.Assert.assertEquals(2, fakeHost.refreshPipParamsCount)

        fakeHost.onLifecycleConfigurationChanged()
        org.junit.Assert.assertEquals(3, fakeHost.refreshPipParamsCount)

        fakeHost.onLifecyclePipModeChanged(true)
        org.junit.Assert.assertEquals(4, fakeHost.refreshPipParamsCount)
        assertTrue(fakeHost.isInPip)

        fakeHost.onLifecyclePipModeChanged(false)
        org.junit.Assert.assertEquals(5, fakeHost.refreshPipParamsCount)
        assertFalse(fakeHost.isInPip)
    }
}
