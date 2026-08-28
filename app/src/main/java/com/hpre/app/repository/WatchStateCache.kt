package com.hpre.app.repository

import com.hpre.app.model.CommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import java.util.LinkedHashMap

data class WatchStateSnapshot(
    val details: VideoDetails,
    val relatedVideos: List<VideoSummary>?,
    val comments: CommentPage?
)

class WatchStateCache(
    private val ttlMs: Long = 300_000L,
    private val maxEntries: Int = 10
) {
    private data class Entry(val snapshot: WatchStateSnapshot, val timestampMs: Long)

    private val lock = Any()
    private val map = object : LinkedHashMap<ContentKey, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContentKey, Entry>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(key: ContentKey, nowMs: Long = System.currentTimeMillis()): WatchStateSnapshot? {
        synchronized(lock) {
            val entry = map[key] ?: return null
            if (nowMs - entry.timestampMs >= ttlMs) {
                map.remove(key)
                return null
            }
            return entry.snapshot
        }
    }

    fun put(key: ContentKey, snapshot: WatchStateSnapshot, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            map[key] = Entry(snapshot, nowMs)
        }
    }

    fun updateRelated(key: ContentKey, relatedVideos: List<VideoSummary>) {
        synchronized(lock) {
            val entry = map[key] ?: return
            val existing = entry.snapshot
            map[key] = Entry(
                WatchStateSnapshot(
                    details = existing.details,
                    relatedVideos = relatedVideos,
                    comments = existing.comments
                ),
                entry.timestampMs
            )
        }
    }

    fun updateComments(key: ContentKey, comments: CommentPage) {
        synchronized(lock) {
            val entry = map[key] ?: return
            val existing = entry.snapshot
            map[key] = Entry(
                WatchStateSnapshot(
                    details = existing.details,
                    relatedVideos = existing.relatedVideos,
                    comments = comments
                ),
                entry.timestampMs
            )
        }
    }

    fun remove(key: ContentKey) {
        synchronized(lock) {
            map.remove(key)
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
