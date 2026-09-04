package com.hpre.app.player

import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStreamHandoffTest {
    private fun playableInfo(key: ContentKey, expireSeconds: Long = 4_102_444_800L): StreamInfo =
        StreamInfo(
            key = key,
            title = "Title ${key.nativeId}",
            videoStreams = listOf(
                VideoStream(
                    url = "https://cdn.example/video.mp4?expire=$expireSeconds",
                    format = "mp4",
                    resolution = "360p",
                    width = 640,
                    height = 360,
                    bitrate = 1_000L
                )
            )
        )

    @Test
    fun valid_handoff_returns_the_exact_reference_once() {
        val info = playableInfo(ContentKey(0, "video"), expireSeconds = 4_102_444_800L)
        val token = PlaybackStreamHandoff.put(info, requestGeneration = 7L, currentTimeMs = 1_000L)

        assertSame(
            info,
            PlaybackStreamHandoff.takeIfValid(token, info.key, 7L, currentTimeMs = 2_000L)
        )
        assertNull(PlaybackStreamHandoff.takeIfValid(token, info.key, 7L, currentTimeMs = 2_000L))
    }

    @Test
    fun rejections_for_wrong_key_generation_ttl_expiry_blank_and_clock_skew_remove_token() {
        val key = ContentKey(0, "video")
        val otherKey = ContentKey(0, "other")

        // Wrong key
        var info = playableInfo(key)
        var token = PlaybackStreamHandoff.put(info, requestGeneration = 1L, currentTimeMs = 1_000L)
        assertNull(PlaybackStreamHandoff.takeIfValid(token, otherKey, 1L, currentTimeMs = 1_000L))
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 1L, currentTimeMs = 1_000L))

        // Wrong generation
        token = PlaybackStreamHandoff.put(info, requestGeneration = 2L, currentTimeMs = 1_000L)
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 99L, currentTimeMs = 1_000L))
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 2L, currentTimeMs = 1_000L))

        // Expired TTL (> 60s)
        token = PlaybackStreamHandoff.put(info, requestGeneration = 3L, currentTimeMs = 1_000L)
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 3L, currentTimeMs = 62_000L))
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 3L, currentTimeMs = 62_000L))

        // Expired media URL (URL expiry 10s, take at 15s)
        val expiredInfo = playableInfo(key, expireSeconds = 10L)
        token = PlaybackStreamHandoff.put(expiredInfo, requestGeneration = 4L, currentTimeMs = 1_000L)
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 4L, currentTimeMs = 15_000L))
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 4L, currentTimeMs = 15_000L))

        // Blank media URLs
        val blankInfo = StreamInfo(key, "Blank")
        token = PlaybackStreamHandoff.put(blankInfo, requestGeneration = 5L, currentTimeMs = 1_000L)
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 5L, currentTimeMs = 2_000L))
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 5L, currentTimeMs = 2_000L))

        // Negative clock age (clock skew backward)
        token = PlaybackStreamHandoff.put(info, requestGeneration = 6L, currentTimeMs = 5_000L)
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 6L, currentTimeMs = 4_000L))
        assertNull(PlaybackStreamHandoff.takeIfValid(token, key, 6L, currentTimeMs = 5_000L))
    }

    @Test
    fun max_entries_evicts_oldest() {
        PlaybackStreamHandoff.clear()
        for (i in 1..25) {
            val key = ContentKey(0, "video_$i")
            PlaybackStreamHandoff.put(playableInfo(key), requestGeneration = i.toLong(), currentTimeMs = 1000L)
        }
        assertEquals(16, PlaybackStreamHandoff.size())
    }
}

