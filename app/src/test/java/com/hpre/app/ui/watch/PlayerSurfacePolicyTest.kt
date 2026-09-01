package com.hpre.app.ui.watch

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class PlayerSurfacePolicyTest {

    @Test
    fun `surface always fits the complete frame without crop or distortion`() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, PlayerSurfacePolicy.resizeMode)
        assertNotEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, PlayerSurfacePolicy.resizeMode)
        assertNotEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, PlayerSurfacePolicy.resizeMode)
    }
}
