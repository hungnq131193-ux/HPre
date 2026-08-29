package com.hpre.app.repository

import java.util.LinkedHashMap

/**
 * Small in-memory cache with per-entry expiry and least-recently-used eviction.
 *
 * Backs the "render what we already have, revalidate behind it" behaviour on Home and Search. Both
 * screens re-request the same handful of feeds constantly (chip A to chip B and back, back
 * navigation from Watch into a search result list), and each of those round trips previously wiped
 * the list and drew a spinner. Caching the last few responses turns those returns into an immediate
 * render.
 *
 * Entries are returned past their TTL via [getStale] so callers can paint stale content instantly
 * and refresh underneath, rather than choosing between a spinner and nothing.
 */
class TtlLruCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val maxEntries: Int
) {
    private data class Entry<V>(val value: V, val timestampMs: Long)

    private val lock = Any()
    private val map = object : LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
            return size > maxEntries
        }
    }

    /** Cached value, or null when absent or expired. Expired entries are dropped. */
    fun get(key: K, nowMs: Long = System.currentTimeMillis()): V? {
        synchronized(lock) {
            val entry = map[key] ?: return null
            if (nowMs - entry.timestampMs >= ttlMs) {
                map.remove(key)
                return null
            }
            return entry.value
        }
    }

    /**
     * Cached value plus whether it is past its TTL, without evicting it.
     *
     * Lets a caller render immediately and decide separately whether to revalidate.
     */
    fun getStale(key: K, nowMs: Long = System.currentTimeMillis()): StaleEntry<V>? {
        synchronized(lock) {
            val entry = map[key] ?: return null
            return StaleEntry(entry.value, isStale = nowMs - entry.timestampMs >= ttlMs)
        }
    }

    fun put(key: K, value: V, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            map[key] = Entry(value, nowMs)
        }
    }

    fun remove(key: K) {
        synchronized(lock) { map.remove(key) }
    }

    fun clear() {
        synchronized(lock) { map.clear() }
    }

    val size: Int
        get() = synchronized(lock) { map.size }

    data class StaleEntry<V>(val value: V, val isStale: Boolean)
}
