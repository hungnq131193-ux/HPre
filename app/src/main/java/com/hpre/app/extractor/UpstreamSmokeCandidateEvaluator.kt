package com.hpre.app.extractor

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.repository.VideoService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

object UpstreamSmokeCandidateEvaluator {

    const val MAX_CANDIDATES = 5
    const val MAX_STREAM_PROBES_PER_CANDIDATE = 3

    private const val MAX_DIRECT_PROBE_BYTES = 1024
    private const val MAX_MANIFEST_PROBE_BYTES = 16 * 1024 // 16 KiB

    data class EvaluationFailure(
        val itemIndex: Int,
        val nativeId: String,
        val stage: String,
        val errorCategory: String
    )

    data class CandidateSuccess(
        val itemIndex: Int,
        val nativeId: String,
        val details: VideoDetails,
        val streamInfo: StreamInfo
    )

    data class ProbeResult(
        val isSuccess: Boolean,
        val errorCategory: String? = null
    )

    data class StreamProbeTarget(
        val url: String,
        val isManifest: Boolean
    )

    sealed interface EvaluationResult {
        data class Success(val candidate: CandidateSuccess) : EvaluationResult
        data class AllCandidatesFailed(val failures: List<EvaluationFailure>) : EvaluationResult
        data object EmptyVideoResults : EvaluationResult
        data class SearchFailed(val errorCategory: String) : EvaluationResult
    }

