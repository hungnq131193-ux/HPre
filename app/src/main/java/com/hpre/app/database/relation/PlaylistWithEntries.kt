package com.hpre.app.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.hpre.app.database.entity.PlaylistEntity
import com.hpre.app.database.entity.PlaylistEntryEntity

data class PlaylistWithEntries(
    @Embedded
    val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "playlistId"
    )
    val entries: List<PlaylistEntryEntity>
)
