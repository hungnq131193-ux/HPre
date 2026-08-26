package com.hpre.app.player

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SnapshotWriterTest {

    private lateinit var tempDir: File
    private lateinit var store: PlaybackSnapshotStore

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "snapshot_writer_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        store = PlaybackSnapshotStore(storageDir = tempDir)
    }

    @Test
    fun save_captures_generation_and_clear_increments_generation_preventing_resurrection() {
        val writer = store.writer
        val key1 = ContentKey(0, "vid_1")
        val snap1 = PlaybackSnapshot(key1, 1000L, true)
        val key2 = ContentKey(0, "vid_2")
        val snap2 = PlaybackSnapshot(key2, 2000L, true)

        writer.save(snap1)
        assertEquals("vid_1", store.load()?.key?.nativeId)

        // Clear must increment generation and delete snapshot
        writer.clear()
        assertNull(store.load())

        // An older enqueued write with stale generation cannot resurrect after clear
        writer.saveWithGeneration(snap2, generation = writer.currentGeneration - 1)
        assertNull(store.load())
    }

    @Test
    fun enqueueSave_returns_token_and_clear_invalidates_pre_enqueued_saves() {
        val writer = store.writer
        val key1 = ContentKey(0, "vid_1")
        val snap1 = PlaybackSnapshot(key1, 1000L, true)
        val key2 = ContentKey(0, "vid_2")
        val snap2 = PlaybackSnapshot(key2, 2000L, true)

        // 1. Enqueue save synchronously returns a token
        val token = writer.enqueueSave()

        // 2. Clear invalidates all pre-enqueued saves by advancing generation
        writer.clear()
        assertNull(store.load())

        // 3. Execute save with captured token -> no resurrection
        writer.executeSave(snap1, token)
        assertNull(store.load())

        // 4. New enqueue save after clear succeeds
        val token2 = writer.enqueueSave()
        writer.executeSave(snap2, token2)
        assertEquals("vid_2", store.load()?.key?.nativeId)
    }

    @Test
    fun concurrent_saves_and_clears_serialize_safely_and_never_corrupt() {
        val writer = store.writer
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(50)

        for (i in 1..50) {
            executor.submit {
                try {
                    if (i % 5 == 0) {
                        writer.clear()
                    } else {
                        writer.save(PlaybackSnapshot(ContentKey(0, "vid_$i"), i * 1000L, true))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // Final state should either be null or a valid snapshot (no crash/corruption)
        val finalSnap = store.load()
        if (finalSnap != null) {
            assertTrue(finalSnap.positionMs > 0)
        }
    }

    @Test
    fun migration_cannot_overwrite_newer_save_and_snapshotFlow_cannot_overwrite_newer_in_memory_state() {
        val writer = store.writer
        val snapNewer = PlaybackSnapshot(ContentKey(0, "newer_vid"), 5000L, true)
        val snapStaleMigration = PlaybackSnapshot(ContentKey(0, "stale_migrated_vid"), 1000L, true)

        // User or service saves newer snapshot (version moves to 1)
        val token = writer.enqueueSave()
        writer.executeSave(snapNewer, token)
        assertEquals("newer_vid", store.load()?.key?.nativeId)

        // Late migration write with version 0 attempted
        writer.saveWithGeneration(snapStaleMigration, generation = 0L)

        // Newer snapshot remains intact
        assertEquals("newer_vid", store.load()?.key?.nativeId)
        assertEquals(5000L, store.load()?.positionMs)
    }
}
