package com.hpre.app.download

import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlanTest {

    private val key = ContentKey(serviceId = 0, nativeId = "abc123")

    private fun videoStream(
        height: Int?,
        videoOnly: Boolean,
        url: String = "https://v/$height/$videoOnly"
    ) = VideoStream(
        url = url,
        format = "mp4",
        resolution = "${height}p",
        width = null,
        height = height,
        bitrate = 1_000_000L,
        isVideoOnly = videoOnly
    )

    private fun audioStream(bitrate: Long?, url: String = "https://a/$bitrate") = AudioStream(
        url = url,
        format = "m4a",
        bitrate = bitrate
    )

    @Test
    fun `audioOnly picks highest bitrate audio`() {
        val info = StreamInfo(
            key = key,
            title = "t",
            audioStreams = listOf(
                audioStream(64_000, "https://a/low"),
                audioStream(160_000, "https://a/high"),
                audioStream(96_000, "https://a/mid")
            )
        )
        val plan = DownloadPlan.select(info, audioOnly = true)
        assertEquals(1, plan?.size)
        assertEquals("https://a/high", plan!![0].url)
        assertEquals(DownloadPlan.primaryId(key), plan[0].id)
    }

    @Test
    fun `video prefers highest muxed stream`() {
        val info = StreamInfo(
            key = key,
            title = "t",
            videoStreams = listOf(
                videoStream(360, videoOnly = false, url = "https://v/mux360"),
                videoStream(720, videoOnly = false, url = "https://v/mux720"),
                videoStream(1080, videoOnly = true, url = "https://v/only1080")
            )
        )
        val plan = DownloadPlan.select(info, audioOnly = false)
        assertEquals(1, plan?.size)
        assertEquals("https://v/mux720", plan!![0].url)
        assertTrue(plan[0].isVideo)
    }

    @Test
    fun `video without muxed falls back to split video plus audio`() {
        val info = StreamInfo(
            key = key,
            title = "t",
            videoStreams = listOf(
                videoStream(480, videoOnly = true, url = "https://v/only480"),
                videoStream(1080, videoOnly = true, url = "https://v/only1080")
            ),
            audioStreams = listOf(audioStream(128_000, "https://a/best"))
        )
        val plan = DownloadPlan.select(info, audioOnly = false)
        assertEquals(2, plan?.size)
        assertEquals("https://v/only1080", plan!![0].url)
        assertEquals("https://a/best", plan[1].url)
        assertEquals(DownloadPlan.audioId(key), plan[1].id)
    }

    @Test
    fun `returns null when nothing downloadable`() {
        val empty = StreamInfo(key = key, title = "t")
        assertNull(DownloadPlan.select(empty, audioOnly = true))
        assertNull(DownloadPlan.select(empty, audioOnly = false))
        assertNull(
            DownloadPlan.select(
                StreamInfo(key = key, title = "t", videoStreams = listOf(videoStream(720, true))),
                audioOnly = false
            )
        )
    }

    @Test
    fun `ids encode content key`() {
        assertEquals("hpre:0:abc123", DownloadPlan.primaryId(key))
        assertEquals("hpre:0:abc123|a", DownloadPlan.audioId(key))
    }
}
