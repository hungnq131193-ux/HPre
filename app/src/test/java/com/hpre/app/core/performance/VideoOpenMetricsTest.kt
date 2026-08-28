package com.hpre.app.core.performance

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOpenMetricsTest {
    @Test fun records_monotonic_elapsed_time_and_ignores_stale_session() {
        var now = 100L
        val records = mutableListOf<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = true, nowMs = { now }, sink = records::add)
        val key = ContentKey(0, "video")

        val first = metrics.start(key)
        now = 125L
        metrics.mark(first, VideoOpenEvent.STREAM_INFO_READY)
        val second = metrics.start(key)
        now = 150L
        metrics.mark(first, VideoOpenEvent.PLAYER_PREPARE)
        metrics.finish(second, VideoOpenEvent.FIRST_FRAME)

        assertEquals(
            listOf(VideoOpenEvent.VIDEO_OPEN_START, VideoOpenEvent.STREAM_INFO_READY,
                VideoOpenEvent.VIDEO_OPEN_START, VideoOpenEvent.FIRST_FRAME),
            records.map { it.event }
        )
        assertEquals(25L, records[1].elapsedMs)
        assertEquals(25L, records.last().elapsedMs)
        assertTrue(second.generation > first.generation)
    }

    @Test fun disabled_metrics_emit_nothing() {
        val records = mutableListOf<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = false, sink = records::add)
        val session = metrics.start(ContentKey(0, "disabled"))
        metrics.mark(session, VideoOpenEvent.DETAILS_READY)
        assertTrue(records.isEmpty())
    }
}
