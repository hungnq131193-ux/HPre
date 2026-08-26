package com.flowtube.app.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.flowtube.app.database.dao.HistoryDao
import com.flowtube.app.database.dao.PlaylistDao
import com.flowtube.app.database.dao.SearchHistoryDao
import com.flowtube.app.database.dao.SubscriptionDao
import com.flowtube.app.database.entity.HistoryEntity
import com.flowtube.app.database.entity.PlaylistEntity
import com.flowtube.app.database.entity.PlaylistEntryEntity
import com.flowtube.app.database.entity.SearchHistoryEntity
import com.flowtube.app.database.entity.SubscriptionEntity

@Database(
    entities = [
        HistoryEntity::class,
        SubscriptionEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class FlowTubeDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
