package com.hpre.app.repository

import com.hpre.app.model.CatalogCacheValue
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.SearchPage
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCacheTest {

    private fun sampleSummary(id: String) = VideoSummary(
        key = ContentKey(0, id),
        title = "Title $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = "https://example.com/avatar.jpg",
        thumbnailUrl = "https://example.com/thumb.jpg",
        durationSeconds = 120,
        viewCount = 1000,
        publishedTimestamp = 10000L
    )

    private fun sampleDetails(id: String) = VideoDetails(
        key = ContentKey(0, id),
        title = "Details $id",
        canonicalUrl = "https://example.com/watch?v=$id",
        description = "Desc",
        channelKey = ContentKey(0, "c_$id"),
        channelName = "Channel $id",
        channelAvatarUrl = "https://example.com/avatar.jpg",
        subscriberCountText = "1K",
        thumbnailUrl = "https://example.com/thumb.jpg",
        durationSeconds = 120,
        viewCount = 1000,
        likeCount = 10,
        publishedTimestamp = 10000L
    )

    @Test
    fun caches_and_retrieves_entry_within_ttl_allowing_metadata_http_urls() {
        val cache = MetadataCache<String, CatalogCacheValue.Trending>(ttlMs = 1000L, maxEntries = 10)
        val value = CatalogCacheValue.Trending(listOf(sampleSummary("1")))
        cache.put("key1", value, nowMs = 100L)

        val retrieved = cache.get("key1", nowMs = 500L)
        assertEquals(value, retrieved)
    }

    @Test
    fun rejects_direct_or_nested_stream_models() {
        val cache = MetadataCache<String, CatalogCacheValue.Trending>(ttlMs = 1000L, maxEntries = 10)

        // Reflection test on typed catalog graph prohibiting stream objects:
        // We verify that validateSafeValue rejects any stream models.
        val validateMethod = MetadataCache::class.java.getDeclaredMethod("validateSafeValue", Any::class.java, java.util.IdentityHashMap::class.java, Int::class.javaPrimitiveType)
        validateMethod.isAccessible = true

        val streamInfo = com.hpre.app.model.StreamInfo(
            key = ContentKey(0, "test"),
            title = "Test"
        )

        try {
            validateMethod.invoke(cache, streamInfo, java.util.IdentityHashMap<Any, Boolean>(), 0)
            org.junit.Assert.fail("Expected IllegalArgumentException for StreamInfo inside validation graph")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val target = e.targetException
            assertTrue(target is IllegalArgumentException)
            assertTrue(target.message?.contains("Stream") == true || target.message?.contains("prohibited") == true)
        }

        val videoStream = com.hpre.app.model.VideoStream(
            url = "https://example.com/video.mp4",
            format = "mp4",
            resolution = "720p",
            width = 1280,
            height = 720,
            bitrate = 1000000L
        )
        try {
            validateMethod.invoke(cache, videoStream, java.util.IdentityHashMap<Any, Boolean>(), 0)
            org.junit.Assert.fail("Expected IllegalArgumentException for VideoStream inside validation graph")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val target = e.targetException
            assertTrue(target is IllegalArgumentException)
            assertTrue(target.message?.contains("Stream") == true || target.message?.contains("prohibited") == true)
        }
    }

    private data class FakeForbiddenStream(val dummy: String = "stream")
    private class FakeDashManifest(val dashUrl: String = "https://example.com/manifest.mpd")
    private class FakeHlsManifest(val hlsUrl: String = "https://example.com/manifest.m3u8")

    private data class ContainerWithNestedForbidden(
        val name: String,
        val nested: Any
    )

    private data class DeepWrapper(val child: Any?)

    @Test
    fun cache_put_rejects_object_exceeding_max_depth_policy_with_depth_message_and_rejects_forbidden_at_normal_depth() {
        // 1. Build a graph deeper than limit with an identifiable forbidden VideoStream/StreamInfo node
        val stream = com.hpre.app.model.VideoStream(
            url = "https://example.com/video.mp4",
            format = "mp4",
            resolution = "1080p",
            width = 1920,
            height = 1080,
            bitrate = 2000000L
        )
        var deepGraph: Any = stream
        for (i in 0..12) {
            deepGraph = DeepWrapper(deepGraph)
        }
        val deepWrapper = CatalogCacheValue.Custom(deepGraph)
        val cache = MetadataCache<String, CatalogCacheValue>(ttlMs = 1000L, maxEntries = 10)

        // Assert exception message/category indicates depth policy first
        try {
            cache.put("key_deep", deepWrapper)
            org.junit.Assert.fail("Expected IllegalArgumentException for deep graph exceeding max allowable inspection depth")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message must indicate depth policy: ${e.message}",
                e.message?.contains("maximum allowable inspection depth") == true ||
                e.message?.contains("depth") == true
            )
        }

        // 2. Separately test direct/nested forbidden stream at normal depth (e.g. depth 2)
        // to prove forbidden stream detection works independently of depth limit
        val normalDepthWrapper = CatalogCacheValue.Custom(ContainerWithNestedForbidden("wrapper", stream))
        try {
            cache.put("key_forbidden_normal_depth", normalDepthWrapper)
            org.junit.Assert.fail("Expected IllegalArgumentException for forbidden stream at normal depth")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message must indicate prohibited stream objects: ${e.message}",
                e.message?.contains("Stream") == true || e.message?.contains("prohibited") == true
            )
            assertFalse(
                "Exception message should not be a depth limit violation at normal depth",
                e.message?.contains("maximum allowable inspection depth") == true
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cache_put_rejects_innocent_object_exceeding_max_depth_policy() {
        var current: Any = "leaf"
        for (i in 0..12) {
            current = DeepWrapper(current)
        }
        val wrapper = CatalogCacheValue.Custom(current)
        val cache = MetadataCache<String, CatalogCacheValue>(ttlMs = 1000L, maxEntries = 10)
        cache.put("key_deep_safe", wrapper)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cache_put_rejects_nested_video_stream_inside_custom_catalog_wrapper() {
        val stream = com.hpre.app.model.VideoStream(
            url = "https://example.com/video.mp4",
            format = "mp4",
            resolution = "1080p",
            width = 1920,
            height = 1080,
            bitrate = 2000000L
        )
        val wrapper = CatalogCacheValue.Custom(ContainerWithNestedForbidden("wrapper", stream))
        val cache = MetadataCache<String, CatalogCacheValue>(ttlMs = 1000L, maxEntries = 10)
        cache.put("key_stream", wrapper)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cache_put_rejects_nested_stream_info_inside_collection_in_wrapper() {
        val streamInfo = com.hpre.app.model.StreamInfo(
            key = ContentKey(0, "test"),
            title = "Test"
        )
        val wrapper = CatalogCacheValue.Custom(mapOf("streams" to listOf(streamInfo)))
        val cache = MetadataCache<String, CatalogCacheValue>(ttlMs = 1000L, maxEntries = 10)
        cache.put("key_stream_info_list", wrapper)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cache_put_rejects_nested_hls_stream_object() {
        val wrapper = CatalogCacheValue.Custom(FakeHlsManifest())
        val cache = MetadataCache<String, CatalogCacheValue>(ttlMs = 1000L, maxEntries = 10)
        cache.put("key_hls", wrapper)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cache_put_rejects_nested_dash_stream_object() {
        val wrapper = CatalogCacheValue.Custom(mapOf("dash" to FakeDashManifest()))
        val cache = MetadataCache<String, CatalogCacheValue>(ttlMs = 1000L, maxEntries = 10)
        cache.put("key_dash", wrapper)
    }

    @Test
    fun ttl_expires_at_exact_boundary() {
        val cache = MetadataCache<String, CatalogCacheValue.Details>(ttlMs = 1000L, maxEntries = 10)
        val v1 = CatalogCacheValue.Details(sampleDetails("1"))
        cache.put("key1", v1, nowMs = 1000L)

        // At exactly nowMs - timestampMs == ttlMs (2000 - 1000 == 1000), it must expire (>= boundary)
        val atBoundary = cache.get("key1", nowMs = 2000L)
        assertNull("Entry must expire when elapsed time equals TTL (>= boundary)", atBoundary)

        // Before boundary (1999 - 1000 = 999 < 1000), it must still be valid
        val v2 = CatalogCacheValue.Details(sampleDetails("2"))
        cache.put("key2", v2, nowMs = 1000L)
        val beforeBoundary = cache.get("key2", nowMs = 1999L)
        assertEquals(v2, beforeBoundary)
    }

    @Test
    fun evicts_oldest_entry_when_max_size_exceeded() {
        val cache = MetadataCache<String, CatalogCacheValue.Trending>(ttlMs = 10000L, maxEntries = 2)
        val v1 = CatalogCacheValue.Trending(listOf(sampleSummary("1")))
        val v2 = CatalogCacheValue.Trending(listOf(sampleSummary("2")))
        val v3 = CatalogCacheValue.Trending(listOf(sampleSummary("3")))

        cache.put("key1", v1, nowMs = 100L)
        cache.put("key2", v2, nowMs = 200L)
        cache.put("key3", v3, nowMs = 300L)

        assertNull(cache.get("key1", nowMs = 400L))
        assertEquals(v2, cache.get("key2", nowMs = 400L))
        assertEquals(v3, cache.get("key3", nowMs = 400L))
    }

    @Test
    fun remove_and_clear_behave_correctly() {
        val cache = MetadataCache<String, CatalogCacheValue.Search>(ttlMs = 10000L, maxEntries = 10)
        val v1 = CatalogCacheValue.Search(SearchPage(emptyList(), null))
        val v2 = CatalogCacheValue.Search(SearchPage(emptyList(), PageToken.Id("next")))

        cache.put("key1", v1, nowMs = 100L)
        cache.put("key2", v2, nowMs = 100L)

        cache.remove("key1")
        assertNull(cache.get("key1", nowMs = 150L))
        assertEquals(v2, cache.get("key2", nowMs = 150L))

        cache.clear()
        assertNull(cache.get("key2", nowMs = 200L))
    }
}

