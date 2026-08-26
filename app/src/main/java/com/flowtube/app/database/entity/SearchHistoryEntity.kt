package com.flowtube.app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["searchedTimestamp"])
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey
    val query: String,
    val searchedTimestamp: Long
)
