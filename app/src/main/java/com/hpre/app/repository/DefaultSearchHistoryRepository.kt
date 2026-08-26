package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.database.dao.SearchHistoryDao
import com.hpre.app.database.entity.SearchHistoryEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class DefaultSearchHistoryRepository(
    private val searchHistoryDao: SearchHistoryDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxEntries: Int = SearchHistoryRepository.MAX_HISTORY_ENTRIES
) : SearchHistoryRepository {

    private val maxEntries: Int = maxEntries.coerceIn(1, SearchHistoryRepository.MAX_HISTORY_ENTRIES)

    override fun observeRecentQueries(limit: Int): Flow<List<LocalSearchHistoryItem>> {
        val clampedLimit = limit.coerceIn(1, SearchHistoryRepository.MAX_HISTORY_ENTRIES)
        return searchHistoryDao.observeRecent(clampedLimit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun recordQuery(rawQuery: String, timestamp: Long): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val normalized = SearchHistoryRepository.normalizeQuery(rawQuery)
            if (normalized.isBlank()) {
                return@withContext AppResult.Success(Unit)
            }
            val entity = SearchHistoryEntity(
                query = normalized,
                searchedTimestamp = timestamp
            )
            searchHistoryDao.recordAndTrim(entity, maxEntries)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun deleteQuery(rawQuery: String): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val normalized = SearchHistoryRepository.normalizeQuery(rawQuery)
            searchHistoryDao.deleteByQuery(normalized)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun clearHistory(): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            searchHistoryDao.clearAll()
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    private fun SearchHistoryEntity.toDomain(): LocalSearchHistoryItem {
        return LocalSearchHistoryItem(
            query = query,
            searchedTimestamp = searchedTimestamp
        )
    }
}
