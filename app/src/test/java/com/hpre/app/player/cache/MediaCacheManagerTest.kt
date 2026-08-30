package com.hpre.app.player.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCacheManagerTest {
    @Test
    fun constants_matchApprovedSpecification() {
        assertEquals(64L * 1024L * 1024L, MediaCacheConstants.MAX_CACHE_BYTES)
        assertEquals(2L * 1024L * 1024L, MediaCacheConstants.FRAGMENT_SIZE_BYTES)
        assertEquals("video_cache", MediaCacheConstants.CACHE_DIR_NAME)
    }
}
