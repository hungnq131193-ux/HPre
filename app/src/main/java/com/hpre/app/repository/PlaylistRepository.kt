package com.hpre.app.repository

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.flow.Flow

data class LocalPlaylist(
    val playlistId: Long,
    val title: String,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val entryCount: Int = 0
)

data class LocalPlaylistEntry(
    val playlistId: Long,
    val videoKey: ContentKey,
    val canonicalUrl: String,
    val title: String,
    val channelKey: ContentKey?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val addedTimestamp: Long,
    val sortOrder: Int
)

data class LocalPlaylistWithEntries(
    val playlist: LocalPlaylist,
    val entries: List<LocalPlaylistEntry>
)

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<LocalPlaylist>>
    fun observePlaylistWithEntries(playlistId: Long): Flow<LocalPlaylistWithEntries?>
    suspend fun getPlaylist(playlistId: Long): AppResult<LocalPlaylist?>
    suspend fun createPlaylist(title: String, timestamp: Long = System.currentTimeMillis()): AppResult<Long>
    suspend fun renamePlaylist(playlistId: Long, newTitle: String, timestamp: Long = System.currentTimeMillis()): AppResult<Unit>
    suspend fun deletePlaylist(playlistId: Long): AppResult<Unit>
    suspend fun addEntry(
        playlistId: Long,
        video: VideoSummary,
        addedTimestamp: Long = System.currentTimeMillis()
    ): AppResult<Unit>
    suspend fun removeEntry(
        playlistId: Long,
        videoKey: ContentKey,
        updatedTimestamp: Long = System.currentTimeMillis()
    ): AppResult<Unit>
    suspend fun reorderEntries(
        playlistId: Long,
        fromIndex: Int,
        toIndex: Int,
        updatedTimestamp: Long = System.currentTimeMillis()
    ): AppResult<Unit>
}
