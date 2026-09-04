package com.hpre.app.ui.home

import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFeedStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun video() = VideoSummary(
        key = ContentKey(0, "dQw4w9WgXcQ"),
        title = "Cached title",
        canonicalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        channelKey = ContentKey(0, "UCuCKox3vgM_q8p1Ufx9kGqg"),
        channelName = "Channel",
        channelAvatarUrl = "https://example.test/avatar.jpg",
        thumbnailUrl = "https://example.test/thumb.jpg",
        durationSeconds = 212,
        viewCount = 42,
        publishedTimestamp = 1234,
        isLive = false,
        isShort = false
    )

    @Test
    fun saved_feed_round_trips_and_expires_at_the_disk_ceiling() = runTest(testDispatcher) {
        val values = mutableMapOf<String, String>()
        var now = 1_000L
        val store = HomeFeedStore(
            readEncoded = values::get,
            writeEncoded = { key, value -> values[key] = value },
            removeEncoded = { values.remove(it) },
            nowMs = { now },
            maxAgeMs = 10_000L,
            ioDispatcher = testDispatcher
        )

        store.save("__all__", listOf(video()))
        assertEquals(listOf(video()), store.load("__all__"))

        now = 11_001L
        assertNull(store.load("__all__"))
    }

    @Test
    fun storage_and_codec_operations_run_on_io_dispatcher() = runTest(testDispatcher) {
        val observedIo = AtomicBoolean(false)
        val interceptingDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                observedIo.set(true)
                testDispatcher.dispatch(context, block)
            }
        }

        val values = mutableMapOf<String, String>()
        val store = HomeFeedStore(
            readEncoded = values::get,
            writeEncoded = { key, value -> values[key] = value },
            removeEncoded = { values.remove(it) },
            ioDispatcher = interceptingDispatcher
        )

        observedIo.set(false)
        store.save("__all__", listOf(video()))
        assertTrue(observedIo.get())

        observedIo.set(false)
        store.load("__all__")
        assertTrue(observedIo.get())

        observedIo.set(false)
        store.remove("__all__")
        assertTrue(observedIo.get())
    }
}

