package com.hpre.app.player

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupQualityPolicyTest {
    private val key = ContentKey(0, "faststart")

    private fun progressive(height: Int) = VideoStream(
        url = "https://example.test/p$height.mp4",
        format = "mp4",
        resolution = "${height}p",
        width = height * 16 / 9,
        height = height,
        bitrate = height * 2_000L,
        isVideoOnly = false,
        mimeType = "video/mp4",
        codec = "avc1.64001F"
    )

    private fun adaptive(height: Int) = progressive(height).copy(
        url = "https://example.test/v$height.mp4",
        isVideoOnly = true
    )

    private fun audio() = AudioStream(
        url = "https://example.test/audio.m4a",
        format = "m4a",
        bitrate = 128_000,
        mimeType = "audio/mp4",
        codec = "mp4a.40.2"
    )

    // --- Startup selection: begin at the smallest usable rendition ---

    @Test
    fun startup_picks_lowest_progressive() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(1080), progressive(720), progressive(360), progressive(240))
            )
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.PROGRESSIVE, result.value.streamType)
        assertEquals(240, result.value.videoStream?.height)
    }

    @Test
    fun startup_includes_renditions_below_240p() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(1080), progressive(360), progressive(144))
            )
        ) as AppResult.Success<SelectedStreams>

        assertEquals(144, result.value.videoStream?.height)
    }

    @Test
    fun startup_uses_lowest_available_when_every_rendition_is_below_240p() {
        val result = StartupStreamSelector.select(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(144), progressive(180)))
        ) as AppResult.Success<SelectedStreams>

        assertEquals(144, result.value.videoStream?.height)
    }

    @Test
    fun startup_picks_lowest_merged_av_when_no_progressive_exists() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(adaptive(1080), adaptive(720), adaptive(360)),
                audioStreams = listOf(audio())
            )
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.MERGED_AV, result.value.streamType)
        assertEquals(360, result.value.videoStream?.height)
    }

    @Test
    fun startup_still_prefers_adaptive_manifest_over_low_progressive() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(360), progressive(1080)),
                hlsManifestUrl = "https://example.test/master.m3u8"
            )
        ) as AppResult.Success<SelectedStreams>

        // Adaptive sources ramp up without a rebuffer, so they win regardless of fast start.
        assertEquals(PlaybackStreamType.HLS, result.value.streamType)
    }

    @Test
    fun disabling_fast_start_restores_highest_within_cap() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(1080), progressive(720), progressive(360))
            ),
            fastStart = false
        ) as AppResult.Success<SelectedStreams>

        assertEquals(720, result.value.videoStream?.height)
    }

    // --- Adaptive escalation ladder ---

    @Test
    fun adaptive_start_cap_respects_existing_policy_ceiling() {
        assertEquals(
            StartupQualityPolicy.FAST_START_ADAPTIVE_CAP,
            StartupQualityPolicy.adaptiveStartCap(policyMaxHeight = null)
        )
        assertEquals(240, StartupQualityPolicy.adaptiveStartCap(policyMaxHeight = 240))
        assertEquals(
            StartupQualityPolicy.FAST_START_ADAPTIVE_CAP,
            StartupQualityPolicy.adaptiveStartCap(policyMaxHeight = 1080)
        )
    }

    @Test
    fun adaptive_fast_start_forces_the_lowest_bitrate_until_escalation() {
        val plan = StartupQualityPolicy.planFor(
            streamType = PlaybackStreamType.HLS,
            startHeight = 0,
            available = emptyList(),
            policyMaxHeight = null
        )

        assertEquals(true, plan?.forceLowestBitrate)
    }

    @Test
    fun adaptive_fast_start_still_forces_lowest_when_policy_cap_needs_no_height_steps() {
        val plan = StartupQualityPolicy.planFor(
            streamType = PlaybackStreamType.HLS,
            startHeight = 0,
            available = emptyList(),
            policyMaxHeight = 240
        )

        assertNotNull(plan)
        assertEquals(true, plan?.forceLowestBitrate)
        assertTrue(plan?.heightSteps?.isEmpty() == true)
    }

    @Test
    fun manual_quality_selection_clears_all_fast_start_constraints() {
        val constraints = StartupQualityPolicy.constraintsAfterManualSelection(
            currentCapHeight = 360,
            currentlyForcingLowestBitrate = true
        )

        assertNull(constraints.capHeight)
        assertEquals(false, constraints.forceLowestBitrate)
    }

    @Test
    fun adaptive_steps_climb_through_ladder_to_target() {
        val steps = StartupQualityPolicy.adaptiveSteps(startCap = 360, targetHeight = 1080)
        assertEquals(listOf(480, 720, 1080), steps)
    }

    @Test
    fun adaptive_steps_end_with_unlimited_when_no_ceiling_exists() {
        val steps = StartupQualityPolicy.adaptiveSteps(
            startCap = 360,
            targetHeight = StartupQualityPolicy.UNLIMITED_HEIGHT
        )
        assertEquals(StartupQualityPolicy.UNLIMITED_HEIGHT, steps.last())
        assertTrue(steps.containsAll(listOf(480, 720, 1080)))
        assertEquals(steps.sorted(), steps)
    }

    @Test
    fun adaptive_steps_are_empty_when_target_is_at_or_below_start_cap() {
        assertTrue(StartupQualityPolicy.adaptiveSteps(startCap = 720, targetHeight = 720).isEmpty())
        assertTrue(StartupQualityPolicy.adaptiveSteps(startCap = 720, targetHeight = 480).isEmpty())
    }

    // --- Progressive escalation target ---

    @Test
    fun progressive_escalation_picks_highest_within_target() {
        val available = StreamSelector.getAvailableQualities(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(1080), progressive(720), progressive(480), progressive(240))
            )
        )

        val target = StartupQualityPolicy.progressiveEscalationTarget(
            available = available,
            streamType = PlaybackStreamType.PROGRESSIVE,
            startHeight = 240
        )

        assertEquals(720, target?.height)
    }

    @Test
    fun progressive_escalation_returns_null_when_already_at_best() {
        val available = StreamSelector.getAvailableQualities(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(360)))
        )

        assertNull(
            StartupQualityPolicy.progressiveEscalationTarget(
                available = available,
                streamType = PlaybackStreamType.PROGRESSIVE,
                startHeight = 360
            )
        )
    }

    @Test
    fun progressive_escalation_does_not_cross_stream_families() {
        val available = StreamSelector.getAvailableQualities(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(240), adaptive(720)),
                audioStreams = listOf(audio())
            )
        )

        assertNull(
            StartupQualityPolicy.progressiveEscalationTarget(
                available = available,
                streamType = PlaybackStreamType.PROGRESSIVE,
                startHeight = 240
            )
        )
        assertEquals(
            720,
            StartupQualityPolicy.progressiveEscalationTarget(
                available = available,
                streamType = PlaybackStreamType.MERGED_AV,
                startHeight = 240
            )?.height
        )
    }

    // --- Plan construction ---

    @Test
    fun plan_for_adaptive_source_caps_start_and_schedules_steps() {
        val plan = StartupQualityPolicy.planFor(
            streamType = PlaybackStreamType.HLS,
            startHeight = 0,
            available = emptyList(),
            policyMaxHeight = null
        )

        assertNotNull(plan)
        assertTrue(plan!!.isAdaptive)
        assertEquals(StartupQualityPolicy.FAST_START_ADAPTIVE_CAP, plan.startCapHeight)
        assertEquals(StartupQualityPolicy.UNLIMITED_HEIGHT, plan.heightSteps.last())
        assertNull(plan.escalationOption)
    }

    @Test
    fun plan_for_progressive_source_switches_once_without_a_start_cap() {
        val available = StreamSelector.getAvailableQualities(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(240), progressive(720), progressive(1080)))
        )

        val plan = StartupQualityPolicy.planFor(
            streamType = PlaybackStreamType.PROGRESSIVE,
            startHeight = 240,
            available = available,
            policyMaxHeight = null
        )

        assertNotNull(plan)
        assertTrue(!plan!!.isAdaptive)
        // Progressive escalation is a media-source rebuild, so it happens once and never above 720p.
        assertNull(plan.startCapHeight)
        assertEquals(listOf(720), plan.heightSteps)
        assertEquals(720, plan.escalationOption?.height)
    }

    @Test
    fun plan_honours_a_user_height_ceiling_below_the_progressive_target() {
        val available = StreamSelector.getAvailableQualities(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(240), progressive(480), progressive(720)))
        )

        val plan = StartupQualityPolicy.planFor(
            streamType = PlaybackStreamType.PROGRESSIVE,
            startHeight = 240,
            available = available,
            policyMaxHeight = 480
        )

        assertEquals(480, plan?.escalationOption?.height)
    }

    @Test
    fun plan_is_absent_when_there_is_nothing_to_escalate_to() {
        val available = StreamSelector.getAvailableQualities(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(360)))
        )

        assertNull(
            StartupQualityPolicy.planFor(
                streamType = PlaybackStreamType.PROGRESSIVE,
                startHeight = 360,
                available = available,
                policyMaxHeight = null
            )
        )
    }

    @Test
    fun plan_is_absent_for_audio_only_but_retained_for_capped_adaptive() {
        assertNull(
            StartupQualityPolicy.planFor(
                streamType = PlaybackStreamType.AUDIO_ONLY,
                startHeight = 0,
                available = emptyList(),
                policyMaxHeight = null
            )
        )
        // There is no height step, but the plan still pins startup to the lowest rendition.
        assertNotNull(
            StartupQualityPolicy.planFor(
                streamType = PlaybackStreamType.DASH,
                startHeight = 0,
                available = emptyList(),
                policyMaxHeight = 240
            )
        )
    }
}
