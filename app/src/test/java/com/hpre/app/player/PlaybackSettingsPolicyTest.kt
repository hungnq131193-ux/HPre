package com.hpre.app.player

import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import com.hpre.app.settings.AppSettings
import com.hpre.app.settings.QualityPreferenceSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSettingsPolicyTest {
    private val key = ContentKey(0, "settings-policy")

    private fun progressive(height: Int) = VideoStream(
        url = "https://example.test/p$height.mp4",
        format = "mp4",
        resolution = "${height}p",
        width = height * 16 / 9,
        height = height,
        bitrate = height * 2_000L,
        isVideoOnly = false,
        mimeType = "video/mp4",
        codec = "avc1.64001F,mp4a.40.2"
    )

    private fun adaptive(height: Int) = progressive(height).copy(
        url = "https://example.test/v$height.mp4",
        isVideoOnly = true,
        codec = "avc1.64001F"
    )

    private fun audio() = AudioStream(
        url = "https://example.test/audio.m4a",
        format = "m4a",
        bitrate = 128_000,
        mimeType = "audio/mp4",
        codec = "mp4a.40.2"
    )

    @Test
    fun wifi_and_mobile_use_their_own_quality_ceiling_and_default_speed() {
        val info = StreamInfo(
            key = key,
            title = "Video",
            videoStreams = listOf(progressive(1080), progressive(720), progressive(360))
        )
        val settings = AppSettings(
            wifiQuality = QualityPreferenceSetting.MEDIUM_720P,
            mobileQuality = QualityPreferenceSetting.LOW_360P,
            defaultPlaybackSpeed = 1.25f
        )

        val wifi = PlaybackSettingsPolicy.resolve(settings, isWifi = true, info)
        val mobile = PlaybackSettingsPolicy.resolve(settings, isWifi = false, info)

        assertEquals(720, wifi.initialQuality?.height)
        assertEquals(UserQualityPolicy.Auto(maxHeight = 720), wifi.qualityPolicy)
        assertEquals(1.25f, wifi.playbackSpeed, 0.001f)
        assertEquals(360, mobile.initialQuality?.height)
        assertEquals(UserQualityPolicy.Auto(maxHeight = 360), mobile.qualityPolicy)
    }

    @Test
    fun direct_stream_falls_back_to_nearest_height_above_the_requested_ceiling() {
        val info = StreamInfo(
            key = key,
            title = "Video",
            videoStreams = listOf(progressive(1080), progressive(480))
        )
        val settings = AppSettings(mobileQuality = QualityPreferenceSetting.LOW_360P)

        val result = PlaybackSettingsPolicy.resolve(settings, isWifi = false, info)

        assertEquals(480, result.initialQuality?.height)
        assertEquals(PlaybackStreamType.PROGRESSIVE, result.initialQuality?.streamType)
    }

    @Test
    fun merged_stream_uses_same_ceiling_policy_when_progressive_is_unavailable() {
        val info = StreamInfo(
            key = key,
            title = "Video",
            videoStreams = listOf(adaptive(1080), adaptive(480)),
            audioStreams = listOf(audio())
        )
        val settings = AppSettings(mobileQuality = QualityPreferenceSetting.LOW_360P)

        val result = PlaybackSettingsPolicy.resolve(settings, isWifi = false, info)

        assertEquals(480, result.initialQuality?.height)
        assertEquals(PlaybackStreamType.MERGED_AV, result.initialQuality?.streamType)
    }

    @Test
    fun live_manifest_stays_adaptive_and_receives_the_user_height_cap() {
        val info = StreamInfo(
            key = key,
            title = "Live",
            hlsManifestUrl = "https://example.test/live.m3u8",
            isLive = true
        )
        val settings = AppSettings(wifiQuality = QualityPreferenceSetting.HIGH_1080P)

        val result = PlaybackSettingsPolicy.resolve(settings, isWifi = true, info)

        assertEquals(PlaybackStreamType.HLS, result.initialQuality?.streamType)
        assertEquals(UserQualityPolicy.Auto(maxHeight = 1080), result.qualityPolicy)
    }

    @Test
    fun auto_keeps_existing_direct_startup_ceiling_without_adding_a_user_cap() {
        val info = StreamInfo(
            key = key,
            title = "Video",
            videoStreams = listOf(progressive(1080), progressive(720), progressive(360))
        )

        val result = PlaybackSettingsPolicy.resolve(AppSettings(), isWifi = true, info)

        assertEquals(720, result.initialQuality?.height)
        assertEquals(UserQualityPolicy.Auto(maxHeight = null), result.qualityPolicy)
    }

    @Test
    fun invalid_default_speed_is_normalized_and_audio_only_has_no_quality() {
        val info = StreamInfo(key, "Audio", audioStreams = listOf(audio()))

        val result = PlaybackSettingsPolicy.resolve(
            AppSettings(defaultPlaybackSpeed = Float.NaN),
            isWifi = false,
            info
        )

        assertEquals(1.0f, result.playbackSpeed, 0.001f)
        assertNull(result.initialQuality)
    }

    @Test
    fun new_video_uses_defaults_but_retry_keeps_current_manual_session_state() {
        val manual = QualityOption(480, "480p", true, "mp4")
        val defaults = PlaybackStartDefaults(
            initialQuality = QualityOption(720, "720p", true, "mp4"),
            qualityPolicy = UserQualityPolicy.Auto(maxHeight = 720),
            playbackSpeed = 1.25f
        )
        val current = PlaybackState(
            playbackSpeed = 1.75f,
            selectedQuality = manual,
            qualityPolicy = UserQualityPolicy.Fixed(manual)
        )

        val fresh = resolvePrepareDefaults(isNewSession = true, requestedQuality = null, current, defaults)
        val retry = resolvePrepareDefaults(isNewSession = false, requestedQuality = manual, current, defaults)

        assertEquals(1.25f, fresh.playbackSpeed, 0.001f)
        assertEquals(720, fresh.initialQuality?.height)
        assertEquals(UserQualityPolicy.Auto(maxHeight = 720), fresh.qualityPolicy)
        assertEquals(1.75f, retry.playbackSpeed, 0.001f)
        assertEquals(manual, retry.initialQuality)
        assertEquals(UserQualityPolicy.Fixed(manual), retry.qualityPolicy)
    }
}
