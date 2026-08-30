package com.hpre.app.model

import com.hpre.app.player.PlaybackStreamType
import com.hpre.app.player.SelectedStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamInfoTest {
    @Test
    fun videoStream_supportsIdentityAndDeliveryMethodWithDefaults() {
        val streamDefault = VideoStream(
            url = "https://video.test/default",
            format = "mp4",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1_000_000L
        )
        assertNull(streamDefault.streamId)
        assertNull(streamDefault.deliveryMethod)

        val streamWithIdentity = VideoStream(
            url = "https://video.test/1",
            format = "mp4",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1_500_000L,
            streamId = "itag_22",
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP
        )
        assertEquals("itag_22", streamWithIdentity.streamId)
        assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, streamWithIdentity.deliveryMethod)
    }

    @Test
    fun audioStream_supportsIdentityAndAudioTrackIdWithDefaults() {
        val audioDefault = AudioStream(
            url = "https://audio.test/default",
            format = "m4a",
            bitrate = 128_000L
        )
        assertNull(audioDefault.streamId)
        assertNull(audioDefault.audioTrackId)

        val audioWithIdentity = AudioStream(
            url = "https://audio.test/1",
            format = "m4a",
            bitrate = 128_000L,
            streamId = "itag_140",
            audioTrackId = "en-orig"
        )
        assertEquals("itag_140", audioWithIdentity.streamId)
        assertEquals("en-orig", audioWithIdentity.audioTrackId)
    }

    @Test
    fun selectedStreams_carriesIsLiveFact() {
        val selVod = SelectedStreams(
            key = ContentKey(0, "vid1"),
            streamType = PlaybackStreamType.PROGRESSIVE,
            isLive = false
        )
        assertFalse(selVod.isLive)

        val selLive = SelectedStreams(
            key = ContentKey(0, "vid2"),
            streamType = PlaybackStreamType.HLS,
            isLive = true
        )
        assertTrue(selLive.isLive)
    }
}
