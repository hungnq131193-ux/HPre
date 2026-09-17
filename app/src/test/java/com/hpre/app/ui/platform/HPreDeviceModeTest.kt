package com.hpre.app.ui.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class HPreDeviceModeTest {
    @Test fun leanback_touchscreen_device_selects_remote() {
        assertEquals(
            HPreInputMode.REMOTE,
            HPreDeviceModePolicy.select(
                HPreDeviceFeatures(
                    hasLeanback = true,
                    hasAutomotive = false,
                    hasTouchscreen = true,
                ),
            ),
        )
    }

    @Test fun automotive_touchscreen_device_selects_remote() {
        assertEquals(
            HPreInputMode.REMOTE,
            HPreDeviceModePolicy.select(
                HPreDeviceFeatures(
                    hasLeanback = false,
                    hasAutomotive = true,
                    hasTouchscreen = true,
                ),
            ),
        )
    }

    @Test fun device_without_touchscreen_selects_remote() {
        assertEquals(
            HPreInputMode.REMOTE,
            HPreDeviceModePolicy.select(
                HPreDeviceFeatures(
                    hasLeanback = false,
                    hasAutomotive = false,
                    hasTouchscreen = false,
                ),
            ),
        )
    }

    @Test fun ordinary_touchscreen_device_selects_touch() {
        assertEquals(
            HPreInputMode.TOUCH,
            HPreDeviceModePolicy.select(
                HPreDeviceFeatures(
                    hasLeanback = false,
                    hasAutomotive = false,
                    hasTouchscreen = true,
                ),
            ),
        )
    }
}
