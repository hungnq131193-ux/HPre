package com.flowtube.app.repository

import com.flowtube.app.model.AudioStream
import com.flowtube.app.model.CatalogCacheValue
import com.flowtube.app.model.Channel
import com.flowtube.app.model.PlaylistSummary
import com.flowtube.app.model.SearchResultItem
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.SubtitleStream
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoStream
import com.flowtube.app.model.VideoSummary
import java.util.IdentityHashMap
import java.util.LinkedHashMap

class MetadataCache<K : Any, V : CatalogCacheValue>(
    private val ttlMs: Long = 60_000L,
    private val maxEntries: Int = 100
) {
    private data class Entry<V : CatalogCacheValue>(val value: V, val timestampMs: Long)

    private val lock = Any()
    private val map = object : LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
            return size > maxEntries
        }
    }

    private fun validateSafeValue(value: Any?, visited: IdentityHashMap<Any, Boolean> = IdentityHashMap(), depth: Int = 0) {
        if (value == null) return
        if (depth > 10) {
            throw IllegalArgumentException("Object graph exceeds maximum allowable inspection depth of 10")
        }
        if (visited.containsKey(value)) return
        visited[value] = true

        if (value is StreamInfo || value is VideoStream || value is AudioStream || value is SubtitleStream) {
            throw IllegalArgumentException("Stream metadata objects are prohibited from cache insertion: ${value.javaClass.simpleName}")
        }
        val className = value.javaClass.name
        if (className.contains("StreamInfo", ignoreCase = true) ||
            className.contains("VideoStream", ignoreCase = true) ||
            className.contains("AudioStream", ignoreCase = true) ||
            className.contains("SubtitleStream", ignoreCase = true) ||
            className.contains("Hls", ignoreCase = true) ||
            className.contains("Dash", ignoreCase = true)
        ) {
            throw IllegalArgumentException("Direct/manifest stream objects are prohibited: $className")
        }

        if (value is Iterable<*>) {
            for (item in value) {
                validateSafeValue(item, visited, depth + 1)
            }
        } else if (value is Map<*, *>) {
            for ((k, v) in value) {
                validateSafeValue(k, visited, depth + 1)
                validateSafeValue(v, visited, depth + 1)
            }
        } else if (value is Array<*>) {
            for (item in value) {
                validateSafeValue(item, visited, depth + 1)
            }
        } else if (value is CatalogCacheValue) {
            when (value) {
                is CatalogCacheValue.Trending -> validateSafeValue(value.items, visited, depth + 1)
                is CatalogCacheValue.Search -> validateSafeValue(value.page, visited, depth + 1)
                is CatalogCacheValue.Details -> validateSafeValue(value.details, visited, depth + 1)
                is CatalogCacheValue.Custom -> validateSafeValue(value.payload, visited, depth + 1)
            }
        } else {
            for (field in value.javaClass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val fieldValue = try {
                    field.isAccessible = true
                    field.get(value)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalAccessException) {
                    null
                } catch (_: ReflectiveOperationException) {
                    null
                } catch (e: Exception) {
                    // Only ignore reflection accessibility issues, but do not swallow unexpected exceptions
                    if (e.javaClass.name.contains("InaccessibleObjectException")) null else throw e
                }
                if (fieldValue != null) {
                    validateSafeValue(fieldValue, visited, depth + 1)
                }
            }
        }
    }

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

    fun put(key: K, value: V, nowMs: Long = System.currentTimeMillis()) {
        validateSafeValue(value)
        synchronized(lock) {
            map[key] = Entry(value, nowMs)
        }
    }

    fun remove(key: K): V? {
        synchronized(lock) {
            return map.remove(key)?.value
        }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    val size: Int
        get() = synchronized(lock) { map.size }
}

