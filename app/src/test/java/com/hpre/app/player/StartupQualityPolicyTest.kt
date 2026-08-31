package com.hpre.app.player

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
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

    @Test
    fun default_startup_keeps_highest_progressive_within_720p() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(1080), progressive(720), progressive(360), progressive(240))
            )
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.PROGRESSIVE, result.value.streamType)
        assertEquals(720, result.value.videoStream?.height)
    }

    @Test
    fun default_startup_does_not_choose_144p_when_360p_is_available() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(1080), progressive(360), progressive(144))
            )
        ) as AppResult.Success<SelectedStreams>

        assertEquals(360, result.value.videoStream?.height)
    }

    @Test
    fun default_startup_uses_best_available_even_below_240p() {
        val result = StartupStreamSelector.select(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(144), progressive(180)))
        ) as AppResult.Success<SelectedStreams>

        assertEquals(180, result.value.videoStream?.height)
    }

    @Test
    fun default_startup_selects_merged_av_within_720p_when_no_progressive_exists() {
        val result = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(adaptive(1080), adaptive(720), adaptive(360)),
                audioStreams = listOf(audio())
            )
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.MERGED_AV, result.value.streamType)
        assertEquals(720, result.value.videoStream?.height)
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
    fun startup_quality_policy_constants() {
        assertEquals(Int.MAX_VALUE, StartupQualityPolicy.UNLIMITED_HEIGHT)
        assertEquals(720, StartupQualityPolicy.PROGRESSIVE_TARGET_HEIGHT)
    }
}

