package com.hpre.app.player.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object MediaCacheConstants {
    const val MAX_CACHE_BYTES = 64L * 1024L * 1024L
    const val FRAGMENT_SIZE_BYTES = 2L * 1024L * 1024L
    const val CACHE_DIR_NAME = "video_cache"
}

interface MediaCacheManager {
    val cache: Cache?
    val isAvailable: Boolean
    suspend fun clearCache(): Boolean
}

@OptIn(UnstableApi::class)
class DefaultMediaCacheManager(
    private val context: Context,
    private val maxBytes: Long = MediaCacheConstants.MAX_CACHE_BYTES
) : MediaCacheManager {

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var simpleCache: SimpleCache? = null

    private fun getOrInitCache(): SimpleCache? {
        if (initialized) return simpleCache
        synchronized(this) {
            if (initialized) return simpleCache
            try {
                val cacheDir = File(context.cacheDir, MediaCacheConstants.CACHE_DIR_NAME)
                val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
                val databaseProvider = StandaloneDatabaseProvider(context)
                simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
            } catch (_: Throwable) {
                simpleCache = null
            }
            initialized = true
            return simpleCache
        }
    }

    override val cache: Cache?
        get() = getOrInitCache()

    override val isAvailable: Boolean
        get() = getOrInitCache() != null

    override suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val c = getOrInitCache() ?: return@withLock false
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
