package com.hpre.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeIdleCallbackTest {
    @Test
    fun idle_callback_runs_once() {
        val registry = FakeIdleQueueRegistry()
        var calls = 0

        registerOneShotIdleCallback(registry) { calls++ }
        registry.runHandlerTwice()

        assertEquals(1, calls)
    }

    @Test
    fun cancelled_idle_callback_does_not_run() {
        val registry = FakeIdleQueueRegistry()
        var calls = 0

        val cancel = registerOneShotIdleCallback(registry) { calls++ }
        cancel()
        registry.runHandlerTwice()

        assertEquals(0, calls)
        assertEquals(1, registry.removeCalls)
    }

    private class FakeIdleQueueRegistry : IdleQueueRegistry {
        private lateinit var handler: () -> Boolean
        var removeCalls = 0

        override fun addIdleHandler(handler: () -> Boolean): Any {
            this.handler = handler
            return handler
        }

        override fun removeIdleHandler(token: Any) {
            removeCalls++
        }

        fun runHandlerTwice() {
            handler()
            handler()
        }
    }
}
