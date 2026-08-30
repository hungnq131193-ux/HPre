package com.hpre.app.player.datasource

import android.net.Uri
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeRequestPolicyTest {
    private val ytUri = Uri.parse("https://rr1---sn-4g5ednks.googlevideo.com/videoplayback?expire=123")

    @Test
    fun dashProfile_addsRnAndRangeQuery_andRemovesRangeHeader() {
        val dataSpec = DataSpec.Builder()
            .setUri(ytUri)
            .setPosition(100)
            .setLength(200)
            .setHttpRequestHeaders(mapOf("Range" to "bytes=100-299", "Custom" to "val"))
            .build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.DASH, requestNumber = 0)

        assertTrue(transformed.uri.toString().contains("rn=0"))
        assertTrue(transformed.uri.toString().contains("range=100-299"))
        assertFalse(transformed.headers.containsKey("Range"))
        assertEquals("val", transformed.headers["Custom"])
        assertEquals("https://www.youtube.com", transformed.headers["Origin"])
        assertEquals(DataSpec.HTTP_METHOD_POST, transformed.httpMethod)
        assertEquals(2, transformed.httpBody?.size)
    }

    @Test
    fun progressiveProfile_addsRn_preservesRangeHeader() {
        val dataSpec = DataSpec.Builder()
            .setUri(ytUri)
            .setPosition(100)
            .setLength(200)
            .setHttpRequestHeaders(mapOf("Range" to "bytes=100-299"))
            .build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.PROGRESSIVE, requestNumber = 1)

        assertTrue(transformed.uri.toString().contains("rn=1"))
        assertFalse(transformed.uri.toString().contains("range="))
        assertEquals("bytes=100-299", transformed.headers["Range"])
        assertEquals(DataSpec.HTTP_METHOD_POST, transformed.httpMethod)
    }

    @Test
    fun hlsProfile_doesNotMutateUri() {
        val dataSpec = DataSpec.Builder()
            .setUri(ytUri)
            .setPosition(100)
            .setLength(200)
            .build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.HLS, requestNumber = 0)

        assertFalse(transformed.uri.toString().contains("rn="))
        assertFalse(transformed.uri.toString().contains("range="))
        assertEquals("https://www.youtube.com", transformed.headers["Origin"])
        assertEquals(DataSpec.HTTP_METHOD_POST, transformed.httpMethod)
    }

    @Test
    fun nonYouTubeHost_isNotMutated() {
        val nonYt = Uri.parse("https://example.com/video.mp4")
        val dataSpec = DataSpec.Builder().setUri(nonYt).build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.DASH, requestNumber = 0)
        assertEquals(nonYt, transformed.uri)
        assertFalse(transformed.isEligibleYouTube)
        assertEquals(DataSpec.HTTP_METHOD_GET, transformed.httpMethod)
    }
}
