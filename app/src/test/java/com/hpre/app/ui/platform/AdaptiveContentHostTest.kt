package com.hpre.app.ui.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveContentHostTest {
    @Test fun non_watch_screens_constrain_content_to_1200_dp() {
        assertTrue(AdaptiveContentPolicy.shouldConstrain(isWatchScreen = false))
        assertEquals(1200, AdaptiveContentPolicy.MAX_CONTENT_WIDTH_DP)
    }

    @Test fun watch_screens_do_not_constrain_content() {
        assertFalse(AdaptiveContentPolicy.shouldConstrain(isWatchScreen = true))
    }
}
