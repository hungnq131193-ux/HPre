package com.hpre.app.repository

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ContentKey
import kotlinx.coroutines.flow.Flow

data class LocalSubscription(
    val channelKey: ContentKey,
    val canonicalUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedTimestamp: Long
)

interface SubscriptionRepository {
    fun observeSubscriptions(): Flow<List<LocalSubscription>>
    fun observeIsSubscribed(key: ContentKey): Flow<Boolean>
    suspend fun isSubscribed(key: ContentKey): AppResult<Boolean>
    suspend fun subscribe(channel: Channel, subscribedTimestamp: Long = System.currentTimeMillis()): AppResult<Unit>
    suspend fun unsubscribe(key: ContentKey): AppResult<Unit>
    suspend fun clearSubscriptions(): AppResult<Unit>
}
