package com.hpre.app.extractor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ExtractorDispatcherTest {

    @Test
    fun executor_has_finite_queue_capacity_and_queues_normal_app_fan_out_when_workers_are_saturated() = runBlocking {
        val pool = ExtractorDispatcher.createBoundedExtractorExecutor(
            maxWorkers = 4,
            queueCapacity = 16,
            keepAliveTime = 60L,
            timeUnit = TimeUnit.SECONDS
        )
        val dispatcher = pool.asCoroutineDispatcher()

        try {
            assertEquals(16, pool.queue.remainingCapacity())

            val startGate = CountDownLatch(4)
            val releaseGate = CountDownLatch(1)
            val workersCompleted = AtomicInteger(0)

            // Block all 4 workers
            val workerJobs = (1..4).map {
                async(dispatcher) {
                    startGate.countDown()
                    releaseGate.await(5, TimeUnit.SECONDS)
                    workersCompleted.incrementAndGet()
                }
            }

            assertTrue("4 workers must start and block", startGate.await(5, TimeUnit.SECONDS))
            assertEquals(4, pool.activeCount)

            // Submit 6 tasks (normal app fan-out <= 6 topic queries)
            val fanOutCompleted = AtomicInteger(0)
            val fanOutJobs = (1..6).map {
                async(dispatcher) {
                    fanOutCompleted.incrementAndGet()
                }
            }

            // Assert finite queue contains the 6 excess tasks deterministically
            assertEquals("Queue must hold 6 fan-out tasks", 6, pool.queue.size)
            assertEquals("Remaining capacity must decrease to 10", 10, pool.queue.remainingCapacity())

            // Release workers and verify all 10 tasks finish
            releaseGate.countDown()
            workerJobs.awaitAll()
            fanOutJobs.awaitAll()

            assertEquals(4, workersCompleted.get())
            assertEquals(6, fanOutCompleted.get())
            assertEquals(0, pool.queue.size)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun cancelled_queued_coroutine_never_executes_body_when_workers_become_free() = runBlocking {
        val pool = ExtractorDispatcher.createBoundedExtractorExecutor(
            maxWorkers = 4,
            queueCapacity = 16,
            keepAliveTime = 60L,
            timeUnit = TimeUnit.SECONDS
        )
        val dispatcher = pool.asCoroutineDispatcher()

        try {
            val startGate = CountDownLatch(4)
            val releaseGate = CountDownLatch(1)

            // Block 4 active workers
            val blockingJobs = (1..4).map {
                async(dispatcher) {
                    startGate.countDown()
                    releaseGate.await(5, TimeUnit.SECONDS)
                }
            }

            assertTrue("4 workers must start", startGate.await(5, TimeUnit.SECONDS))

            val bodyExecuted = AtomicBoolean(false)
            val queuedJob = async(dispatcher) {
                bodyExecuted.set(true)
            }

            // Task is deterministically in executor queue
            assertEquals(1, pool.queue.size)

            // Cancel coroutine before any worker becomes available
            queuedJob.cancel()
            assertTrue(queuedJob.isCancelled)

            // Unblock workers and let them drain queue
            releaseGate.countDown()
            blockingJobs.awaitAll()

            try {
                queuedJob.await()
                fail("Cancelled coroutine must throw CancellationException")
            } catch (e: CancellationException) {
                // Expected
            }

            assertFalse("Cancelled queued coroutine body must never execute", bodyExecuted.get())
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun overload_beyond_finite_queue_capacity_is_rejected_and_never_executes_body_outside_extractor_pool() = runBlocking {
        val pool = ExtractorDispatcher.createBoundedExtractorExecutor(
            maxWorkers = 4,
            queueCapacity = 16,
            keepAliveTime = 60L,
            timeUnit = TimeUnit.SECONDS
        )
        val dispatcher = pool.asCoroutineDispatcher()

        try {
            val startGate = CountDownLatch(4)
            val releaseGate = CountDownLatch(1)

            // 1. Saturate 4 workers
            val workerJobs = (1..4).map {
                async(dispatcher) {
                    startGate.countDown()
                    releaseGate.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("4 workers must be active", startGate.await(5, TimeUnit.SECONDS))

            // 2. Fill queue to capacity (16 tasks)
            val queuedJobs = (1..16).map {
                async(dispatcher) {
                    // Queued tasks wait for release
                }
            }
            assertEquals("Queue must be full at 16", 16, pool.queue.size)
            assertEquals("Remaining capacity must be 0", 0, pool.queue.remainingCapacity())

            // 3. Submit excess task (21st task) via coroutine dispatcher
            val bodyExecuted = AtomicBoolean(false)
            val executionThreadName = AtomicInteger(0)
            val rejectedJob = async(dispatcher) {
                bodyExecuted.set(true)
            }

            // Await rejected job - kotlinx asCoroutineDispatcher cancels coroutine on RejectedExecutionException
            try {
                rejectedJob.await()
                fail("Overloaded coroutine must fail with CancellationException")
            } catch (e: CancellationException) {
                // Expected rejection cancellation
            }

            assertFalse("Rejected coroutine body must never execute", bodyExecuted.get())

            // 4. Submit excess task via direct executor execute - must throw RejectedExecutionException via AbortPolicy
            val rawRunnableRan = AtomicBoolean(false)
            try {
                pool.execute {
                    rawRunnableRan.set(true)
                }
                fail("ThreadPoolExecutor must throw RejectedExecutionException on capacity overflow")
            } catch (e: RejectedExecutionException) {
                // Expected AbortPolicy
            }
            assertFalse("Raw runnable rejected by AbortPolicy must never run", rawRunnableRan.get())

            // 5. Clean drain: unblock workers and verify everything terminates cleanly
            releaseGate.countDown()
            workerJobs.awaitAll()
            queuedJobs.awaitAll()

            assertEquals(0, pool.queue.size)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun default_executor_configuration_has_eight_workers_and_queue_capacity_of_thirty_two() {
        val pool = ExtractorDispatcher.createBoundedExtractorExecutor()
        try {
            assertEquals("Default queue capacity must be 32", 32, pool.queue.remainingCapacity())
            assertEquals("Core pool size must be 8", 8, pool.corePoolSize)
            assertEquals("Maximum pool size must be 8", 8, pool.maximumPoolSize)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun executor_limits_concurrency_to_four_and_queues_remaining_tasks() = runBlocking {
        val callingThread = Thread.currentThread()
        val pool = ExtractorDispatcher.createBoundedExtractorExecutor(
            maxWorkers = 4,
            keepAliveTime = 60L,
            timeUnit = TimeUnit.SECONDS
        )
        val dispatcher = pool.asCoroutineDispatcher()

        try {
            val totalTasks = 8
            val startedCount = AtomicInteger(0)
            val runningCount = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            val startGate = CountDownLatch(4)
            val releaseGate = CountDownLatch(1)
            val completedCount = AtomicInteger(0)
            val rawThreadNames = ConcurrentHashMap.newKeySet<String>()

            val jobs = (1..totalTasks).map {
                async(dispatcher) {
                    startedCount.incrementAndGet()
                    val currentThread = Thread.currentThread()
                    assertNotEquals(callingThread, currentThread)

                    // Extract actual thread name if coroutine name suffix is present
                    val rawName = currentThread.name.substringBefore(" @coroutine")
                    rawThreadNames.add(rawName)

                    assertTrue("Worker thread must be a daemon", currentThread.isDaemon)
                    assertTrue(
                        "Worker thread name must match HPre-Extractor-\\d+, was: $rawName",
                        rawName.matches(Regex("HPre-Extractor-\\d+"))
                    )

                    val currentRunning = runningCount.incrementAndGet()
                    var currentMax = maxObservedConcurrency.get()
                    while (currentRunning > currentMax) {
                        if (maxObservedConcurrency.compareAndSet(currentMax, currentRunning)) {
                            break
                        }
                        currentMax = maxObservedConcurrency.get()
                    }

                    startGate.countDown()
                    releaseGate.await(5, TimeUnit.SECONDS)

                    runningCount.decrementAndGet()
                    completedCount.incrementAndGet()
                }
            }

            assertTrue("First 4 tasks must start concurrently", startGate.await(5, TimeUnit.SECONDS))
            assertEquals(4, maxObservedConcurrency.get())
            assertEquals(4, runningCount.get())
            assertEquals("Remaining 4 tasks must be queued in pool queue", 4, pool.queue.size)

            releaseGate.countDown()
            jobs.awaitAll()

            assertEquals(8, completedCount.get())
            assertEquals(8, startedCount.get())
            assertTrue("Max concurrency must be <= 4", maxObservedConcurrency.get() <= 4)
            assertTrue("Should not exceed 4 unique worker threads for 4 max workers", rawThreadNames.size <= 4)
            assertTrue("Should create at least 1 unique worker thread", rawThreadNames.isNotEmpty())
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun idle_workers_time_out_via_factory() {
        val pool = ExtractorDispatcher.createBoundedExtractorExecutor(
            maxWorkers = 2,
            keepAliveTime = 50L,
            timeUnit = TimeUnit.MILLISECONDS
        )

        try {
            val latch = CountDownLatch(2)
            repeat(2) {
                pool.execute {
                    Thread.sleep(20)
                    latch.countDown()
                }
            }

            assertTrue("Tasks should complete", latch.await(2, TimeUnit.SECONDS))

            val deadline = System.currentTimeMillis() + 3000L
            while (pool.poolSize > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            assertEquals(0, pool.poolSize)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun production_singleton_dispatches_off_main_thread_to_daemon_worker() = runBlocking {
        val callingThread = Thread.currentThread()
        val job = async(ExtractorDispatcher.IO) {
            val currentThread = Thread.currentThread()
            assertNotEquals(callingThread, currentThread)
            assertTrue(currentThread.isDaemon)
            val rawName = currentThread.name.substringBefore(" @coroutine")
            assertTrue(
                "Worker thread name must match HPre-Extractor-\\d+, was: $rawName",
                rawName.matches(Regex("HPre-Extractor-\\d+"))
            )
            "success"
        }
        assertEquals("success", job.await())
    }
}
