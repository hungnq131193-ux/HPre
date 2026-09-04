package com.hpre.app.player

import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.hasUsableMediaUrls
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object PlaybackStreamHandoff {
    private const val MAX_ENTRIES = 16
    private const val DEFAULT_TTL_MS = 60_000L

    private data class HandoffEntry(
        val streamInfo: StreamInfo,
        val createdAtMs: Long,
        val requestGen: Long
    )

    private val values = ConcurrentHashMap<String, HandoffEntry>()
    private val lock = Any()

    fun put(
        value: StreamInfo,
        requestGeneration: Long,
        currentTimeMs: Long = System.currentTimeMillis()
    ): String = synchronized(lock) {
        evictExpiredAndOverflow(currentTimeMs)
        val token = UUID.randomUUID().toString()
        values[token] = HandoffEntry(value, currentTimeMs, requestGeneration)
        token
    }

    fun takeIfValid(
        token: String?,
        expectedKey: ContentKey,
        expectedRequestGeneration: Long,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StreamInfo? = synchronized(lock) {
        if (token.isNullOrBlank()) return null
        val entry = values.remove(token) ?: return null
        val age = currentTimeMs - entry.createdAtMs
        if (age !in 0L..DEFAULT_TTL_MS) return null
        if (entry.streamInfo.key != expectedKey) return null
        if (entry.requestGen != expectedRequestGeneration) return null
        if (!entry.streamInfo.hasUsableMediaUrls(currentTimeMs)) return null
        return entry.streamInfo
    }

    fun size(): Int = values.size

    fun remove(token: String?) {
        if (!token.isNullOrBlank()) {
            values.remove(token)
        }
    }

    fun clear() = synchronized(lock) {
        values.clear()
    }

    private fun evictExpiredAndOverflow(currentTimeMs: Long) {
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTimeMs - entry.value.createdAtMs > DEFAULT_TTL_MS) {
                iterator.remove()
            }
        }
        if (values.size >= MAX_ENTRIES) {
            val sorted = values.entries.sortedBy { it.value.createdAtMs }
            val toRemoveCount = (values.size - MAX_ENTRIES) + 1
            for (i in 0 until toRemoveCount.coerceAtMost(sorted.size)) {
                values.remove(sorted[i].key)
            }
        }
    }
}


