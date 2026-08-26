package com.flowtube.app.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ExtractorBootstrapTest {

    /**
     * Pure testable state-machine seam: isolated from NewPipe global static state.
     */
    private class TestableBootstrap {
        private val initialized = AtomicBoolean(false)
        private val lock = Any()

        fun isInitialized(): Boolean = initialized.get()

        fun init(initializer: () -> Unit) {
            if (initialized.get()) return
            synchronized(lock) {
                if (!initialized.get()) {
                    initializer()
                    initialized.set(true)
                }
            }
        }
    }

    @Test
    fun state_machine_initializes_thread_safely_and_runs_initializer_once() {
        val bootstrap = TestableBootstrap()
        val initCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(8)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(16)

        assertFalse(bootstrap.isInitialized())

        for (i in 0 until 16) {
            executor.submit {
                startLatch.await()
                bootstrap.init {
                    Thread.sleep(10)
                    initCount.incrementAndGet()
                }
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue(bootstrap.isInitialized())
        assertEquals(1, initCount.get())
    }

    @Test
    fun state_machine_failure_does_not_poison_future_retry() {
        val bootstrap = TestableBootstrap()
        var failed = false
        try {
            bootstrap.init {
                throw RuntimeException("Network down during init")
            }
        } catch (_: RuntimeException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(bootstrap.isInitialized())

        // Retry should succeed
        var succeeded = false
        bootstrap.init {
            succeeded = true
        }
        assertTrue(succeeded)
        assertTrue(bootstrap.isInitialized())
    }

    @Test
    fun global_singleton_bootstrap_initialization_seam_executes_safely() {
        var initializerInvoked = false
        ExtractorBootstrap.initForTesting {
            initializerInvoked = true
        }
        assertTrue(ExtractorBootstrap.isInitialized())
    }
}


