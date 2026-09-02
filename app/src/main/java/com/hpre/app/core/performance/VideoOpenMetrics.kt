package com.hpre.app.core.performance

import android.os.SystemClock
import android.util.Log
import com.hpre.app.model.ContentKey
import java.util.concurrent.atomic.AtomicLong

enum class VideoOpenEvent {
    VIDEO_OPEN_START,
    EXTRACTOR_START,
    EXTRACTOR_FINISH,
    DETAILS_READY,
    STREAM_INFO_READY,
    PLAYER_PREPARE,
    PLAYER_READY,
    FIRST_FRAME,
    PLAYBACK_ERROR
}

class VideoOpenSession internal constructor(
    val key: ContentKey,
    val generation: Long,
    internal val startedAtMs: Long
)

data class VideoOpenRecord(
    val generation: Long,
    val event: VideoOpenEvent,
    val elapsedMs: Long,
    val category: String? = null
)

class VideoOpenMetrics(
    private val enabled: Boolean = true,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val sink: (VideoOpenRecord) -> Unit = { record ->
        Log.d(
            "HPrePerformance",
            "${record.event} generation=${record.generation} elapsedMs=${record.elapsedMs}" +
                record.category?.let { " category=$it" }.orEmpty()
        )
    }
) {
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val active = mutableMapOf<ContentKey, VideoOpenSession>()

    fun start(key: ContentKey): VideoOpenSession {
        val session = VideoOpenSession(key, generation.incrementAndGet(), nowMs())
        synchronized(lock) {
            active.clear()
            active[key] = session
        }
        emit(session, VideoOpenEvent.VIDEO_OPEN_START)
        return session
    }

    fun activeSession(key: ContentKey): VideoOpenSession? = synchronized(lock) { active[key] }

    fun mark(session: VideoOpenSession, event: VideoOpenEvent, category: String? = null) {
        if (isCurrent(session)) emit(session, event, category)
    }

    fun finish(session: VideoOpenSession, event: VideoOpenEvent, category: String? = null) {
        if (!isCurrent(session)) return
        emit(session, event, category)
        synchronized(lock) {
            if (active[session.key] == session) active.remove(session.key)
        }
    }

    private fun isCurrent(session: VideoOpenSession): Boolean =
        synchronized(lock) { active[session.key] == session }

    private fun emit(session: VideoOpenSession, event: VideoOpenEvent, category: String? = null) {
        if (!enabled) return
        sink(
            VideoOpenRecord(
                generation = session.generation,
                event = event,
                elapsedMs = (nowMs() - session.startedAtMs).coerceAtLeast(0L),
                category = category
            )
        )
    }

    companion object {
        val Default = VideoOpenMetrics()
    }
}
