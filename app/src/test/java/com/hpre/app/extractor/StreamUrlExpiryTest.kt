package com.hpre.app.extractor

import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlExpiryTest {
    private val key = ContentKey(0, "expiry")

    @Test
    fun returns_earliest_expiry_across_stream_urls() {
        val info = StreamInfo(
            key = key,
            title = "Title",
            videoStreams = listOf(VideoStream("https://v.test/x?expire=200", "mp4", "360p", 640, 360, 1)),
            audioStreams = listOf(AudioStream("https://a.test/x?expires=150", "m4a", 1)),
            hlsManifestUrl = "https://h.test/master.m3u8?expire=180"
        )

        assertEquals(150_000L, info.earliestUrlExpiryMs())
    }

    @Test
    fun accepts_epoch_milliseconds_and_ignores_malformed_values() {
        assertEquals(
            1_700_000_000_000L,
            StreamInfo(key, "Title", dashManifestUrl = "https://d.test/manifest?expire=1700000000000&expires=bad")
                .earliestUrlExpiryMs()
        )
        assertNull(StreamInfo(key, "Title", hlsManifestUrl = "https://h.test/x?expire=bad").earliestUrlExpiryMs())
    }
}
