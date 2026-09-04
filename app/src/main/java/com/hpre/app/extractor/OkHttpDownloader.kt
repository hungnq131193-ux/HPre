package com.hpre.app.extractor

import com.hpre.app.core.network.DiagnosticOperation
import com.hpre.app.core.network.DiagnosticStatus
import com.hpre.app.core.network.LogCategory
import com.hpre.app.core.network.RedactingLogger
import com.hpre.app.core.network.SafeDiagnosticLogger
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

enum class ExtractorOperationContext {
    EXTRACTION_METADATA
}

/**
 * Safe typed internal exception holding only HTTP status and operation context (never URL/cookie).
 */
class ExtractorHttpException(
    val statusCode: Int,
    val operationContext: ExtractorOperationContext = ExtractorOperationContext.EXTRACTION_METADATA
) : IOException("HTTP $statusCode during $operationContext")

/**
 * Custom OkHttpDownloader implementing NewPipeExtractor's Downloader contract.
 *
 * Requirements:
 * - Uses shared/pooled OkHttpClient or Call.Factory
 * - Documented non-sensitive User-Agent (protected precedence: do not duplicate if present, otherwise set default)
 * - Strict privacy: NEVER logs request cookies, authorization tokens, or raw stream query params
 * - Response body is safely consumed using `.use { ... }`
 * - Non-success HTTP responses map to ExtractorHttpException with only safe status/operation context
 * - Correctly bridges OkHttp call execution to NewPipeExtractor Response
 * - Tracks active call per worker thread for deterministic cancellation
 */
open class OkHttpDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val callFactory: Call.Factory = client,
    private val diagnosticLogger: SafeDiagnosticLogger = RedactingLogger
) : Downloader() {

    companion object {
        const val USER_AGENT = com.hpre.app.core.network.NetworkPolicy.DEFAULT_USER_AGENT

        private val activeCallsByThread = ConcurrentHashMap<Long, Call>()

        fun cancelActiveCallForThread(threadId: Long) {
            activeCallsByThread[threadId]?.cancel()
        }

        fun defaultClient(): OkHttpClient {
            return com.hpre.app.core.network.NetworkPolicy(
                connectTimeoutSeconds = 15,
                readTimeoutSeconds = 20,
                callTimeoutSeconds = 30,
                maxIdleConnections = 8,
                keepAliveDurationMinutes = 5
            ).createOkHttpClient()
        }
    }

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder().url(url)

        var hasUserAgent = false
        var contentTypeHeaderValue: String? = null

        if (headers != null) {
            for ((name, values) in headers) {
                if (values != null) {
                    if (name.equals("User-Agent", ignoreCase = true)) {
                        hasUserAgent = true
                    }
                    if (name.equals("Content-Type", ignoreCase = true)) {
                        contentTypeHeaderValue = values.firstOrNull()
                    }
                    for (value in values) {
                        requestBuilder.addHeader(name, value)
                    }
                }
            }
        }

        if (!hasUserAgent) {
            requestBuilder.header("User-Agent", USER_AGENT)
        }

        val mediaType = contentTypeHeaderValue?.toMediaTypeOrNull()
        val requestBody = when {
            dataToSend != null -> dataToSend.toRequestBody(mediaType)
            httpMethod.equals("POST", ignoreCase = true) || httpMethod.equals("PUT", ignoreCase = true) -> ByteArray(0).toRequestBody(mediaType)
            else -> null
        }

        requestBuilder.method(httpMethod, requestBody)

        val currentThreadId = Thread.currentThread().id
        val call = callFactory.newCall(requestBuilder.build())
        activeCallsByThread[currentThreadId] = call

        try {
            val okHttpResponse = call.execute()
            return okHttpResponse.use { response ->
                val responseCode = response.code
                val responseMessage = response.message
                val responseHeaders = response.headers.toMultimap()
                val responseBody = response.body?.string().orEmpty()
                val latestUrl = response.request.url.toString()

                if (responseCode == 429) {
                    diagnosticLogger.logDiagnostic(
                        component = com.hpre.app.core.network.DiagnosticComponent.OKHTTP_DOWNLOADER,
                        category = LogCategory.NETWORK,
                        operation = DiagnosticOperation.EXTRACTION_METADATA,
                        status = DiagnosticStatus.Http4xx
                    )
                    throw ExtractorHttpException(
                        statusCode = 429,
                        operationContext = ExtractorOperationContext.EXTRACTION_METADATA
                    )
                }

                if (responseCode !in 200..299) {
                    val status = when (responseCode) {
                        403 -> DiagnosticStatus.Http403
                        in 400..499 -> DiagnosticStatus.Http4xx
                        in 500..599 -> DiagnosticStatus.Http5xx
                        else -> DiagnosticStatus.Unknown
                    }
                    diagnosticLogger.logDiagnostic(
                        component = com.hpre.app.core.network.DiagnosticComponent.OKHTTP_DOWNLOADER,
                        category = LogCategory.NETWORK,
                        operation = DiagnosticOperation.EXTRACTION_METADATA,
                        status = status
                    )
                    throw ExtractorHttpException(
                        statusCode = responseCode,
                        operationContext = ExtractorOperationContext.EXTRACTION_METADATA
                    )
                }

                Response(
                    responseCode,
                    responseMessage,
                    responseHeaders,
                    responseBody,
                    latestUrl
                )
            }
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Call interrupted").apply { initCause(e) }
        } finally {
            activeCallsByThread.remove(currentThreadId, call)
        }
    }
}



