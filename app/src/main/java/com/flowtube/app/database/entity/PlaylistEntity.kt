package com.flowtube.app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_playlists",
    indices = [
        Index(value = ["updatedTimestamp"])
    ]
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val playlistId: Long = 0,
    val title: String,
    val createdTimestamp: Long,
    val updatedTimestamp: Long
)
