package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.database.dao.HistoryDao
import com.hpre.app.database.entity.HistoryEntity
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.settings.PlaybackPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class DefaultHistoryRepository(
    private val historyDao: HistoryDao,
    private val playbackPreferences: PlaybackPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : HistoryRepository {

    override fun observeHistory(): Flow<List<WatchHistoryItem>> {
        return historyDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getHistoryItem(key: ContentKey): AppResult<WatchHistoryItem?> = withContext(ioDispatcher) {
        try {
            val entity = historyDao.getByKey(key.serviceId, key.nativeId)
            AppResult.Success(entity?.toDomain())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun recordHistory(
        summary: VideoSummary,
        positionMs: Long,
        watchedTimestamp: Long
    ): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val historyEnabled = playbackPreferences.isHistoryEnabled.first()
            if (!historyEnabled) {
                return@withContext AppResult.Success(Unit)
            }

            val effectivePosition = if (HistoryRepository.shouldOfferResume(positionMs, summary.durationSeconds)) {
                positionMs
            } else {
                0L
            }

            val entity = HistoryEntity(
                serviceId = summary.key.serviceId,
                videoId = summary.key.nativeId,
                canonicalUrl = summary.canonicalUrl,
                title = summary.title,
                channelId = summary.channelKey?.nativeId,
                channelName = summary.channelName,
                thumbnailUrl = summary.thumbnailUrl,
                durationSeconds = summary.durationSeconds,
                playbackPositionMs = effectivePosition,
                watchedTimestamp = watchedTimestamp
            )
            historyDao.upsert(entity)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun deleteHistoryItem(key: ContentKey): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            historyDao.deleteByKey(key.serviceId, key.nativeId)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun clearHistory(): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            historyDao.clearAll()
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    private fun HistoryEntity.toDomain(): WatchHistoryItem {
        return WatchHistoryItem(
            key = ContentKey(serviceId, videoId),
            canonicalUrl = canonicalUrl,
            title = title,
            channelKey = channelId?.let { ContentKey(serviceId, it) },
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            playbackPositionMs = playbackPositionMs,
            watchedTimestamp = watchedTimestamp
        )
    }
}
