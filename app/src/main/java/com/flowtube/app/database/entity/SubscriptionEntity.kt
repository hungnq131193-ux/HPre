package com.flowtube.app.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "local_subscriptions",
    primaryKeys = ["serviceId", "channelId"],
    indices = [
        Index(value = ["subscribedTimestamp"])
    ]
)
data class SubscriptionEntity(
    val serviceId: Int,
    val channelId: String,
    val canonicalUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedTimestamp: Long
)
