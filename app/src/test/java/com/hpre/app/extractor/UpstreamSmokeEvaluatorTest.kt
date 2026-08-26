package com.hpre.app.extractor

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.AudioStream
import com.hpre.app.model.Channel
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.CommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.PlaylistDetails
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoStream
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpstreamSmokeEvaluatorTest {

    companion object {
        fun createFakeSummary(nativeId: String, title: String = "Title $nativeId"): VideoSummary {
            return VideoSummary(
                key = ContentKey(0, nativeId),
                title = title,
                canonicalUrl = "https://example.com/watch?v=$nativeId",
                channelKey = ContentKey(0, "c1"),
                channelName = "Channel 1",
                channelAvatarUrl = null,
                thumbnailUrl = null,
                durationSeconds = 100,
                viewCount = 1000,
                publishedTimestamp = 1600000000L
            )
        }

        fun createFakeDetails(nativeId: String, title: String = "Title $nativeId"): VideoDetails {
            return VideoDetails(
                key = ContentKey(0, nativeId),
                title = title,
                canonicalUrl = "https://example.com/watch?v=$nativeId",
                description = "Desc",
                channelKey = ContentKey(0, "c1"),
                channelName = "Channel 1",
                channelAvatarUrl = null,
                subscriberCountText = "1K",
                thumbnailUrl = null,
                durationSeconds = 100,
                viewCount = 1000,
                likeCount = 10,
                publishedTimestamp = 1600000000L
            )
        }

        fun createFakeStreamInfo(nativeId: String, streamUrl: String = "https://stream.example.com/$nativeId.mp4", hasUsableStream: Boolean = true): StreamInfo {
            return StreamInfo(
                key = ContentKey(0, nativeId),
                title = "Title $nativeId",
                videoStreams = if (hasUsableStream) listOf(
                    VideoStream(
                        url = streamUrl,
                        format = "mp4",
                        resolution = "1080p",
                        width = 1920,
                        height = 1080,
                        bitrate = 5000000L,
                        isVideoOnly = false
                    )
                ) else emptyList()
            )
        }
    }

    private class TestVideoService(
        val searchItems: List<SearchResultItem> = emptyList(),
        val searchResult: AppResult<SearchPage>? = null,
        val videoResponses: Map<String, AppResult<VideoDetails>> = emptyMap(),
        val streamResponses: Map<String, AppResult<StreamInfo>> = emptyMap(),
        val defaultStreamUrl: String? = null
    ) : VideoService {
        override val serviceId: Int = 0
        override val serviceName: String = "TestService"
        override val supportsShorts: Boolean = false
        override val supportsComments: Boolean = false
        override val supportsSearchSuggestions: Boolean = false

        override suspend fun search(query: String, filter: SearchFilter, pageToken: PageToken?): AppResult<SearchPage> {
            return searchResult ?: AppResult.Success(SearchPage(items = searchItems, nextPageToken = null))
        }

        override suspend fun video(key: ContentKey): AppResult<VideoDetails> {
            return videoResponses[key.nativeId] ?: AppResult.Success(createFakeDetails(key.nativeId))
        }

        override suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo> {
            return streamResponses[key.nativeId] ?: AppResult.Success(
                createFakeStreamInfo(
                    key.nativeId,
                    streamUrl = defaultStreamUrl ?: "https://stream.example.com/${key.nativeId}.mp4"
                )
            )
        }

        override suspend fun suggestions(query: String): AppResult<List<String>> = AppResult.Success(emptyList())
        override suspend fun channel(key: ContentKey): AppResult<ChannelDetails> = throw UnsupportedOperationException()
        override suspend fun related(key: ContentKey): AppResult<List<VideoSummary>> = AppResult.Success(emptyList())
        override suspend fun playlist(key: ContentKey): AppResult<PlaylistDetails> = throw UnsupportedOperationException()
        override suspend fun comments(key: ContentKey, pageToken: PageToken?): AppResult<CommentPage> = throw UnsupportedOperationException()
        override suspend fun trending(): AppResult<List<VideoSummary>> = AppResult.Success(emptyList())
    }

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun probe_direct_stream_returns_success_on_206_with_media_bytes() = runBlocking {
        val serverUrl = mockServer.url("/stream.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1023/100000")
                .setBody("media-binary-data-chunk")
        )

        val probeResult = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrl,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )

        assertTrue(probeResult.isSuccess)
        val recordedRequest = mockServer.takeRequest()
        assertEquals("bytes=0-1023", recordedRequest.getHeader("Range"))
    }

    @Test
    fun probe_direct_stream_accepts_206_with_wildcard_total_and_bounded_end() = runBlocking {
        val serverUrlWildcard = mockServer.url("/stream-wildcard.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-511/*")
                .setBody("media-chunk-512")
        )

        val probeResult = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlWildcard,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )

        assertTrue(probeResult.isSuccess)
    }

    @Test
    fun probe_direct_stream_rejects_206_with_end_1024_or_greater() = runBlocking {
        val serverUrlExceeds = mockServer.url("/stream-exceeds.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1024/100000")
                .setBody("media-chunk-1025")
        )

        val probeResult = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlExceeds,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )

        assertTrue(!probeResult.isSuccess)
        assertEquals("InvalidContentRange", probeResult.errorCategory)
    }

    @Test
    fun probe_direct_stream_rejects_206_with_total_less_than_or_equal_to_end() = runBlocking {
        // total == end (invalid because end is zero-indexed, length = end+1, total must be > end)
        val serverUrlTotalEqEnd = mockServer.url("/stream-total-eq.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1023/1023")
                .setBody("media-chunk")
        )

        val resultTotalEqEnd = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlTotalEqEnd,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resultTotalEqEnd.isSuccess)
        assertEquals("InvalidContentRange", resultTotalEqEnd.errorCategory)

        // total < end
        val serverUrlTotalLessThanEnd = mockServer.url("/stream-total-lt.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1023/500")
                .setBody("media-chunk")
        )

        val resultTotalLessThanEnd = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlTotalLessThanEnd,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resultTotalLessThanEnd.isSuccess)
        assertEquals("InvalidContentRange", resultTotalLessThanEnd.errorCategory)
    }

    @Test
    fun probe_direct_stream_rejects_206_with_malformed_syntax_and_missing_end() = runBlocking {
        // bytes=... instead of standard bytes <range>
        val urlEqualsPrefix = mockServer.url("/equals-prefix.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes=0-1023/100000")
                .setBody("media-chunk")
        )
        val resEqualsPrefix = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = urlEqualsPrefix,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resEqualsPrefix.isSuccess)
        assertEquals("InvalidContentRange", resEqualsPrefix.errorCategory)

        // missing end (e.g. bytes 0-/100000)
        val urlMissingEnd = mockServer.url("/missing-end.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-/100000")
                .setBody("media-chunk")
        )
        val resMissingEnd = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = urlMissingEnd,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resMissingEnd.isSuccess)
        assertEquals("InvalidContentRange", resMissingEnd.errorCategory)

        // negative start or end
        val urlNegative = mockServer.url("/negative.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes -1-1023/100000")
                .setBody("media-chunk")
        )
        val resNegative = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = urlNegative,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resNegative.isSuccess)
        assertEquals("InvalidContentRange", resNegative.errorCategory)

        // random malformed junk
        val urlMalformed = mockServer.url("/junk.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "invalid-content-range")
                .setBody("media-chunk")
        )
        val resMalformed = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = urlMalformed,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resMalformed.isSuccess)
        assertEquals("InvalidContentRange", resMalformed.errorCategory)
    }

    @Test
    fun probe_direct_stream_rejects_206_with_missing_or_invalid_content_range() = runBlocking {
        val serverUrlMissing = mockServer.url("/missing-range.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setBody("media-chunk")
        )

        val resultMissing = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlMissing,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resultMissing.isSuccess)
        assertEquals("InvalidContentRange", resultMissing.errorCategory)

        val serverUrlInvalid = mockServer.url("/invalid-range.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 500-1023/100000")
                .setBody("media-chunk")
        )

        val resultInvalid = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlInvalid,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resultInvalid.isSuccess)
        assertEquals("InvalidContentRange", resultInvalid.errorCategory)
    }

    @Test
    fun probe_direct_stream_accepts_200_only_when_content_length_bounded() = runBlocking {
        val serverUrlBounded = mockServer.url("/bounded.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Length", "512")
                .setBody("small-media-payload")
        )

        val resultBounded = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlBounded,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        if (!resultBounded.isSuccess) {
            org.junit.Assert.fail("resultBounded failed with category: " + resultBounded.errorCategory)
        }
        assertTrue(resultBounded.isSuccess)

        val serverUrlUnbounded = mockServer.url("/unbounded.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Length", "5000000")
                .setBody("A".repeat(5000))
        )

        val resultUnbounded = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlUnbounded,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        if (resultUnbounded.isSuccess) {
            org.junit.Assert.fail("resultUnbounded should have failed but succeeded")
        }
        assertTrue(!resultUnbounded.isSuccess)
        assertEquals("UnboundedResponseRejected", resultUnbounded.errorCategory)

        val serverUrlMissingLength = mockServer.url("/missing-length.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setChunkedBody("chunked-payload-without-length", 5)
        )

        val resultMissingLength = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlMissingLength,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resultMissingLength.isSuccess)
        assertEquals("UnboundedResponseRejected", resultMissingLength.errorCategory)
    }

    @Test
    fun probe_direct_stream_rejects_200_html_response() = runBlocking {
        val serverUrl = mockServer.url("/stream.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body>Bot Detection / Error Page</body></html>")
        )

        val probeResult = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrl,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )

        assertTrue(!probeResult.isSuccess)
        assertEquals("HtmlResponseRejected", probeResult.errorCategory)
    }

    @Test
    fun probe_direct_stream_fails_on_403_and_404_status() = runBlocking {
        val serverUrl403 = mockServer.url("/stream403.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )

        val probeResult403 = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrl403,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!probeResult403.isSuccess)
        assertEquals("HttpStatus_403", probeResult403.errorCategory)

        val serverUrl404 = mockServer.url("/stream404.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
        )

        val probeResult404 = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrl404,
            isManifest = false,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!probeResult404.isSuccess)
        assertEquals("HttpStatus_404", probeResult404.errorCategory)
    }

    @Test
    fun probe_manifest_returns_success_on_200_with_accepted_manifest_content_and_type() = runBlocking {
        val serverUrl = mockServer.url("/manifest.m3u8").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n")
        )

        val probeResult = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrl,
            isManifest = true,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )

        assertTrue(probeResult.isSuccess)
    }

    @Test
    fun probe_manifest_rejects_unsupported_media_type_or_missing_manifest_marker() = runBlocking {
        val serverUrlWrongType = mockServer.url("/manifest.txt").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("#EXTM3U\n#EXT-X-VERSION:3\n")
        )
        val resultWrongType = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlWrongType,
            isManifest = true,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resultWrongType.isSuccess)
        assertEquals("InvalidManifestContentType", resultWrongType.errorCategory)

        val serverUrlNoMarker = mockServer.url("/manifest.m3u8").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("some random text without marker")
        )
        val resultNoMarker = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlNoMarker,
            isManifest = true,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(!resultNoMarker.isSuccess)
        assertEquals("InvalidManifestContent", resultNoMarker.errorCategory)
    }

    @Test
    fun probe_manifest_handles_dash_mpd_and_caps_body_read_within_16kib() = runBlocking {
        val serverUrlDash = mockServer.url("/manifest.mpd").toString()
        val largeMpdPrefix = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">" + "A".repeat(20000)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/dash+xml")
                .setBody(largeMpdPrefix)
        )

        val probeResult = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrlDash,
            isManifest = true,
            client = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(probeResult.isSuccess)
    }

    @Test
    fun probe_handles_timeout_and_network_error_safely() = runBlocking {
        val serverUrl = mockServer.url("/timeout.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )

        val testClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(200, TimeUnit.MILLISECONDS)
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        val probeResult = UpstreamSmokeCandidateEvaluator.probeStreamCandidate(
            url = serverUrl,
            isManifest = false,
            client = testClient
        )

        assertTrue(!probeResult.isSuccess)
        assertEquals("ProbeTimeoutOrNetworkError", probeResult.errorCategory)
    }

    @Test
    fun evaluates_candidates_sequentially_bounded_by_max_candidate_stream_probes() = runBlocking {
        val stream1 = mockServer.url("/stream1.mp4").toString()
        val stream2 = mockServer.url("/stream2.mp4").toString()
        val stream3 = mockServer.url("/stream3.mp4").toString()
        val stream4 = mockServer.url("/stream4.mp4").toString()

        // vid1 has 3 streams -> all fail probe
        // vid2 has 1 stream -> fails probe
        // vid3 has 1 stream -> succeeds probe
        mockServer.enqueue(MockResponse().setResponseCode(403))
        mockServer.enqueue(MockResponse().setResponseCode(403))
        mockServer.enqueue(MockResponse().setResponseCode(403))
        mockServer.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>error</html>"))
        mockServer.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Content-Range", "bytes 0-1023/100000").setBody("bytes-ok"))

        val items = (1..4).map { SearchResultItem.VideoItem(createFakeSummary("vid$it")) }
        val service = TestVideoService(
            searchItems = items,
            videoResponses = items.associate { it.summary.key.nativeId to AppResult.Success(createFakeDetails(it.summary.key.nativeId)) },
            streamResponses = mapOf(
                "vid1" to AppResult.Success(StreamInfo(key = ContentKey(0, "vid1"), title = "Title vid1", videoStreams = listOf(
                    VideoStream(url = "$stream1?q=1", format = "mp4", resolution = "1080p", width = 1920, height = 1080, bitrate = 2000, isVideoOnly = false),
                    VideoStream(url = "$stream1?q=2", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000, isVideoOnly = false),
                    VideoStream(url = "$stream1?q=3", format = "mp4", resolution = "480p", width = 854, height = 480, bitrate = 500, isVideoOnly = false),
                    VideoStream(url = "$stream1?q=4", format = "mp4", resolution = "360p", width = 640, height = 360, bitrate = 250, isVideoOnly = false) // 4th stream should not be probed because bounded by maxStreamProbesPerCandidate=3
                ))),
                "vid2" to AppResult.Success(StreamInfo(key = ContentKey(0, "vid2"), title = "Title vid2", videoStreams = listOf(VideoStream(url = stream2, format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000L, isVideoOnly = false)))),
                "vid3" to AppResult.Success(StreamInfo(key = ContentKey(0, "vid3"), title = "Title vid3", videoStreams = listOf(VideoStream(url = stream3, format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000L, isVideoOnly = false)))),
                "vid4" to AppResult.Success(StreamInfo(key = ContentKey(0, "vid4"), title = "Title vid4", videoStreams = listOf(VideoStream(url = stream4, format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000L, isVideoOnly = false))))
            )
        )

        val result = UpstreamSmokeCandidateEvaluator.evaluate(
            service = service,
            query = "Kotlin",
            maxCandidates = 5,
            maxStreamProbesPerCandidate = 3,
            probeClient = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )

        if (result is UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed) {
            org.junit.Assert.fail("FAIL: " + UpstreamSmokeCandidateEvaluator.formatFailureSummary(result.failures))
        }

        assertTrue(result is UpstreamSmokeCandidateEvaluator.EvaluationResult.Success)
        val success = result as UpstreamSmokeCandidateEvaluator.EvaluationResult.Success
        assertEquals("vid3", success.candidate.nativeId)
        assertEquals(2, success.candidate.itemIndex)
        assertEquals(5, mockServer.requestCount)
    }

    @Test
    fun passes_if_first_candidate_fails_with_content_unavailable_but_second_candidate_succeeds() = runBlocking {
        val streamUrl = mockServer.url("/stream2.mp4").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1023/100000")
                .setBody("video-data")
        )

        val items = listOf(
            SearchResultItem.VideoItem(createFakeSummary("vid1")),
            SearchResultItem.VideoItem(createFakeSummary("vid2"))
        )
        val service = TestVideoService(
            searchItems = items,
            videoResponses = mapOf(
                "vid1" to AppResult.Failure(AppError.ContentUnavailable),
                "vid2" to AppResult.Success(createFakeDetails("vid2"))
            ),
            streamResponses = mapOf(
                "vid2" to AppResult.Success(createFakeStreamInfo("vid2", streamUrl = streamUrl))
            )
        )

        val result = UpstreamSmokeCandidateEvaluator.evaluate(
            service = service,
            query = "Kotlin",
            maxCandidates = 5,
            probeClient = UpstreamSmokeCandidateEvaluator.createProbeClient()
        )
        assertTrue(result is UpstreamSmokeCandidateEvaluator.EvaluationResult.Success)
        val success = result as UpstreamSmokeCandidateEvaluator.EvaluationResult.Success
        assertEquals(1, success.candidate.itemIndex)
        assertEquals("vid2", success.candidate.nativeId)
        assertEquals("vid2", success.candidate.details.key.nativeId)
    }

    @Test
    fun enforces_hard_bounds_on_max_candidates_and_max_stream_probes_per_candidate() = runBlocking {
        assertEquals(5, UpstreamSmokeCandidateEvaluator.MAX_CANDIDATES)
        assertEquals(3, UpstreamSmokeCandidateEvaluator.MAX_STREAM_PROBES_PER_CANDIDATE)

        val items = (1..10).map { SearchResultItem.VideoItem(createFakeSummary("vid$it")) }
        var videoCallCount = 0
        val service = object : VideoService by TestVideoService(
            searchItems = items,
            streamResponses = items.associate {
                it.summary.key.nativeId to AppResult.Success(
                    createFakeStreamInfo(
                        it.summary.key.nativeId,
                        streamUrl = mockServer.url("/${it.summary.key.nativeId}.mp4").toString()
                    )
                )
            }
        ) {
            override suspend fun video(key: ContentKey): AppResult<VideoDetails> {
                videoCallCount++
                return AppResult.Failure(AppError.ContentUnavailable)
            }
        }

        // Caller attempts to pass maxCandidates = 100 and maxStreamProbesPerCandidate = 50 -> must coerce to 5 and 3
        val result = UpstreamSmokeCandidateEvaluator.evaluate(
            service = service,
            query = "Kotlin",
            maxCandidates = 100,
            maxStreamProbesPerCandidate = 50
        )
        assertTrue(result is UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed)
        assertEquals(5, videoCallCount)
        val failure = result as UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed
        assertEquals(5, failure.failures.size)

        // Negative values should be coerced safely to 0
        videoCallCount = 0
        val resultNegative = UpstreamSmokeCandidateEvaluator.evaluate(
            service = service,
            query = "Kotlin",
            maxCandidates = -5,
            maxStreamProbesPerCandidate = -10
        )
        assertTrue(resultNegative is UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed)
        assertEquals(0, videoCallCount)
        val failureNegative = resultNegative as UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed
        assertEquals(0, failureNegative.failures.size)
    }

    @Test
    fun stops_after_max_5_candidates_and_reports_per_stage_safe_failure_categories() = runBlocking {
        val items = (1..10).map { SearchResultItem.VideoItem(createFakeSummary("vid$it")) }
        var videoCallCount = 0
        val service = object : VideoService by TestVideoService(
            searchItems = items,
            streamResponses = items.associate {
                it.summary.key.nativeId to AppResult.Success(
                    createFakeStreamInfo(
                        it.summary.key.nativeId,
                        streamUrl = mockServer.url("/${it.summary.key.nativeId}.mp4").toString()
                    )
                )
            }
        ) {
            override suspend fun video(key: ContentKey): AppResult<VideoDetails> {
                videoCallCount++
                return when (key.nativeId) {
                    "vid1" -> AppResult.Failure(AppError.ContentUnavailable)
                    "vid2" -> AppResult.Failure(AppError.AgeRestricted)
                    "vid3" -> AppResult.Failure(AppError.GeoRestricted)
                    "vid4" -> AppResult.Failure(AppError.LoginRequired)
                    "vid5" -> AppResult.Failure(AppError.ExtractionFailed)
                    else -> AppResult.Success(createFakeDetails(key.nativeId))
                }
            }
        }

        val result = UpstreamSmokeCandidateEvaluator.evaluate(service, "Kotlin", maxCandidates = 5)
        assertTrue(result is UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed)
        val failure = result as UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed
        assertEquals(5, videoCallCount)
        assertEquals(5, failure.failures.size)

        val summary = UpstreamSmokeCandidateEvaluator.formatFailureSummary(failure.failures)
        assertTrue(summary.contains("Candidate 0 (vid1): stage=video_details, error=ContentUnavailable"))
        assertTrue(summary.contains("Candidate 1 (vid2): stage=video_details, error=AgeRestricted"))
        assertTrue(summary.contains("Candidate 2 (vid3): stage=video_details, error=GeoRestricted"))
        assertTrue(summary.contains("Candidate 3 (vid4): stage=video_details, error=LoginRequired"))
        assertTrue(summary.contains("Candidate 4 (vid5): stage=video_details, error=ExtractionFailed"))
    }

    @Test
    fun returns_search_failed_when_search_fails() = runBlocking {
        val service = TestVideoService(
            searchResult = AppResult.Failure(AppError.NetworkError)
        )
        val result = UpstreamSmokeCandidateEvaluator.evaluate(service, "Kotlin", maxCandidates = 5)
        assertTrue(result is UpstreamSmokeCandidateEvaluator.EvaluationResult.SearchFailed)
        assertEquals("NetworkError", (result as UpstreamSmokeCandidateEvaluator.EvaluationResult.SearchFailed).errorCategory)
    }

    @Test
    fun returns_empty_video_results_when_no_videos_in_search() = runBlocking {
        val service = TestVideoService(
            searchItems = listOf(
                SearchResultItem.ChannelItem(
                    Channel(ContentKey(0, "c1"), "Channel 1", "https://example.com", null, null, null, null)
                )
            )
        )
        val result = UpstreamSmokeCandidateEvaluator.evaluate(service, "Kotlin", maxCandidates = 5)
        assertTrue(result is UpstreamSmokeCandidateEvaluator.EvaluationResult.EmptyVideoResults)
    }

    @Test
    fun evaluates_stream_info_failure_and_invalid_stream_candidates() = runBlocking {
        val items = listOf(
            SearchResultItem.VideoItem(createFakeSummary("vid1")),
            SearchResultItem.VideoItem(createFakeSummary("vid2"))
        )
        val service = TestVideoService(
            searchItems = items,
            streamResponses = mapOf(
                "vid1" to AppResult.Failure(AppError.ExtractionFailed),
                "vid2" to AppResult.Success(createFakeStreamInfo("vid2", hasUsableStream = false))
            )
        )

        val result = UpstreamSmokeCandidateEvaluator.evaluate(service, "Kotlin", maxCandidates = 5)
        assertTrue(result is UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed)
        val failure = result as UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed
        assertEquals(2, failure.failures.size)
        assertEquals("stream_info", failure.failures[0].stage)
        assertEquals("ExtractionFailed", failure.failures[0].errorCategory)
        assertEquals("stream_info_validation", failure.failures[1].stage)
        assertEquals("NoValidStreams", failure.failures[1].errorCategory)
    }
}
