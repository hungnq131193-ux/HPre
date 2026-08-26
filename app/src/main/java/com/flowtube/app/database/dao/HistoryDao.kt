package com.flowtube.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.flowtube.app.database.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedTimestamp DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE serviceId = :serviceId AND videoId = :videoId LIMIT 1")
    suspend fun getByKey(serviceId: Int, videoId: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

    @Query("DELETE FROM watch_history WHERE serviceId = :serviceId AND videoId = :videoId")
    suspend fun deleteByKey(serviceId: Int, videoId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()
}
