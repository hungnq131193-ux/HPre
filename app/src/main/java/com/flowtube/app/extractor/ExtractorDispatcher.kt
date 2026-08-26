package com.flowtube.app.extractor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Default dispatcher provider for NewPipeExtractor blocking execution.
 * Uses an ExecutorService backed CoroutineDispatcher so worker threads can be tracked and interrupted/cancelled.
 */
object ExtractorDispatcher {
    private val threadIndex = AtomicInteger(0)

    val IO: CoroutineDispatcher = Executors.newCachedThreadPool(object : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "FlowTube-Extractor-${threadIndex.incrementAndGet()}")
            t.isDaemon = true
            return t
        }
    }).asCoroutineDispatcher()
}

