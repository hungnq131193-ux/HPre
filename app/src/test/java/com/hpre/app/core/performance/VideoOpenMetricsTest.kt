package com.hpre.app.core.performance

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOpenMetricsTest {

    @Test
    fun tap_is_claimed_once_and_replaced_taps_are_stale() {
        var now = 100L
        val records = mutableListOf<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = true, nowMs = { now }, sink = records::add)
        val firstKey = ContentKey(0, "first")
        val secondKey = ContentKey(0, "second")

        val first = metrics.startTap(firstKey)
        val second = metrics.startTap(secondKey)

        assertNull(metrics.claimTap(firstKey))
        assertSame(second, metrics.claimTap(secondKey))
        assertNull(metrics.claimTap(secondKey))
        metrics.mark(first, VideoOpenEvent.STREAM_RESOLVE_START)
        assertEquals(2, records.count { it.event == VideoOpenEvent.VIDEO_OPEN_START })
    }

    @Test
    fun records_three_phase_durations_from_first_matching_mark() {
        var now = 1_000L
        val records = mutableListOf<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = true, nowMs = { now }, sink = records::add)
        val session = metrics.start(ContentKey(0, "video"))

        now = 1_010L
        metrics.mark(session, VideoOpenEvent.STREAM_RESOLVE_START)
        now = 1_040L
        metrics.mark(session, VideoOpenEvent.STREAM_INFO_READY)
        now = 1_055L
        metrics.mark(session, VideoOpenEvent.PLAYER_PREPARE)
        now = 1_090L
        metrics.finish(session, VideoOpenEvent.FIRST_FRAME, category = "PROGRESSIVE")

        assertEquals(
            listOf(30L, 15L, 35L),
            records.mapNotNull(VideoOpenRecord::segmentMs)
        )
        assertEquals(
            listOf(
                VideoOpenSegment.STREAM_RESOLVE_TO_STREAM_INFO_READY,
                VideoOpenSegment.STREAM_INFO_READY_TO_PLAYER_PREPARE,
                VideoOpenSegment.PLAYER_PREPARE_TO_FIRST_FRAME
            ),
            records.mapNotNull(VideoOpenRecord::segment)
        )
        assertEquals(90L, records.last().elapsedMs)
    }

    @Test
    fun cancel_retires_pending_or_active_session_without_emitting_identity() {
        val records = mutableListOf<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = true, nowMs = { 100L }, sink = records::add)
        val session = metrics.startTap(ContentKey(7, "https://secret.test/watch?token=abc"))

        metrics.cancel(session)
        metrics.mark(session, VideoOpenEvent.STREAM_RESOLVE_START)

        assertNull(metrics.claimTap(session.key))
        assertEquals(1, records.size)
        assertFalse(records.single().toString().contains("secret"))
        assertFalse(records.single().toString().contains("token"))
    }

    @Test
    fun duplicate_mark_and_replaced_or_cancelled_session_do_not_overwrite_or_emit_late() {
        var now = 100L
        val records = mutableListOf<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = true, nowMs = { now }, sink = records::add)
        val session = metrics.start(ContentKey(0, "video"))

        now = 120L
        metrics.mark(session, VideoOpenEvent.STREAM_RESOLVE_START)
        now = 150L
        metrics.mark(session, VideoOpenEvent.STREAM_RESOLVE_START)
        now = 160L
        metrics.mark(session, VideoOpenEvent.STREAM_INFO_READY)

        assertEquals(1, records.count { it.event == VideoOpenEvent.STREAM_RESOLVE_START })
        val readyRecord = records.first { it.event == VideoOpenEvent.STREAM_INFO_READY }
        assertEquals(40L, readyRecord.segmentMs)

        metrics.cancel(session)
        now = 200L
        metrics.mark(session, VideoOpenEvent.PLAYER_PREPARE)
        assertEquals(0, records.count { it.event == VideoOpenEvent.PLAYER_PREPARE })
    }

    @Test
    fun disabled_metrics_emit_nothing() {
        val records = mutableListOf<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = false, sink = records::add)
        val session = metrics.start(ContentKey(0, "disabled"))
        metrics.mark(session, VideoOpenEvent.DETAILS_READY)
        assertTrue(records.isEmpty())
    }

    @Test
    fun starting_another_key_replaces_the_only_active_session() {
        val metrics = VideoOpenMetrics(enabled = true, sink = {})
        val firstKey = ContentKey(0, "first")
        val secondKey = ContentKey(0, "second")

        metrics.start(firstKey)
        val second = metrics.start(secondKey)

        assertNull(metrics.activeSession(firstKey))
        assertEquals(second, metrics.activeSession(secondKey))
    }
}

