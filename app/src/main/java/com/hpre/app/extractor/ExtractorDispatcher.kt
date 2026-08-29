package com.hpre.app.extractor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Default dispatcher provider for NewPipeExtractor blocking execution.
 * Uses an ExecutorService backed CoroutineDispatcher so worker threads can be tracked and interrupted/cancelled.
 *
 * Concurrency & queue bounds:
 * - maxWorkers = 4 active workers to prevent overloading device resources and upstream servers.
 * - queueCapacity = 16 (finite queue backed by [ArrayBlockingQueue] with default [ThreadPoolExecutor.AbortPolicy]).
 *   Chosen above normal app fan-out (currently <= 6 topic queries during recommendation loads) to allow normal
 *   bursts to queue safely while preventing unbounded accumulation of stale tasks.
 * - allowCoreThreadTimeOut(true) ensures idle worker threads terminate after keepAliveTime.
 */
object ExtractorDispatcher {
    /**
     * Default finite queue capacity. Set to 16, well above normal fan-out of 6 topic queries,
     * to absorb bursts while rejecting stale task accumulation under sustained overload.
     */
    const val DEFAULT_QUEUE_CAPACITY: Int = 16

    private val threadIndex = AtomicInteger(0)

    internal fun createBoundedExtractorExecutor(
        maxWorkers: Int = 4,
        queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
        keepAliveTime: Long = 60L,
        timeUnit: TimeUnit = TimeUnit.SECONDS
    ): ThreadPoolExecutor {
        return ThreadPoolExecutor(
            maxWorkers,
            maxWorkers,
            keepAliveTime,
            timeUnit,
            ArrayBlockingQueue(queueCapacity),
            object : ThreadFactory {
                override fun newThread(r: Runnable): Thread {
                    val t = Thread(r, "HPre-Extractor-${threadIndex.incrementAndGet()}")
                    t.isDaemon = true
                    return t
                }
            },
            ThreadPoolExecutor.AbortPolicy()
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    val IO: CoroutineDispatcher = createBoundedExtractorExecutor().asCoroutineDispatcher()
}

