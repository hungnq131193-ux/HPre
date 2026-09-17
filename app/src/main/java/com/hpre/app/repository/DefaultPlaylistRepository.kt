package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.database.dao.PlaylistDao
import com.hpre.app.database.entity.PlaylistEntity
import com.hpre.app.database.entity.PlaylistEntryEntity
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class DefaultPlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<LocalPlaylist>> {
        return playlistDao.observeAllPlaylists().map { playlists ->
            playlists.map { it.toDomain() }
        }
    }

    override fun observePlaylistWithEntries(playlistId: Long): Flow<LocalPlaylistWithEntries?> {
        return playlistDao.observePlaylistWithEntries(playlistId).map { rel ->
            rel?.let {
                LocalPlaylistWithEntries(
                    playlist = it.playlist.toDomain(entryCount = it.entries.size),
                    entries = it.entries.map { entry -> entry.toDomain() }
                )
            }
        }
    }

    override suspend fun getPlaylist(playlistId: Long): AppResult<LocalPlaylist?> = withContext(ioDispatcher) {
        try {
            val entity = playlistDao.getPlaylistById(playlistId)
            val count = if (entity != null) playlistDao.getEntries(playlistId).size else 0
            AppResult.Success(entity?.toDomain(entryCount = count))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun createPlaylist(title: String, timestamp: Long): AppResult<Long> = withContext(ioDispatcher) {
        try {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) {
                return@withContext AppResult.Failure(AppError.Unknown)
            }
            val id = playlistDao.insertPlaylist(
                PlaylistEntity(
                    title = cleanTitle,
                    createdTimestamp = timestamp,
                    updatedTimestamp = timestamp
                )
            )
            AppResult.Success(id)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun renamePlaylist(playlistId: Long, newTitle: String, timestamp: Long): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val cleanTitle = newTitle.trim()
            if (cleanTitle.isEmpty()) {
                return@withContext AppResult.Failure(AppError.Unknown)
            }
            val existing = playlistDao.getPlaylistById(playlistId)
                ?: return@withContext AppResult.Failure(AppError.ContentUnavailable)
            playlistDao.updatePlaylist(
                existing.copy(
                    title = cleanTitle,
                    updatedTimestamp = timestamp
                )
            )
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            playlistDao.deletePlaylist(playlistId)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun addEntry(
        playlistId: Long,
        video: VideoSummary,
        addedTimestamp: Long
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val existing = playlistDao.getEntry(playlistId, video.key.serviceId, video.key.nativeId)
            if (existing != null) {
                // Entry already exists in this playlist
                return@withContext AppResult.Success(Unit)
            }
            val entry = PlaylistEntryEntity(
                playlistId = playlistId,
                serviceId = video.key.serviceId,
                videoId = video.key.nativeId,
                canonicalUrl = video.canonicalUrl,
                title = video.title,
                channelId = video.channelKey?.nativeId,
                channelName = video.channelName,
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds,
                addedTimestamp = addedTimestamp,
                sortOrder = 0 // addEntryToEnd will compute the next sortOrder atomically
            )
            playlistDao.addEntryToEnd(entry, addedTimestamp)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun removeEntry(
        playlistId: Long,
        videoKey: ContentKey,
        updatedTimestamp: Long
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            playlistDao.removeEntryAndCompact(playlistId, videoKey.serviceId, videoKey.nativeId, updatedTimestamp)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun reorderEntries(
        playlistId: Long,
        fromIndex: Int,
        toIndex: Int,
        updatedTimestamp: Long
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val success = playlistDao.reorderEntries(playlistId, fromIndex, toIndex, updatedTimestamp)
            if (success) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.Unknown)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    private fun PlaylistEntity.toDomain(entryCount: Int = 0): LocalPlaylist {
        return LocalPlaylist(
            playlistId = playlistId,
            title = title,
            createdTimestamp = createdTimestamp,
            updatedTimestamp = updatedTimestamp,
            entryCount = entryCount
        )
    }

    private fun PlaylistEntryEntity.toDomain(): LocalPlaylistEntry {
        return LocalPlaylistEntry(
            playlistId = playlistId,
            videoKey = ContentKey(serviceId, videoId),
            canonicalUrl = canonicalUrl,
            title = title,
            channelKey = channelId?.let { ContentKey(serviceId, it) },
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            addedTimestamp = addedTimestamp,
            sortOrder = sortOrder
        )
    }
}
