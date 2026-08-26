package com.hpre.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "local_playlist_entries",
    primaryKeys = ["playlistId", "serviceId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId", "sortOrder"]),
        Index(value = ["playlistId"])
    ]
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val serviceId: Int,
    val videoId: String,
    val canonicalUrl: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val addedTimestamp: Long,
    val sortOrder: Int
)
