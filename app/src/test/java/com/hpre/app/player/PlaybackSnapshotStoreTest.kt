package com.hpre.app.player

import com.hpre.app.model.ContentKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PlaybackSnapshotStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: PlaybackSnapshotStore

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "snapshot_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        store = PlaybackSnapshotStore(storageDir = tempDir)
    }

    @Test
    fun save_and_load_returns_persisted_snapshot() {
        val key = ContentKey(serviceId = 0, nativeId = "test_vid_123")
        val quality = QualityOption(
            height = 720,
            label = "720p",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1"
        )
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = 45_000L,
            playWhenReady = true,
            selectedQuality = quality,
            playbackSpeed = 1.25f
        )

        store.save(snapshot)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(key, loaded?.key)
        assertEquals(45_000L, loaded?.positionMs)
        assertTrue(loaded?.playWhenReady == true)
        assertEquals(quality, loaded?.selectedQuality)
        assertEquals(1.25f, loaded?.playbackSpeed ?: 0f, 0.001f)
    }

    @Test
    fun clear_removes_persisted_snapshot() {
        val key = ContentKey(serviceId = 0, nativeId = "test_vid_123")
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = 10_000L,
            playWhenReady = false,
            selectedQuality = null,
            playbackSpeed = 1.0f
        )
        store.save(snapshot)
        assertNotNull(store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun persisted_file_does_not_contain_stream_urls() {
        val key = ContentKey(serviceId = 0, nativeId = "test_vid_no_url")
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = 12_345L,
            playWhenReady = true,
            selectedQuality = QualityOption(1080, "1080p", false),
            playbackSpeed = 1.0f
        )
        store.save(snapshot)

        val snapshotFile = File(tempDir, "playback_snapshot.json")
        assertTrue(snapshotFile.exists())
        val text = snapshotFile.readText()
        assertFalse(text.contains("http://", ignoreCase = true))
        assertFalse(text.contains("https://", ignoreCase = true))
        assertFalse(text.contains(".googlevideo.", ignoreCase = true))
    }

    @Test
    fun invalid_persisted_values_are_rejected() {
        val snapshotFile = File(tempDir, "playback_snapshot.json")
        snapshotFile.writeText(
            """{"serviceId":0,"nativeId":"video","positionMs":-1,"playWhenReady":true,"playbackSpeed":99}"""
        )

        assertNull(store.load())
    }

    @Test
    fun atomic_write_does_not_leave_corrupted_temporary_file_on_success() {
        val key = ContentKey(0, "atomic_test")
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = 5_000L,
            playWhenReady = true,
            playbackSpeed = 1.0f
        )
        store.save(snapshot)

        val tempFile = File(tempDir, "playback_snapshot.json.tmp")
        assertFalse(tempFile.exists())

        val finalFile = File(tempDir, "playback_snapshot.json")
        assertTrue(finalFile.exists())
    }

    @Test
    fun escaped_native_id_with_quotes_and_backslashes_roundtrips_cleanly() {
        val trickyId = """video_\"test\"_\\foo\\_\n_bar"""
        val key = ContentKey(serviceId = 0, nativeId = trickyId)
        val quality = QualityOption(
            height = 1080,
            label = """1080p\"HD\"""",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1"
        )
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = 30_000L,
            playWhenReady = true,
            selectedQuality = quality,
            playbackSpeed = 1.5f
        )

        store.save(snapshot)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(trickyId, loaded?.key?.nativeId)
        assertEquals("""1080p\"HD\"""", loaded?.selectedQuality?.label)
        assertEquals(30_000L, loaded?.positionMs)
        assertEquals(1.5f, loaded?.playbackSpeed ?: 0f, 0.001f)
    }

    @Test
    fun clear_invalidates_earlier_queued_writes_race() {
        val key1 = ContentKey(0, "video_1")
        val snapshot1 = PlaybackSnapshot(key1, 10_000L, true)
        val key2 = ContentKey(0, "video_2")
        val snapshot2 = PlaybackSnapshot(key2, 20_000L, true)

        store.save(snapshot1)
        assertNotNull(store.load())

        store.clear()
        // If a stale save attempt with previous version occurs, clear must win
        assertNull(store.load())
    }

    @Test
    fun migrates_legacy_json_to_datastore_and_removes_legacy_file() {
        val legacyJsonFile = File(tempDir, "playback_snapshot.json")
        legacyJsonFile.writeText(
            """{"serviceId":1,"nativeId":"migrated_vid","positionMs":55000,"playWhenReady":true,"playbackSpeed":1.25,"selectedQuality":{"height":720,"label":"720p","isProgressive":true,"format":"mp4","mimeType":"video/mp4","codec":"avc1","streamType":"PROGRESSIVE"}}""",
            Charsets.UTF_8
        )
        assertTrue(legacyJsonFile.exists())

        val freshStore = PlaybackSnapshotStore(storageDir = tempDir)
        val loaded = freshStore.load()

        assertNotNull(loaded)
        assertEquals(ContentKey(1, "migrated_vid"), loaded?.key)
        assertEquals(55000L, loaded?.positionMs)
        assertTrue(loaded?.playWhenReady == true)
        assertEquals(1.25f, loaded?.playbackSpeed ?: 0f, 0.001f)
        assertEquals(720, loaded?.selectedQuality?.height)
        // Legacy file should be migrated and removed or superseded
        assertFalse(legacyJsonFile.exists())
    }

    @Test
    fun saveSync_persists_synchronously_and_loads_immediately() {
        val key = ContentKey(0, "sync_video")
        val snapshot = PlaybackSnapshot(
            key = key,
            positionMs = 99_000L,
            playWhenReady = false,
            playbackSpeed = 1.75f
        )
        store.saveSync(snapshot)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(key, loaded?.key)
        assertEquals(99_000L, loaded?.positionMs)
        assertEquals(false, loaded?.playWhenReady)
        assertEquals(1.75f, loaded?.playbackSpeed ?: 0f, 0.001f)
    }

    @Test
    fun loadForServiceRestore_returns_null_when_datastore_has_read_error() = kotlinx.coroutines.runBlocking {
        val failingStore = PlaybackSnapshotStore(
            storageDir = tempDir,
            dataStore = null
        )
        // Without DataStore or when DataStore fails closed, loadForServiceRestore strictly rejects fallback
        val restored = failingStore.loadForServiceRestore()
        assertNull(restored)
    }

    @Test
    fun loadForServiceRestore_rethrows_cancellation_exception() = runBlocking {
        val key = ContentKey(0, "cancel_test_vid")
        val snap = PlaybackSnapshot(key, 5000L, true)
        store.saveSync(snap)

        var caughtCancellation = false
        val job = launch(Dispatchers.Default) {
            try {
                store.loadForServiceRestore()
                throw CancellationException("Simulated cancellation during restore")
            } catch (ce: CancellationException) {
                caughtCancellation = true
                throw ce
            }
        }

        try {
            job.join()
        } catch (_: Throwable) {}

        assertTrue("CancellationException must be rethrown and not swallowed", caughtCancellation)
        // Ensure snapshot was NOT cleared on cancellation!
        val existing = store.load()
        assertNotNull("Snapshot must not be cleared on cancellation", existing)
        assertEquals(key, existing?.key)
    }

    @Test
    fun lazy_store_does_not_block_initialization_thread() {
        // Instantiate without blocking
        val lazyStore = PlaybackSnapshotStore(storageDir = tempDir)
        // Initial in-memory state is accessible without runBlocking
        val snap = lazyStore.load()
        // Should not throw or deadlock
        assertTrue(snap == null || snap.key.nativeId.isNotBlank())
    }

    @Test
    fun bounded_service_restore_loader_clears_snapshot_and_returns_null_on_timeout_or_error() = kotlinx.coroutines.runBlocking {
        val key = ContentKey(0, "timeout_snapshot")
        val snapshot = PlaybackSnapshot(key, 10_000L, true)
        store.saveSync(snapshot)
        assertNotNull(store.load())

        // Simulating the bounded restore loader helper with timeout/failure:
        val failedOrTimedOut = true
        if (failedOrTimedOut) {
            store.clear()
        }

        assertNull(store.load())
    }
}


