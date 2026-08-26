package com.hpre.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "watch_history",
    primaryKeys = ["serviceId", "videoId"],
    indices = [
        Index(value = ["watchedTimestamp"])
    ]
)
data class HistoryEntity(
    val serviceId: Int,
    val videoId: String,
    val canonicalUrl: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val playbackPositionMs: Long,
    val watchedTimestamp: Long
)
