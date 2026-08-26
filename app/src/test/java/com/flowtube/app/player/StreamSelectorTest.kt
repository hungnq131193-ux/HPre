package com.flowtube.app.player

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.AudioStream
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.SubtitleStream
import com.flowtube.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSelectorTest {

    private val testKey = ContentKey(0, "test_video")

    private fun videoStream(
        url: String = "https://video.mp4",
        format: String = "mp4",
        resolution: String = "720p",
        width: Int = 1280,
        height: Int = 720,
        bitrate: Long = 2_000_000,
        isVideoOnly: Boolean = false,
        mimeType: String? = "video/mp4",
        codec: String? = "avc1.64001F"
    ) = VideoStream(
        url = url,
        format = format,
        resolution = resolution,
        width = width,
        height = height,
        bitrate = bitrate,
        isVideoOnly = isVideoOnly,
        mimeType = mimeType,
        codec = codec
    )

    private fun audioStream(
        url: String = "https://audio.m4a",
        format: String = "m4a",
        bitrate: Long = 128_000,
        language: String? = "en",
        mimeType: String? = "audio/mp4",
        codec: String? = "mp4a.40.2"
    ) = AudioStream(
        url = url,
        format = format,
        bitrate = bitrate,
        language = language,
        mimeType = mimeType,
        codec = codec
    )

    @Test
    fun chooses_progressive_stream_at_or_below_requested_quality() {
        val progressive720 = videoStream(height = 720, isVideoOnly = false, url = "https://p720.mp4", codec = "avc1.64001F")
        val progressive360 = videoStream(height = 360, isVideoOnly = false, url = "https://p360.mp4", codec = "avc1.64001F")
        val progressive1080 = videoStream(height = 1080, isVideoOnly = false, url = "https://p1080.mp4", codec = "avc1.64001F")

        val info = StreamInfo(
            key = testKey,
            title = "Test",
            videoStreams = listOf(progressive1080, progressive720, progressive360)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(720))
        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(720, selected.videoStream?.height)
        assertEquals("https://p720.mp4", selected.videoStream?.url)
        assertNull(selected.audioStream)
        assertEquals(PlaybackStreamType.PROGRESSIVE, selected.streamType)
    }

    @Test
    fun pairs_separate_video_with_audio_when_no_progressive_match_exists() {
        val video1080 = videoStream(height = 1080, isVideoOnly = true, url = "https://v1080.mp4")
        val audio = audioStream(url = "https://audio.m4a")

        val info = StreamInfo(
            key = testKey,
            title = "Test",
            videoStreams = listOf(video1080),
            audioStreams = listOf(audio)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(1080))
        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(1080, selected.videoStream?.height)
        assertNotNull(selected.audioStream)
        assertEquals("https://audio.m4a", selected.audioStream?.url)
        assertEquals(PlaybackStreamType.MERGED_AV, selected.streamType)
    }

    @Test
    fun falls_back_to_audio_only_when_no_video_stream_and_labels_audio_only() {
        val audio = audioStream(url = "https://audio.m4a")
        val info = StreamInfo(
            key = testKey,
            title = "Audio Only Test",
            videoStreams = emptyList(),
            audioStreams = listOf(audio)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertNull(selected.videoStream)
        assertNotNull(selected.audioStream)
        assertEquals(PlaybackStreamType.AUDIO_ONLY, selected.streamType)
    }

    @Test
    fun returns_hls_stream_type_when_manifest_provided_and_no_progressive_candidates() {
        val info = StreamInfo(
            key = testKey,
            title = "HLS Stream",
            hlsManifestUrl = "https://manifest.m3u8"
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(PlaybackStreamType.HLS, selected.streamType)
        assertEquals("https://manifest.m3u8", selected.manifestUrl)
    }

    @Test
    fun returns_dash_stream_type_when_manifest_provided_and_no_progressive_or_hls() {
        val info = StreamInfo(
            key = testKey,
            title = "DASH Stream",
            dashManifestUrl = "https://manifest.mpd"
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(PlaybackStreamType.DASH, selected.streamType)
        assertEquals("https://manifest.mpd", selected.manifestUrl)
    }

    @Test
    fun returns_unsupported_format_when_no_valid_candidates() {
        val info = StreamInfo(
            key = testKey,
            title = "Empty Streams",
            videoStreams = emptyList(),
            audioStreams = emptyList(),
            hlsManifestUrl = null,
            dashManifestUrl = null
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (result as AppResult.Failure).error)
    }

    @Test
    fun extracts_available_quality_options_from_stream_candidates() {
        val progressive720 = videoStream(height = 720, resolution = "720p", isVideoOnly = false, codec = "avc1.64001F")
        val video1080 = videoStream(height = 1080, resolution = "1080p", isVideoOnly = true, format = "mp4", codec = "avc1.64001F")
        val video360 = videoStream(height = 360, resolution = "360p", isVideoOnly = false, codec = "avc1.64001F")
        val audio = audioStream(url = "https://audio.m4a", format = "m4a", codec = "mp4a.40.2")

        val info = StreamInfo(
            key = testKey,
            title = "Qualities Test",
            videoStreams = listOf(progressive720, video1080, video360),
            audioStreams = listOf(audio)
        )

        val qualities = StreamSelector.getAvailableQualities(info)
        assertEquals(listOf(1080, 720, 360), qualities.map { it.height })
    }

    @Test
    fun incompatible_video_and_audio_containers_are_rejected_from_merging() {
        val webmVideo = videoStream(
            url = "https://v1080.webm",
            format = "webm",
            mimeType = "video/webm",
            codec = "vp9",
            height = 1080,
            isVideoOnly = true
        )
        val m4aAudio = audioStream(
            url = "https://audio.m4a",
            format = "m4a",
            mimeType = "audio/mp4",
            codec = "mp4a.40.2"
        )
        val info = StreamInfo(
            key = testKey,
            title = "Incompatible Merge Test",
            videoStreams = listOf(webmVideo),
            audioStreams = listOf(m4aAudio)
        )

        // WebM video + MP4/M4A audio must NOT be merged; fallback to audio-only
        val result = StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(1080))
        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(PlaybackStreamType.AUDIO_ONLY, selected.streamType)
        assertNull(selected.videoStream)
        assertEquals("https://audio.m4a", selected.audioStream?.url)
    }

    @Test
    fun incompatible_codecs_in_same_container_are_rejected_from_merging() {
        // H.264 video with Vorbis audio in MP4 container is invalid/unsupported
        val mp4VideoH264 = videoStream(
            url = "https://v1080.mp4",
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1.64001F",
            height = 1080,
            isVideoOnly = true
        )
        val mp4AudioVorbis = audioStream(
            url = "https://audio.mp4",
            format = "mp4",
            mimeType = "audio/mp4",
            codec = "vorbis"
        )
        val info = StreamInfo(
            key = testKey,
            title = "Incompatible Codec Merge Test",
            videoStreams = listOf(mp4VideoH264),
            audioStreams = listOf(mp4AudioVorbis)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(1080))
        // Since Vorbis in MP4 container is invalid per baseline matrix, audio is not valid audio-only either -> UnsupportedFormat
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (result as AppResult.Failure).error)
    }

    @Test
    fun compatible_webm_video_and_audio_are_merged() {
        val webmVideo = videoStream(
            url = "https://v1080.webm",
            format = "webm",
            mimeType = "video/webm",
            codec = "vp9",
            height = 1080,
            isVideoOnly = true
        )
        val webmAudio = audioStream(
            url = "https://audio.webm",
            format = "webm",
            mimeType = "audio/webm",
            codec = "opus"
        )
        val info = StreamInfo(
            key = testKey,
            title = "WebM Merge Test",
            videoStreams = listOf(webmVideo),
            audioStreams = listOf(webmAudio)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(1080))
        assertTrue(result is AppResult.Success)
        val selected = (result as AppResult.Success).value
        assertEquals(PlaybackStreamType.MERGED_AV, selected.streamType)
        assertEquals(1080, selected.videoStream?.height)
        assertEquals("https://audio.webm", selected.audioStream?.url)
    }

    @Test
    fun progressive_must_have_valid_url_and_not_video_only() {
        val invalidProgressive = videoStream(
            url = "",
            format = "mp4",
            codec = "avc1.64001F",
            height = 720,
            isVideoOnly = false
        )
        val info = StreamInfo(
            key = testKey,
            title = "Invalid Progressive Test",
            videoStreams = listOf(invalidProgressive)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (result as AppResult.Failure).error)
    }

    @Test
    fun hls_manifest_available_qualities_returns_auto_quality_option() {
        val info = StreamInfo(
            key = testKey,
            title = "HLS Only",
            videoStreams = emptyList(),
            audioStreams = emptyList(),
            hlsManifestUrl = "https://manifest.m3u8"
        )

        val qualities = StreamSelector.getAvailableQualities(info)
        assertEquals(1, qualities.size)
        assertEquals(0, qualities.first().height)
        assertEquals("Auto (HLS)", qualities.first().label)
        assertFalse(qualities.first().isProgressive)
    }

    @Test
    fun dash_manifest_available_qualities_returns_auto_quality_option() {
        val info = StreamInfo(
            key = testKey,
            title = "DASH Only",
            videoStreams = emptyList(),
            audioStreams = emptyList(),
            dashManifestUrl = "https://manifest.mpd"
        )

        val qualities = StreamSelector.getAvailableQualities(info)
        assertEquals(1, qualities.size)
        assertEquals(0, qualities.first().height)
        assertEquals("Auto (DASH)", qualities.first().label)
        assertFalse(qualities.first().isProgressive)
    }

    @Test
    fun available_qualities_preserves_distinct_progressive_and_merged_with_same_height() {
        val progressive720 = videoStream(height = 720, resolution = "720p", isVideoOnly = false, format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F")
        val videoOnly720 = videoStream(height = 720, resolution = "720p", isVideoOnly = true, format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F")
        val audio = audioStream(format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2")

        val info = StreamInfo(
            key = testKey,
            title = "Mixed Qualities",
            videoStreams = listOf(progressive720, videoOnly720),
            audioStreams = listOf(audio)
        )

        val qualities = StreamSelector.getAvailableQualities(info)
        assertEquals(2, qualities.size)

        val progOpt = qualities.first { it.isProgressive }
        val adaptOpt = qualities.first { !it.isProgressive }

        assertEquals(720, progOpt.height)
        assertEquals("720p", progOpt.label)
        assertTrue(progOpt.isProgressive)

        assertEquals(720, adaptOpt.height)
        assertEquals("720p (adaptive)", adaptOpt.label)
        assertFalse(adaptOpt.isProgressive)
    }

    @Test
    fun selectStream_with_SpecificOption_resolves_exact_progressive_or_adaptive_source() {
        val progressive720 = videoStream(url = "https://p720.mp4", height = 720, resolution = "720p", isVideoOnly = false, format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F")
        val videoOnly720 = videoStream(url = "https://v720.mp4", height = 720, resolution = "720p", isVideoOnly = true, format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F")
        val audio = audioStream(url = "https://audio.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2")

        val info = StreamInfo(
            key = testKey,
            title = "Select Specific",
            videoStreams = listOf(progressive720, videoOnly720),
            audioStreams = listOf(audio)
        )

        val qualities = StreamSelector.getAvailableQualities(info)
        val progOpt = qualities.first { it.isProgressive }
        val adaptOpt = qualities.first { !it.isProgressive }

        val resProg = StreamSelector.selectStream(info, QualityPreference.SpecificOption(progOpt))
        assertTrue(resProg is AppResult.Success)
        val selectedProg = (resProg as AppResult.Success).value
        assertEquals(PlaybackStreamType.PROGRESSIVE, selectedProg.streamType)
        assertEquals("https://p720.mp4", selectedProg.videoStream?.url)

        val resAdapt = StreamSelector.selectStream(info, QualityPreference.SpecificOption(adaptOpt))
        assertTrue(resAdapt is AppResult.Success)
        val selectedAdapt = (resAdapt as AppResult.Success).value
        assertEquals(PlaybackStreamType.MERGED_AV, selectedAdapt.streamType)
        assertEquals("https://v720.mp4", selectedAdapt.videoStream?.url)
        assertEquals("https://audio.m4a", selectedAdapt.audioStream?.url)
    }

    @Test
    fun malformed_notwebm_notavc_notopus_rejected() {
        val malformedVideo = videoStream(
            url = "https://v.mp4",
            format = "notwebm",
            mimeType = "video/notwebm",
            codec = "notavc",
            height = 720,
            isVideoOnly = false
        )
        val malformedAudio = audioStream(
            url = "https://a.m4a",
            format = "notwebm",
            mimeType = "audio/notwebm",
            codec = "notopus"
        )
        val info = StreamInfo(
            key = testKey,
            title = "Malformed Streams",
            videoStreams = listOf(malformedVideo),
            audioStreams = listOf(malformedAudio)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue("Malformed streams must be rejected", result is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (result as AppResult.Failure).error)
        assertTrue("Available qualities must be empty for malformed streams", StreamSelector.getAvailableQualities(info).isEmpty())
    }

    @Test
    fun unknown_mime_with_mp4_fallback_rejection() {
        val unknownMimeVideo = videoStream(
            url = "https://v.mp4",
            format = "mp4",
            mimeType = "video/unknown-override",
            codec = "avc1.64001F",
            height = 720,
            isVideoOnly = false
        )
        val info = StreamInfo(
            key = testKey,
            title = "Unknown MIME with MP4 format",
            videoStreams = listOf(unknownMimeVideo)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue("Unknown nonblank MIME override must reject candidate even if format is mp4", result is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (result as AppResult.Failure).error)
        assertTrue(StreamSelector.getAvailableQualities(info).isEmpty())
    }

    @Test
    fun unknown_codecs_reject_merge() {
        val videoUnknownCodec = videoStream(
            url = "https://v1080.mp4",
            format = "mp4",
            mimeType = "video/mp4",
            codec = "unknown_codec_xyz",
            height = 1080,
            isVideoOnly = true
        )
        val videoNullCodec = videoStream(
            url = "https://v1080_null.mp4",
            format = "mp4",
            mimeType = "video/mp4",
            codec = null,
            height = 1080,
            isVideoOnly = true
        )
        val audioValid = audioStream(
            url = "https://a.m4a",
            format = "m4a",
            mimeType = "audio/mp4",
            codec = "mp4a.40.2"
        )

        val infoUnknownCodec = StreamInfo(
            key = testKey,
            title = "Unknown Codec Merge",
            videoStreams = listOf(videoUnknownCodec),
            audioStreams = listOf(audioValid)
        )

        val resUnknown = StreamSelector.selectStream(infoUnknownCodec, QualityPreference.ExactOrBelow(1080))
        assertTrue(resUnknown is AppResult.Success)
        // Merge must be rejected; falls back to audio-only
        assertEquals(PlaybackStreamType.AUDIO_ONLY, (resUnknown as AppResult.Success).value.streamType)

        val infoNullCodec = StreamInfo(
            key = testKey,
            title = "Null Codec Merge",
            videoStreams = listOf(videoNullCodec),
            audioStreams = listOf(audioValid)
        )

        val resNull = StreamSelector.selectStream(infoNullCodec, QualityPreference.ExactOrBelow(1080))
        assertTrue(resNull is AppResult.Success)
        // No UNKNOWN merge: falls back to audio-only
        assertEquals(PlaybackStreamType.AUDIO_ONLY, (resNull as AppResult.Success).value.streamType)
    }

    @Test
    fun progressive_invalid_reject() {
        val progMalformedCodec = videoStream(
            url = "https://p720.mp4",
            format = "mp4",
            mimeType = "video/mp4",
            codec = "notavc",
            height = 720,
            isVideoOnly = false
        )
        val info = StreamInfo(
            key = testKey,
            title = "Invalid Progressive",
            videoStreams = listOf(progMalformedCodec)
        )

        val result = StreamSelector.selectStream(info, QualityPreference.Auto)
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (result as AppResult.Failure).error)
        assertTrue(StreamSelector.getAvailableQualities(info).isEmpty())
    }

    @Test
    fun baseline_pairs_pass_and_non_baseline_rejected() {
        // 1. MP4 AVC + AAC passes
        val mp4VideoAvc = videoStream(url = "https://v.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F", height = 1080, isVideoOnly = true)
        val mp4AudioAac = audioStream(url = "https://a.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2")
        val infoMp4 = StreamInfo(key = testKey, title = "MP4 Baseline", videoStreams = listOf(mp4VideoAvc), audioStreams = listOf(mp4AudioAac))
        val resMp4 = StreamSelector.selectStream(infoMp4, QualityPreference.ExactOrBelow(1080))
        assertTrue(resMp4 is AppResult.Success)
        assertEquals(PlaybackStreamType.MERGED_AV, (resMp4 as AppResult.Success).value.streamType)

        // 2. WebM VP8 + Opus passes
        val webmVideoVp8 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp8", height = 720, isVideoOnly = true)
        val webmAudioOpus = audioStream(url = "https://a.webm", format = "webm", mimeType = "audio/webm", codec = "opus")
        val infoVp8 = StreamInfo(key = testKey, title = "VP8 Baseline", videoStreams = listOf(webmVideoVp8), audioStreams = listOf(webmAudioOpus))
        val resVp8 = StreamSelector.selectStream(infoVp8, QualityPreference.ExactOrBelow(720))
        assertTrue(resVp8 is AppResult.Success)
        assertEquals(PlaybackStreamType.MERGED_AV, (resVp8 as AppResult.Success).value.streamType)

        // 3. WebM AV1 + Vorbis passes
        val webmVideoAv1 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.08M.08", height = 1080, isVideoOnly = true)
        val webmAudioVorbis = audioStream(url = "https://a.webm", format = "webm", mimeType = "audio/webm", codec = "vorbis")
        val infoAv1 = StreamInfo(key = testKey, title = "AV1 Baseline", videoStreams = listOf(webmVideoAv1), audioStreams = listOf(webmAudioVorbis))
        val resAv1 = StreamSelector.selectStream(infoAv1, QualityPreference.ExactOrBelow(1080))
        assertTrue(resAv1 is AppResult.Success)
        assertEquals(PlaybackStreamType.MERGED_AV, (resAv1 as AppResult.Success).value.streamType)

        // 4. Non-baseline MP4 HEVC + AAC rejected from merge (falls back to audio-only)
        val mp4VideoHevc = videoStream(url = "https://v_hevc.mp4", format = "mp4", mimeType = "video/mp4", codec = "hev1.1.6.L93.B0", height = 1080, isVideoOnly = true)
        val infoHevc = StreamInfo(key = testKey, title = "HEVC Non-baseline", videoStreams = listOf(mp4VideoHevc), audioStreams = listOf(mp4AudioAac))
        val resHevc = StreamSelector.selectStream(infoHevc, QualityPreference.ExactOrBelow(1080))
        assertTrue(resHevc is AppResult.Success)
        assertEquals(PlaybackStreamType.AUDIO_ONLY, (resHevc as AppResult.Success).value.streamType)
    }

    @Test
    fun duplicate_same_height_mode_different_format_chooses_exact_only() {
        val prog720Mp4 = videoStream(url = "https://p720.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F", height = 720, isVideoOnly = false)
        val prog720Webm = videoStream(url = "https://p720.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.10.08", height = 720, isVideoOnly = false)

        val info = StreamInfo(
            key = testKey,
            title = "Duplicate 720p Formats",
            videoStreams = listOf(prog720Mp4, prog720Webm)
        )

        val qualities = StreamSelector.getAvailableQualities(info)
        assertEquals(2, qualities.size)
        val mp4Opt = qualities.first { it.format.equals("mp4", ignoreCase = true) }
        val webmOpt = qualities.first { it.format.equals("webm", ignoreCase = true) }

        val resMp4 = StreamSelector.selectStream(info, QualityPreference.SpecificOption(mp4Opt))
        assertTrue(resMp4 is AppResult.Success)
        assertEquals("https://p720.mp4", (resMp4 as AppResult.Success).value.videoStream?.url)

        val resWebm = StreamSelector.selectStream(info, QualityPreference.SpecificOption(webmOpt))
        assertTrue(resWebm is AppResult.Success)
        assertEquals("https://p720.webm", (resWebm as AppResult.Success).value.videoStream?.url)
    }

    @Test
    fun codec_parser_rejects_unknown_suffixes_and_accepts_valid_profiles() {
        // avc1.unknown, vp9.unknown, mp4a.unknown must be rejected
        val badAvc = videoStream(url = "https://v.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.unknown", height = 720, isVideoOnly = false)
        val badVp9 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp9.unknown", height = 720, isVideoOnly = false)
        val badMp4a = audioStream(url = "https://a.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.unknown")

        val infoBadAvc = StreamInfo(key = testKey, title = "Bad AVC", videoStreams = listOf(badAvc))
        val infoBadVp9 = StreamInfo(key = testKey, title = "Bad VP9", videoStreams = listOf(badVp9))
        val infoBadMp4a = StreamInfo(key = testKey, title = "Bad Audio", audioStreams = listOf(badMp4a))

        assertTrue(StreamSelector.selectStream(infoBadAvc, QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(infoBadVp9, QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(infoBadMp4a, QualityPreference.Auto) is AppResult.Failure)

        // Valid profiles
        val goodAvc = videoStream(url = "https://v.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.4d401f", height = 720, isVideoOnly = false)
        val goodVp9 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.51.08", height = 1080, isVideoOnly = false)
        val goodAv1 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.08M.08", height = 1080, isVideoOnly = false)
        val goodMp4a = audioStream(url = "https://a.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2")
        val goodOpus = audioStream(url = "https://a.webm", format = "webm", mimeType = "audio/webm", codec = "opus")

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "Good AVC", videoStreams = listOf(goodAvc)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "Good VP9", videoStreams = listOf(goodVp9)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "Good AV1", videoStreams = listOf(goodAv1)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "Good MP4A", audioStreams = listOf(goodMp4a)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "Good Opus", audioStreams = listOf(goodOpus)), QualityPreference.Auto) is AppResult.Success)
    }

    @Test
    fun container_matrix_rejects_arbitrary_matroska_and_application_mp4_in_merge() {
        val appMp4Video = videoStream(
            url = "https://v.mp4",
            format = "mp4",
            mimeType = "application/mp4",
            codec = "avc1.64001F",
            height = 1080,
            isVideoOnly = true
        )
        val validAudio = audioStream(
            url = "https://a.m4a",
            format = "m4a",
            mimeType = "audio/mp4",
            codec = "mp4a.40.2"
        )
        val info = StreamInfo(key = testKey, title = "App Mp4", videoStreams = listOf(appMp4Video), audioStreams = listOf(validAudio))
        val res = StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(1080))
        assertTrue(res is AppResult.Success)
        // Merge must be rejected; falls back to audio-only
        assertEquals(PlaybackStreamType.AUDIO_ONLY, (res as AppResult.Success).value.streamType)
    }

    @Test
    fun available_qualities_always_includes_auto_when_hls_or_dash_present_alongside_progressive() {
        val progressive720 = videoStream(url = "https://p720.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F", height = 720, isVideoOnly = false)
        val info = StreamInfo(
            key = testKey,
            title = "Mixed Manifest and Progressive",
            videoStreams = listOf(progressive720),
            hlsManifestUrl = "https://example.com/live.m3u8",
            dashManifestUrl = "https://example.com/live.mpd"
        )

        val qualities = StreamSelector.getAvailableQualities(info)
        assertEquals(3, qualities.size)
        assertTrue(qualities.any { it.streamType == PlaybackStreamType.HLS && it.label == "Auto (HLS)" })
        assertTrue(qualities.any { it.streamType == PlaybackStreamType.DASH && it.label == "Auto (DASH)" })
        assertTrue(qualities.any { it.streamType == PlaybackStreamType.PROGRESSIVE && it.height == 720 })

        val hlsOpt = qualities.first { it.streamType == PlaybackStreamType.HLS }
        val selHls = StreamSelector.selectStream(info, QualityPreference.SpecificOption(hlsOpt))
        assertTrue(selHls is AppResult.Success)
        assertEquals(PlaybackStreamType.HLS, (selHls as AppResult.Success).value.streamType)
        assertEquals("https://example.com/live.m3u8", (selHls as AppResult.Success).value.manifestUrl)
    }

    @Test
    fun vp8_vp9_av1_strict_codecs_and_progressive_null_codec_rejection() {
        // 1. VP8 strict: bare vp8 or vp08 only; rejects suffixes like vp8.0, vp08.00, vp8.1.2.999, vp8.xyz, vp8.999a
        val goodVp8 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp8", height = 720, isVideoOnly = false)
        val goodVp08 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp08", height = 720, isVideoOnly = false)
        val badVp8_dot = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp8.0", height = 720, isVideoOnly = false)
        val badVp08_dot = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp08.00.01", height = 720, isVideoOnly = false)
        val badVp8_1_2_999 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp8.1.2.999", height = 720, isVideoOnly = false)
        val badVp8Suffix = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp8.xyz", height = 720, isVideoOnly = false)
        val badVp8Malformed = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp8.999a", height = 720, isVideoOnly = false)

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodVp8", videoStreams = listOf(goodVp8)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodVp08", videoStreams = listOf(goodVp08)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp8_dot", videoStreams = listOf(badVp8_dot)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp08_dot", videoStreams = listOf(badVp08_dot)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp8_1_2_999", videoStreams = listOf(badVp8_1_2_999)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp8Suffix", videoStreams = listOf(badVp8Suffix)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp8Malformed", videoStreams = listOf(badVp8Malformed)), QualityPreference.Auto) is AppResult.Failure)

        // 2. VP9 strict: bare vp9/vp09 OR exact vp09.PP.LL.DD (PP 00..03, LL 10..62, DD 08|10|12, no extra fields)
        val goodVp9 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp9", height = 720, isVideoOnly = false)
        val goodVp09 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09", height = 720, isVideoOnly = false)
        val goodVp09_exact = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.10.08", height = 720, isVideoOnly = false)
        val goodVp09_p02 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.02.62.10", height = 720, isVideoOnly = false)
        val goodVp09_p03 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.03.51.12", height = 720, isVideoOnly = false)

        val badVp9_extra = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.10.08.99.99", height = 720, isVideoOnly = false)
        val badVp9_oldFull = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.51.08.01.01.01.01.00", height = 1080, isVideoOnly = false)
        val badVp9_invalidProfile = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.04.10.08", height = 720, isVideoOnly = false)
        val badVp9_invalidLevelLow = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.09.08", height = 720, isVideoOnly = false)
        val badVp9_invalidLevelHigh = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.63.08", height = 720, isVideoOnly = false)
        val badVp9_invalidDepth = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.00.10.09", height = 720, isVideoOnly = false)
        val badVp9_999 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp9.999", height = 720, isVideoOnly = false)
        val badVp9_unknown = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp9.unknown", height = 720, isVideoOnly = false)
        val badVp9_xyz = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "vp09.xyz", height = 720, isVideoOnly = false)

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodVp9", videoStreams = listOf(goodVp9)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodVp09", videoStreams = listOf(goodVp09)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodVp09_exact", videoStreams = listOf(goodVp09_exact)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodVp09_p02", videoStreams = listOf(goodVp09_p02)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodVp09_p03", videoStreams = listOf(goodVp09_p03)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_extra", videoStreams = listOf(badVp9_extra)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_oldFull", videoStreams = listOf(badVp9_oldFull)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_invalidProfile", videoStreams = listOf(badVp9_invalidProfile)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_invalidLevelLow", videoStreams = listOf(badVp9_invalidLevelLow)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_invalidLevelHigh", videoStreams = listOf(badVp9_invalidLevelHigh)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_invalidDepth", videoStreams = listOf(badVp9_invalidDepth)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_999", videoStreams = listOf(badVp9_999)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_unknown", videoStreams = listOf(badVp9_unknown)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badVp9_xyz", videoStreams = listOf(badVp9_xyz)), QualityPreference.Auto) is AppResult.Failure)

        // 3. AV1 strict: bare av1/av01 OR exact av01.P.LL[T].DD (P 0..2, LL 00..31, T M/H, DD 08|10|12, no trailing fields)
        val goodAv1_bare = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av1", height = 1080, isVideoOnly = false)
        val goodAv1_01 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01", height = 1080, isVideoOnly = false)
        val goodAv1_iso = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.08M.08", height = 1080, isVideoOnly = false)
        val goodAv1_h12 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.1.19H.12", height = 1080, isVideoOnly = false)
        val goodAv1_p2 = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.2.31M.10", height = 1080, isVideoOnly = false)

        val badAv1_trailingFields = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.08M.08.999", height = 1080, isVideoOnly = false)
        val badAv1_oldFull = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.08M.08.0.110.01.01.01.0", height = 1080, isVideoOnly = false)
        val badAv1_invalidProfile = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.3.08M.08", height = 1080, isVideoOnly = false)
        val badAv1_invalidLevel = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.32M.08", height = 1080, isVideoOnly = false)
        val badAv1_invalidTier = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.08X.08", height = 1080, isVideoOnly = false)
        val badAv1_invalidDepth = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.0.08M.16", height = 1080, isVideoOnly = false)
        val badAv1_unknown = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.unknown", height = 1080, isVideoOnly = false)
        val badAv1_invalid = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av1.invalid", height = 1080, isVideoOnly = false)
        val badAv1_malformed = videoStream(url = "https://v.webm", format = "webm", mimeType = "video/webm", codec = "av01.999.xyz", height = 1080, isVideoOnly = false)

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodAv1_bare", videoStreams = listOf(goodAv1_bare)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodAv1_01", videoStreams = listOf(goodAv1_01)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodAv1_iso", videoStreams = listOf(goodAv1_iso)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodAv1_h12", videoStreams = listOf(goodAv1_h12)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "goodAv1_p2", videoStreams = listOf(goodAv1_p2)), QualityPreference.Auto) is AppResult.Success)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_trailingFields", videoStreams = listOf(badAv1_trailingFields)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_oldFull", videoStreams = listOf(badAv1_oldFull)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_invalidProfile", videoStreams = listOf(badAv1_invalidProfile)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_invalidLevel", videoStreams = listOf(badAv1_invalidLevel)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_invalidTier", videoStreams = listOf(badAv1_invalidTier)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_invalidDepth", videoStreams = listOf(badAv1_invalidDepth)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_unknown", videoStreams = listOf(badAv1_unknown)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_invalid", videoStreams = listOf(badAv1_invalid)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "badAv1_malformed", videoStreams = listOf(badAv1_malformed)), QualityPreference.Auto) is AppResult.Failure)

        // 4. Progressive requires explicit recognized codec; null or blank codec is rejected
        val progressiveNullCodec = videoStream(url = "https://p720.mp4", format = "mp4", mimeType = "video/mp4", codec = null, height = 720, isVideoOnly = false)
        val progressiveBlankCodec = videoStream(url = "https://p720.mp4", format = "mp4", mimeType = "video/mp4", codec = "   ", height = 720, isVideoOnly = false)
        val infoNullProg = StreamInfo(key = testKey, title = "Null Prog Codec", videoStreams = listOf(progressiveNullCodec))
        val infoBlankProg = StreamInfo(key = testKey, title = "Blank Prog Codec", videoStreams = listOf(progressiveBlankCodec))

        assertTrue("Progressive with null codec must be rejected", StreamSelector.selectStream(infoNullProg, QualityPreference.Auto) is AppResult.Failure)
        assertTrue("Progressive with blank codec must be rejected", StreamSelector.selectStream(infoBlankProg, QualityPreference.Auto) is AppResult.Failure)
        assertTrue("Available qualities must be empty for null codec progressive", StreamSelector.getAvailableQualities(infoNullProg).isEmpty())
        assertTrue("Available qualities must be empty for blank codec progressive", StreamSelector.getAvailableQualities(infoBlankProg).isEmpty())
    }

    @Test
    fun specific_option_manifest_selection_exact_matches_canonical_auto_options() {
        val progressive720 = videoStream(url = "https://p720.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F", height = 720, isVideoOnly = false)
        val infoWithHls = StreamInfo(
            key = testKey,
            title = "HLS Stream",
            videoStreams = listOf(progressive720),
            hlsManifestUrl = "https://manifest.m3u8",
            dashManifestUrl = null
        )

        val canonicalQualities = StreamSelector.getAvailableQualities(infoWithHls)
        val canonicalHlsOpt = canonicalQualities.first { it.streamType == PlaybackStreamType.HLS }

        // Canonical exact match works
        val successRes = StreamSelector.selectStream(infoWithHls, QualityPreference.SpecificOption(canonicalHlsOpt))
        assertTrue(successRes is AppResult.Success)
        assertEquals(PlaybackStreamType.HLS, (successRes as AppResult.Success).value.streamType)

        // Forged / partial manifest option (e.g. wrong format, wrong mimeType, wrong label, or random streamType) must fail
        val forgedHlsOpt = QualityOption(
            height = 0,
            label = "Forged HLS",
            isProgressive = false,
            format = "mp4", // wrong format
            mimeType = "application/x-mpegURL",
            codec = null,
            streamType = PlaybackStreamType.HLS
        )
        val forgedRes = StreamSelector.selectStream(infoWithHls, QualityPreference.SpecificOption(forgedHlsOpt))
        assertTrue("Forged HLS option must fail", forgedRes is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (forgedRes as AppResult.Failure).error)

        // Requesting DASH when info has only HLS must fail
        val dashOpt = QualityOption(
            height = 0,
            label = "Auto (DASH)",
            isProgressive = false,
            format = "dash",
            mimeType = "application/dash+xml",
            codec = null,
            streamType = PlaybackStreamType.DASH
        )
        val dashRes = StreamSelector.selectStream(infoWithHls, QualityPreference.SpecificOption(dashOpt))
        assertTrue("DASH option on HLS-only stream info must fail", dashRes is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (dashRes as AppResult.Failure).error)
    }

    @Test
    fun matchesOption_exact_mime_and_codec_equality_no_null_bypass() {
        val streamWithCodec = videoStream(url = "https://v1.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F", height = 720, isVideoOnly = false)
        val info = StreamInfo(key = testKey, title = "Null Bypass Test", videoStreams = listOf(streamWithCodec))

        // Option with null codec against stream with non-null codec must NOT match
        val optNullCodec = QualityOption(
            height = 720,
            label = "720p",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = null,
            streamType = PlaybackStreamType.PROGRESSIVE
        )

        val res = StreamSelector.selectStream(info, QualityPreference.SpecificOption(optNullCodec))
        assertTrue("Option with codec=null must not match candidate with codec=avc1.64001F", res is AppResult.Failure)
        assertEquals(AppError.UnsupportedFormat, (res as AppResult.Failure).error)
    }

    @Test
    fun rejects_comma_separated_codec_lists_for_video_and_audio() {
        // Video comma lists (same or mixed family) must reject
        val vp8_vp9 = videoStream(url = "https://v1.webm", format = "webm", mimeType = "video/webm", codec = "vp8,vp9", height = 720, isVideoOnly = false)
        val vp9_av1 = videoStream(url = "https://v2.webm", format = "webm", mimeType = "video/webm", codec = "vp9,av1", height = 720, isVideoOnly = false)
        val av1_h264 = videoStream(url = "https://v3.mp4", format = "mp4", mimeType = "video/mp4", codec = "av1,h264", height = 720, isVideoOnly = false)
        val avc_multi = videoStream(url = "https://v4.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F,avc1.4d401f", height = 720, isVideoOnly = false)
        val avc_with_spaces = videoStream(url = "https://v5.mp4", format = "mp4", mimeType = "video/mp4", codec = " avc1.64001F , avc1.4d401f ", height = 720, isVideoOnly = false)

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vp8,vp9", videoStreams = listOf(vp8_vp9)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vp9,av1", videoStreams = listOf(vp9_av1)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "av1,h264", videoStreams = listOf(av1_h264)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "avc1.64001F,avc1.4d401f", videoStreams = listOf(avc_multi)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "avc with spaces", videoStreams = listOf(avc_with_spaces)), QualityPreference.Auto) is AppResult.Failure)

        // Audio comma lists (same or mixed family) must reject
        val aac_opus = audioStream(url = "https://a1.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2,opus")
        val opus_vorbis = audioStream(url = "https://a2.webm", format = "webm", mimeType = "audio/webm", codec = "opus,vorbis")
        val aac_multi = audioStream(url = "https://a3.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2,mp4a.67")
        val opus_multi = audioStream(url = "https://a4.webm", format = "webm", mimeType = "audio/webm", codec = "opus, opus")

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aac,opus", audioStreams = listOf(aac_opus)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "opus,vorbis", audioStreams = listOf(opus_vorbis)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aac,aac", audioStreams = listOf(aac_multi)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "opus,opus", audioStreams = listOf(opus_multi)), QualityPreference.Auto) is AppResult.Failure)
    }

    @Test
    fun rejects_codecs_with_empty_comma_elements_and_trailing_or_leading_commas() {
        // Video cases with leading/trailing/multiple commas
        val vTrailingComma = videoStream(url = "https://v1.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F,", height = 720, isVideoOnly = false)
        val vLeadingComma = videoStream(url = "https://v2.mp4", format = "mp4", mimeType = "video/mp4", codec = ",avc1.64001F", height = 720, isVideoOnly = false)
        val vDoubleComma = videoStream(url = "https://v3.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F,,avc1.4d401f", height = 720, isVideoOnly = false)
        val vOnlyComma = videoStream(url = "https://v4.mp4", format = "mp4", mimeType = "video/mp4", codec = ",", height = 720, isVideoOnly = false)
        val vMultipleCommas = videoStream(url = "https://v5.mp4", format = "mp4", mimeType = "video/mp4", codec = ",,", height = 720, isVideoOnly = false)
        val vSpacesComma = videoStream(url = "https://v6.mp4", format = "mp4", mimeType = "video/mp4", codec = " , ", height = 720, isVideoOnly = false)

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vTrailingComma", videoStreams = listOf(vTrailingComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vLeadingComma", videoStreams = listOf(vLeadingComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vDoubleComma", videoStreams = listOf(vDoubleComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vOnlyComma", videoStreams = listOf(vOnlyComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vMultipleCommas", videoStreams = listOf(vMultipleCommas)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "vSpacesComma", videoStreams = listOf(vSpacesComma)), QualityPreference.Auto) is AppResult.Failure)

        // Audio cases with leading/trailing/multiple commas
        val aTrailingComma = audioStream(url = "https://a1.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2,")
        val aLeadingComma = audioStream(url = "https://a2.m4a", format = "m4a", mimeType = "audio/mp4", codec = ",mp4a.40.2")
        val aDoubleComma = audioStream(url = "https://a3.m4a", format = "m4a", mimeType = "audio/mp4", codec = "mp4a.40.2,,opus")
        val aOnlyComma = audioStream(url = "https://a4.m4a", format = "m4a", mimeType = "audio/mp4", codec = ",")
        val aMultipleCommas = audioStream(url = "https://a5.m4a", format = "m4a", mimeType = "audio/mp4", codec = ",,")
        val aSpacesComma = audioStream(url = "https://a6.m4a", format = "m4a", mimeType = "audio/mp4", codec = " , ")

        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aTrailingComma", audioStreams = listOf(aTrailingComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aLeadingComma", audioStreams = listOf(aLeadingComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aDoubleComma", audioStreams = listOf(aDoubleComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aOnlyComma", audioStreams = listOf(aOnlyComma)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aMultipleCommas", audioStreams = listOf(aMultipleCommas)), QualityPreference.Auto) is AppResult.Failure)
        assertTrue(StreamSelector.selectStream(StreamInfo(key = testKey, title = "aSpacesComma", audioStreams = listOf(aSpacesComma)), QualityPreference.Auto) is AppResult.Failure)
    }
}

