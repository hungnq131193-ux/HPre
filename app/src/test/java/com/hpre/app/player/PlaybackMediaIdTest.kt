package com.hpre.app.player

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMediaIdTest {
    @Test
    fun stable_content_key_round_trips_without_exposing_a_url() {
        val key = ContentKey(7, "native_id-with_symbols")

        val encoded = PlaybackMediaId.encode(key)

        assertEquals(key, PlaybackMediaId.decode(encoded))
        assertEquals(false, encoded.contains("http", ignoreCase = true))
    }

    @Test
    fun malformed_media_id_is_rejected() {
        assertNull(PlaybackMediaId.decode(""))
        assertNull(PlaybackMediaId.decode("not-a-HPre-media-id"))
        assertNull(PlaybackMediaId.decode("HPre:not-an-int:value"))
    }
}
