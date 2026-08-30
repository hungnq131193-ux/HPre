package com.hpre.app.player.datasource

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class YouTubeMediaHttpDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun youtubeRequest_sendsPostWithTwoBytesBodyAndHeaders() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("payload"))

        val baseUrl = server.url("/videoplayback").toString()
        val mockYtUrl = baseUrl.replace("localhost", "rr1---sn-4g5ednks.googlevideo.com")
            .replace("127.0.0.1", "rr1---sn-4g5ednks.googlevideo.com")

        val policyTransformed = YouTubeRequestPolicy.transformDataSpec(
            DataSpec.Builder().setUri(Uri.parse(mockYtUrl)).build(),
            YouTubeRequestProfile.PROGRESSIVE,
            0
        )

        val actualTestUri = Uri.parse(baseUrl).buildUpon()
            .encodedQuery(policyTransformed.uri.query)
            .build()

        val delegateFactory = OkHttpDataSource.Factory(okHttpClient)
        val dataSource = YouTubeMediaHttpDataSource(delegateFactory.createDataSource(), YouTubeRequestProfile.PROGRESSIVE)

        val spec = DataSpec.Builder()
            .setUri(actualTestUri)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(0x78, 0x00))
            .setHttpRequestHeaders(policyTransformed.headers)
            .build()

        val bytes = dataSource.open(spec)
        assertEquals(7L, bytes)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("https://www.youtube.com", recorded.getHeader("Origin"))
        assertEquals("trailers", recorded.getHeader("TE"))
        assertArrayEquals(byteArrayOf(0x78, 0x00), recorded.body.readByteArray())
        dataSource.close()
    }

    @Test
    fun redirect302_followsRedirectAndTransitionsToGet() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/target").toString())
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("target_payload"))

        val delegateFactory = OkHttpDataSource.Factory(okHttpClient)
        val dataSource = YouTubeMediaHttpDataSource(delegateFactory.createDataSource(), YouTubeRequestProfile.PROGRESSIVE)

        val spec = DataSpec.Builder()
            .setUri(Uri.parse(server.url("/start").toString()))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(0x78, 0x00))
            .build()

        val bytes = dataSource.open(spec)
        assertEquals(14L, bytes)

        val req1 = server.takeRequest()
        assertEquals("POST", req1.method)

        val req2 = server.takeRequest()
        assertEquals("GET", req2.method)
        assertEquals(0, req2.bodySize)
        dataSource.close()
    }
}
