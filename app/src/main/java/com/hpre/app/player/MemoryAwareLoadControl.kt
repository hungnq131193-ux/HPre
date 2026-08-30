package com.hpre.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

internal object PlaybackMemoryBudget {
    private const val MIB = 1024 * 1024

    fun targetBytes(memoryClassMb: Int, lowRam: Boolean): Int {
        val budgetMb = if (lowRam) (memoryClassMb / 8).coerceIn(16, 32)
        else (memoryClassMb / 8).coerceIn(32, 64)
        return budgetMb * MIB
    }
}

/** Bounds compressed media buffering, not decoder/surface memory or the entire process heap. */
@OptIn(UnstableApi::class)
internal class MemoryAwareLoadControl(private val maxTargetBytes: Int) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    HPrePlaybackService.MIN_PLAYBACK_BUFFER_MS,
    HPrePlaybackService.MAX_PLAYBACK_BUFFER_MS,
    HPrePlaybackService.BUFFER_FOR_PLAYBACK_MS,
    HPrePlaybackService.BUFFER_AFTER_REBUFFER_MS,
    C.LENGTH_UNSET,
    false,
    0,
    false
) {
    override fun calculateTargetBufferBytes(trackSelectionArray: Array<out ExoTrackSelection?>): Int =
        // Keep Media3's smaller audio-only target; a video budget must not increase audio RAM.
        minOf(super.calculateTargetBufferBytes(trackSelectionArray), maxTargetBytes)
}
