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
    fun `fullscreen fills every edge without zoom cropping`() {
        val mode = PlayerSurfacePolicy.resizeMode(fillScreen = true)

        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, mode)
        assertNotEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, mode)
        assertNotEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, mode)
    }

    @Test
    fun `embedded mini player and pip keep aspect ratio fit`() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerSurfacePolicy.resizeMode(fillScreen = false)
        )
    }
}
