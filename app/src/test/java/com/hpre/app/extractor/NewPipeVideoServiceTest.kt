package com.hpre.app.extractor

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.core.performance.VideoOpenEvent
import com.hpre.app.core.performance.VideoOpenMetrics
import com.hpre.app.core.performance.VideoOpenRecord
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class NewPipeVideoServiceTest {

    @Test
    fun prefetch_and_stream_info_share_one_bundle_extraction() = runTest {
        ExtractorBootstrap.init(OkHttpDownloader())
        val key = ContentKey(0, "prefetch_shared")
        val expected = ExtractedVideoBundle(
            VideoDetails(key, "Title", "https://example.test/shared", null, null, null, null, null, null, null, null, null, null),
            StreamInfo(key, "Title", hlsManifestUrl = "https://example.test/master.m3u8?expire=4102444800"),
            emptyList()
        )
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val operations = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
                calls++
                runBlocking { gate.await() }
                return expected
            }
        }
        val service = NewPipeVideoService(
            ioDispatcher = Dispatchers.IO,
            operations = operations,
            serviceScope = backgroundScope
        )

        val prefetch = async { service.prefetch(listOf(key)) }
        val stream = async { service.streamInfo(key) }
        runCurrent()
        gate.complete(Unit)

        prefetch.await()
        assertEquals(AppResult.Success(expected.streamInfo), stream.await())
        assertEquals(1, calls)
    }

    @Test
    fun video_stream_and_related_share_one_bundle_extraction() = runTest {
        ExtractorBootstrap.init(OkHttpDownloader())
        val key = ContentKey(0, "shared_bundle")
        val details = VideoDetails(
            key, "Details", "https://example.test/shared_bundle", null, null, null,
            null, null, null, null, null, null, null
        )
        val streams = StreamInfo(key, "Streams", hlsManifestUrl = "https://example.test/master.m3u8?expire=4102444800")
        val related = listOf(
            VideoSummary(
                ContentKey(0, "related"), "Related", "https://example.test/related",
                null, null, null, null, null, null, null
            )
        )
        val expected = ExtractedVideoBundle(details, streams, related)
        val records = CopyOnWriteArrayList<VideoOpenRecord>()
        val metrics = VideoOpenMetrics(enabled = true, nowMs = { 0L }, sink = { records.add(it) })
        val metricsSession = metrics.start(key)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        var extractions = 0
        val operations = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
                extractions++
                started.countDown()
                assertTrue(gate.await(5, TimeUnit.SECONDS))
                return expected
            }
        }
        val service = NewPipeVideoService(
            ioDispatcher = Dispatchers.IO,
            operations = operations,
            serviceScope = backgroundScope,
            videoOpenMetrics = metrics
        )

        val video = async { service.video(key) }
        val stream = async { service.streamInfo(key) }
        val relatedResult = async { service.related(key) }
        runCurrent()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(1, extractions)
        assertEquals(listOf(VideoOpenEvent.VIDEO_OPEN_START, VideoOpenEvent.EXTRACTOR_START), records.map { it.event })

        gate.countDown()
        assertEquals(AppResult.Success(details), video.await())
        assertEquals(AppResult.Success(streams), stream.await())
        assertEquals(AppResult.Success(related), relatedResult.await())
        assertEquals(
            listOf(VideoOpenEvent.VIDEO_OPEN_START, VideoOpenEvent.EXTRACTOR_START, VideoOpenEvent.EXTRACTOR_FINISH),
            records.map { it.event }
        )
        assertTrue(records.all { it.generation == metricsSession.generation })

        // Cached subscribers must neither extract again nor fabricate new extractor timings.
        assertEquals(AppResult.Success(streams), service.streamInfo(key))
        assertEquals(1, extractions)
        assertEquals(3, records.size)
    }

    @Test
    fun expired_stream_refresh_reuses_cached_metadata_and_only_resolves_playback_urls() = runTest {
        ExtractorBootstrap.init(OkHttpDownloader())
        val key = ContentKey(0, "expired_url")
        val details = VideoDetails(
            key, "Cached metadata", "https://example.test/expired_url", null, null, null,
            null, null, null, null, null, null, null
        )
        val oldStream = StreamInfo(
            key,
            "Old stream",
            hlsManifestUrl = "https://example.test/old.m3u8?expire=1"
        )
        val freshStream = StreamInfo(
            key,
            "Fresh stream",
            hlsManifestUrl = "https://example.test/new.m3u8?expire=4102444800"
        )
        var bundleCalls = 0
        var refreshCalls = 0
        val service = NewPipeVideoService(
            operations = object : ExtractorOperations by DefaultExtractorOperations() {
                override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
                    bundleCalls++
                    return ExtractedVideoBundle(details, oldStream, emptyList())
                }

                override fun refreshStreamInfo(key: ContentKey): StreamInfo {
                    refreshCalls++
                    return freshStream
                }
            },
            serviceScope = backgroundScope
        )

        assertEquals(AppResult.Success(details), service.video(key))
        assertEquals(AppResult.Success(freshStream), service.streamInfo(key))
        assertEquals(AppResult.Success(details), service.video(key))
        assertEquals(1, bundleCalls)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun service_initializes_with_correct_service_id_and_capabilities() {
        val fakeOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override val serviceId: Int = 0
            override val serviceName: String = "YouTube"
            override val supportsShorts: Boolean = false
            override val supportsComments: Boolean = true
            override val supportsSearchSuggestions: Boolean = true
        }
        val service = NewPipeVideoService(operations = fakeOps)
        assertEquals(0, service.serviceId)
        assertEquals("YouTube", service.serviceName)
        assertFalse("V1 semantic Shorts must be false", service.supportsShorts)
        assertTrue(service.supportsComments)
        assertTrue(service.supportsSearchSuggestions)
    }

    @Test
    fun real_downloader_cancellation_aborts_call_and_propagates_cancellation_exception() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        val requestStartedLatch = CountDownLatch(1)
        val callCancelledObserved = AtomicBoolean(false)

        val serverSocket = java.net.ServerSocket(0)
        val serverPort = serverSocket.localPort

        val serverThread = Thread {
            try {
                val socket = serverSocket.accept()
                // Do not respond immediately; wait until closed / cancelled
                val input = socket.getInputStream()
                val buffer = ByteArray(1024)
                input.read(buffer) // Read HTTP request headers
                requestStartedLatch.countDown()
                // Block until client disconnects or thread is interrupted
                try {
                    while (input.read(buffer) != -1) {
                        Thread.sleep(100)
                    }
                } catch (_: Throwable) {}
                socket.close()
            } catch (_: Throwable) {}
        }
        serverThread.isDaemon = true
        serverThread.start()

        val realClient = OkHttpDownloader.defaultClient()
        val realDownloader = OkHttpDownloader(realClient)

        val realCallingOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
                val req = Request.newBuilder().url("http://127.0.0.1:$serverPort/delayed").httpMethod("GET").build()
                try {
                    realDownloader.execute(req)
                } catch (e: Exception) {
                    if (e is InterruptedIOException || e is java.net.SocketException || (e is IOException && e.message?.contains("Canceled") == true)) {
                        callCancelledObserved.set(true)
                    }
                    throw e
                }
                val details = VideoDetails(
                    key = key,
                    title = "Title",
                    canonicalUrl = "https://example.com",
                    description = null,
                    channelKey = null,
                    channelName = null,
                    channelAvatarUrl = null,
                    subscriberCountText = null,
                    thumbnailUrl = null,
                    durationSeconds = null,
                    viewCount = null,
                    likeCount = null,
                    publishedTimestamp = null,
                    isLive = false,
                    isShort = false
                )
                return ExtractedVideoBundle(details, StreamInfo(key, details.title), emptyList())
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = realCallingOps
        )

        try {
            val deferred = async(Dispatchers.Default) {
                service.video(ContentKey(0, "dQw4w9WgXcQ"))
            }

            assertTrue("Request must reach server socket within 5s", requestStartedLatch.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)

            deferred.cancel()

            try {
                deferred.await()
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                // Expected
            }

            assertTrue("Real OkHttp call cancellation must be observed", callCancelledObserved.get())
        } finally {
            try { serverSocket.close() } catch (_: Throwable) {}
            serverThread.interrupt()
        }
    }

    @Test
    fun service_methods_map_http_failures_truthfully_to_domain_app_errors() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        val errorOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun search(query: String, filter: SearchFilter, pageToken: PageToken?) =
                throw ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)

            override fun videoBundle(key: ContentKey): ExtractedVideoBundle =
                throw ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)

            override fun channel(key: ContentKey) =
                throw ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)

            override fun playlist(key: ContentKey) =
                throw ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)

            override fun comments(key: ContentKey, pageToken: PageToken?) =
                throw ExtractorHttpException(404, ExtractorOperationContext.EXTRACTION_METADATA)
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = errorOps
        )

        val searchResult = service.search("query", SearchFilter.ALL, null)
        assertTrue("searchResult was $searchResult", searchResult is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (searchResult as AppResult.Failure).error)

        val videoResult = service.video(ContentKey(0, "dQw4w9WgXcQ"))
        assertTrue("videoResult was $videoResult", videoResult is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (videoResult as AppResult.Failure).error)

        val streamResult = service.streamInfo(ContentKey(0, "dQw4w9WgXcQ"))
        assertTrue("streamResult was $streamResult", streamResult is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (streamResult as AppResult.Failure).error)

        val channelResult = service.channel(ContentKey(0, "UCuCKox3vgM_q8p1Ufx9kGqg"))
        assertTrue("channelResult was $channelResult", channelResult is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (channelResult as AppResult.Failure).error)

        val playlistResult = service.playlist(ContentKey(0, "PL_TEST"))
        assertTrue("playlistResult was $playlistResult", playlistResult is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (playlistResult as AppResult.Failure).error)

        val commentsResult = service.comments(ContentKey(0, "dQw4w9WgXcQ"), null)
        assertTrue("commentsResult was $commentsResult", commentsResult is AppResult.Failure)
        assertEquals(AppError.ContentUnavailable, (commentsResult as AppResult.Failure).error)
    }

    @Test
    fun pagination_maintains_semantics_and_calls_continuation_without_first_page_fetch(): Unit = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())

        var firstPageFetchCount = 0
        var continuationFetchCount = 0
        var recordedReconstitutedPage: org.schabi.newpipe.extractor.Page? = null

        val recordingOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun search(query: String, filter: SearchFilter, pageToken: PageToken?): com.hpre.app.model.SearchPage {
                if (pageToken == null) {
                    firstPageFetchCount++
                    return com.hpre.app.model.SearchPage(
                        items = listOf(
                            com.hpre.app.model.SearchResultItem.VideoItem(
                                com.hpre.app.model.VideoSummary(
                                    key = ContentKey(0, "dQw4w9WgXcQ"),
                                    title = "Page 1 Item",
                                    canonicalUrl = "https://youtube.com/watch?v=dQw4w9WgXcQ",
                                    channelKey = null,
                                    channelName = null,
                                    channelAvatarUrl = null,
                                    thumbnailUrl = null,
                                    durationSeconds = null,
                                    viewCount = null,
                                    publishedTimestamp = null,
                                    isLive = false,
                                    isShort = false
                                )
                            )
                        ),
                        nextPageToken = PageToken.Id("continuation_token_123")
                    )
                } else {
                    continuationFetchCount++
                    // Reconstitute the Page model as DefaultExtractorOperations would
                    val queryHandler = org.schabi.newpipe.extractor.ServiceList.YouTube.searchQHFactory.fromQuery(query, emptyList(), "")
                    recordedReconstitutedPage = NewPipeMappers.reconstituteNewPipePage(pageToken, queryHandler.url)
                    return com.hpre.app.model.SearchPage(
                        items = listOf(
                            com.hpre.app.model.SearchResultItem.VideoItem(
                                com.hpre.app.model.VideoSummary(
                                    key = ContentKey(0, "eQw4w9WgXcQ"),
                                    title = "Page 2 Item",
                                    canonicalUrl = "https://youtube.com/watch?v=eQw4w9WgXcQ",
                                    channelKey = null,
                                    channelName = null,
                                    channelAvatarUrl = null,
                                    thumbnailUrl = null,
                                    durationSeconds = null,
                                    viewCount = null,
                                    publishedTimestamp = null,
                                    isLive = false,
                                    isShort = false
                                )
                            )
                        ),
                        nextPageToken = PageToken.Url("https://youtube.com/continuation?token=distinct_page3")
                    )
                }
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = recordingOps
        )

        // 1. Continuation call with PageToken.Id
        val continuationId = PageToken.Id("continuation_token_123")
        val page2Result = service.search("kotlin", SearchFilter.ALL, continuationId)

        assertTrue(page2Result is AppResult.Success)
        val page2 = (page2Result as AppResult.Success).value

        // Assert only continuation method called exactly once, first-page count zero
        assertEquals(0, firstPageFetchCount)
        assertEquals(1, continuationFetchCount)

        // Assert reconstitution semantics maintained
        assertNotNull(recordedReconstitutedPage)
        assertEquals("continuation_token_123", recordedReconstitutedPage?.id)
        assertNotNull(recordedReconstitutedPage?.url)

        // Assert distinct next token exposed directly without mutation
        assertEquals(PageToken.Url("https://youtube.com/continuation?token=distinct_page3"), page2.nextPageToken)
        assertEquals("Page 2 Item", (page2.items[0] as com.hpre.app.model.SearchResultItem.VideoItem).summary.title)

        // 2. Continuation call with PageToken.Url
        continuationFetchCount = 0
        firstPageFetchCount = 0
        val continuationUrl = PageToken.Url("https://youtube.com/continuation?token=distinct_page3")
        val page3Result = service.search("kotlin", SearchFilter.ALL, continuationUrl)

        assertTrue(page3Result is AppResult.Success)
        assertEquals(0, firstPageFetchCount)
        assertEquals(1, continuationFetchCount)
        assertEquals("https://youtube.com/continuation?token=distinct_page3", recordedReconstitutedPage?.url)
        assertNull(recordedReconstitutedPage?.id)
    }

    @Test
    fun service_method_403_metadata_returns_login_required_and_never_stream_expired() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())

        val forbiddenOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
                throw ExtractorHttpException(403, ExtractorOperationContext.EXTRACTION_METADATA)
            }
        }

        val service = NewPipeVideoService(
            ioDispatcher = ExtractorDispatcher.IO,
            operations = forbiddenOps
        )

        val result = service.video(ContentKey(0, "dQw4w9WgXcQ"))
        assertTrue("result must be Failure but was $result", result is AppResult.Failure)
        val failure = result as AppResult.Failure
        assertEquals(AppError.LoginRequired, failure.error)
        assertFalse("Failure error must not be StreamExpired", failure.error == AppError.StreamExpired)
    }

    @Test
    fun rejects_mismatched_service_id_with_extraction_failed_without_calling_operations() = runBlocking {
        ExtractorBootstrap.init(OkHttpDownloader())
        var operationsCalled = false
        val zeroCallingOps = object : ExtractorOperations by DefaultExtractorOperations() {
            override val serviceId: Int = 0
            override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
                operationsCalled = true
                throw AssertionError("Operations must not be called on serviceId mismatch")
            }
            override fun channel(key: ContentKey): com.hpre.app.model.ChannelDetails {
                operationsCalled = true
                throw AssertionError("Operations must not be called on serviceId mismatch")
            }
            override fun playlist(key: ContentKey): com.hpre.app.model.PlaylistDetails {
                operationsCalled = true
                throw AssertionError("Operations must not be called on serviceId mismatch")
            }
            override fun comments(key: ContentKey, pageToken: PageToken?): com.hpre.app.model.CommentPage {
                operationsCalled = true
                throw AssertionError("Operations must not be called on serviceId mismatch")
            }
        }

        val service = NewPipeVideoService(operations = zeroCallingOps)
        val foreignKey = ContentKey(serviceId = 999, nativeId = "test1234567")

        val vRes = service.video(foreignKey)
        assertTrue(vRes is AppResult.Failure)
        assertEquals(AppError.ExtractionFailed, (vRes as AppResult.Failure).error)

        val sRes = service.streamInfo(foreignKey)
        assertTrue(sRes is AppResult.Failure)
        assertEquals(AppError.ExtractionFailed, (sRes as AppResult.Failure).error)

        val cRes = service.channel(foreignKey)
        assertTrue(cRes is AppResult.Failure)
        assertEquals(AppError.ExtractionFailed, (cRes as AppResult.Failure).error)

        val rRes = service.related(foreignKey)
        assertTrue(rRes is AppResult.Failure)
        assertEquals(AppError.ExtractionFailed, (rRes as AppResult.Failure).error)

        val pRes = service.playlist(foreignKey)
        assertTrue(pRes is AppResult.Failure)
        assertEquals(AppError.ExtractionFailed, (pRes as AppResult.Failure).error)

        val cmRes = service.comments(foreignKey, null)
        assertTrue(cmRes is AppResult.Failure)
        assertEquals(AppError.ExtractionFailed, (cmRes as AppResult.Failure).error)

        assertFalse("Operations should never have been invoked for mismatched serviceId", operationsCalled)
    }
}

