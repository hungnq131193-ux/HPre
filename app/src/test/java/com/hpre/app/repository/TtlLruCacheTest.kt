package com.hpre.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TtlLruCacheTest {

    private fun cache(ttlMs: Long = 1_000L, maxEntries: Int = 3) =
        TtlLruCache<String, String>(ttlMs = ttlMs, maxEntries = maxEntries)

    @Test
    fun get_returns_stored_value_before_ttl_expires() {
        val cache = cache()
        cache.put("k", "v", nowMs = 0L)

        assertEquals("v", cache.get("k", nowMs = 999L))
    }

    @Test
    fun get_returns_null_and_evicts_once_ttl_elapsed() {
        val cache = cache()
        cache.put("k", "v", nowMs = 0L)

        assertNull(cache.get("k", nowMs = 1_000L))
        assertEquals("expired entry must be dropped", 0, cache.size)
    }

    @Test
    fun get_returns_null_for_absent_key() {
        assertNull(cache().get("missing"))
    }

    /**
     * The stale-while-revalidate path: content stays available past its TTL so the UI can render it
     * immediately, with [TtlLruCache.StaleEntry.isStale] telling the caller to refresh underneath.
     */
    @Test
    fun getStale_returns_expired_value_flagged_as_stale_without_evicting() {
        val cache = cache()
        cache.put("k", "v", nowMs = 0L)

        val fresh = cache.getStale("k", nowMs = 500L)
        assertNotNull(fresh)
        assertEquals("v", fresh!!.value)
        assertFalse(fresh.isStale)

        val stale = cache.getStale("k", nowMs = 5_000L)
        assertNotNull(stale)
        assertEquals("v", stale!!.value)
        assertTrue(stale.isStale)
        assertEquals("getStale must not evict, so content stays renderable", 1, cache.size)
    }

    @Test
    fun getStale_returns_null_for_absent_key() {
        assertNull(cache().getStale("missing"))
    }

    @Test
    fun put_overwrites_value_and_resets_expiry() {
        val cache = cache()
        cache.put("k", "old", nowMs = 0L)
        cache.put("k", "new", nowMs = 900L)

        assertEquals("new", cache.get("k", nowMs = 1_500L))
        assertEquals(1, cache.size)
    }

    @Test
    fun exceeding_maxEntries_evicts_the_least_recently_used_entry() {
        val cache = cache(maxEntries = 3)
        cache.put("a", "1", nowMs = 0L)
        cache.put("b", "2", nowMs = 0L)
        cache.put("c", "3", nowMs = 0L)

        cache.put("d", "4", nowMs = 0L)

        assertEquals(3, cache.size)
        assertNull("oldest untouched entry must be evicted", cache.get("a", nowMs = 0L))
        assertEquals("2", cache.get("b", nowMs = 0L))
        assertEquals("4", cache.get("d", nowMs = 0L))
    }

    /** Reading an entry marks it as recently used, protecting it from the next eviction. */
    @Test
    fun reading_an_entry_protects_it_from_eviction() {
        val cache = cache(maxEntries = 3)
        cache.put("a", "1", nowMs = 0L)
        cache.put("b", "2", nowMs = 0L)
        cache.put("c", "3", nowMs = 0L)

        cache.get("a", nowMs = 0L)
        cache.put("d", "4", nowMs = 0L)

        assertEquals("recently read entry must survive", "1", cache.get("a", nowMs = 0L))
        assertNull("least recently used entry is evicted instead", cache.get("b", nowMs = 0L))
    }

    @Test
    fun remove_and_clear_drop_entries() {
        val cache = cache()
        cache.put("a", "1", nowMs = 0L)
        cache.put("b", "2", nowMs = 0L)

        cache.remove("a")
        assertNull(cache.get("a", nowMs = 0L))
        assertEquals(1, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("b", nowMs = 0L))
    }
}
