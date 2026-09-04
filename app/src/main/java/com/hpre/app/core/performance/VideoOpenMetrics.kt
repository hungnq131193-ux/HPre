package com.hpre.app.core.performance

import android.os.SystemClock
import android.util.Log
import com.hpre.app.model.ContentKey
import java.util.concurrent.atomic.AtomicLong

enum class VideoOpenEvent {
    VIDEO_OPEN_START,
    STREAM_RESOLVE_START,
    EXTRACTOR_START,
    EXTRACTOR_FINISH,
    DETAILS_READY,
    STREAM_INFO_READY,
    PLAYER_PREPARE,
    PLAYER_READY,
    FIRST_FRAME,
    PLAYBACK_ERROR
}

enum class VideoOpenSegment {
    STREAM_RESOLVE_TO_STREAM_INFO_READY,
    STREAM_INFO_READY_TO_PLAYER_PREPARE,
    PLAYER_PREPARE_TO_FIRST_FRAME
}

class VideoOpenSession internal constructor(
    val key: ContentKey,
    val generation: Long,
    internal val startedAtMs: Long
) {
    internal val timestamps = mutableMapOf<VideoOpenEvent, Long>()
}

data class VideoOpenRecord(
    val generation: Long,
    val event: VideoOpenEvent,
    val elapsedMs: Long,
    val segment: VideoOpenSegment? = null,
    val segmentMs: Long? = null,
    val category: String? = null
)

class VideoOpenMetrics(
    private val enabled: Boolean = true,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val sink: (VideoOpenRecord) -> Unit = { record ->
        val segPart = if (record.segment != null && record.segmentMs != null) {
            " segment=${record.segment} segmentMs=${record.segmentMs}"
        } else ""
        val catPart = record.category?.let { " category=$it" }.orEmpty()
        Log.d(
            "HPrePerformance",
            "${record.event} generation=${record.generation} elapsedMs=${record.elapsedMs}$segPart$catPart"
        )
    }
) {
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private var pendingTap: VideoOpenSession? = null
    private var activeSession: VideoOpenSession? = null

    fun startTap(key: ContentKey): VideoOpenSession {
        val session = VideoOpenSession(key, generation.incrementAndGet(), nowMs())
        synchronized(lock) {
            pendingTap = session
            activeSession = null
        }
        recordTimestamp(session, VideoOpenEvent.VIDEO_OPEN_START, session.startedAtMs)
        emit(session, VideoOpenEvent.VIDEO_OPEN_START)
        return session
    }

    fun claimTap(key: ContentKey): VideoOpenSession? = synchronized(lock) {
        val tap = pendingTap
        if (tap != null && tap.key == key) {
            pendingTap = null
            activeSession = tap
            tap
        } else {
            null
        }
    }

    fun start(key: ContentKey): VideoOpenSession {
        val session = VideoOpenSession(key, generation.incrementAndGet(), nowMs())
        synchronized(lock) {
            pendingTap = null
            activeSession = session
        }
        recordTimestamp(session, VideoOpenEvent.VIDEO_OPEN_START, session.startedAtMs)
        emit(session, VideoOpenEvent.VIDEO_OPEN_START)
        return session
    }

    fun cancel(session: VideoOpenSession) {
        synchronized(lock) {
            if (pendingTap == session) pendingTap = null
            if (activeSession == session) activeSession = null
        }
    }

    fun activeSession(key: ContentKey): VideoOpenSession? = synchronized(lock) {
        if (activeSession?.key == key) activeSession else null
    }

    fun mark(session: VideoOpenSession, event: VideoOpenEvent, category: String? = null) {
        val (shouldEmit, segment, segmentMs) = synchronized(lock) {
            if (!isCurrentLocked(session)) return
            if (session.timestamps.containsKey(event)) return
            val currentTime = nowMs()
            session.timestamps[event] = currentTime

            val segInfo = segmentFor(event)
            val seg = segInfo?.first
            val startEvent = segInfo?.second
            val segMs = if (seg != null && startEvent != null) {
                session.timestamps[startEvent]?.let { startTs ->
                    (currentTime - startTs).coerceAtLeast(0L)
                }
            } else null
            Triple(true, seg, segMs)
        }
        if (shouldEmit) emit(session, event, category, segment, segmentMs)
    }

    fun finish(session: VideoOpenSession, event: VideoOpenEvent, category: String? = null) {
        val (shouldEmit, segment, segmentMs) = synchronized(lock) {
            if (!isCurrentLocked(session)) return
            val currentTime = nowMs()
            if (!session.timestamps.containsKey(event)) {
                session.timestamps[event] = currentTime
            }
            val segInfo = segmentFor(event)
            val seg = segInfo?.first
            val startEvent = segInfo?.second
            val segMs = if (seg != null && startEvent != null) {
                session.timestamps[startEvent]?.let { startTs ->
                    (currentTime - startTs).coerceAtLeast(0L)
                }
            } else null

            if (activeSession == session) activeSession = null
            if (pendingTap == session) pendingTap = null
            Triple(true, seg, segMs)
        }
        if (shouldEmit) emit(session, event, category, segment, segmentMs)
    }

    private fun isCurrentLocked(session: VideoOpenSession): Boolean =
        activeSession == session || pendingTap == session

    private fun recordTimestamp(session: VideoOpenSession, event: VideoOpenEvent, timestamp: Long) {
        synchronized(lock) {
            session.timestamps[event] = timestamp
        }
    }

    private fun emit(
        session: VideoOpenSession,
        event: VideoOpenEvent,
        category: String? = null,
        segment: VideoOpenSegment? = null,
        segmentMs: Long? = null
    ) {
        if (!enabled) return
        sink(
            VideoOpenRecord(
                generation = session.generation,
                event = event,
                elapsedMs = (nowMs() - session.startedAtMs).coerceAtLeast(0L),
                segment = segment,
                segmentMs = segmentMs,
                category = category
            )
        )
    }

    companion object {
        val Default = VideoOpenMetrics()

        private fun segmentFor(event: VideoOpenEvent): Pair<VideoOpenSegment, VideoOpenEvent>? = when (event) {
            VideoOpenEvent.STREAM_INFO_READY ->
                VideoOpenSegment.STREAM_RESOLVE_TO_STREAM_INFO_READY to VideoOpenEvent.STREAM_RESOLVE_START
            VideoOpenEvent.PLAYER_PREPARE ->
                VideoOpenSegment.STREAM_INFO_READY_TO_PLAYER_PREPARE to VideoOpenEvent.STREAM_INFO_READY
            VideoOpenEvent.FIRST_FRAME ->
                VideoOpenSegment.PLAYER_PREPARE_TO_FIRST_FRAME to VideoOpenEvent.PLAYER_PREPARE
            else -> null
        }
    }
}

