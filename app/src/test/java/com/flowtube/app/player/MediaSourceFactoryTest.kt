package com.flowtube.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(UnstableApi::class)
class MediaSourceFactoryTest {

    private val testKey = com.flowtube.app.model.ContentKey(0, "test_item")

    private fun createFakeMediaSource(): MediaSource {
        return Proxy.newProxyInstance(
            MediaSource::class.java.classLoader,
            arrayOf(MediaSource::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "FakeMediaSource"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as MediaSource
    }

    private class FakeDataSourceFactory : DataSource.Factory {
        override fun createDataSource(): DataSource {
            throw UnsupportedOperationException()
        }
    }

    private val dataSourceFactory = FakeDataSourceFactory()

    @Test
    fun progressive_creates_progressive_media_source_with_correct_mime_and_subtitles() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.PROGRESSIVE,
            videoStream = com.flowtube.app.model.VideoStream(
                url = "https://example.com/video.mp4",
                format = "mp4",
                resolution = "720p",
                width = 1280,
                height = 720,
                bitrate = 1_500_000L,
                isVideoOnly = false,
                mimeType = "video/mp4"
            ),
            subtitles = listOf(
                com.flowtube.app.model.SubtitleStream(
                    url = "https://example.com/sub.vtt",
                    language = "en",
                    format = "vtt",
                    mimeType = "text/vtt"
                )
            )
        )

        var createdFor: SelectedStreams? = null
        val fakeSource = createFakeMediaSource()
        val customCreator = MediaSourceCreator { s ->
            createdFor = s
            fakeSource
        }
        val factory = MediaSourceFactory(dataSourceFactory, customCreator::createMediaSource)
        val source = factory.createMediaSource(selected)

        assertNotNull(source)
        assertEquals(PlaybackStreamType.PROGRESSIVE, createdFor?.streamType)
        assertEquals("https://example.com/video.mp4", createdFor?.videoStream?.url)
        assertEquals(1, createdFor?.subtitles?.size)
    }

    @Test
    fun merged_av_passes_both_video_and_audio_streams() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.MERGED_AV,
            videoStream = com.flowtube.app.model.VideoStream(
                url = "https://example.com/video.webm",
                format = "webm",
                resolution = "1080p",
                width = 1920,
                height = 1080,
                bitrate = 2_500_000L,
                isVideoOnly = true,
                mimeType = "video/webm"
            ),
            audioStream = com.flowtube.app.model.AudioStream(
                url = "https://example.com/audio.webm",
                format = "webm",
                bitrate = 128_000L,
                mimeType = "audio/webm"
            )
        )

        var createdFor: SelectedStreams? = null
        val fakeSource = createFakeMediaSource()
        val customCreator = MediaSourceCreator { s ->
            createdFor = s
            fakeSource
        }
        val factory = MediaSourceFactory(dataSourceFactory, customCreator::createMediaSource)
        val source = factory.createMediaSource(selected)

        assertNotNull(source)
        assertEquals(PlaybackStreamType.MERGED_AV, createdFor?.streamType)
        assertEquals("https://example.com/video.webm", createdFor?.videoStream?.url)
        assertEquals("https://example.com/audio.webm", createdFor?.audioStream?.url)
    }

    @Test
    fun audio_only_passes_audio_stream() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.AUDIO_ONLY,
            audioStream = com.flowtube.app.model.AudioStream(
                url = "https://example.com/audio.m4a",
                format = "m4a",
                bitrate = 128_000L,
                mimeType = "audio/mp4"
            )
        )

        var createdFor: SelectedStreams? = null
        val fakeSource = createFakeMediaSource()
        val customCreator = MediaSourceCreator { s ->
            createdFor = s
            fakeSource
        }
        val factory = MediaSourceFactory(dataSourceFactory, customCreator::createMediaSource)
        val source = factory.createMediaSource(selected)

        assertNotNull(source)
        assertEquals(PlaybackStreamType.AUDIO_ONLY, createdFor?.streamType)
        assertEquals("https://example.com/audio.m4a", createdFor?.audioStream?.url)
    }

    @Test
    fun hls_passes_manifest_url() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.HLS,
            manifestUrl = "https://example.com/master.m3u8"
        )

        var createdFor: SelectedStreams? = null
        val fakeSource = createFakeMediaSource()
        val customCreator = MediaSourceCreator { s ->
            createdFor = s
            fakeSource
        }
        val factory = MediaSourceFactory(dataSourceFactory, customCreator::createMediaSource)
        val source = factory.createMediaSource(selected)

        assertNotNull(source)
        assertEquals(PlaybackStreamType.HLS, createdFor?.streamType)
        assertEquals("https://example.com/master.m3u8", createdFor?.manifestUrl)
    }

    @Test
    fun dash_passes_manifest_url() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.DASH,
            manifestUrl = "https://example.com/manifest.mpd"
        )

        var createdFor: SelectedStreams? = null
        val fakeSource = createFakeMediaSource()
        val customCreator = MediaSourceCreator { s ->
            createdFor = s
            fakeSource
        }
        val factory = MediaSourceFactory(dataSourceFactory, customCreator::createMediaSource)
        val source = factory.createMediaSource(selected)

        assertNotNull(source)
        assertEquals(PlaybackStreamType.DASH, createdFor?.streamType)
        assertEquals("https://example.com/manifest.mpd", createdFor?.manifestUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformed_required_fields_throws_exception() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.PROGRESSIVE,
            videoStream = null
        )

        val factory = MediaSourceFactory(dataSourceFactory)
        factory.createMediaSource(selected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknown_ambiguous_mime_or_format_is_rejected_with_exception() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.PROGRESSIVE,
            videoStream = com.flowtube.app.model.VideoStream(
                url = "https://example.com/video.xyz",
                format = "unknown_xyz_format",
                resolution = "720p",
                width = 1280,
                height = 720,
                bitrate = 1_500_000L,
                isVideoOnly = false,
                mimeType = null
            )
        )

        val factory = MediaSourceFactory(dataSourceFactory)
        factory.createMediaSource(selected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun video_notwebm_mime_override_rejected_with_exception() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.PROGRESSIVE,
            videoStream = com.flowtube.app.model.VideoStream(
                url = "https://example.com/video.webm",
                format = "webm",
                resolution = "720p",
                width = 1280,
                height = 720,
                bitrate = 1_500_000L,
                isVideoOnly = false,
                mimeType = "video/notwebm"
            )
        )
        val factory = MediaSourceFactory(dataSourceFactory)
        factory.createMediaSource(selected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun video_unknown_mp4_mime_override_rejected_with_exception() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.PROGRESSIVE,
            videoStream = com.flowtube.app.model.VideoStream(
                url = "https://example.com/video.mp4",
                format = "mp4",
                resolution = "720p",
                width = 1280,
                height = 720,
                bitrate = 1_500_000L,
                isVideoOnly = false,
                mimeType = "video/unknown-mp4"
            )
        )
        val factory = MediaSourceFactory(dataSourceFactory)
        factory.createMediaSource(selected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun video_notmp4_format_rejected_with_exception() {
        val selected = SelectedStreams(
            key = testKey,
            streamType = PlaybackStreamType.PROGRESSIVE,
            videoStream = com.flowtube.app.model.VideoStream(
                url = "https://example.com/video.mp4",
                format = "notmp4",
                resolution = "720p",
                width = 1280,
                height = 720,
                bitrate = 1_500_000L,
                isVideoOnly = false,
                mimeType = null
            )
        )
        val factory = MediaSourceFactory(dataSourceFactory)
        factory.createMediaSource(selected)
    }
}


