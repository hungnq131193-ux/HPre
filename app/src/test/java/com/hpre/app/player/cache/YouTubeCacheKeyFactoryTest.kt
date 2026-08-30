package com.hpre.app.player.cache

import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCacheKeyFactoryTest {
    private val contentKey = ContentKey(0, "dQw4w9WgXcQ")

    @Test
    fun sameIdentifiedVideo_withDifferentSignedUrls_generatesIdenticalCacheKey() {
        val stream1 = VideoStream(
            url = "https://rr1.googlevideo.com/videoplayback?expire=100&sig=abc",
            format = "mp4",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1000L,
            streamId = "22"
        )
        val stream2 = VideoStream(
            url = "https://rr2.googlevideo.com/videoplayback?expire=200&sig=xyz",
            format = "mp4",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1000L,
            streamId = "22"
        )

        val key1 = YouTubeCacheKeyFactory.buildVideoCacheKey(contentKey, stream1)
        val key2 = YouTubeCacheKeyFactory.buildVideoCacheKey(contentKey, stream2)

        assertEquals(key1, key2)
        assertTrue(key1.startsWith("hpre:v1:v:"))
    }

    @Test
    fun distinctResolutionsOrVideoOnly_generatesDistinctCacheKeys() {
        val stream720 = VideoStream(
            url = "https://v.test/720",
            format = "mp4",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1000L,
            streamId = "22",
            isVideoOnly = false
        )
        val stream1080 = VideoStream(
            url = "https://v.test/1080",
            format = "mp4",
            resolution = "1080p",
            width = 1920,
            height = 1080,
            bitrate = 2000L,
            streamId = "137",
            isVideoOnly = true
        )

        val key720 = YouTubeCacheKeyFactory.buildVideoCacheKey(contentKey, stream720)
        val key1080 = YouTubeCacheKeyFactory.buildVideoCacheKey(contentKey, stream1080)

        assertNotEquals(key720, key1080)
    }

    @Test
    fun missingStreamId_fallsBackToUrlHash() {
        val streamNoId = VideoStream(
            url = "https://v.test/raw.mp4",
            format = "mp4",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1000L,
            streamId = null
        )
        val key = YouTubeCacheKeyFactory.buildVideoCacheKey(contentKey, streamNoId)

        assertTrue(key.startsWith("hpre:v1:url:"))
    }

    @Test
    fun audioStream_includesTrackAndLanguage() {
        val audioEn = AudioStream(
            url = "https://a.test/en",
            format = "m4a",
            bitrate = 128_000L,
            averageBitrate = 128_000L,
            streamId = "140",
            audioTrackId = "en",
            language = "en"
        )
        val audioVi = AudioStream(
            url = "https://a.test/vi",
            format = "m4a",
            bitrate = 128_000L,
            averageBitrate = 128_000L,
            streamId = "140",
            audioTrackId = "vi",
            language = "vi"
        )

        val keyEn = YouTubeCacheKeyFactory.buildAudioCacheKey(contentKey, audioEn)
        val keyVi = YouTubeCacheKeyFactory.buildAudioCacheKey(contentKey, audioVi)

        assertNotEquals(keyEn, keyVi)
        assertTrue(keyEn.startsWith("hpre:v1:a:"))
    }
}
