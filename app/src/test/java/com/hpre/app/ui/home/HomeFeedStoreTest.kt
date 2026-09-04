package com.hpre.app.ui.home

import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeFeedStoreTest {
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
    fun saved_feed_round_trips_and_expires_at_the_disk_ceiling() {
        val values = mutableMapOf<String, String>()
        var now = 1_000L
        val store = HomeFeedStore(
            readEncoded = values::get,
            writeEncoded = { key, value -> values[key] = value },
            removeEncoded = { values.remove(it) },
            nowMs = { now },
            maxAgeMs = 10_000L
        )

        store.save("__all__", listOf(video()))
        assertEquals(listOf(video()), store.load("__all__"))

        now = 11_001L
        assertNull(store.load("__all__"))
    }
}
