package com.hpre.app.player

import com.hpre.app.model.ContentKey

internal data class PendingPrepare(
    val key: ContentKey,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val initialQuality: QualityOption? = null,
    val playbackSpeed: Float = 1.0f
)

internal class PendingSessionCommands {
    private var prepare: PendingPrepare? = null

    @Synchronized
    fun setPrepare(value: PendingPrepare) {
        prepare = value
    }

    @Synchronized
    fun takePrepare(): PendingPrepare? {
        val value = prepare
        prepare = null
        return value
    }

    @Synchronized
    fun clearPrepare() {
        prepare = null
    }
}
