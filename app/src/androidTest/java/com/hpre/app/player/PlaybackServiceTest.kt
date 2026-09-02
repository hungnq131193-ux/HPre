package com.hpre.app.player

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {

    private val testKey = ContentKey(0, "service_test_video_123")

    @Test
    fun controller_reconnect_keeps_one_session_media_item_and_position() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller1 = SessionPlayerController(context)

        val streamInfo = StreamInfo(
            key = testKey,
            title = "Service Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/prog.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        // Prepare video via first controller
        controller1.prepare(testKey, streamInfo, startPositionMs = 12_345L, playWhenReady = false)
        delay(1000)

        controller1.seekTo(12_345L)
        delay(500)

        // A second observer connects while the paused service session is still owned.
        // Activity recreation uses the app-scoped controller and does not disconnect it.
        val controller2 = SessionPlayerController(context)
        delay(1000)

        val snapshot = controller2.getTestingSnapshot()
        assertNotNull(snapshot)
        assertEquals(12_345L, snapshot.actualPositionMs)

        controller1.release()
        controller2.release()
    }

    @Test
    fun stale_prepare_A_then_B_results_in_B_authoritative() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val keyA = ContentKey(0, "video_A")
        val keyB = ContentKey(0, "video_B")

        val streamA = StreamInfo(
            key = keyA,
            title = "Video A",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/a.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_000_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        val streamB = StreamInfo(
            key = keyB,
            title = "Video B",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/b.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_000_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        // Send A then rapidly send B
        controller.prepare(keyA, streamA, startPositionMs = 5_000L, playWhenReady = false)
        controller.prepare(keyB, streamB, startPositionMs = 20_000L, playWhenReady = false)
        delay(1200)

        val snapshot = controller.getTestingSnapshot()
        assertNotNull(snapshot)
        assertEquals(keyB, controller.state.value.key)
        assertEquals("Video B", controller.state.value.title)

        controller.release()
    }

    @Test
    fun notification_channel_is_registered() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        HPreMediaNotification.ensureNotificationChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = nm.getNotificationChannel(HPreMediaNotification.CHANNEL_ID)
            assertNotNull(channel)
            assertEquals(HPreMediaNotification.CHANNEL_NAME, channel.name)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        }
    }

    @Test
    fun clear_media_resets_service_and_controller_state() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val streamInfo = StreamInfo(
            key = testKey,
            title = "Clear Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/prog.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        controller.prepare(testKey, streamInfo, startPositionMs = 10_000L, playWhenReady = false)
        delay(1000)

        controller.clearMedia()
        delay(500)

        assertNull(controller.state.value.key)
        assertNull(controller.state.value.title)

        controller.release()
    }

    @Test
    fun background_disabled_lifecycle_stop_clears_media_and_persisted_snapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val streamInfo = StreamInfo(
            key = testKey,
            title = "Bg Disabled Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/prog.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        controller.prepare(testKey, streamInfo, startPositionMs = 10_000L, playWhenReady = false)
        delay(1000)

        // Set policy: background disabled, non-PiP
        controller.updateLifecyclePolicy(backgroundEnabled = false, pipActiveOrEntering = false)
        delay(300)

        // Activity changing configuration with background disabled does NOT clear
        controller.onLifecycleStop(isChangingConfigurations = true, isInPip = false)
        delay(500)
        assertEquals(testKey, controller.state.value.key)

        // In PiP mode with background disabled does NOT clear
        controller.onLifecycleStop(isChangingConfigurations = false, isInPip = true)
        delay(500)
        assertEquals(testKey, controller.state.value.key)

        // Real backgrounding (isChangingConfigurations = false, isInPip = false) DOES clear
        controller.onLifecycleStop(isChangingConfigurations = false, isInPip = false)
        delay(1000)

        // Should clear media and state
        assertNull(controller.state.value.key)
        assertNull(controller.snapshotStore.load())

        controller.release()
    }

    @Test
    fun prepare_protocol_restores_and_retains_quality_and_speed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val opt720 = QualityOption(
            height = 720,
            label = "720p",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1"
        )
        val streamInfo = StreamInfo(
            key = testKey,
            title = "Quality Speed Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/prog.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        controller.prepareWithSpeed(testKey, streamInfo, startPositionMs = 5000L, playWhenReady = false, initialQuality = opt720, playbackSpeed = 1.5f)
        delay(1000)

        assertEquals(1.5f, controller.state.value.playbackSpeed, 0.01f)
        assertEquals(720, controller.state.value.selectedQuality?.height)

        val snap = controller.snapshotStore.load()
        assertNotNull(snap)
        assertEquals(1.5f, snap?.playbackSpeed ?: 0f, 0.01f)

        controller.clearMedia()
        delay(500)
        controller.release()
    }

    @Test
    fun setPlaybackSpeed_updates_service_and_persists_speed_in_snapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val streamInfo = StreamInfo(
            key = testKey,
            title = "Speed Change Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/prog.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        controller.prepare(testKey, streamInfo, startPositionMs = 0L, playWhenReady = false)
        delay(1000)

        controller.setPlaybackSpeed(2.0f)
        delay(800)

        assertEquals(2.0f, controller.state.value.playbackSpeed, 0.01f)
        val snap = controller.snapshotStore.load()
        assertNotNull(snap)
        assertEquals(2.0f, snap?.playbackSpeed ?: 0f, 0.01f)

        controller.clearMedia()
        delay(500)
        controller.release()
    }

    @Test
    fun blocked_prepare_then_clear_results_in_no_media() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val slowKey = ContentKey(0, "slow_resolving_video")

        // Prepare without handoff (will query videoService asynchronously)
        controller.prepare(
            key = slowKey,
            streamInfo = StreamInfo(slowKey, "Slow Title"),
            startPositionMs = 0L,
            playWhenReady = false
        )
        // Immediately clear before async resolution completes
        controller.clearMedia()
        delay(1500)

        assertNull(controller.state.value.key)
        assertNull(controller.snapshotStore.load())

        controller.release()
    }

    @Test
    fun quality_selection_success_and_invalid_rejected_protocol() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val opt720 = QualityOption(
            height = 720,
            label = "720p",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1"
        )
        val opt1080 = QualityOption(
            height = 1080,
            label = "1080p",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1"
        )
        val optInvalid = QualityOption(
            height = 4320,
            label = "8K",
            isProgressive = true,
            format = "webm"
        )
        val streamInfo = StreamInfo(
            key = testKey,
            title = "Quality Protocol Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/720.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                ),
                VideoStream(
                    url = "https://hpre.test/1080.mp4",
                    format = "mp4",
                    resolution = "1080p",
                    width = 1920,
                    height = 1080,
                    bitrate = 3_000_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        controller.prepare(testKey, streamInfo, startPositionMs = 0L, playWhenReady = false, initialQuality = opt720)
        delay(1000)
        assertEquals(720, controller.state.value.selectedQuality?.height)

        // Select valid 1080p
        controller.selectQuality(opt1080)
        delay(1000)
        assertEquals(1080, controller.state.value.selectedQuality?.height)
        assertNull(controller.state.value.pendingQuality)
        assertNull(controller.state.value.error)

        // Select invalid quality not available
        controller.selectQuality(optInvalid)
        delay(500)
        // Must maintain authoritative 1080p without staying permanently corrupted
        assertEquals(1080, controller.state.value.selectedQuality?.height)

        controller.clearMedia()
        delay(500)
        controller.release()
    }

    @Test
    fun datastore_emits_before_connect_then_controller_connect_sends_policy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        // Set policy locally before connection completes or as local desired policy
        controller.updateLifecyclePolicy(backgroundEnabled = false, pipActiveOrEntering = false)
        delay(1200)

        // Snapshot or service state should reflect background false
        val streamInfo = StreamInfo(
            key = testKey,
            title = "Policy Connect Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/prog.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )
        controller.prepare(testKey, streamInfo, startPositionMs = 0L, playWhenReady = false)
        delay(1000)

        // Since background is disabled, lifecycle stop should clear media
        controller.onLifecycleStop(isChangingConfigurations = false)
        delay(1000)

        assertNull(controller.state.value.key)
        controller.release()
    }

    @Test
    fun out_of_order_quality_commands_only_commit_latest_generation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val opt720 = QualityOption(
            height = 720,
            label = "720p",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1"
        )
        val opt1080 = QualityOption(
            height = 1080,
            label = "1080p",
            isProgressive = true,
            format = "mp4",
            mimeType = "video/mp4",
            codec = "avc1"
        )
        val streamInfo = StreamInfo(
            key = testKey,
            title = "A/B Out Of Order Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/720.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                ),
                VideoStream(
                    url = "https://hpre.test/1080.mp4",
                    format = "mp4",
                    resolution = "1080p",
                    width = 1920,
                    height = 1080,
                    bitrate = 3_000_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        controller.prepare(testKey, streamInfo, startPositionMs = 0L, playWhenReady = false, initialQuality = opt720)
        delay(1000)
        assertEquals(720, controller.state.value.selectedQuality?.height)

        // Rapidly dispatch A (720) then B (1080)
        controller.selectQuality(opt720)
        controller.selectQuality(opt1080)
        delay(1200)

        // B must be selected and pending cleared
        assertEquals(1080, controller.state.value.selectedQuality?.height)
        assertNull(controller.state.value.pendingQuality)
        assertNull(controller.state.value.error)

        controller.clearMedia()
        delay(500)
        controller.release()
    }

    @Test
    fun stale_old_controller_disconnect_after_new_connect_is_ignored() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionPlayerController(context)
        val streamInfo = StreamInfo(
            key = testKey,
            title = "Disconnect Identity Test",
            videoStreams = listOf(
                VideoStream(
                    url = "https://hpre.test/prog.mp4",
                    format = "mp4",
                    resolution = "720p",
                    width = 1280,
                    height = 720,
                    bitrate = 1_500_000,
                    isVideoOnly = false,
                    mimeType = "video/mp4",
                    codec = "avc1"
                )
            )
        )

        controller.prepare(testKey, streamInfo, startPositionMs = 0L, playWhenReady = false)
        delay(1000)
        assertEquals(testKey, controller.state.value.key)

        // Force a stale disconnect invocation with an invalid/outdated attempt token
        controller.simulateDisconnectedForToken(attemptToken = -999L)
        delay(500)

        // Controller must not have disconnected or wiped state
        assertEquals(testKey, controller.state.value.key)
        assertNotNull(controller.getTestingSnapshot())

        // Force a stale disconnect with same latest attempt token but different fake controller instance
        val fakeFuture = withContext(Dispatchers.Main) {
            androidx.media3.session.MediaController.Builder(
                context,
                androidx.media3.session.SessionToken(
                    context,
                    android.content.ComponentName(context, HPrePlaybackService::class.java)
                )
            ).buildAsync()
        }
        val fakeController = withContext(Dispatchers.IO) {
            fakeFuture.get()
        }

        val activeSnapshot = controller.getTestingSnapshot()
        assertNotNull(activeSnapshot)
        // Invoking with non-active controller instance should be ignored
        controller.simulateDisconnectedForToken(attemptToken = 1L, controller = fakeController)
        delay(500)
        assertEquals(testKey, controller.state.value.key)
        withContext(Dispatchers.Main) {
            androidx.media3.session.MediaController.releaseFuture(fakeFuture)
        }

        controller.clearMedia()
        delay(500)
        controller.release()
    }

    @Test
    fun sessionPlayerController_connection_hints_production_bundle_format() {
        val prewarmHints = SessionPlayerController.createConnectionHints(isPrewarm = true)
        assertTrue(prewarmHints.getBoolean(HPrePlaybackService.KEY_INFRASTRUCTURE_PREWARM, false))

        val normalHints = SessionPlayerController.createConnectionHints(isPrewarm = false)
        org.junit.Assert.assertFalse(normalHints.getBoolean(HPrePlaybackService.KEY_INFRASTRUCTURE_PREWARM, true))
    }
}
