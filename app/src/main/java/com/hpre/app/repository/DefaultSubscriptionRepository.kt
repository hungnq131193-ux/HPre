package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.database.dao.SubscriptionDao
import com.hpre.app.database.entity.SubscriptionEntity
import com.hpre.app.model.Channel
import com.hpre.app.model.ContentKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class DefaultSubscriptionRepository(
    private val subscriptionDao: SubscriptionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SubscriptionRepository {

    override fun observeSubscriptions(): Flow<List<LocalSubscription>> {
        return subscriptionDao.observeAll().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun observeIsSubscribed(key: ContentKey): Flow<Boolean> {
        return subscriptionDao.observeIsSubscribed(key.serviceId, key.nativeId)
    }

    override suspend fun isSubscribed(key: ContentKey): AppResult<Boolean> = withContext(ioDispatcher) {
        try {
            AppResult.Success(subscriptionDao.isSubscribed(key.serviceId, key.nativeId))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun subscribe(channel: Channel, subscribedTimestamp: Long): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val entity = SubscriptionEntity(
                serviceId = channel.key.serviceId,
                channelId = channel.key.nativeId,
                canonicalUrl = channel.canonicalUrl,
                name = channel.name,
                avatarUrl = channel.avatarUrl,
                subscribedTimestamp = subscribedTimestamp
            )
            subscriptionDao.upsert(entity)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun unsubscribe(key: ContentKey): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            subscriptionDao.deleteByKey(key.serviceId, key.nativeId)
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    override suspend fun clearSubscriptions(): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            subscriptionDao.clearAll()
            AppResult.Success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown)
        }
    }

    private fun SubscriptionEntity.toDomain(): LocalSubscription {
        return LocalSubscription(
            channelKey = ContentKey(serviceId, channelId),
            canonicalUrl = canonicalUrl,
            name = name,
            avatarUrl = avatarUrl,
            subscribedTimestamp = subscribedTimestamp
        )
    }
}
