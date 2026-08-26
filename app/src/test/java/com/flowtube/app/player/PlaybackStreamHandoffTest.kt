package com.flowtube.app.player

import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStreamHandoffTest {
    @Test
    fun stream_info_is_consumed_once_without_serializing_urls() {
        val info = StreamInfo(ContentKey(0, "video"), "Title", hlsManifestUrl = "https://cdn.example/live.m3u8")

        val token = PlaybackStreamHandoff.put(info)

        assertEquals(info, PlaybackStreamHandoff.take(token))
        assertNull(PlaybackStreamHandoff.take(token))
        assertEquals(false, token.contains("http", ignoreCase = true))
    }

    @Test
    fun clear_discards_all_pending_handoffs() {
        val token = PlaybackStreamHandoff.put(StreamInfo(ContentKey(0, "video"), "Title"))

        PlaybackStreamHandoff.clear()

        assertNull(PlaybackStreamHandoff.take(token))
    }

    @Test
    fun takeConditional_with_request_generation_rejects_stale_requests() {
        val info1 = StreamInfo(ContentKey(0, "video_1"), "Title 1")
        val info2 = StreamInfo(ContentKey(0, "video_2"), "Title 2")

        val token1 = PlaybackStreamHandoff.put(info1, requestGen = 1L)
        val token2 = PlaybackStreamHandoff.put(info2, requestGen = 2L)

        // Stale request with generation 1 trying to take token2 (gen 2) is rejected and token2 is retired
        assertNull(PlaybackStreamHandoff.takeConditional(token2, expectedRequestGen = 1L))

        // Stale request with generation 1 trying to peek/take token1 with gen 1 succeeds
        val consumed1 = PlaybackStreamHandoff.takeConditional(token1, expectedRequestGen = 1L)
        assertEquals(info1, consumed1)

        // Since token2 was removed/retired on mismatch, subsequent take returns null
        val consumed2 = PlaybackStreamHandoff.takeConditional(token2, expectedRequestGen = 2L)
        assertNull(consumed2)
    }

    @Test
    fun max_entries_and_ttl_bound_the_handoff_cache() {
        PlaybackStreamHandoff.clear()
        // Put more than max entries (e.g. 20)
        for (i in 1..25) {
            PlaybackStreamHandoff.put(StreamInfo(ContentKey(0, "video_$i"), "Title $i"), currentTimeMs = 1000L)
        }
        // Oldest entries should be evicted or bounded to maxSize (e.g., 16)
        val stats = PlaybackStreamHandoff.size()
        org.junit.Assert.assertTrue(stats <= 16)

        // TTL expiration test
        val token = PlaybackStreamHandoff.put(StreamInfo(ContentKey(0, "video_exp"), "Title"), currentTimeMs = 1000L)
        // Taking at time 1000L + 61_000L (TTL 60s)
        val expired = PlaybackStreamHandoff.take(token, currentTimeMs = 62_000L)
        assertNull(expired)
    }

    @Test
    fun takeConditional_mismatch_removes_token_or_retires_immediately() {
        PlaybackStreamHandoff.clear()
        val info = StreamInfo(ContentKey(0, "video_mismatch"), "Title Mismatch")
        val token = PlaybackStreamHandoff.put(info, requestGen = 10L)
        assertEquals(1, PlaybackStreamHandoff.size())

        // Request with mismatched expected generation (e.g. 5L != 10L)
        val consumed = PlaybackStreamHandoff.takeConditional(token, expectedRequestGen = 5L)
        assertNull(consumed)

        // Token must be cleaned up / removed on mismatch so map size does not leak
        assertEquals(0, PlaybackStreamHandoff.size())
        assertNull(PlaybackStreamHandoff.take(token))
    }
}
