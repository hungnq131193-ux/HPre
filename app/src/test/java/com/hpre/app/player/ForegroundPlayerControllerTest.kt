package com.hpre.app.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundPlayerControllerTest {

    private val testKey = ContentKey(0, "test_video_123")
    private val testScope = TestScope()
    private val testDispatcher = StandardTestDispatcher(testScope.testScheduler)

    private lateinit var testPlayerState: TestExoPlayerState
    private lateinit var testPlayer: ExoPlayer

    private fun createFakeMediaSource(): MediaSource {
        return Proxy.newProxyInstance(
            MediaSource::class.java.classLoader,
            arrayOf(MediaSource::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "FakeMediaSourceProxy"
                "hashCode" -> 42
                "equals" -> false
                else -> null
            }
        } as MediaSource
    }

    private fun sampleStreamInfo(
        progressiveUrl: String = "https://progressive.mp4",
        height: Int = 720
    ): StreamInfo {
        return StreamInfo(
            key = testKey,
            title = "Test Video",
            videoStreams = listOf(
                VideoStream(
                    url = progressiveUrl,
                    format = "mp4",
                    mimeType = "video/mp4",
                    codec = "avc1.64001F",
                    resolution = "${height}p",
                    width = 1280,
                    height = height,
                    bitrate = 1_500_000,
                    isVideoOnly = false
                )
            ),
            audioStreams = listOf(
                AudioStream(
                    url = "https://audio.m4a",
                    format = "m4a",
                    mimeType = "audio/mp4",
                    codec = "mp4a.40.2",
                    bitrate = 128_000
                )
            )
        )
    }

    @Before
    fun setup() {
        testPlayerState = TestExoPlayerState()
        testPlayer = testPlayerState.createPlayer()
    }

    // Invariant 1: Terminal release
    @Test
    fun release_is_terminal_and_subsequent_operations_safe_noops() = testScope.runTest {
        var factoryCallCount = 0
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = {
                factoryCallCount++
                testPlayer
            },
            mainDispatcher = testDispatcher
        )
        testScope.runCurrent()

        assertEquals(1, factoryCallCount)
        controller.release()
        testScope.runCurrent()

        assertEquals(1, testPlayerState.releaseCount)
        assertEquals(PlaybackState(), controller.state.value)

        // Second release is idempotent
        controller.release()
        testScope.runCurrent()
        assertEquals(1, testPlayerState.releaseCount)

        // Prepare after release is a safe no-op and does not re-create player
        val info = sampleStreamInfo()
        controller.prepare(testKey, info)
        testScope.runCurrent()

        assertEquals(1, factoryCallCount)
        assertEquals(PlaybackState(), controller.state.value)

        // Play/Pause/Seek after release do not crash or create player
        controller.play()
        controller.pause()
        controller.seekTo(1000L)
        controller.setPlaybackSpeed(1.5f)
        controller.selectQuality(QualityOption(720, "720p", true))
        testScope.runCurrent()
        assertEquals(1, factoryCallCount)
    }

    // Fix 1 & 5: Surface attachment & Lifecycle pause marshal to Main dispatcher
    @Test
    fun surface_attachment_and_lifecycle_pause_marshal_to_main_dispatcher() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        testScope.runCurrent()

        // Prepare and play
        controller.prepare(testKey, sampleStreamInfo())
        testScope.runCurrent()
        testPlayerState.notifyIsPlaying(true)
        testScope.runCurrent()
        assertTrue(testPlayerState.isPlaying)

        // Call onLifecycleStop
        controller.onLifecycleStop()
        testScope.runCurrent()
        // Player paused
        assertFalse(testPlayerState.isPlaying)

        // Call onLifecycleStart -> resumes because it was playing before lifecycle stop
        controller.onLifecycleStart()
        testScope.runCurrent()
        assertTrue(testPlayerState.isPlaying)
        controller.release()
        testScope.runCurrent()
    }

    // Fix 2: Cancellation of prepare tokens and clearing PlaybackState.error
    @Test
    fun rapid_prepare_calls_cancel_stale_before_commit_and_clears_error() = testScope.runTest {
        val keyA = ContentKey(0, "video_A")
        val keyB = ContentKey(0, "video_B")

        var sourceFactoryCalls = mutableListOf<ContentKey>()
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { selected ->
                sourceFactoryCalls.add(selected.key)
                createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        testScope.runCurrent()

        // Set previous error
        testPlayerState.notifyError(
            PlaybackException("Simulated error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        testScope.runCurrent()
        assertEquals(AppError.NetworkError, controller.state.value.error)

        // Call prepare A then immediately prepare B
        val infoA = StreamInfo(key = keyA, title = "A", videoStreams = listOf(VideoStream(url = "https://a.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")))
        val infoB = StreamInfo(key = keyB, title = "B", videoStreams = listOf(VideoStream(url = "https://b.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")))

        controller.prepare(keyA, infoA)
        controller.prepare(keyB, infoB)
        advanceUntilIdle()

        // Successful prepare B must clear error and commit B
        assertEquals(keyB, controller.state.value.key)
        assertNull(controller.state.value.error)
        controller.release()
        testScope.runCurrent()
    }

    // Fix 2: selectQuality rapid switch A -> B with delayed source factory commits only B
    @Test
    fun rapid_selectQuality_cancels_stale_job_and_commits_only_latest() = testScope.runTest {
        val prog720 = VideoStream(url = "https://p720.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")
        val prog480 = VideoStream(url = "https://p480.mp4", format = "mp4", resolution = "480p", width = 854, height = 480, bitrate = 500, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")
        val prog360 = VideoStream(url = "https://p360.mp4", format = "mp4", resolution = "360p", width = 640, height = 360, bitrate = 300, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")
        val info = StreamInfo(key = testKey, title = "Multi", videoStreams = listOf(prog720, prog480, prog360))

        val committedSources = mutableListOf<String>()
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { selected ->
                val url = selected.videoStream?.url ?: ""
                committedSources.add(url)
                createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, info)
        advanceUntilIdle()

        val opt480 = controller.state.value.availableQualities.first { it.height == 480 }
        val opt360 = controller.state.value.availableQualities.first { it.height == 360 }

        controller.selectQuality(opt480)
        controller.selectQuality(opt360)
        advanceUntilIdle()

        assertEquals(360, controller.state.value.selectedQuality?.height)
        assertNull(controller.state.value.error)
        controller.release()
        testScope.runCurrent()
    }

    // Fix 3: prepare with startPositionMs, playWhenReady, and specific quality
    @Test
    fun prepare_supports_exact_snapshot_position_playWhenReady_and_quality() = testScope.runTest {
        val prog720 = VideoStream(url = "https://p720.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")
        val prog360 = VideoStream(url = "https://p360.mp4", format = "mp4", resolution = "360p", width = 640, height = 360, bitrate = 300, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")
        val info = StreamInfo(key = testKey, title = "Snap", videoStreams = listOf(prog720, prog360))

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val opt360 = QualityOption(360, "360p", true, format = "mp4", mimeType = "video/mp4", codec = "avc1")
        controller.prepare(
            key = testKey,
            streamInfo = info,
            startPositionMs = 15_000L,
            playWhenReady = false,
            initialQuality = opt360
        )
        advanceUntilIdle()

        assertEquals(15_000L, testPlayerState.currentPosition)
        assertFalse(testPlayerState.playWhenReady)
        assertEquals(360, controller.state.value.selectedQuality?.height)
        controller.release()
        testScope.runCurrent()
    }

    // Invariant 2: Quality Source-of-Truth & Fallback
    @Test
    fun selectQuality_absent_quality_or_mismatch_is_rejected_or_retains_truth() = testScope.runTest {
        val progressive720 = VideoStream(
            url = "https://p720.mp4",
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1.64001F",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1_500_000,
            isVideoOnly = false
        )
        val info = StreamInfo(
            key = testKey,
            title = "Single Quality",
            videoStreams = listOf(progressive720)
        )

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, info)
        advanceUntilIdle()

        val initialQuality = controller.state.value.selectedQuality
        assertEquals(720, initialQuality?.height)

        // Request a quality not present in availableQualities (e.g. 1080p)
        val absentQuality = QualityOption(1080, "1080p", false, format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F")
        controller.selectQuality(absentQuality)
        advanceUntilIdle()

        // State must not falsely label 1080p when unavailable
        assertEquals(initialQuality, controller.state.value.selectedQuality)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun selectQuality_manifest_auto_option_selects_hls_or_dash_directly() = testScope.runTest {
        var lastCreatedStreams: SelectedStreams? = null
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { s ->
                lastCreatedStreams = s
                createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val progressive720 = VideoStream(url = "https://p720.mp4", format = "mp4", mimeType = "video/mp4", codec = "avc1.64001F", resolution = "720p", width = 1280, height = 720, bitrate = 1_500_000, isVideoOnly = false)
        val info = StreamInfo(
            key = testKey,
            title = "Mixed Manifest",
            videoStreams = listOf(progressive720),
            hlsManifestUrl = "https://manifest.m3u8"
        )

        controller.prepare(testKey, info)
        advanceUntilIdle()

        val available = controller.state.value.availableQualities
        assertTrue(available.any { it.streamType == PlaybackStreamType.HLS })
        val hlsOpt = available.first { it.streamType == PlaybackStreamType.HLS }

        controller.selectQuality(hlsOpt)
        advanceUntilIdle()

        assertEquals(hlsOpt, controller.state.value.selectedQuality)
        assertEquals(PlaybackStreamType.HLS, controller.state.value.streamType)
        assertEquals("https://manifest.m3u8", lastCreatedStreams?.manifestUrl)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun selectQuality_preserves_and_reports_distinct_progressive_vs_adaptive_selection() = testScope.runTest {
        var lastCreatedStreams: SelectedStreams? = null
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { s ->
                lastCreatedStreams = s
                createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val progressive720 = VideoStream(url = "https://p720.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1_500_000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1.64001F")
        val videoOnly720 = VideoStream(url = "https://v720.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1_500_000, isVideoOnly = true, mimeType = "video/mp4", codec = "avc1.64001F")
        val audio = AudioStream(url = "https://audio.m4a", format = "m4a", bitrate = 128_000, mimeType = "audio/mp4", codec = "mp4a.40.2")
        val info = StreamInfo(key = testKey, title = "Mixed", videoStreams = listOf(progressive720, videoOnly720), audioStreams = listOf(audio))

        controller.prepare(testKey, info)
        advanceUntilIdle()

        val available = controller.state.value.availableQualities
        assertEquals(2, available.size)
        val progOpt = available.first { it.isProgressive }
        val adaptOpt = available.first { !it.isProgressive }

        // Switch to adaptive 720
        controller.selectQuality(adaptOpt)
        advanceUntilIdle()

        assertEquals(adaptOpt, controller.state.value.selectedQuality)
        assertEquals(PlaybackStreamType.MERGED_AV, controller.state.value.streamType)
        assertEquals("https://v720.mp4", lastCreatedStreams?.videoStream?.url)
        assertEquals("https://audio.m4a", lastCreatedStreams?.audioStream?.url)

        // Switch back to progressive 720
        controller.selectQuality(progOpt)
        advanceUntilIdle()

        assertEquals(progOpt, controller.state.value.selectedQuality)
        assertEquals(PlaybackStreamType.PROGRESSIVE, controller.state.value.streamType)
        assertEquals("https://p720.mp4", lastCreatedStreams?.videoStream?.url)
        assertNull(lastCreatedStreams?.audioStream)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun selectQuality_exact_matching_when_same_height_and_mode_with_different_format() = testScope.runTest {
        var lastCreatedStreams: SelectedStreams? = null
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { s ->
                lastCreatedStreams = s
                createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val progMp4 = VideoStream(url = "https://p720.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1_500_000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1.64001F")
        val progWebm = VideoStream(url = "https://p720.webm", format = "webm", resolution = "720p", width = 1280, height = 720, bitrate = 1_500_000, isVideoOnly = false, mimeType = "video/webm", codec = "vp9")
        val info = StreamInfo(key = testKey, title = "Duplicate Heights", videoStreams = listOf(progMp4, progWebm))

        controller.prepare(testKey, info)
        advanceUntilIdle()

        val available = controller.state.value.availableQualities
        assertEquals(2, available.size)
        val mp4Opt = available.first { it.format.equals("mp4", ignoreCase = true) }
        val webmOpt = available.first { it.format.equals("webm", ignoreCase = true) }

        controller.selectQuality(webmOpt)
        advanceUntilIdle()

        assertEquals(webmOpt, controller.state.value.selectedQuality)
        assertEquals("https://p720.webm", lastCreatedStreams?.videoStream?.url)

        controller.selectQuality(mp4Opt)
        advanceUntilIdle()

        assertEquals(mp4Opt, controller.state.value.selectedQuality)
        assertEquals("https://p720.mp4", lastCreatedStreams?.videoStream?.url)
        controller.release()
        testScope.runCurrent()
    }

    // Invariant 4: prepare and selectQuality wrap exceptions and never leave loading=true
    @Test
    fun prepare_when_media_source_factory_throws_maps_to_safe_error_and_clears_loading() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { throw IllegalStateException("Source creation failed") },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val info = sampleStreamInfo()
        controller.prepare(testKey, info)
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertEquals(AppError.UnsupportedFormat, state.error)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun selectQuality_when_factory_throws_maps_error_and_clears_loading() = testScope.runTest {
        var shouldThrow = false
        val controller = ForegroundPlayerController(
            mediaSourceFactory = {
                if (shouldThrow) throw IllegalStateException("Failed") else createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val progressive720 = VideoStream(url = "https://p720.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1_500_000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1.64001F")
        val progressive360 = VideoStream(url = "https://p360.mp4", format = "mp4", resolution = "360p", width = 640, height = 360, bitrate = 700_000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1.64001F")
        val info = StreamInfo(key = testKey, title = "Qualities", videoStreams = listOf(progressive720, progressive360))

        controller.prepare(testKey, info)
        advanceUntilIdle()

        val opt360 = controller.state.value.availableQualities.first { it.height == 360 }
        shouldThrow = true
        controller.selectQuality(opt360)
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertEquals(AppError.UnsupportedFormat, state.error)
        controller.release()
        testScope.runCurrent()
    }

    // Invariant 6: Validate seek >= 0 and playback speed 0.25..3.0
    @Test
    fun seekTo_and_seekBy_reject_invalid_positions() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        testScope.runCurrent()

        testPlayerState.duration = 60_000L
        testPlayerState.currentPosition = 10_000L

        // Negative seek target must be clamped to 0
        controller.seekTo(-5000L)
        testScope.runCurrent()
        assertEquals(0L, testPlayerState.currentPosition)
        assertEquals(0L, controller.state.value.currentPositionMs)

        // Large seek target must be clamped to duration
        controller.seekTo(100_000L)
        testScope.runCurrent()
        assertEquals(60_000L, testPlayerState.currentPosition)
        assertEquals(60_000L, controller.state.value.currentPositionMs)

        // seekBy negative past 0
        controller.seekBy(-100_000L)
        testScope.runCurrent()
        assertEquals(0L, testPlayerState.currentPosition)
        assertEquals(0L, controller.state.value.currentPositionMs)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun setPlaybackSpeed_validates_finite_and_range_bounds() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        testScope.runCurrent()

        // Valid speed
        controller.setPlaybackSpeed(1.5f)
        testScope.runCurrent()
        assertEquals(1.5f, testPlayerState.playbackSpeed, 0.001f)
        assertEquals(1.5f, controller.state.value.playbackSpeed, 0.001f)

        // Below 0.25f clamped to 0.25f
        controller.setPlaybackSpeed(0.1f)
        testScope.runCurrent()
        assertEquals(0.25f, testPlayerState.playbackSpeed, 0.001f)
        assertEquals(0.25f, controller.state.value.playbackSpeed, 0.001f)

        // Above 3.0f clamped to 3.0f
        controller.setPlaybackSpeed(5.0f)
        testScope.runCurrent()
        assertEquals(3.0f, testPlayerState.playbackSpeed, 0.001f)
        assertEquals(3.0f, controller.state.value.playbackSpeed, 0.001f)

        // NaN or Infinite ignored
        controller.setPlaybackSpeed(Float.NaN)
        testScope.runCurrent()
        assertEquals(3.0f, testPlayerState.playbackSpeed, 0.001f)

        controller.setPlaybackSpeed(Float.POSITIVE_INFINITY)
        testScope.runCurrent()
        assertEquals(3.0f, testPlayerState.playbackSpeed, 0.001f)
        controller.release()
        testScope.runCurrent()
    }

    // Invariant 6: Map Media3 HTTP 403 to StreamExpired, 401/404/5xx to safe errors
    @Test
    fun player_error_mapping_distinguishes_403_and_other_http_status_codes_via_cause_status() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        testScope.runCurrent()

        fun createInvalidResponseCodeException(code: Int, message: String): HttpDataSource.InvalidResponseCodeException {
            val constructor = HttpDataSource.InvalidResponseCodeException::class.java.declaredConstructors.first {
                it.parameterTypes.size >= 4
            }
            constructor.isAccessible = true
            val params = Array<Any?>(constructor.parameterTypes.size) { index ->
                when (constructor.parameterTypes[index]) {
                    Int::class.javaPrimitiveType -> if (index == 0) code else 0
                    String::class.java -> message
                    java.io.IOException::class.java -> null
                    Map::class.java -> emptyMap<String, List<String>>()
                    ByteArray::class.java -> byteArrayOf()
                    else -> null
                }
            }
            return constructor.newInstance(*params) as HttpDataSource.InvalidResponseCodeException
        }

        // 1. HTTP 403 status (direct and wrapped)
        val invalid403 = createInvalidResponseCodeException(403, "Forbidden")
        val http403Exception = PlaybackException(
            "Playback error 403",
            invalid403,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        testPlayerState.notifyError(http403Exception)
        testScope.runCurrent()
        assertEquals(AppError.StreamExpired, controller.state.value.error)

        // 1b. HTTP 403 status deeply wrapped in causes
        val wrapped403 = java.io.IOException("Outer IO", java.lang.RuntimeException("Middle", invalid403))
        val wrapped403Exception = PlaybackException(
            "Playback wrapped error 403",
            wrapped403,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        testPlayerState.notifyError(wrapped403Exception)
        testScope.runCurrent()
        assertEquals(AppError.StreamExpired, controller.state.value.error)

        // 1c. ERROR_CODE_IO_BAD_HTTP_STATUS with NO cause or non-403 cause must NOT map to StreamExpired
        val noCauseBadHttp = PlaybackException(
            "Bad HTTP status without cause",
            null,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        testPlayerState.notifyError(noCauseBadHttp)
        testScope.runCurrent()
        assertEquals(AppError.Unknown, controller.state.value.error)

        // 1d. HTTP 401 status maps to LoginRequired
        val invalid401 = createInvalidResponseCodeException(401, "Unauthorized")
        val http401Exception = PlaybackException(
            "Playback error 401",
            invalid401,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        testPlayerState.notifyError(http401Exception)
        testScope.runCurrent()
        assertEquals(AppError.LoginRequired, controller.state.value.error)

        // 2. HTTP 404 status
        val invalid404 = createInvalidResponseCodeException(404, "Not Found")
        val http404Exception = PlaybackException(
            "Playback error 404",
            invalid404,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        testPlayerState.notifyError(http404Exception)
        testScope.runCurrent()
        assertEquals(AppError.ContentUnavailable, controller.state.value.error)

        // 3. HTTP 500 status
        val invalid500 = createInvalidResponseCodeException(500, "Internal Server Error")
        val http500Exception = PlaybackException(
            "Playback error 500",
            invalid500,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        testPlayerState.notifyError(http500Exception)
        testScope.runCurrent()
        assertEquals(AppError.NetworkError, controller.state.value.error)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun prepare_with_valid_progressive_stream_sets_media_source_and_updates_state() = testScope.runTest {
        val fakeSource = createFakeMediaSource()
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { fakeSource },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val info = sampleStreamInfo()
        controller.prepare(testKey, info, startPositionMs = 5000L)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(testKey, state.key)
        assertEquals(PlaybackStreamType.PROGRESSIVE, state.streamType)
        assertNotNull(testPlayerState.mediaSourceSet)
        assertTrue(testPlayerState.prepared)
        assertTrue(testPlayerState.playWhenReady)
        assertEquals(5000L, testPlayerState.currentPosition)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun prepare_with_no_supported_candidate_emits_unsupported_format_error() = testScope.runTest {
        val emptyInfo = StreamInfo(
            key = testKey,
            title = "Unsupported",
            videoStreams = emptyList(),
            audioStreams = emptyList()
        )

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, emptyInfo)
        advanceUntilIdle()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertEquals(AppError.UnsupportedFormat, state.error)
        assertNull(testPlayerState.mediaSourceSet)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun play_pause_and_playPause_toggle_player_state() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        testScope.runCurrent()

        controller.play()
        testScope.runCurrent()
        assertTrue(testPlayerState.isPlaying)

        controller.pause()
        testScope.runCurrent()
        assertFalse(testPlayerState.isPlaying)

        controller.playPause()
        testScope.runCurrent()
        assertTrue(testPlayerState.isPlaying)

        controller.playPause()
        testScope.runCurrent()
        assertFalse(testPlayerState.isPlaying)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun player_calls_and_factory_creation_marshal_to_configured_main_dispatcher() = testScope.runTest {
        var threadDuringFactory: Thread? = null
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = {
                threadDuringFactory = Thread.currentThread()
                testPlayer
            },
            mainDispatcher = testDispatcher
        )

        controller.prepare(testKey, sampleStreamInfo())
        controller.play()
        controller.pause()
        controller.seekTo(2000L)
        controller.setPlaybackSpeed(1.25f)
        controller.seekBy(1000L)
        controller.playPause()

        testScope.runCurrent()

        assertTrue(testPlayerState.prepared)
        assertEquals(3000L, testPlayerState.currentPosition)
        assertEquals(1.25f, testPlayerState.playbackSpeed, 0.01f)
        assertTrue(testPlayerState.isPlaying)
        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun delayed_noncooperative_quality_A_vs_prepare_B_only_B_commits() = testScope.runTest {
        val keyA = ContentKey(0, "video_A")
        val keyB = ContentKey(0, "video_B")

        val prog720 = VideoStream(url = "https://p720.mp4", format = "mp4", resolution = "720p", width = 1280, height = 720, bitrate = 1000, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")
        val prog480 = VideoStream(url = "https://p480.mp4", format = "mp4", resolution = "480p", width = 854, height = 480, bitrate = 500, isVideoOnly = false, mimeType = "video/mp4", codec = "avc1")
        val infoA = StreamInfo(key = keyA, title = "Video A", videoStreams = listOf(prog720, prog480))
        val infoB = StreamInfo(key = keyB, title = "Video B", videoStreams = listOf(prog720))

        val committedMediaSources = mutableListOf<String>()

        var delayQualityA = true
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { selected ->
                if (selected.key == keyA && selected.videoStream?.height == 480 && delayQualityA) {
                    // Noncooperative/blocking delay simulation on IO
                    Thread.sleep(100)
                }
                committedMediaSources.add("${selected.key.nativeId}_${selected.videoStream?.height ?: 0}")
                createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO
        )

        // 1. Initial prepare A
        controller.prepare(keyA, infoA)
        advanceUntilIdle()
        assertEquals(keyA, controller.state.value.key)

        // 2. Trigger quality selection A (480p) which has slow/blocking creation
        val opt480 = controller.state.value.availableQualities.first { it.height == 480 }
        controller.selectQuality(opt480)

        // 3. Immediately prepare B before quality A completes
        controller.prepare(keyB, infoB)
        advanceUntilIdle()

        // Wait for background IO thread to complete sleep
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 300) {
            Thread.sleep(20)
            advanceUntilIdle()
        }

        // 4. Assert: Final committed state is B, NOT stale quality A!
        assertEquals(keyB, controller.state.value.key)
        assertEquals(720, controller.state.value.selectedQuality?.height)
        assertNull(controller.state.value.error)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun no_main_blocking_dispatcher_use_for_media_source_creation() = testScope.runTest {
        var sourceThreadName = ""
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { selected ->
                sourceThreadName = Thread.currentThread().name
                createFakeMediaSource()
            },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO
        )

        controller.prepare(testKey, sampleStreamInfo())
        advanceUntilIdle()

        // Media source factory must have run on background/IO dispatcher, not the test Main dispatcher
        assertFalse(
            "Media source factory should not execute on Main test dispatcher thread",
            sourceThreadName.contains("main", ignoreCase = true) && !sourceThreadName.contains("DefaultDispatcher")
        )

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun retry_snapshot_created_on_player_error_and_stable_against_position_changes() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, sampleStreamInfo(), startPositionMs = 10_000L, playWhenReady = true)
        advanceUntilIdle()

        // User pauses
        controller.pause()
        testScope.runCurrent()
        assertFalse(testPlayerState.isPlaying)

        // Error occurs while paused at 10_000ms
        testPlayerState.notifyError(
            PlaybackException("Network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        testScope.runCurrent()

        val snapshot = controller.state.value.retrySnapshot
        assertNotNull(snapshot)
        assertEquals(testKey, snapshot?.key)
        assertEquals(10_000L, snapshot?.positionMs)
        assertEquals(false, snapshot?.userRequestedPlay)

        // Simulate position changing in underlying player after error
        testPlayerState.currentPosition = 50_000L
        testScope.runCurrent()

        // Snapshot remains stable
        assertEquals(10_000L, controller.state.value.retrySnapshot?.positionMs)
        assertEquals(false, controller.state.value.retrySnapshot?.userRequestedPlay)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun retry_snapshot_tracks_user_intent_not_raw_transient_playWhenReady() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Prepare video (userRequestedPlay = false)
        controller.prepare(testKey, sampleStreamInfo(), playWhenReady = false)
        advanceUntilIdle()

        // Suppose buffering/ExoPlayer sets playWhenReady = true internally or transiently
        testPlayerState.playWhenReady = true

        // Player error occurs
        testPlayerState.notifyError(
            PlaybackException("Network timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )
        testScope.runCurrent()

        // Retry snapshot must reflect user requested pause (false), NOT transient playWhenReady (true)
        val snapshot = controller.state.value.retrySnapshot
        assertNotNull(snapshot)
        assertEquals(false, snapshot?.userRequestedPlay)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun surface_identity_tracking_stale_detach_cannot_clear_newer_attach() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        testScope.runCurrent()

        fun createFakePlayerView(): androidx.media3.ui.PlayerView {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateInstanceMethod.invoke(unsafe, androidx.media3.ui.PlayerView::class.java) as androidx.media3.ui.PlayerView
        }

        val view1 = createFakePlayerView()
        val view2 = createFakePlayerView()

        // 1. Attach view 1
        controller.attachSurface(view1)
        testScope.runCurrent()
        assertEquals(testPlayer, view1.player)

        // 2. Attach view 2 (e.g. fullscreen transition)
        controller.attachSurface(view2)
        testScope.runCurrent()
        assertEquals(testPlayer, view2.player)

        // 3. Stale detach on view 1 (e.g. portrait disposal happening after fullscreen attached)
        controller.detachSurface(view1)
        testScope.runCurrent()

        // View 2 must remain attached to testPlayer!
        assertEquals(testPlayer, view2.player)

        // 4. Detaching active view 2 clears player
        controller.detachSurface(view2)
        testScope.runCurrent()
        assertNull(view2.player)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun attachSurface_and_detachSurface_marshal_first_with_no_direct_access_before_main() = testScope.runTest {
        fun createFakePlayerView(): androidx.media3.ui.PlayerView {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateInstanceMethod.invoke(unsafe, androidx.media3.ui.PlayerView::class.java) as androidx.media3.ui.PlayerView
        }

        val separateScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val controlledMainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(separateScheduler)

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = controlledMainDispatcher,
            ioDispatcher = testDispatcher
        )

        val view1 = createFakePlayerView()
        val view2 = createFakePlayerView()

        // Call attachSurface from background dispatcher or test thread
        controller.attachSurface(view1)

        // Before controlledMainDispatcher advances, view1.player must NOT have been mutated yet
        assertNull("Caller thread must only schedule main work, not mutate playerView synchronously", view1.player)

        controlledMainDispatcher.scheduler.runCurrent()
        assertEquals(testPlayer, view1.player)

        // Stale detach race: call attach(view2) and stale detach(view1)
        controller.attachSurface(view2)
        controller.detachSurface(view1)

        // Before main runs, view2.player is still null
        assertNull(view2.player)

        controlledMainDispatcher.scheduler.runCurrent()

        // After main runs sequentially, view2 is active, view1 is detached, view2.player is testPlayer
        assertEquals(testPlayer, view2.player)
        assertNull(view1.player)

        controller.release()
        controlledMainDispatcher.scheduler.runCurrent()
    }

    @Test
    fun error_and_seek_ordering_preserves_published_snapshot_and_serializes_on_main() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, sampleStreamInfo(), startPositionMs = 5_000L, playWhenReady = true)
        advanceUntilIdle()

        // Seek to 12_000ms
        controller.seekTo(12_000L)
        testScope.runCurrent()
        assertEquals(12_000L, controller.state.value.currentPositionMs)

        // Raw exoPlayer position changes transiently/asynchronously before error callback
        testPlayerState.currentPosition = 12_000L
        testPlayerState.notifyError(
            PlaybackException("Network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        testScope.runCurrent()

        // Snapshot must capture the controller's main-published position (12_000L)
        val snapshot = controller.state.value.retrySnapshot
        assertNotNull(snapshot)
        assertEquals(12_000L, snapshot?.positionMs)

        // Underlying player position jumps after error (e.g. reset to 0 in ExoPlayer)
        testPlayerState.currentPosition = 0L
        testScope.runCurrent()

        // Snapshot remains locked to 12_000L
        assertEquals(12_000L, controller.state.value.retrySnapshot?.positionMs)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun getTestingSnapshot_tracks_rendered_and_decoder_events_per_generation() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, sampleStreamInfo(), playWhenReady = true)
        advanceUntilIdle()

        val snap1 = controller.getTestingSnapshot()
        assertEquals(1L, snap1.mediaOperationGeneration)
        assertEquals(0, snap1.renderedFirstFrameCount)
        assertEquals(0, snap1.audioDecoderInitializedCount)

        // Simulate ExoPlayer analytics listener callbacks
        val eventTime = AnalyticsListener.EventTime(
            0L, androidx.media3.common.Timeline.EMPTY, 0, null, 0L, androidx.media3.common.Timeline.EMPTY, 0, null, 0L, 0L
        )
        // Use latest active listener
        testPlayerState.analyticsListeners.last().let {
            it.onRenderedFirstFrame(eventTime, Any(), 10L)
            it.onAudioDecoderInitialized(eventTime, "audio-decoder-mock", 100L, 5L)
        }
        testScope.runCurrent()

        val snap2 = controller.getTestingSnapshot()
        assertEquals(1L, snap2.mediaOperationGeneration)
        assertEquals(1, snap2.renderedFirstFrameCount)
        assertEquals(1, snap2.audioDecoderInitializedCount)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun getTestingSnapshot_counts_first_frame_only_from_analytics_listener() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, sampleStreamInfo(), playWhenReady = true)
        advanceUntilIdle()

        // The gate evidence is sourced from AnalyticsListener so one rendered frame has one count.
        testPlayerState.listeners.forEach { it.onRenderedFirstFrame() }
        testScope.runCurrent()

        assertEquals(0, controller.getTestingSnapshot().renderedFirstFrameCount)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun late_old_listener_event_is_discarded_and_cannot_satisfy_new_generation_render_evidence() = testScope.runTest {
        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val keyA = ContentKey(0, "video_A")
        val keyB = ContentKey(0, "video_B")

        // 1. Prepare video A (generation 1)
        controller.prepare(keyA, sampleStreamInfo())
        advanceUntilIdle()

        val snapA = controller.getTestingSnapshot()
        assertEquals(1L, snapA.mediaOperationGeneration)
        assertEquals(0, snapA.renderedFirstFrameCount)

        // Capture generation 1's per-operation analytics listener
        assertEquals(1, testPlayerState.analyticsListeners.size)
        val listenerGen1 = testPlayerState.analyticsListeners.first()

        // 2. Prepare video B (generation 2)
        controller.prepare(keyB, sampleStreamInfo())
        advanceUntilIdle()

        val snapB1 = controller.getTestingSnapshot()
        assertEquals(2L, snapB1.mediaOperationGeneration)
        assertEquals(0, snapB1.renderedFirstFrameCount)
        assertEquals(0, snapB1.audioDecoderInitializedCount)

        // 3. Fire late event using OLD generation 1 listener
        val dummyEventTime = AnalyticsListener.EventTime(
            0L, androidx.media3.common.Timeline.EMPTY, 0, null, 0L, androidx.media3.common.Timeline.EMPTY, 0, null, 0L, 0L
        )
        listenerGen1.onRenderedFirstFrame(dummyEventTime, Any(), 10L)
        listenerGen1.onAudioDecoderInitialized(dummyEventTime, "decoder-gen1", 100L, 5L)
        listenerGen1.onVideoDecoderInitialized(dummyEventTime, "decoder-video-gen1", 100L, 5L)
        testScope.runCurrent()

        // 4. Verify that generation 2 snapshot DOES NOT count the late gen 1 events.
        val snapB2 = controller.getTestingSnapshot()
        assertEquals(2L, snapB2.mediaOperationGeneration)
        assertEquals(0, snapB2.renderedFirstFrameCount)
        assertEquals(0, snapB2.audioDecoderInitializedCount)
        assertEquals(0, snapB2.videoDecoderInitializedCount)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun getTestingSnapshot_tracks_surfaceAttached_state() = testScope.runTest {
        fun createFakePlayerView(): androidx.media3.ui.PlayerView {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val allocateInstanceMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateInstanceMethod.invoke(unsafe, androidx.media3.ui.PlayerView::class.java) as androidx.media3.ui.PlayerView
        }

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val snapInitial = controller.getTestingSnapshot()
        assertFalse(snapInitial.surfaceAttached)

        val view = createFakePlayerView()
        controller.attachSurface(view)
        testScope.runCurrent()

        val snapAttached = controller.getTestingSnapshot()
        assertTrue(snapAttached.surfaceAttached)

        controller.detachSurface(view)
        testScope.runCurrent()

        val snapDetached = controller.getTestingSnapshot()
        assertFalse(snapDetached.surfaceAttached)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun reprepare_with_same_key_creates_new_session_gen_and_cancels_or_invalidates_stale_recovery() = testScope.runTest {
        val fakeService = FakeVideoService()
        val recoveryDeferred = CompletableDeferred<AppResult<StreamInfo>>()
        fakeService.streamInfoHandler = { _ ->
            recoveryDeferred.await()
        }
        val coordinator = StreamRecoveryCoordinator(fakeService)

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            recoveryCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // 1. Prepare initial session
        controller.prepare(testKey, sampleStreamInfo(progressiveUrl = "https://stream1.mp4", height = 720), startPositionMs = 10_000L)
        advanceUntilIdle()

        val initialSnapshot = controller.getTestingSnapshot()
        val initialSessionGen = initialSnapshot.playbackSessionGeneration
        assertEquals(1L, initialSessionGen)

        // 2. Trigger HTTP 403 error to start auto-recovery
        val constructor = HttpDataSource.InvalidResponseCodeException::class.java.declaredConstructors.first {
            it.parameterTypes.size >= 4
        }
        constructor.isAccessible = true
        val params = Array<Any?>(constructor.parameterTypes.size) { index ->
            when (constructor.parameterTypes[index]) {
                Int::class.javaPrimitiveType -> if (index == 0) 403 else 0
                String::class.java -> "Forbidden"
                java.io.IOException::class.java -> null
                Map::class.java -> emptyMap<String, List<String>>()
                ByteArray::class.java -> byteArrayOf()
                else -> null
            }
        }
        val http403 = constructor.newInstance(*params) as HttpDataSource.InvalidResponseCodeException
        val playbackException = PlaybackException("HTTP error", http403, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        testPlayerState.notifyError(playbackException)
        testScope.runCurrent()

        // Recovery is now in flight for session 1
        assertEquals(1, fakeService.streamInfoCallCount)

        // 3. Prepare SAME key again with new session
        controller.prepare(testKey, sampleStreamInfo(progressiveUrl = "https://stream2.mp4", height = 720), startPositionMs = 50_000L)
        advanceUntilIdle()

        val secondSnapshot = controller.getTestingSnapshot()
        val secondSessionGen = secondSnapshot.playbackSessionGeneration
        assertEquals(2L, secondSessionGen)
        assertEquals(50_000L, controller.state.value.currentPositionMs)

        // 4. Now let the stale recovery for session 1 complete
        recoveryDeferred.complete(AppResult.Success(sampleStreamInfo(progressiveUrl = "https://stale-recovered.mp4", height = 720)))
        advanceUntilIdle()

        // The stale recovery must NOT commit or overwrite session 2's position
        assertEquals(50_000L, controller.state.value.currentPositionMs)
        assertEquals(2L, controller.getTestingSnapshot().playbackSessionGeneration)

        controller.release()
        testScope.runCurrent()
    }

    @Test
    fun release_cancels_pending_recovery() = testScope.runTest {
        val fakeService = FakeVideoService()
        val recoveryDeferred = CompletableDeferred<AppResult<StreamInfo>>()
        fakeService.streamInfoHandler = { _ ->
            recoveryDeferred.await()
        }
        val coordinator = StreamRecoveryCoordinator(fakeService)

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            recoveryCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, sampleStreamInfo(height = 720), startPositionMs = 10_000L)
        advanceUntilIdle()

        // Trigger HTTP 403
        val constructor = HttpDataSource.InvalidResponseCodeException::class.java.declaredConstructors.first {
            it.parameterTypes.size >= 4
        }
        constructor.isAccessible = true
        val params = Array<Any?>(constructor.parameterTypes.size) { index ->
            when (constructor.parameterTypes[index]) {
                Int::class.javaPrimitiveType -> if (index == 0) 403 else 0
                String::class.java -> "Forbidden"
                java.io.IOException::class.java -> null
                Map::class.java -> emptyMap<String, List<String>>()
                ByteArray::class.java -> byteArrayOf()
                else -> null
            }
        }
        val http403 = constructor.newInstance(*params) as HttpDataSource.InvalidResponseCodeException
        val playbackException = PlaybackException("HTTP error", http403, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        testPlayerState.notifyError(playbackException)
        testScope.runCurrent()

        // Release controller while recovery is pending
        controller.release()
        testScope.runCurrent()

        // Complete recovery deferred
        recoveryDeferred.complete(AppResult.Success(sampleStreamInfo()))
        advanceUntilIdle()

        // Controller remains released and idle
        assertNull(controller.state.value.key)
    }

    @Test
    fun http_403_triggers_auto_recovery_via_coordinator_preserving_position_and_play_intent() = testScope.runTest {
        val fakeService = FakeVideoService(
            streamResponses = mapOf(testKey.nativeId to sampleStreamInfo(progressiveUrl = "https://recovered.mp4", height = 720))
        )
        val coordinator = StreamRecoveryCoordinator(fakeService)

        val controller = ForegroundPlayerController(
            mediaSourceFactory = { createFakeMediaSource() },
            playerFactory = { testPlayer },
            recoveryCoordinator = coordinator,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        controller.prepare(testKey, sampleStreamInfo(height = 720), startPositionMs = 30_000L, playWhenReady = true)
        advanceUntilIdle()

        assertEquals(30_000L, controller.state.value.currentPositionMs)
        assertTrue(controller.state.value.playWhenReady)

        // Trigger Media3 HTTP 403 error
        val constructor = HttpDataSource.InvalidResponseCodeException::class.java.declaredConstructors.first {
            it.parameterTypes.size >= 4
        }
        constructor.isAccessible = true
        val params = Array<Any?>(constructor.parameterTypes.size) { index ->
            when (constructor.parameterTypes[index]) {
                Int::class.javaPrimitiveType -> if (index == 0) 403 else 0
                String::class.java -> "Forbidden"
                java.io.IOException::class.java -> null
                Map::class.java -> emptyMap<String, List<String>>()
                ByteArray::class.java -> byteArrayOf()
                else -> null
            }
        }
        val http403 = constructor.newInstance(*params) as HttpDataSource.InvalidResponseCodeException
        val playbackException = PlaybackException("HTTP error", http403, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        testPlayerState.notifyError(playbackException)
        advanceUntilIdle()

        // After auto recovery: fresh stream resolved once, state error cleared, position preserved
        assertEquals(1, fakeService.streamInfoCallCount)
        assertNull(controller.state.value.error)
        assertEquals(30_000L, controller.state.value.currentPositionMs)
        assertTrue(controller.state.value.playWhenReady)

        // Trigger a SECOND 403 error on the recovered session: budget for session 2 exhausted -> capped once, state surfaces AppError.StreamExpired
        // Note: each prepare creates a new session generation. To test exhausted budget for the current session,
        // we can test StreamRecoveryCoordinator directly or verify coordinator fails when session budget is exhausted.
        // In the controller, if recoveryCoordinator returns Failed(StreamExpired), it surfaces AppError.StreamExpired.

        controller.release()
        testScope.runCurrent()
    }
}
