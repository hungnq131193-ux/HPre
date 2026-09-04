package com.hpre.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlExpiryTest {
    private val key = ContentKey(0, "expiry")

    @Test
    fun usable_media_requires_a_nonblank_playable_url_and_future_expiry() {
        val now = 1_000_000L
        assertFalse(StreamInfo(key, "Empty").hasUsableMediaUrls(now))
        assertFalse(
            StreamInfo(key, "Expired", hlsManifestUrl = "https://h.test/x?expire=900")
                .hasUsableMediaUrls(now)
        )
        assertTrue(
            StreamInfo(key, "Future", hlsManifestUrl = "https://h.test/x?expire=4102444800")
                .hasUsableMediaUrls(now)
        )
    }

    @Test
    fun subtitle_expiry_does_not_affect_usable_media_urls() {
        val now = 1_000_000L
        val info = StreamInfo(
            key = key,
            title = "Title",
            videoStreams = listOf(VideoStream("https://v.test/x?expire=4102444800", "mp4", "360p", 640, 360, 1)),
            subtitles = listOf(SubtitleStream("https://s.test/sub?expire=500", "en", "vtt"))
        )
        assertTrue(info.hasUsableMediaUrls(now))
    }

    @Test
    fun returns_earliest_expiry_across_stream_urls() {
        val info = StreamInfo(
            key = key,
            title = "Title",
            videoStreams = listOf(VideoStream("https://v.test/x?expire=200", "mp4", "360p", 640, 360, 1)),
            audioStreams = listOf(AudioStream("https://a.test/x?expires=150", "m4a", 1)),
            hlsManifestUrl = "https://h.test/master.m3u8?expire=180"
        )

        assertEquals(150_000L, info.earliestMediaUrlExpiryMs())
    }

    @Test
    fun accepts_epoch_milliseconds_and_ignores_malformed_values() {
        assertEquals(
            1_700_000_000_000L,
            StreamInfo(key, "Title", dashManifestUrl = "https://d.test/manifest?expire=1700000000000&expires=bad")
                .earliestMediaUrlExpiryMs()
        )
        assertNull(StreamInfo(key, "Title", hlsManifestUrl = "https://h.test/x?expire=bad").earliestMediaUrlExpiryMs())
    }
}
