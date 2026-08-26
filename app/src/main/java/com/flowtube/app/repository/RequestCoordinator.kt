package com.flowtube.app.repository

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.SearchPage
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
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
import kotlin.coroutines.coroutineContext

/**
 * Explicit sealed request result kind to prevent generic erasure bugs/collisions.
 */
sealed class RequestResultKind<T : Any>(val name: String) {
    data object Trending : RequestResultKind<List<VideoSummary>>("Trending")
    data object SearchFirst : RequestResultKind<SearchPage>("SearchFirst")
    data object SearchAppend : RequestResultKind<SearchPage>("SearchAppend")
    data object VideoDetails : RequestResultKind<com.flowtube.app.model.VideoDetails>("VideoDetails")
}

/**
 * Type-safe key for request deduplication parameterized by exact result type T and RequestResultKind.
 * Can only be instantiated via typed factory per kind.
 */
class RequestKey<T : Any> internal constructor(
    val id: String,
    val kind: RequestResultKind<T>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequestKey<*>) return false
        return id == other.id && kind == other.kind
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        return result
    }

    override fun toString(): String {
        return "RequestKey(id=$id, kind=$kind)"
    }

    companion object {
        fun trending(id: String): RequestKey<List<VideoSummary>> =
            RequestKey(id, RequestResultKind.Trending)

        fun searchFirst(id: String): RequestKey<SearchPage> =
            RequestKey(id, RequestResultKind.SearchFirst)

        fun searchAppend(id: String): RequestKey<SearchPage> =
            RequestKey(id, RequestResultKind.SearchAppend)

        fun videoDetails(id: String): RequestKey<VideoDetails> =
            RequestKey(id, RequestResultKind.VideoDetails)
    }
}

class RequestCoordinator(
    private val scope: CoroutineScope
) : AutoCloseable {
    private class InFlightRequest<T : Any>(
        val deferred: CompletableDeferred<AppResult<T>>,
        val job: Deferred<*>,
        var subscriberCount: Int
    )

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<RequestKey<*>, InFlightRequest<*>>()
    private var isClosed = false

    internal val inFlightCountForTest: Int
        get() = kotlinx.coroutines.runBlocking {
            mutex.withLock { inFlight.size }
        }

    internal fun subscriberCountForTest(key: RequestKey<*>): Int = kotlinx.coroutines.runBlocking {
        mutex.withLock { inFlight[key]?.subscriberCount ?: 0 }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> execute(
        key: RequestKey<T>,
        block: suspend () -> AppResult<T>
    ): AppResult<T> {
        val deferredToAwait: CompletableDeferred<AppResult<T>>
        var myInFlightRequest: InFlightRequest<T>? = null

        mutex.withLock {
            if (isClosed) {
                return AppResult.Failure(AppError.Unknown)
            }
            val existing = inFlight[key] as? InFlightRequest<T>
            if (existing != null) {
                existing.subscriberCount++
                deferredToAwait = existing.deferred
                myInFlightRequest = existing
            } else {
                val newDeferred = CompletableDeferred<AppResult<T>>()
                val callerContext = coroutineContext.minusKey(Job)
                val inFlightHolder = arrayOfNulls<InFlightRequest<T>>(1)
                val upstreamJob = scope.async(callerContext, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val result = try {
                            block()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            AppResult.Failure(AppError.Unknown)
                        }
                        newDeferred.complete(result)
                    } catch (ce: CancellationException) {
                        newDeferred.completeExceptionally(ce)
                        throw ce
                    } catch (e: Throwable) {
                        newDeferred.complete(AppResult.Failure(AppError.Unknown))
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                val thisReq = inFlightHolder[0]
                                if (thisReq != null && inFlight[key] === thisReq) {
                                    inFlight.remove(key)
                                }
                            }
                        }
                    }
                }
                val inFlightReq = InFlightRequest(newDeferred, upstreamJob, subscriberCount = 1)
                inFlightHolder[0] = inFlightReq
                inFlight[key] = inFlightReq
                deferredToAwait = newDeferred
                myInFlightRequest = inFlightReq
            }
        }

        try {
            return deferredToAwait.await()
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    val current = inFlight[key] as? InFlightRequest<T>
                    val thisReq = myInFlightRequest
                    if (current != null && thisReq != null && current === thisReq && current.deferred === deferredToAwait) {
                        current.subscriberCount--
                        if (current.subscriberCount <= 0) {
                            current.job.cancel(ce)
                            if (inFlight[key] === current) {
                                inFlight.remove(key)
                            }
                        }
                    }
                }
            }
            throw ce
        }
    }

    override fun close() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                isClosed = true
                val snapshot = inFlight.values.toList()
                inFlight.clear()
                val cancellation = CancellationException("RequestCoordinator closed")
                for (req in snapshot) {
                    req.job.cancel(cancellation)
                    req.deferred.completeExceptionally(cancellation)
                }
            }
        }
    }
}

