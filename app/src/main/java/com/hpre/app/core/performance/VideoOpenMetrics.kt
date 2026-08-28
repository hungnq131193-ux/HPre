package com.hpre.app.core.performance

import android.os.SystemClock
import android.util.Log
import com.hpre.app.BuildConfig
import com.hpre.app.model.ContentKey
import java.util.concurrent.atomic.AtomicLong

enum class VideoOpenEvent {
    VIDEO_OPEN_START,
    DETAILS_READY,
    STREAM_INFO_READY,
    PLAYER_PREPARE,
    PLAYER_READY,
    FIRST_FRAME
}

class VideoOpenSession internal constructor(
    val key: ContentKey,
    val generation: Long,
    internal val startedAtMs: Long
)

data class VideoOpenRecord(
    val key: ContentKey,
    val generation: Long,
    val event: VideoOpenEvent,
    val elapsedMs: Long
)

class VideoOpenMetrics(
    private val enabled: Boolean = BuildConfig.DEBUG,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val sink: (VideoOpenRecord) -> Unit = { record ->
        Log.d(
            "HPrePerformance",
            "${record.event} key=${record.key.serviceId}:${record.key.nativeId} " +
                "generation=${record.generation} elapsedMs=${record.elapsedMs}"
        )
    }
) {
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val active = mutableMapOf<ContentKey, VideoOpenSession>()

    fun start(key: ContentKey): VideoOpenSession {
        val session = VideoOpenSession(key, generation.incrementAndGet(), nowMs())
        synchronized(lock) { active[key] = session }
        emit(session, VideoOpenEvent.VIDEO_OPEN_START)
        return session
    }

    fun activeSession(key: ContentKey): VideoOpenSession? = synchronized(lock) { active[key] }

    fun mark(session: VideoOpenSession, event: VideoOpenEvent) {
        if (isCurrent(session)) emit(session, event)
    }

    fun finish(session: VideoOpenSession, event: VideoOpenEvent) {
        if (!isCurrent(session)) return
        emit(session, event)
        synchronized(lock) {
            if (active[session.key] == session) active.remove(session.key)
        }
    }

    private fun isCurrent(session: VideoOpenSession): Boolean =
        synchronized(lock) { active[session.key] == session }

    private fun emit(session: VideoOpenSession, event: VideoOpenEvent) {
        if (!enabled) return
        sink(
            VideoOpenRecord(
                key = session.key,
                generation = session.generation,
                event = event,
                elapsedMs = (nowMs() - session.startedAtMs).coerceAtLeast(0L)
            )
        )
    }

    companion object {
        val Default = VideoOpenMetrics()
    }
}
