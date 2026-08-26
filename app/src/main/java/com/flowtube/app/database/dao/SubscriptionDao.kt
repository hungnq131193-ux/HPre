package com.flowtube.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.flowtube.app.database.entity.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM local_subscriptions ORDER BY subscribedTimestamp DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM local_subscriptions WHERE serviceId = :serviceId AND channelId = :channelId LIMIT 1")
    suspend fun getByKey(serviceId: Int, channelId: String): SubscriptionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM local_subscriptions WHERE serviceId = :serviceId AND channelId = :channelId)")
    fun observeIsSubscribed(serviceId: Int, channelId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM local_subscriptions WHERE serviceId = :serviceId AND channelId = :channelId)")
    suspend fun isSubscribed(serviceId: Int, channelId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM local_subscriptions WHERE serviceId = :serviceId AND channelId = :channelId")
    suspend fun deleteByKey(serviceId: Int, channelId: String)

    @Query("DELETE FROM local_subscriptions")
    suspend fun clearAll()
}
