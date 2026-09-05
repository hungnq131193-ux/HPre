package com.hpre.app.player

import androidx.media3.common.Player
import com.hpre.app.model.ContentKey

internal class AutoplayQueue {
    private var currentKey: ContentKey? = null
    private var candidates: List<ContentKey> = emptyList()
    private val visited = linkedSetOf<ContentKey>()
    private var lastHandledSessionGeneration = Long.MIN_VALUE

    fun resetForManualStart(key: ContentKey) {
        currentKey = key
        candidates = emptyList()
        visited.clear()
        visited += key
        lastHandledSessionGeneration = Long.MIN_VALUE
    }

    fun updateCandidates(sourceKey: ContentKey, values: List<ContentKey>): Boolean {
        if (sourceKey != currentKey) return false
        candidates = values.asSequence()
            .filter { it != sourceKey && it !in visited }
            .distinct()
            .toList()
        return true
    }

    fun takeNext(
        endedKey: ContentKey,
        sessionGeneration: Long,
        allowAdvance: Boolean = true
    ): ContentKey? {
        if (endedKey != currentKey || sessionGeneration <= lastHandledSessionGeneration) return null
        lastHandledSessionGeneration = sessionGeneration
        if (!allowAdvance) return null
        val next = candidates.firstOrNull() ?: return null
        candidates = candidates.drop(1)
        visited += next
        currentKey = next
        return next
    }

    fun clear() {
        currentKey = null
        candidates = emptyList()
        visited.clear()
        lastHandledSessionGeneration = Long.MIN_VALUE
    }
}

internal fun shouldStartAutoplay(
    enabled: Boolean,
    lifecycleStarted: Boolean,
    backgroundEnabled: Boolean,
    pipActive: Boolean
): Boolean = enabled && (lifecycleStarted || backgroundEnabled || pipActive)

internal fun shouldHandleAutoplayEnded(
    playbackState: Int,
    eventKey: ContentKey?,
    currentKey: ContentKey?
): Boolean = playbackState == Player.STATE_ENDED && eventKey != null && eventKey == currentKey

internal fun canCommitAutoplay(
    expectedKey: ContentKey,
    currentKey: ContentKey?,
    expectedSessionGeneration: Long,
    currentSessionGeneration: Long,
    expectedRequestGeneration: Long,
    currentRequestGeneration: Long,
    enabled: Boolean,
    lifecycleStarted: Boolean,
    backgroundEnabled: Boolean,
    pipActive: Boolean
): Boolean = expectedKey == currentKey &&
    expectedSessionGeneration == currentSessionGeneration &&
    expectedRequestGeneration == currentRequestGeneration &&
    shouldStartAutoplay(enabled, lifecycleStarted, backgroundEnabled, pipActive)
