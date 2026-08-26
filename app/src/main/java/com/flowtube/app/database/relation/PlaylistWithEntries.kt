package com.flowtube.app.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.flowtube.app.database.entity.PlaylistEntity
import com.flowtube.app.database.entity.PlaylistEntryEntity

data class PlaylistWithEntries(
    @Embedded
    val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "playlistId"
    )
    val entries: List<PlaylistEntryEntity>
)
