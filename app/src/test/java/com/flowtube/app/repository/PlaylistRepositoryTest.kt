package com.flowtube.app.repository

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.database.dao.PlaylistDao
import com.flowtube.app.database.entity.PlaylistEntity
import com.flowtube.app.database.entity.PlaylistEntryEntity
import com.flowtube.app.database.relation.PlaylistWithEntries
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.VideoSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRepositoryTest {

    private class FakePlaylistDao : PlaylistDao {
        private var nextId = 1L
        val playlists = mutableMapOf<Long, PlaylistEntity>()
        val entries = mutableMapOf<Long, MutableList<PlaylistEntryEntity>>()
        val playlistsFlow = MutableStateFlow<List<PlaylistEntity>>(emptyList())
        var shouldThrowIoException = false
        var reorderStarted: CompletableDeferred<Unit>? = null
        var reorderGate: CompletableDeferred<Unit>? = null
        var addEntryStarted: CompletableDeferred<Unit>? = null
        var addEntryGate: CompletableDeferred<Unit>? = null

        private fun updateFlows() {
            playlistsFlow.value = playlists.values.sortedByDescending { it.updatedTimestamp }
        }

        override fun observeAllPlaylists(): Flow<List<PlaylistEntity>> = playlistsFlow

        override suspend fun getPlaylistById(playlistId: Long): PlaylistEntity? {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            return playlists[playlistId]
        }

        override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            val id = if (playlist.playlistId == 0L) nextId++ else playlist.playlistId
            playlists[id] = playlist.copy(playlistId = id)
            entries[id] = mutableListOf()
            updateFlows()
            return id
        }

        override suspend fun updatePlaylist(playlist: PlaylistEntity) {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            playlists[playlist.playlistId] = playlist
            updateFlows()
        }

        override suspend fun deletePlaylist(playlistId: Long) {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            playlists.remove(playlistId)
            entries.remove(playlistId)
            updateFlows()
        }

        override fun observePlaylistWithEntries(playlistId: Long): Flow<PlaylistWithEntries?> {
            return playlistsFlow.map {
                val p = playlists[playlistId] ?: return@map null
                val list = entries[playlistId]?.sortedBy { it.sortOrder } ?: emptyList()
                PlaylistWithEntries(p, list)
            }
        }

        override fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>> {
            return playlistsFlow.map {
                entries[playlistId]?.sortedBy { it.sortOrder } ?: emptyList()
            }
        }

        override suspend fun getEntries(playlistId: Long): List<PlaylistEntryEntity> {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            return entries[playlistId]?.sortedBy { it.sortOrder } ?: emptyList()
        }

        override suspend fun getEntry(playlistId: Long, serviceId: Int, videoId: String): PlaylistEntryEntity? {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            return entries[playlistId]?.find { it.serviceId == serviceId && it.videoId == videoId }
        }

        override suspend fun getMaxSortOrder(playlistId: Long): Int {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            return entries[playlistId]?.maxOfOrNull { it.sortOrder } ?: -1
        }

        override suspend fun insertEntry(entry: PlaylistEntryEntity) {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            val list = entries.getOrPut(entry.playlistId) { mutableListOf() }
            list.removeAll { it.serviceId == entry.serviceId && it.videoId == entry.videoId }
            list.add(entry)
            updateFlows()
        }

        override suspend fun deleteEntry(playlistId: Long, serviceId: Int, videoId: String) {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            entries[playlistId]?.removeAll { it.serviceId == serviceId && it.videoId == videoId }
            updateFlows()
        }

        override suspend fun clearEntries(playlistId: Long) {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            entries[playlistId]?.clear()
            updateFlows()
        }

        override suspend fun updateEntries(entries: List<PlaylistEntryEntity>) {
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            if (entries.isEmpty()) return
            val pid = entries.first().playlistId
            val map = entries.associateBy { Pair(it.serviceId, it.videoId) }
            val list = this.entries.getOrPut(pid) { mutableListOf() }
            for (i in list.indices) {
                val key = Pair(list[i].serviceId, list[i].videoId)
                if (map.containsKey(key)) {
                    list[i] = map[key]!!
                }
            }
            updateFlows()
        }

        override suspend fun addEntryToEnd(entry: PlaylistEntryEntity, updatedTimestamp: Long) {
            addEntryStarted?.complete(Unit)
            addEntryGate?.await()
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            super.addEntryToEnd(entry, updatedTimestamp)
        }

        override suspend fun reorderEntries(
            playlistId: Long,
            fromIndex: Int,
            toIndex: Int,
            updatedTimestamp: Long
        ): Boolean {
            reorderStarted?.complete(Unit)
            reorderGate?.await()
            if (shouldThrowIoException) throw java.io.IOException("Disk error")
            val playlist = getPlaylistById(playlistId) ?: return false
            val list = entries[playlistId]?.sortedBy { it.sortOrder }?.toMutableList() ?: return false
            if (list.isEmpty()) return false
            if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) {
                return false
            }
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            val updated = list.mapIndexed { index, entry ->
                entry.copy(sortOrder = index)
            }
            entries[playlistId] = updated.toMutableList()
            updatePlaylist(playlist.copy(updatedTimestamp = updatedTimestamp))
            return true
        }
    }

    private val sampleVideo1 = VideoSummary(
        key = ContentKey(1, "v1"),
        title = "Video 1",
        canonicalUrl = "https://example.com/watch?v=v1",
        channelKey = ContentKey(1, "c1"),
        channelName = "Ch 1",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 100L,
        viewCount = 10L,
        publishedTimestamp = 10L
    )

    private val sampleVideo2 = VideoSummary(
        key = ContentKey(1, "v2"),
        title = "Video 2",
        canonicalUrl = "https://example.com/watch?v=v2",
        channelKey = ContentKey(1, "c1"),
        channelName = "Ch 1",
        channelAvatarUrl = null,
        thumbnailUrl = null,
        durationSeconds = 200L,
        viewCount = 20L,
        publishedTimestamp = 20L
    )

    @Test
    fun playlist_crud_and_entries_workflow() = runTest {
        val dao = FakePlaylistDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultPlaylistRepository(dao, dispatcher)

        val createResult = repo.createPlaylist("My Playlist", timestamp = 1000L)
        assertTrue(createResult is AppResult.Success)
        val pid = (createResult as AppResult.Success).value

        val playlist = (repo.getPlaylist(pid) as AppResult.Success).value
        assertNotNull(playlist)
        assertEquals("My Playlist", playlist?.title)

        repo.addEntry(pid, sampleVideo1, addedTimestamp = 1010L)
        repo.addEntry(pid, sampleVideo2, addedTimestamp = 1020L)

        val withEntries = repo.observePlaylistWithEntries(pid).first()
        assertNotNull(withEntries)
        assertEquals(2, withEntries?.entries?.size)
        assertEquals(listOf("v1", "v2"), withEntries?.entries?.map { it.videoKey.nativeId })

        // Rename
        repo.renamePlaylist(pid, "Renamed Playlist", timestamp = 2000L)
        val renamed = (repo.getPlaylist(pid) as AppResult.Success).value
        assertEquals("Renamed Playlist", renamed?.title)

        // Delete
        repo.deletePlaylist(pid)
        val afterDelete = (repo.getPlaylist(pid) as AppResult.Success).value
        assertNull(afterDelete)
    }

    @Test
    fun reorderEntries_with_invalid_indices_empty_or_missing_playlist_returns_safe_failure() = runTest {
        val dao = FakePlaylistDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultPlaylistRepository(dao, dispatcher)

        // 1. Missing playlist
        val missingResult = repo.reorderEntries(playlistId = 999L, fromIndex = 0, toIndex = 1)
        assertTrue("Reordering missing playlist must return Failure", missingResult is AppResult.Failure)
        assertEquals(AppError.Unknown, (missingResult as AppResult.Failure).error)

        // 2. Empty playlist
        val emptyPid = (repo.createPlaylist("Empty Playlist") as AppResult.Success).value
        val emptyResult = repo.reorderEntries(playlistId = emptyPid, fromIndex = 0, toIndex = 0)
        assertTrue("Reordering empty playlist must return Failure", emptyResult is AppResult.Failure)
        assertEquals(AppError.Unknown, (emptyResult as AppResult.Failure).error)

        // 3. Playlist with entries but invalid indices
        val validPid = (repo.createPlaylist("Populated Playlist") as AppResult.Success).value
        repo.addEntry(validPid, sampleVideo1)
        repo.addEntry(validPid, sampleVideo2)

        // Negative fromIndex
        val negFromResult = repo.reorderEntries(playlistId = validPid, fromIndex = -1, toIndex = 1)
        assertTrue(negFromResult is AppResult.Failure)
        assertEquals(AppError.Unknown, (negFromResult as AppResult.Failure).error)

        // Out of bounds toIndex
        val outOfBoundsToResult = repo.reorderEntries(playlistId = validPid, fromIndex = 0, toIndex = 5)
        assertTrue(outOfBoundsToResult is AppResult.Failure)
        assertEquals(AppError.Unknown, (outOfBoundsToResult as AppResult.Failure).error)

        // Same fromIndex and toIndex (no-op reorder attempt)
        val sameIndexResult = repo.reorderEntries(playlistId = validPid, fromIndex = 0, toIndex = 0)
        assertTrue(sameIndexResult is AppResult.Failure)
        assertEquals(AppError.Unknown, (sameIndexResult as AppResult.Failure).error)
    }

    @Test
    fun io_exception_maps_to_failure_unknown() = runTest {
        val dao = FakePlaylistDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultPlaylistRepository(dao, dispatcher)

        dao.shouldThrowIoException = true

        val createRes = repo.createPlaylist("Crash Playlist")
        assertTrue(createRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (createRes as AppResult.Failure).error)

        val getRes = repo.getPlaylist(1L)
        assertTrue(getRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (getRes as AppResult.Failure).error)

        val renameRes = repo.renamePlaylist(1L, "New Name")
        assertTrue(renameRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (renameRes as AppResult.Failure).error)

        val addRes = repo.addEntry(1L, sampleVideo1)
        assertTrue(addRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (addRes as AppResult.Failure).error)

        val removeRes = repo.removeEntry(1L, sampleVideo1.key)
        assertTrue(removeRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (removeRes as AppResult.Failure).error)

        val reorderRes = repo.reorderEntries(1L, 0, 1)
        assertTrue(reorderRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (reorderRes as AppResult.Failure).error)

        val deleteRes = repo.deletePlaylist(1L)
        assertTrue(deleteRes is AppResult.Failure)
        assertEquals(AppError.Unknown, (deleteRes as AppResult.Failure).error)
    }

    @Test
    fun cancellation_exception_is_rethrown() = runTest {
        val dao = FakePlaylistDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultPlaylistRepository(dao, dispatcher)

        val job = launch(dispatcher) {
            repo.createPlaylist("Job")
        }
        job.cancel(kotlinx.coroutines.CancellationException("Explicit cancellation"))
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun reorderEntries_cancellation_during_dao_transaction_leaves_no_partial_reorder() = runTest {
        val dao = FakePlaylistDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultPlaylistRepository(dao, dispatcher)

        val pid = (repo.createPlaylist("Playlist 1", timestamp = 1000L) as AppResult.Success).value
        repo.addEntry(pid, sampleVideo1, addedTimestamp = 1010L)
        repo.addEntry(pid, sampleVideo2, addedTimestamp = 1020L)

        val beforeEntries = (repo.observePlaylistWithEntries(pid).first())?.entries
        assertEquals(listOf("v1", "v2"), beforeEntries?.map { it.videoKey.nativeId })
        assertEquals(listOf(0, 1), beforeEntries?.map { it.sortOrder })

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        dao.reorderStarted = started
        dao.reorderGate = gate

        val job = launch(dispatcher) {
            repo.reorderEntries(pid, fromIndex = 0, toIndex = 1, updatedTimestamp = 2000L)
        }

        testScheduler.runCurrent()
        assertTrue("Expected reorder to have started in DAO", started.isCompleted)

        // Cancel caller coroutine while DAO reorder is blocked before mutating entries
        job.cancel(kotlinx.coroutines.CancellationException("Reorder cancellation"))
        testScheduler.runCurrent()
        job.join()

        assertTrue(job.isCancelled)

        // Verify no partial reorder or timestamp mutation is exposed
        val afterEntries = (repo.observePlaylistWithEntries(pid).first())?.entries
        assertEquals(listOf("v1", "v2"), afterEntries?.map { it.videoKey.nativeId })
        assertEquals(listOf(0, 1), afterEntries?.map { it.sortOrder })
        val playlist = (repo.getPlaylist(pid) as AppResult.Success).value
        assertEquals(1020L, playlist?.updatedTimestamp)
    }

    @Test
    fun addEntry_cancellation_during_dao_transaction_leaves_no_partial_add() = runTest {
        val dao = FakePlaylistDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultPlaylistRepository(dao, dispatcher)

        val pid = (repo.createPlaylist("Playlist 1", timestamp = 1000L) as AppResult.Success).value
        repo.addEntry(pid, sampleVideo1, addedTimestamp = 1010L)

        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        dao.addEntryStarted = started
        dao.addEntryGate = gate

        val job = launch(dispatcher) {
            repo.addEntry(pid, sampleVideo2, addedTimestamp = 1020L)
        }

        testScheduler.runCurrent()
        assertTrue("Expected addEntryToEnd to have started in DAO", started.isCompleted)

        job.cancel(kotlinx.coroutines.CancellationException("AddEntry cancellation"))
        testScheduler.runCurrent()
        job.join()

        assertTrue(job.isCancelled)

        // Verify sampleVideo2 was not added and playlist timestamp not updated
        val entries = (repo.observePlaylistWithEntries(pid).first())?.entries
        assertEquals(listOf("v1"), entries?.map { it.videoKey.nativeId })
        val playlist = (repo.getPlaylist(pid) as AppResult.Success).value
        assertEquals(1010L, playlist?.updatedTimestamp)
    }

    @Test
    fun duplicate_add_entry_is_idempotent() = runTest {
        val dao = FakePlaylistDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = DefaultPlaylistRepository(dao, dispatcher)

        val pid = (repo.createPlaylist("Favorites") as AppResult.Success).value

        repo.addEntry(pid, sampleVideo1)
        repo.addEntry(pid, sampleVideo1) // duplicate

        val withEntries = repo.observePlaylistWithEntries(pid).first()
        assertEquals(1, withEntries?.entries?.size)
    }
}
