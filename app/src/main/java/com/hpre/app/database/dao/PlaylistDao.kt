package com.hpre.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hpre.app.database.entity.PlaylistEntity
import com.hpre.app.database.entity.PlaylistEntryEntity
import com.hpre.app.database.relation.PlaylistWithEntries
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM local_playlists ORDER BY updatedTimestamp DESC")
    fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM local_playlists WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM local_playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Transaction
    @Query("SELECT * FROM local_playlists WHERE playlistId = :playlistId LIMIT 1")
    fun observePlaylistWithEntries(playlistId: Long): Flow<PlaylistWithEntries?>

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    suspend fun getEntries(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :playlistId AND serviceId = :serviceId AND videoId = :videoId LIMIT 1")
    suspend fun getEntry(playlistId: Long, serviceId: Int, videoId: String): PlaylistEntryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM local_playlist_entries WHERE playlistId = :playlistId")
    suspend fun getMaxSortOrder(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PlaylistEntryEntity)

    @Query("DELETE FROM local_playlist_entries WHERE playlistId = :playlistId AND serviceId = :serviceId AND videoId = :videoId")
    suspend fun deleteEntry(playlistId: Long, serviceId: Int, videoId: String)

    @Query("DELETE FROM local_playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Update
    suspend fun updateEntries(entries: List<PlaylistEntryEntity>)

    @Transaction
    suspend fun addEntryToEnd(entry: PlaylistEntryEntity, updatedTimestamp: Long) {
        val maxSort = getMaxSortOrder(entry.playlistId)
        val nextSort = maxSort + 1
        insertEntry(entry.copy(sortOrder = nextSort))
        val playlist = getPlaylistById(entry.playlistId)
        if (playlist != null) {
            updatePlaylist(playlist.copy(updatedTimestamp = updatedTimestamp))
        }
    }

    @Transaction
    suspend fun removeEntryAndCompact(playlistId: Long, serviceId: Int, videoId: String, updatedTimestamp: Long) {
        deleteEntry(playlistId, serviceId, videoId)
        val entries = getEntries(playlistId)
        val reindexed = entries.mapIndexed { index, entry ->
            entry.copy(sortOrder = index)
        }
        updateEntries(reindexed)
        val playlist = getPlaylistById(playlistId)
        if (playlist != null) {
            updatePlaylist(playlist.copy(updatedTimestamp = updatedTimestamp))
        }
    }

    @Transaction
    suspend fun reorderEntries(playlistId: Long, fromIndex: Int, toIndex: Int, updatedTimestamp: Long): Boolean {
        val playlist = getPlaylistById(playlistId) ?: return false
        val entries = getEntries(playlistId).toMutableList()
        if (entries.isEmpty()) return false
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) {
            return false
        }
        val item = entries.removeAt(fromIndex)
        entries.add(toIndex, item)
        val updated = entries.mapIndexed { index, entry ->
            entry.copy(sortOrder = index)
        }
        updateEntries(updated)
        updatePlaylist(playlist.copy(updatedTimestamp = updatedTimestamp))
        return true
    }
}