    fun createProbeClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+)/((\d+)|\*)$""", RegexOption.IGNORE_CASE)

    fun isValidDirectContentRange(headerValue: String?): Boolean {
        if (headerValue == null) return false
        val trimmed = headerValue.trim()
        val match = CONTENT_RANGE_REGEX.matchEntire(trimmed) ?: return false

        val startStr = match.groupValues[1]
        val endStr = match.groupValues[2]
        val totalStr = match.groupValues[3]

        val start = startStr.toLongOrNull() ?: return false
        val end = endStr.toLongOrNull() ?: return false

        if (start != 0L) return false
        if (end < 0L || end > 1023L) return false
        if (end < start) return false

        val inclusiveLength = end - start + 1
        if (inclusiveLength > MAX_DIRECT_PROBE_BYTES) return false

        if (totalStr != "*") {
            val total = totalStr.toLongOrNull() ?: return false
            if (total <= 0L) return false
            if (total <= end) return false
        }

        return true
    }

    fun probeStreamCandidate(
        url: String,
        isManifest: Boolean,
        client: OkHttpClient = createProbeClient()
    ): ProbeResult {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", OkHttpDownloader.USER_AGENT)

        if (!isManifest) {
            requestBuilder.header("Range", "bytes=0-1023")
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type").orEmpty().trim().lowercase()

                if (!isManifest) {
                    if (code !in listOf(200, 206)) {
                        return ProbeResult(isSuccess = false, errorCategory = "HttpStatus_$code")
                    }
                    if (contentType.isBlank()) {
                        return ProbeResult(isSuccess = false, errorCategory = "BlankContentType")
                    }
                    if (contentType.contains("text/html") || contentType.contains("application/xhtml+xml")) {
                        return ProbeResult(isSuccess = false, errorCategory = "HtmlResponseRejected")
                    }

                    if (code == 206) {
                        val contentRange = response.header("Content-Range")
                        if (!isValidDirectContentRange(contentRange)) {
                            return ProbeResult(isSuccess = false, errorCategory = "InvalidContentRange")
                        }
                    } else if (code == 200) {
                        val headerLength = response.header("Content-Length")?.toLongOrNull()
                        val bodyLength = response.body?.contentLength() ?: -1L
                        val contentLength = if (headerLength != null && headerLength > 0L) headerLength else bodyLength
                        val isLikelyMedia = contentType.startsWith("video/") ||
                                contentType.startsWith("audio/") ||
                                contentType.contains("application/octet-stream")
                        if (!isLikelyMedia || contentLength <= 0L || contentLength > MAX_DIRECT_PROBE_BYTES) {
                            return ProbeResult(isSuccess = false, errorCategory = "UnboundedResponseRejected")
                        }
                    } else {
                        return ProbeResult(isSuccess = false, errorCategory = "HttpStatus_$code")
                    }

                    val bodyStream = response.body?.byteStream()
                        ?: return ProbeResult(isSuccess = false, errorCategory = "EmptyResponseBody")

                    val readBytes = bodyStream.use { stream ->
                        readBoundedBytes(stream, MAX_DIRECT_PROBE_BYTES)
                    }

                    if (readBytes.isEmpty()) {
                        return ProbeResult(isSuccess = false, errorCategory = "EmptyResponseBody")
                    }
                    ProbeResult(isSuccess = true)
                } else {
                    if (code !in 200..299) {
                        return ProbeResult(isSuccess = false, errorCategory = "HttpStatus_$code")
                    }
                    if (contentType.isBlank()) {
                        return ProbeResult(isSuccess = false, errorCategory = "BlankContentType")
                    }
                    if (contentType.contains("text/html") || contentType.contains("application/xhtml+xml")) {
                        return ProbeResult(isSuccess = false, errorCategory = "HtmlResponseRejected")
                    }

                    val isAcceptedManifestType = contentType.contains("mpegurl") ||
                            contentType.contains("dash+xml") ||
                            contentType.contains("application/vnd.apple.mpegurl") ||
                            contentType.contains("application/x-mpegurl") ||
                            contentType.contains("text/plain") ||
                            contentType.contains("application/xml") ||
                            contentType.contains("text/xml")

                    if (!isAcceptedManifestType) {
                        return ProbeResult(isSuccess = false, errorCategory = "InvalidManifestContentType")
                    }

                    val bodyStream = response.body?.byteStream()
                        ?: return ProbeResult(isSuccess = false, errorCategory = "EmptyResponseBody")

                    val readBytes = bodyStream.use { stream ->
                        readBoundedBytes(stream, MAX_MANIFEST_PROBE_BYTES)
                    }

                    if (readBytes.isEmpty()) {
                        return ProbeResult(isSuccess = false, errorCategory = "EmptyResponseBody")
                    }

                    val bodyPrefix = String(readBytes, Charsets.UTF_8).trim()
                    val hasRecognizedMarker = bodyPrefix.startsWith("#EXTM3U") || bodyPrefix.contains("<MPD")

                    if (!hasRecognizedMarker) {
                        return ProbeResult(isSuccess = false, errorCategory = "InvalidManifestContent")
                    }

                    ProbeResult(isSuccess = true)
                }
            }
        } catch (e: Exception) {
            ProbeResult(isSuccess = false, errorCategory = "ProbeTimeoutOrNetworkError")
        }
    }

    private fun readBoundedBytes(inputStream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        while (totalRead < maxBytes) {
            val read = inputStream.read(buffer, totalRead, maxBytes - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == maxBytes) {
            buffer
        } else {
            buffer.copyOf(totalRead)
        }
    }

    fun formatFailureSummary(failures: List<EvaluationFailure>): String {
        return failures.joinToString("; ") {
            "Candidate ${it.itemIndex} (${it.nativeId}): stage=${it.stage}, error=${it.errorCategory}"
        }
    }

    suspend fun evaluate(
        service: VideoService,
        query: String,
        maxCandidates: Int = MAX_CANDIDATES,
        maxStreamProbesPerCandidate: Int = MAX_STREAM_PROBES_PER_CANDIDATE,
        probeClient: OkHttpClient = createProbeClient()
    ): EvaluationResult {
        val boundedCandidates = maxCandidates.coerceIn(0, MAX_CANDIDATES)
        val boundedProbes = maxStreamProbesPerCandidate.coerceIn(0, MAX_STREAM_PROBES_PER_CANDIDATE)

        if (boundedCandidates == 0) {
            return EvaluationResult.AllCandidatesFailed(emptyList())
        }

        val searchResult = service.search(query.trim(), SearchFilter.ALL, pageToken = null)
        if (searchResult is AppResult.Failure) {
            return EvaluationResult.SearchFailed(searchResult.error.javaClass.simpleName)
        }

        val page = (searchResult as AppResult.Success).value
        val videoItems = page.items.filterIsInstance<SearchResultItem.VideoItem>()
        if (videoItems.isEmpty()) {
            return EvaluationResult.EmptyVideoResults
        }

        val candidates = videoItems.take(boundedCandidates)
        val failures = mutableListOf<EvaluationFailure>()

        for ((index, item) in candidates.withIndex()) {
            val key = item.summary.key
            if (key.nativeId.isBlank() || item.summary.title.isBlank()) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "summary_validation",
                        errorCategory = "InvalidSummary"
                    )
                )
                continue
            }

            val videoRes = service.video(key)
            if (videoRes is AppResult.Failure) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "video_details",
                        errorCategory = videoRes.error.javaClass.simpleName
                    )
                )
                continue
            }

            val details = (videoRes as AppResult.Success).value
            if (details.key != key || details.title.isBlank()) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "video_details_validation",
                        errorCategory = "InvalidVideoDetails"
                    )
                )
                continue
            }

            val streamRes = service.streamInfo(key)
            if (streamRes is AppResult.Failure) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "stream_info",
                        errorCategory = streamRes.error.javaClass.simpleName
                    )
                )
                continue
            }

            val streams = (streamRes as AppResult.Success).value
            if (streams.key != key) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "stream_info_validation",
                        errorCategory = "KeyMismatch"
                    )
                )
                continue
            }

            val validVideoCandidates = streams.videoStreams.filter { NewPipeMappers.isValidHttpUrl(it.url, allowLocalhost = true) }
            val validAudioCandidates = streams.audioStreams.filter { NewPipeMappers.isValidHttpUrl(it.url, allowLocalhost = true) }
            val validHls = streams.hlsManifestUrl?.takeIf { NewPipeMappers.isValidHttpUrl(it, allowLocalhost = true) }
            val validDash = streams.dashManifestUrl?.takeIf { NewPipeMappers.isValidHttpUrl(it, allowLocalhost = true) }

            val hasProductionValidCandidate = validVideoCandidates.isNotEmpty() ||
                    validAudioCandidates.isNotEmpty() ||
                    validHls != null ||
                    validDash != null

            if (!hasProductionValidCandidate) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "stream_info_validation",
                        errorCategory = "NoValidStreams"
                    )
                )
                continue
            }

            if (boundedProbes == 0) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "stream_probe",
                        errorCategory = "ProbeLimitZero"
                    )
                )
                continue
            }

            val probeTargets = mutableListOf<StreamProbeTarget>().apply {
                validVideoCandidates.forEach { add(StreamProbeTarget(it.url, isManifest = false)) }
                validAudioCandidates.forEach { add(StreamProbeTarget(it.url, isManifest = false)) }
                validHls?.let { add(StreamProbeTarget(it, isManifest = true)) }
                validDash?.let { add(StreamProbeTarget(it, isManifest = true)) }
            }.take(boundedProbes)

            var candidateProbeSuccess = false
            var lastProbeError = "ProbeFailed"

            for (target in probeTargets) {
                val probeResult = probeStreamCandidate(target.url, target.isManifest, probeClient)
                if (probeResult.isSuccess) {
                    candidateProbeSuccess = true
                    break
                } else {
                    lastProbeError = probeResult.errorCategory ?: "ProbeFailed"
                }
            }

            if (!candidateProbeSuccess) {
                failures.add(
                    EvaluationFailure(
                        itemIndex = index,
                        nativeId = key.nativeId,
                        stage = "stream_probe",
                        errorCategory = lastProbeError
                    )
                )
                continue
            }

            return EvaluationResult.Success(
                CandidateSuccess(
                    itemIndex = index,
                    nativeId = key.nativeId,
                    details = details,
                    streamInfo = streams
                )
            )
        }

        return EvaluationResult.AllCandidatesFailed(failures)
    }
}

