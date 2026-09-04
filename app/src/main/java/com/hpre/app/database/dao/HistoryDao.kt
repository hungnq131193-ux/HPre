package com.hpre.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hpre.app.database.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedTimestamp DESC, serviceId ASC, videoId ASC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY watchedTimestamp DESC, serviceId ASC, videoId ASC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY watchedTimestamp DESC, serviceId ASC, videoId ASC LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int): Flow<List<HistoryEntity>>

    @Query("SELECT COUNT(*) FROM watch_history")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM watch_history WHERE serviceId = :serviceId AND videoId = :videoId LIMIT 1")
    suspend fun getByKey(serviceId: Int, videoId: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

    @Query("""
        DELETE FROM watch_history
        WHERE rowid NOT IN (
            SELECT rowid FROM watch_history
            ORDER BY watchedTimestamp DESC, serviceId ASC, videoId ASC
            LIMIT :maxEntries
        )
    """)
    suspend fun trimOldest(maxEntries: Int)

    @Transaction
    suspend fun recordAndTrim(entity: HistoryEntity, maxEntries: Int) {
        upsert(entity)
        trimOldest(maxEntries)
    }

    @Query("DELETE FROM watch_history WHERE serviceId = :serviceId AND videoId = :videoId")
    suspend fun deleteByKey(serviceId: Int, videoId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}

