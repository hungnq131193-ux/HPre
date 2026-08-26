package com.flowtube.app.player

import com.flowtube.app.model.StreamInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object PlaybackStreamHandoff {
    private const val MAX_ENTRIES = 16
    private const val DEFAULT_TTL_MS = 60_000L // 60 seconds TTL

    private data class HandoffEntry(
        val streamInfo: StreamInfo,
        val createdAtMs: Long,
        val requestGen: Long = 0L
    )

    private val values = ConcurrentHashMap<String, HandoffEntry>()
    private val lock = Any()

    fun put(
        value: StreamInfo,
        currentTimeMs: Long = System.currentTimeMillis(),
        requestGen: Long = 0L
    ): String = synchronized(lock) {
        evictExpiredAndOverflow(currentTimeMs)
        val token = UUID.randomUUID().toString()
        values[token] = HandoffEntry(value, currentTimeMs, requestGen)
        token
    }

    fun take(token: String?, currentTimeMs: Long = System.currentTimeMillis()): StreamInfo? {
        if (token.isNullOrBlank()) return null
        val entry = values.remove(token) ?: return null
        if (currentTimeMs - entry.createdAtMs > DEFAULT_TTL_MS) {
            return null
        }
        return entry.streamInfo
    }

    fun takeConditional(
        token: String?,
        expectedRequestGen: Long,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StreamInfo? = synchronized(lock) {
        if (token.isNullOrBlank()) return null
        val entry = values[token] ?: return null
        if (currentTimeMs - entry.createdAtMs > DEFAULT_TTL_MS) {
            values.remove(token)
            return null
        }
        if (entry.requestGen != 0L && expectedRequestGen != 0L && entry.requestGen != expectedRequestGen) {
            values.remove(token)
            return null
        }
        values.remove(token)
        return entry.streamInfo
    }

    fun peek(token: String?, currentTimeMs: Long = System.currentTimeMillis()): StreamInfo? {
        if (token.isNullOrBlank()) return null
        val entry = values[token] ?: return null
        if (currentTimeMs - entry.createdAtMs > DEFAULT_TTL_MS) {
            return null
        }
        return entry.streamInfo
    }

    fun remove(token: String?) {
        if (!token.isNullOrBlank()) {
            values.remove(token)
        }
    }

    fun size(): Int = values.size

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
            // Remove oldest entries to keep under limit
            val sorted = values.entries.sortedBy { it.value.createdAtMs }
            val toRemoveCount = (values.size - MAX_ENTRIES) + 1
            for (i in 0 until toRemoveCount.coerceAtMost(sorted.size)) {
                values.remove(sorted[i].key)
            }
        }
    }
}


