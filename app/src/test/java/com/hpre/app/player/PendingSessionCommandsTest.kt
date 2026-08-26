package com.hpre.app.player

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSessionCommandsTest {
    @Test
    fun prepare_before_connection_is_retained_and_drained_once() {
        val commands = PendingSessionCommands()
        val first = PendingPrepare(ContentKey(0, "first_video"), 1_000L, false)

        commands.setPrepare(first)

        assertEquals(first, commands.takePrepare())
        assertNull(commands.takePrepare())
    }

    @Test
    fun newest_prepare_replaces_stale_prepare_before_connection() {
        val commands = PendingSessionCommands()
        val stale = PendingPrepare(ContentKey(0, "stale_video"), 1_000L, true)
        val current = PendingPrepare(ContentKey(0, "current_video"), 8_000L, false)

        commands.setPrepare(stale)
        commands.setPrepare(current)

        assertEquals(current, commands.takePrepare())
    }

    @Test
    fun multiple_rapid_prepares_retain_latest_only_and_drain_atomically() {
        val commands = PendingSessionCommands()
        for (i in 1..10) {
            commands.setPrepare(PendingPrepare(ContentKey(0, "vid_$i"), i * 1000L, true))
        }

        val finalPrepare = commands.takePrepare()
        assertEquals(ContentKey(0, "vid_10"), finalPrepare?.key)
        assertEquals(10_000L, finalPrepare?.positionMs)
        assertNull(commands.takePrepare())
    }
}

