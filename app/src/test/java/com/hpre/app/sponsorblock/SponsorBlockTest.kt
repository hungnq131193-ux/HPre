package com.hpre.app.sponsorblock

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockTest {

    private val controller = SponsorSkipController()

    @Test
    fun position_inside_segment_returns_end() {
        controller.setSegments(listOf(SponsorSegment(10_000, 20_000)))
        assertEquals(20_000L, controller.skipTargetFor(15_000L))
    }

    @Test
    fun boundary_start_skips_boundary_end_does_not() {
        controller.setSegments(listOf(SponsorSegment(10_000, 20_000)))
        assertEquals(20_000L, controller.skipTargetFor(10_000L))
    }

    @Test
    fun segment_skipped_only_once_until_reamed() {
        controller.setSegments(listOf(SponsorSegment(10_000, 20_000)))
        assertEquals(20_000L, controller.skipTargetFor(10_000L))
        assertNull(controller.skipTargetFor(15_000L))
        // User seeks back into the segment -> re-armed, skips again.
        controller.onSeekTo(12_000L)
        assertEquals(20_000L, controller.skipTargetFor(15_000L))
    }

    @Test
    fun seek_past_segment_end_does_not_ream() {
        controller.setSegments(listOf(SponsorSegment(10_000, 20_000)))
        assertEquals(20_000L, controller.skipTargetFor(11_000L))
        controller.onSeekTo(25_000L)
        controller.onSeekTo(15_000L)
        // After seeking back inside, it re-arms (end > position).
        assertEquals(20_000L, controller.skipTargetFor(15_000L))
    }

    @Test
    fun positions_outside_segments_never_skip() {
        controller.setSegments(listOf(SponsorSegment(10_000, 20_000), SponsorSegment(50_000, 60_000)))
        assertNull(controller.skipTargetFor(5_000L))
        assertNull(controller.skipTargetFor(20_000L))
        assertNull(controller.skipTargetFor(49_999L))
        assertEquals(60_000L, controller.skipTargetFor(55_000L))
    }

    @Test
    fun reset_clears_segments() {
        controller.setSegments(listOf(SponsorSegment(10_000, 20_000)))
        controller.reset()
        assertNull(controller.skipTargetFor(15_000L))
    }

    @Test
    fun parses_api_response_and_ignores_non_sponsor_categories() {
        val client = SponsorBlockClient(OkHttpClient())
        val json = """[
            {"category":"sponsor","segment":[10.5,20.0],"UUID":"a"},
            {"category":"intro","segment":[0.0,5.0],"UUID":"b"},
            {"category":"selfpromo","segment":[30.0,40.0],"UUID":"c"},
            {"category":"sponsor","segment":[60.25,70.0],"UUID":"d"}
        ]"""
        val segments = client.parseSegments(json)
        assertEquals(listOf(SponsorSegment(10_500, 20_000), SponsorSegment(60_250, 70_000)), segments)
    }

    @Test
    fun parse_rejects_malformed_and_reversed_segments() {
        val client = SponsorBlockClient(OkHttpClient())
        assertTrue(client.parseSegments("not json").isEmpty())
        assertTrue(client.parseSegments("""{"x":1}""").isEmpty())
        assertTrue(
            client.parseSegments("""[{"category":"sponsor","segment":[30.0,20.0]}]""").isEmpty()
        )
        assertTrue(client.parseSegments("").isEmpty())
    }
}
