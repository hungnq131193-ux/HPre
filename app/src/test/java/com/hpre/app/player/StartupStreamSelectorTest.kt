package com.hpre.app.player

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStreamSelectorTest {
    private val key = ContentKey(0, "startup")

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
    fun startup_prefers_hls_then_dash_before_progressive() {
        val hls = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(720)),
                hlsManifestUrl = "https://example.test/master.m3u8",
                dashManifestUrl = "https://example.test/manifest.mpd"
            )
        ) as AppResult.Success<SelectedStreams>
        assertEquals(PlaybackStreamType.HLS, hls.value.streamType)

        val dash = StartupStreamSelector.select(
            StreamInfo(
                key,
                "Test",
                videoStreams = listOf(progressive(720)),
                dashManifestUrl = "https://example.test/manifest.mpd"
            )
        ) as AppResult.Success<SelectedStreams>
        assertEquals(PlaybackStreamType.DASH, dash.value.streamType)
    }

    @Test
    fun startup_prefers_progressive_at_or_below_720() {
        val result = StartupStreamSelector.select(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(1080), progressive(720)))
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.PROGRESSIVE, result.value.streamType)
        assertEquals(720, result.value.videoStream?.height)
    }

    @Test
    fun startup_uses_higher_progressive_when_it_is_the_only_progressive_stream() {
        val result = StartupStreamSelector.select(
            StreamInfo(key, "Test", videoStreams = listOf(progressive(1080)))
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.PROGRESSIVE, result.value.streamType)
        assertEquals(1080, result.value.videoStream?.height)
    }

    @Test
    fun startup_preserves_compatible_merged_av_fallback() {
        val result = StartupStreamSelector.select(
            StreamInfo(key, "Test", videoStreams = listOf(adaptive(1080)), audioStreams = listOf(audio()))
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.MERGED_AV, result.value.streamType)
        assertEquals(1080, result.value.videoStream?.height)
    }

    @Test
    fun startup_preserves_hls_fallback_when_no_video_stream_exists() {
        val result = StartupStreamSelector.select(
            StreamInfo(key, "Test", hlsManifestUrl = "https://example.test/master.m3u8")
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.HLS, result.value.streamType)
        assertEquals("https://example.test/master.m3u8", result.value.manifestUrl)
    }

    @Test
    fun startup_preserves_audio_only_fallback_when_no_video_exists() {
        val result = StartupStreamSelector.select(
            StreamInfo(key, "Test", audioStreams = listOf(audio()))
        ) as AppResult.Success<SelectedStreams>

        assertEquals(PlaybackStreamType.AUDIO_ONLY, result.value.streamType)
        assertEquals("https://example.test/audio.m4a", result.value.audioStream?.url)
    }

    @Test
    fun startup_returns_unsupported_when_no_valid_stream_exists() {
        val result = StartupStreamSelector.select(StreamInfo(key, "Test"))

        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (result as AppResult.Failure).error)
    }
}
