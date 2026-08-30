package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.CatalogCacheValue
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CatalogRepository manages short TTL caching for feeds and search results,
 * query normalization at boundary, and in-flight request deduplication without holding mutexes over remote work.
 * Service requests are repository-owned so that cancellation of an initiator does not cancel active waiters,
 * while executing within the calling dispatcher context.
 */
class CatalogRepository(
    private val videoService: VideoService,
    private val repositoryScope: CoroutineScope,
    private val requestCoordinator: RequestCoordinator = RequestCoordinator(repositoryScope),
    private val ttlMs: Long = 60_000L, // 1 minute default TTL
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private val trendingCache = MetadataCache<String, CatalogCacheValue.Trending>(ttlMs = ttlMs, maxEntries = 2)
    private val searchCache = MetadataCache<String, CatalogCacheValue.Search>(ttlMs = ttlMs, maxEntries = 24)
    private val videoCache = MetadataCache<ContentKey, CatalogCacheValue.Details>(ttlMs = ttlMs, maxEntries = 32)

    private var trendingGeneration: Long = 0L
    private val searchGenerations = mutableMapOf<String, Long>()
    private val videoGenerations = mutableMapOf<ContentKey, Long>()
    private var globalClearGeneration: Long = 0L

    internal val inFlightCountForTest: Int
        get() = requestCoordinator.inFlightCountForTest

    suspend fun getTrending(forceRefresh: Boolean = false): AppResult<List<VideoSummary>> {
        val now = timeProvider()
        val myTrendingGen: Long
        val myClearGen: Long

        mutex.withLock {
            val cached = trendingCache.get("trending", now)
            if (!forceRefresh && cached != null) {
                return AppResult.Success(cached.items)
            }
            if (forceRefresh) {
                trendingGeneration++
            }
            myTrendingGen = trendingGeneration
            myClearGen = globalClearGeneration
        }

        val requestKey = RequestKey.trending("trending:$myTrendingGen:$myClearGen")
        val result = requestCoordinator.execute(requestKey) {
            val res = try {
                videoService.trending()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }

            mutex.withLock {
                if (res is AppResult.Success && myTrendingGen == trendingGeneration && myClearGen == globalClearGeneration) {
                    trendingCache.put("trending", CatalogCacheValue.Trending(res.value), timeProvider())
                }
            }
            res
        }

        return result
    }

    fun normalizeQuery(query: String): String {
        return query.trim().replace("\\s+".toRegex(), " ")
    }

    suspend fun video(key: ContentKey, forceRefresh: Boolean = false): AppResult<VideoDetails> {
        val now = timeProvider()
        val myVideoGen: Long
        val myClearGen: Long

        mutex.withLock {
            val cached = videoCache.get(key, now)
            if (!forceRefresh && cached != null) {
                return AppResult.Success(cached.details)
            }
            if (forceRefresh) {
                videoGenerations[key] = (videoGenerations[key] ?: 0L) + 1L
            }
            myVideoGen = videoGenerations[key] ?: 0L
            myClearGen = globalClearGeneration
        }

        val requestKey = RequestKey.videoDetails("video:${key.serviceId}:${key.nativeId}:$myVideoGen:$myClearGen")
        val result = requestCoordinator.execute(requestKey) {
            val res = try {
                videoService.video(key)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }

            mutex.withLock {
                if (res is AppResult.Success && myVideoGen == (videoGenerations[key] ?: 0L) && myClearGen == globalClearGeneration) {
                    videoCache.put(key, CatalogCacheValue.Details(res.value), timeProvider())
                }
            }
            res
        }

        return result
    }

    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        pageToken: PageToken? = null,
        forceRefresh: Boolean = false
    ): AppResult<SearchPage> {
        val normalizedQuery = normalizeQuery(query)
        val isFirstPage = pageToken == null
        val cacheKey = "${filter.name}:$normalizedQuery"
        val now = timeProvider()

        if (!isFirstPage) {
            // Pagination with token: coalesce identical append requests via typed request coordinator key
            val tokenKey = when (pageToken) {
                is PageToken.Id -> "id:${pageToken.id}"
                is PageToken.Url -> "url:${pageToken.url}"
            }
            val requestKey = RequestKey.searchAppend("search_append:$cacheKey:$tokenKey")
            return requestCoordinator.execute(requestKey) {
                try {
                    videoService.search(normalizedQuery, filter, pageToken)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    AppResult.Failure(AppError.Unknown)
                }
            }
        }

        val mySearchGen: Long
        val myClearGen: Long
        mutex.withLock {
            val cached = searchCache.get(cacheKey, now)
            if (!forceRefresh && cached != null) {
                return AppResult.Success(cached.page)
            }
            if (forceRefresh) {
                searchGenerations[cacheKey] = (searchGenerations[cacheKey] ?: 0L) + 1L
            }
            mySearchGen = searchGenerations[cacheKey] ?: 0L
            myClearGen = globalClearGeneration
        }

        val requestKey = RequestKey.searchFirst("search:$cacheKey:$mySearchGen:$myClearGen")
        val result = requestCoordinator.execute(requestKey) {
            val res = try {
                videoService.search(normalizedQuery, filter, pageToken)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Unknown)
            }

            mutex.withLock {
                if (res is AppResult.Success && mySearchGen == (searchGenerations[cacheKey] ?: 0L) && myClearGen == globalClearGeneration) {
                    searchCache.put(cacheKey, CatalogCacheValue.Search(res.value), timeProvider())
                }
            }
            res
        }

        return result
    }

    suspend fun clearCache() {
        mutex.withLock {
            trendingCache.clear()
            searchCache.clear()
            videoCache.clear()
            // Increment generations so any in-flight requests cannot repopulate the cleared cache upon completion
            trendingGeneration++
            // globalClearGeneration already invalidates every in-flight request, so retaining all
            // per-key generations only keeps old searches/videos alive for the process lifetime.
            searchGenerations.clear()
            videoGenerations.clear()
            globalClearGeneration++
        }
    }
}



