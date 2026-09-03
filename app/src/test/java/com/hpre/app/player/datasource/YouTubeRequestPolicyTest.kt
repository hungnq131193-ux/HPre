package com.hpre.app.player.datasource

import android.net.Uri
import androidx.media3.common.C
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
    fun dashProfile_replacesExistingRange_andDoesNotDuplicateRn() {
        val uri = Uri.parse(
            "https://rr1---sn-4g5ednks.googlevideo.com/videoplayback?expire=123&rn=7&range=0-99"
        )
        val dataSpec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(100)
            .setLength(200)
            .build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.DASH, requestNumber = 8)

        assertEquals(
            "https://rr1---sn-4g5ednks.googlevideo.com/videoplayback?expire=123&rn=7&range=100-299",
            transformed.uriString
        )
    }

    @Test
    fun dashProfile_clearsDataSpecRangeAfterEncodingItInQuery() {
        val dataSpec = DataSpec.Builder()
            .setUri(ytUri)
            .setPosition(100)
            .setLength(200)
            .build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.DASH, requestNumber = 0)

        assertEquals(0L, transformed.position)
        assertEquals(C.LENGTH_UNSET.toLong(), transformed.length)
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

    @Test
    fun lookalikeVideoPlaybackPath_isNotEligible() {
        val uri = Uri.parse("https://rr1.googlevideo.com/videoplaybackevil?expire=123")
        val dataSpec = DataSpec.Builder().setUri(uri).build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.DASH, requestNumber = 0)

        assertFalse(transformed.isEligibleYouTube)
        assertEquals(uri.toString(), transformed.uriString)
    }

    @Test
    fun queryParameterReplacement_preservesExistingEncodingWithoutDoubleEncoding() {
        val original = "https://rr1---sn-8qj-i5olr.googlevideo.com/videoplayback?expire=123&spc=abc%3D%3D&mime=video%2Fmp4"
        val dataSpec = DataSpec.Builder().setUri(Uri.parse(original)).build()

        val transformed = YouTubeRequestPolicy.transformDataSpec(dataSpec, YouTubeRequestProfile.PROGRESSIVE, requestNumber = 0)

        assertTrue(transformed.uriString.contains("spc=abc%3D%3D"))
        assertTrue(transformed.uriString.contains("mime=video%2Fmp4"))
        assertTrue(transformed.uriString.contains("rn=0"))
        assertFalse(transformed.uriString.contains("%253D"))
        assertFalse(transformed.uriString.contains("%252F"))
    }
}
