package com.hpre.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMediaMetadataTest {
    @Test
    fun media_item_maps_to_safe_mini_player_identity_and_title() {
        val key = ContentKey(3, "native-video")
        val item = MediaItem.Builder()
            .setMediaId(PlaybackMediaId.encode(key))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("A video title").build())
            .build()

        val mapped = PlaybackMediaMetadata.from(item)

        assertEquals(key, mapped?.key)
        assertEquals("A video title", mapped?.title)
    }

    @Test
    fun malformed_media_item_does_not_create_active_identity() {
        val item = MediaItem.Builder().setMediaId("malformed").build()

        assertNull(PlaybackMediaMetadata.from(item))
    }

    @Test
    fun blank_or_missing_title_defaults_to_safe_title() {
        val key = ContentKey(0, "default_title_test")
        val itemNoMeta = MediaItem.Builder()
            .setMediaId(PlaybackMediaId.encode(key))
            .build()
        val mappedNoMeta = PlaybackMediaMetadata.from(itemNoMeta)
        assertEquals(key, mappedNoMeta?.key)
        assertEquals("HPre video", mappedNoMeta?.title)

        val itemBlankTitle = MediaItem.Builder()
            .setMediaId(PlaybackMediaId.encode(key))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("   ").build())
            .build()
        val mappedBlankTitle = PlaybackMediaMetadata.from(itemBlankTitle)
        assertEquals("HPre video", mappedBlankTitle?.title)
    }
}

