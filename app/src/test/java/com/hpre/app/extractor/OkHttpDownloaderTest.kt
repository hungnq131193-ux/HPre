package com.hpre.app.extractor

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request as OkRequest
import okhttp3.Response as OkResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

class OkHttpDownloaderTest {

    private fun createMockClient(
        statusCode: Int = 200,
        responseBody: String = "ok",
        interceptor: (OkRequest) -> Unit = {}
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                interceptor(request)
                OkResponse.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("Response $statusCode")
                    .body(responseBody.toResponseBody("text/plain".toMediaTypeOrNull()))
                    .build()
            }
            .build()
    }

    @Test
    fun execute_get_request_applies_default_user_agent_if_not_present() {
        var recordedUserAgent: String? = null
        val client = createMockClient { req ->
            recordedUserAgent = req.header("User-Agent")
        }
        val downloader = OkHttpDownloader(client)

        val request = Request.newBuilder()
            .url("https://example.com/test")
            .httpMethod("GET")
            .build()

        val response = downloader.execute(request)
        assertEquals(200, response.responseCode())
        assertEquals("ok", response.responseBody())
        assertEquals(OkHttpDownloader.USER_AGENT, recordedUserAgent)
    }

    @Test
    fun execute_preserves_custom_user_agent_without_duplicate() {
        var userAgentHeaders: List<String> = emptyList()
        val client = createMockClient { req ->
            userAgentHeaders = req.headers("User-Agent")
        }
        val downloader = OkHttpDownloader(client)

        val request = Request.newBuilder()
            .url("https://example.com/test")
            .httpMethod("GET")
            .headers(mapOf("User-Agent" to listOf("CustomAgent/1.0")))
            .build()

        val response = downloader.execute(request)
        assertEquals(200, response.responseCode())
        assertEquals(listOf("CustomAgent/1.0"), userAgentHeaders)
    }

    @Test
    fun execute_post_with_body_and_headers_case_insensitively_propagates_correctly() {
        var recordedMethod: String? = null
        var recordedContentType: String? = null
        var recordedCustomHeader: String? = null
        val client = createMockClient { req ->
            recordedMethod = req.method
            recordedContentType = req.body?.contentType()?.toString()
            recordedCustomHeader = req.header("X-Custom-Header")
        }
        val downloader = OkHttpDownloader(client)

        val request = Request.newBuilder()
            .url("https://example.com/api")
            .httpMethod("POST")
            .headers(mapOf(
                "content-type" to listOf("application/json; charset=utf-8"),
                "X-Custom-Header" to listOf("CustomValue")
            ))
            .dataToSend("{\"query\":\"test\"}".toByteArray(Charsets.UTF_8))
            .build()

        val response = downloader.execute(request)
        assertEquals(200, response.responseCode())
        assertEquals("POST", recordedMethod)
        assertEquals("application/json; charset=utf-8", recordedContentType)
        assertEquals("CustomValue", recordedCustomHeader)
    }

    @Test
    fun execute_maps_429_to_safe_extractor_http_exception_without_url_or_token_leakage() {
        val client = createMockClient(statusCode = 429, responseBody = "Rate limit body with secret=SECRET_123")
        val downloader = OkHttpDownloader(client)

        val request = Request.newBuilder()
            .url("https://example.com/test?token=SECRET_TOKEN")
            .httpMethod("GET")
            .build()

        try {
            downloader.execute(request)
            fail("Expected ExtractorHttpException")
        } catch (e: ExtractorHttpException) {
            assertEquals(429, e.statusCode)
            assertEquals(ExtractorOperationContext.EXTRACTION_METADATA, e.operationContext)
            assertFalse(e.message?.contains("SECRET_TOKEN") ?: false)
            assertFalse(e.message?.contains("SECRET_123") ?: false)
            assertFalse(e.message?.contains("https://") ?: false)
        }
    }

    @Test
    fun execute_maps_non_2xx_to_extractor_http_exception_with_only_safe_context() {
        val client = createMockClient(statusCode = 403, responseBody = "Forbidden body with token=SECRET_TOKEN")
        val downloader = OkHttpDownloader(client)

        val request = Request.newBuilder()
            .url("https://example.com/secret?token=SECRET_TOKEN")
            .httpMethod("GET")
            .build()

        try {
            downloader.execute(request)
            fail("Expected ExtractorHttpException")
        } catch (e: ExtractorHttpException) {
            assertEquals(403, e.statusCode)
            assertEquals(ExtractorOperationContext.EXTRACTION_METADATA, e.operationContext)
            // Verify safe message never leaks URL / token
            assertFalse(e.message?.contains("SECRET_TOKEN") ?: false)
            assertFalse(e.message?.contains("https://") ?: false)
        }
    }
}
