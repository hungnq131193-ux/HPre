package com.hpre.app.player

import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PendingSessionCommandsTest {
    private fun testInfo(key: ContentKey) = StreamInfo(key = key, title = "Title ${key.nativeId}")

    @Test
    fun prepare_before_connection_is_retained_and_drained_once() {
        val commands = PendingSessionCommands()
        val key = ContentKey(0, "first_video")
        val info = testInfo(key)
        val first = PendingPrepare(key, info, 1_000L, false)

        commands.setPrepare(first)

        val taken = commands.takePrepare()
        assertEquals(first, taken)
        assertSame(info, taken?.streamInfo)
        assertNull(commands.takePrepare())
    }

    @Test
    fun newest_prepare_replaces_stale_prepare_before_connection() {
        val commands = PendingSessionCommands()
        val staleKey = ContentKey(0, "stale_video")
        val currentKey = ContentKey(0, "current_video")
        val currentInfo = testInfo(currentKey)
        val stale = PendingPrepare(staleKey, testInfo(staleKey), 1_000L, true)
        val current = PendingPrepare(currentKey, currentInfo, 8_000L, false)

        commands.setPrepare(stale)
        commands.setPrepare(current)

        val taken = commands.takePrepare()
        assertEquals(current, taken)
        assertSame(currentInfo, taken?.streamInfo)
    }

    @Test
    fun multiple_rapid_prepares_retain_latest_only_and_drain_atomically() {
        val commands = PendingSessionCommands()
        for (i in 1..10) {
            val key = ContentKey(0, "vid_$i")
            commands.setPrepare(PendingPrepare(key, testInfo(key), i * 1000L, true))
        }

        val finalPrepare = commands.takePrepare()
        val expectedKey = ContentKey(0, "vid_10")
        assertEquals(expectedKey, finalPrepare?.key)
        assertEquals(10_000L, finalPrepare?.positionMs)
        assertEquals(expectedKey, finalPrepare?.streamInfo?.key)
        assertNull(commands.takePrepare())
    }

    @Test
    fun clear_discards_prepare_waiting_for_connection() {
        val commands = PendingSessionCommands()
        val key = ContentKey(0, "stale_video")
        commands.setPrepare(PendingPrepare(key, testInfo(key), 1_000L, true))

        commands.clearPrepare()

        assertNull(commands.takePrepare())
    }
}


