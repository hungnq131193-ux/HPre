package com.hpre.app.repository

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.flow.Flow

data class WatchHistoryItem(
    val key: ContentKey,
    val canonicalUrl: String,
    val title: String,
    val channelKey: ContentKey?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val playbackPositionMs: Long,
    val watchedTimestamp: Long
)

interface HistoryRepository {
    fun observeHistory(): Flow<List<WatchHistoryItem>>
    suspend fun getHistoryItem(key: ContentKey): AppResult<WatchHistoryItem?>
    suspend fun recordHistory(
        summary: VideoSummary,
        positionMs: Long,
        watchedTimestamp: Long = System.currentTimeMillis()
    ): AppResult<Unit>
    suspend fun deleteHistoryItem(key: ContentKey): AppResult<Unit>
    suspend fun clearHistory(): AppResult<Unit>

    companion object {
        const val RESUME_COMPLETION_THRESHOLD_RATIO = 0.95

        fun shouldOfferResume(positionMs: Long, durationSeconds: Long?): Boolean {
            if (positionMs <= 0L) return false
            if (durationSeconds == null || durationSeconds <= 0L) return true
            val durationMs = durationSeconds * 1000L
            val ratio = positionMs.toDouble() / durationMs.toDouble()
            return ratio < RESUME_COMPLETION_THRESHOLD_RATIO
        }

        fun shouldOfferResumeMs(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs <= 0L) return false
            if (durationMs <= 0L) return true
            val ratio = positionMs.toDouble() / durationMs.toDouble()
            return ratio < RESUME_COMPLETION_THRESHOLD_RATIO
        }
    }
}
