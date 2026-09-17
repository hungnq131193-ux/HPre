package com.hpre.app.extractor

import com.hpre.app.BuildConfig
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class VideoExtractionCoordinator(
    private val scope: CoroutineScope,
    private val ttlMs: Long = 20_000L,
    private val maxEntries: Int = 16,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val countExtractions: Boolean = BuildConfig.DEBUG
) {
    private data class CacheEntry(
        val bundle: ExtractedVideoBundle,
        val expiresAtMs: Long
    )

    private class InFlight(
        val result: CompletableDeferred<AppResult<ExtractedVideoBundle>>,
        val job: Deferred<*>,
        var subscribers: Int,
        val isRefresh: Boolean
    )

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<ContentKey, CacheEntry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ContentKey, CacheEntry>?
        ): Boolean = size > maxEntries
    }
    private val inFlight = mutableMapOf<ContentKey, InFlight>()
    private val extractionCounts = object : LinkedHashMap<ContentKey, Int>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ContentKey, Int>?
        ): Boolean = size > maxEntries
    }

    internal val cacheSizeForTest: Int
        get() = kotlinx.coroutines.runBlocking { mutex.withLock { cache.size } }

    internal val inFlightCountForTest: Int
        get() = kotlinx.coroutines.runBlocking { mutex.withLock { inFlight.size } }

    internal fun extractionCountForTest(key: ContentKey): Int = kotlinx.coroutines.runBlocking {
        mutex.withLock { extractionCounts[key] ?: 0 }
    }

    suspend fun peek(key: ContentKey): ExtractedVideoBundle? = mutex.withLock {
        val now = nowMs()
        val cached = cache[key] ?: return@withLock null
        if (now >= cached.expiresAtMs) {
            cache.remove(key)
            return@withLock null
        }
        cached.bundle
    }

    suspend fun execute(
        key: ContentKey,
        forceRefresh: Boolean = false,
        loader: suspend () -> AppResult<ExtractedVideoBundle>
    ): AppResult<ExtractedVideoBundle> {
        val request: InFlight
        mutex.withLock {
            val now = nowMs()
            cache.entries.removeAll { now >= it.value.expiresAtMs }
            val existing = inFlight[key]
            if (existing != null && (!forceRefresh || existing.isRefresh)) {
                existing.subscribers++
                request = existing
            } else {
                val cached = cache[key]
                if (!forceRefresh && cached != null) {
                    return AppResult.Success(cached.bundle)
                }
                if (countExtractions) {
                    extractionCounts[key] = (extractionCounts[key] ?: 0) + 1
                }
                val result = CompletableDeferred<AppResult<ExtractedVideoBundle>>()
                val holder = arrayOfNulls<InFlight>(1)
                val callerContext = coroutineContext.minusKey(Job)
                val job = scope.async(callerContext, start = CoroutineStart.DEFAULT) {
                    try {
                        val loaded = try {
                            loader()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            AppResult.Failure(AppError.Unknown)
                        }
                        if (loaded is AppResult.Success) {
                            mutex.withLock {
                                // A replaced extraction may finish for its original subscribers,
                                // but must never overwrite the newer recovery result.
                                if (inFlight[key] === holder[0]) {
                                    val storedAtMs = nowMs()
                                    cache[key] = CacheEntry(loaded.value, storedAtMs + ttlMs)
                                }
                            }
                        }
                        result.complete(loaded)
                    } catch (cancelled: CancellationException) {
                        result.completeExceptionally(cancelled)
                        throw cancelled
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                val current = holder[0]
                                if (current != null && inFlight[key] === current) {
                                    inFlight.remove(key)
                                }
                            }
                        }
                    }
                }
                request = InFlight(result, job, subscribers = 1, isRefresh = forceRefresh)
                holder[0] = request
                inFlight[key] = request
            }
        }

        try {
            return request.result.await()
        } catch (cancelled: CancellationException) {
            var cancelledUpstream = false
            withContext(NonCancellable) {
                mutex.withLock {
                    request.subscribers--
                    if (request.subscribers <= 0) {
                        if (inFlight[key] === request) inFlight.remove(key)
                        request.job.cancel(cancelled)
                        cancelledUpstream = true
                    }
                }
                if (cancelledUpstream) {
                    request.job.join()
                }
            }
            throw cancelled
        }
    }
}
