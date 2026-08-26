package com.flowtube.app.repository

import com.flowtube.app.core.error.AppResult
import kotlinx.coroutines.flow.Flow

data class LocalSearchHistoryItem(
    val query: String,
    val searchedTimestamp: Long
)

interface SearchHistoryRepository {
    fun observeRecentQueries(limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<LocalSearchHistoryItem>>
    suspend fun recordQuery(rawQuery: String, timestamp: Long = System.currentTimeMillis()): AppResult<Unit>
    suspend fun deleteQuery(rawQuery: String): AppResult<Unit>
    suspend fun clearHistory(): AppResult<Unit>

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 20
        const val MAX_HISTORY_ENTRIES = 100

        fun normalizeQuery(query: String): String {
            return query.trim().lowercase(java.util.Locale.ROOT).replace("\\s+".toRegex(), " ")
        }
    }
}
