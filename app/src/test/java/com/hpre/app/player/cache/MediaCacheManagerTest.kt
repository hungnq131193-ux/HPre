package com.hpre.app.player.cache

import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MediaCacheManagerTest {
    @Test
    fun concurrent_initialization_attempts_storage_once() = kotlinx.coroutines.test.runTest {
        val cacheDirectoryReads = AtomicInteger()
        val manager = DefaultMediaCacheManager(object : ContextWrapper(null) {
            override fun getCacheDir(): File {
                cacheDirectoryReads.incrementAndGet()
                Thread.sleep(25)
                error("simulated unavailable cache storage")
            }
        })

        val results = coroutineScope {
            List(12) {
                async(Dispatchers.Default) {
                    manager.initialize()
                }
            }.awaitAll()
        }

        assertEquals(List(12) { false }, results)
        assertEquals(1, cacheDirectoryReads.get())
        assertNull(manager.cache)
    }

    @Test
    fun cache_lookup_does_not_initialize_storage_on_the_calling_thread() {
        var cacheDirectoryReads = 0
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getCacheDir(): File {
                cacheDirectoryReads++
                error("cache directory must only be opened by explicit initialization")
            }
        }
        val manager = DefaultMediaCacheManager(context)

        assertNull(manager.cache)
        assertEquals(0, cacheDirectoryReads)
    }

    @Test
    fun constants_matchApprovedSpecification() {
        assertEquals(128L * 1024L * 1024L, MediaCacheConstants.MAX_CACHE_BYTES)
        assertEquals(2L * 1024L * 1024L, MediaCacheConstants.FRAGMENT_SIZE_BYTES)
        assertEquals("video_cache", MediaCacheConstants.CACHE_DIR_NAME)
    }
}
