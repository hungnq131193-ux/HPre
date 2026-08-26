package com.hpre.app.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.database.entity.PlaylistEntity
import com.hpre.app.database.entity.PlaylistEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistDaoTest {

    private lateinit var database: HPreDatabase
    private val dao get() = database.playlistDao()

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HPreDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        database.close()
    }

    private fun entry(
        playlistId: Long,
        serviceId: Int = 1,
        videoId: String,
        sortOrder: Int = 0
    ) = PlaylistEntryEntity(
        playlistId = playlistId,
        serviceId = serviceId,
        videoId = videoId,
        canonicalUrl = "https://example.com/watch?v=$videoId",
        title = "Video $videoId",
        channelId = "ch1",
        channelName = "Channel 1",
        thumbnailUrl = "https://example.com/thumb.jpg",
        durationSeconds = 120L,
        addedTimestamp = 1000L,
        sortOrder = sortOrder
    )

    @Test
    fun playlist_crud_operations() = runTest {
        val id = dao.insertPlaylist(
            PlaylistEntity(
                title = "Favorites",
                createdTimestamp = 1000L,
                updatedTimestamp = 1000L
            )
        )
        val loaded = dao.getPlaylistById(id)
        assertNotNull(loaded)
        assertEquals("Favorites", loaded?.title)

        dao.updatePlaylist(loaded!!.copy(title = "Top Favorites", updatedTimestamp = 2000L))
        val updated = dao.getPlaylistById(id)
        assertEquals("Top Favorites", updated?.title)
        assertEquals(2000L, updated?.updatedTimestamp)

        dao.deletePlaylist(id)
        assertNull(dao.getPlaylistById(id))
    }

    @Test
    fun playlist_entries_reorder_is_contiguous_and_transactional() = runTest {
        val pid = dao.insertPlaylist(PlaylistEntity(title = "Mix", createdTimestamp = 100L, updatedTimestamp = 100L))

        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v0"), 101L)
        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v1"), 102L)
        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v2"), 103L)

        val initial = dao.getEntries(pid)
        assertEquals(listOf("v0", "v1", "v2"), initial.map { it.videoId })
        assertEquals(listOf(0, 1, 2), initial.map { it.sortOrder })

        // Reorder index 0 (v0) to index 2
        val success = dao.reorderEntries(pid, fromIndex = 0, toIndex = 2, updatedTimestamp = 200L)
        org.junit.Assert.assertTrue(success)

        val reordered = dao.getEntries(pid)
        assertEquals(listOf("v1", "v2", "v0"), reordered.map { it.videoId })
        assertEquals(listOf(0, 1, 2), reordered.map { it.sortOrder })
        assertEquals(200L, dao.getPlaylistById(pid)?.updatedTimestamp)

        // Invalid reorder returns false without corrupting state
        val failInvalid = dao.reorderEntries(pid, fromIndex = -1, toIndex = 2, updatedTimestamp = 300L)
        org.junit.Assert.assertFalse(failInvalid)
        val failSame = dao.reorderEntries(pid, fromIndex = 1, toIndex = 1, updatedTimestamp = 300L)
        org.junit.Assert.assertFalse(failSame)
        val failMissing = dao.reorderEntries(9999L, fromIndex = 0, toIndex = 1, updatedTimestamp = 300L)
        org.junit.Assert.assertFalse(failMissing)
    }

    @Test
    fun remove_entry_and_compact_preserves_contiguous_sort_order() = runTest {
        val pid = dao.insertPlaylist(PlaylistEntity(title = "Mix", createdTimestamp = 100L, updatedTimestamp = 100L))

        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v0"), 101L)
        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v1"), 102L)
        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v2"), 103L)

        // Remove middle item v1
        dao.removeEntryAndCompact(pid, 1, "v1", 200L)

        val remaining = dao.getEntries(pid)
        assertEquals(listOf("v0", "v2"), remaining.map { it.videoId })
        assertEquals(listOf(0, 1), remaining.map { it.sortOrder })
    }

    @Test
    fun deleting_playlist_cascades_and_deletes_all_entries() = runTest {
        val pid = dao.insertPlaylist(PlaylistEntity(title = "Cascade Test", createdTimestamp = 100L, updatedTimestamp = 100L))
        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v0"), 101L)
        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v1"), 102L)

        assertEquals(2, dao.getEntries(pid).size)

        dao.deletePlaylist(pid)

        assertEquals(0, dao.getEntries(pid).size)
    }

    @Test
    fun observe_playlist_with_entries() = runTest {
        val pid = dao.insertPlaylist(PlaylistEntity(title = "With Entries", createdTimestamp = 100L, updatedTimestamp = 100L))
        dao.addEntryToEnd(entry(playlistId = pid, videoId = "v1"), 101L)

        val result = dao.observePlaylistWithEntries(pid).first()
        assertNotNull(result)
        assertEquals("With Entries", result?.playlist?.title)
        assertEquals(1, result?.entries?.size)
        assertEquals("v1", result?.entries?.first()?.videoId)
    }
}
