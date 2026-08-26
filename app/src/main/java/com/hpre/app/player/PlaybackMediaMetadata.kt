package com.hpre.app.player

import androidx.media3.common.MediaItem
import com.hpre.app.model.ContentKey

internal data class PlaybackMediaMetadata(
    val key: ContentKey,
    val title: String
) {
    companion object {
        fun from(item: MediaItem?): PlaybackMediaMetadata? {
            item ?: return null
            val key = PlaybackMediaId.decode(item.mediaId) ?: return null
            val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: "HPre video"
            return PlaybackMediaMetadata(key, title)
        }
    }
}
