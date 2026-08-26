package com.flowtube.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.flowtube.app.database.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedTimestamp DESC")
    fun observeAll(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedTimestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE query = :query LIMIT 1")
    suspend fun getByQuery(query: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteByQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun getCount(): Int

    @Query("""
        DELETE FROM search_history 
        WHERE query NOT IN (
            SELECT query FROM search_history ORDER BY searchedTimestamp DESC LIMIT :maxEntries
        )
    """)
    suspend fun trimOldest(maxEntries: Int)

    @Transaction
    suspend fun recordAndTrim(entity: SearchHistoryEntity, maxEntries: Int) {
        upsert(entity)
        trimOldest(maxEntries)
    }
}
