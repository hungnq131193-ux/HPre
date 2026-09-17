package com.hpre.app.player.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object MediaCacheConstants {
    const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    const val FRAGMENT_SIZE_BYTES = 2L * 1024L * 1024L
    const val CACHE_DIR_NAME = "video_cache"
}

@OptIn(UnstableApi::class)
interface MediaCacheManager {
    /** Returns the initialized cache without doing disk work. */
    val cache: Cache?
    val isAvailable: Boolean
    suspend fun initialize(): Boolean = isAvailable
    suspend fun clearCache(): Boolean
}

@OptIn(UnstableApi::class)
class DefaultMediaCacheManager(
    private val context: Context,
    private val maxBytes: Long = MediaCacheConstants.MAX_CACHE_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MediaCacheManager {

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var initializedCache: Cache? = null

    private fun initializeLocked(): Cache? {
        if (initialized) return initializedCache
        try {
            val cacheDir = File(context.cacheDir, MediaCacheConstants.CACHE_DIR_NAME)
            val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
            val databaseProvider = StandaloneDatabaseProvider(context)
            initializedCache = SimpleCache(cacheDir, evictor, databaseProvider)
        } catch (_: Throwable) {
            initializedCache = null
        }
        initialized = true
        return initializedCache
    }

    override val cache: Cache?
        get() = initializedCache

    override val isAvailable: Boolean
        get() = initializedCache != null

    override suspend fun initialize(): Boolean = withContext(ioDispatcher) {
        synchronized(this@DefaultMediaCacheManager) { initializeLocked() != null }
    }

    override suspend fun clearCache(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val c = synchronized(this@DefaultMediaCacheManager) { initializeLocked() }
                ?: return@withLock false
            var success = true
            try {
                val keys = c.keys.toList()
                for (key in keys) {
                    try {
                        c.removeResource(key)
                    } catch (_: Throwable) {
                        success = false
                    }
                }
            } catch (_: Throwable) {
                success = false
            }
            success
        }
    }
}
