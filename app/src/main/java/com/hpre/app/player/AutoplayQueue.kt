package com.hpre.app.player

import androidx.media3.common.Player
import com.hpre.app.model.ContentKey

internal class AutoplayQueue {
    private var currentKey: ContentKey? = null
    private var candidates: List<ContentKey> = emptyList()
    private val visited = linkedSetOf<ContentKey>()
    private var lastHandledSessionGeneration = Long.MIN_VALUE
    private val manual = ArrayDeque<QueuedItem>()
    private var pendingNext: ContentKey? = null

    var lastTakeWasManual: Boolean = false
        private set

    val manualSnapshot: List<QueuedItem>
        get() = manual.toList()

    fun enqueue(item: QueuedItem, playNext: Boolean): Boolean {
        if (item.key == currentKey) return false
        if (playNext) manual.addFirst(item) else manual.addLast(item)
        return true
    }

    fun removeManual(index: Int): Boolean {
        if (index !in manual.indices) return false
        manual.removeAt(index)
        return true
    }

    fun dropManualThrough(index: Int): Boolean {
        if (index !in manual.indices) return false
        repeat(index + 1) { manual.removeFirst() }
        return true
    }

    fun resetForManualStart(key: ContentKey) {
        currentKey = key
        candidates = emptyList()
        visited.clear()
        visited += key
        lastHandledSessionGeneration = Long.MIN_VALUE
        pendingNext = null
        lastTakeWasManual = false
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
        pendingNext = null
        lastTakeWasManual = false
        // User-queued items run regardless of the autoplay setting; suggestions still honor it.
        val manualNext = manual.removeFirstOrNull()
        if (manualNext != null) {
            pendingNext = manualNext.key
            lastTakeWasManual = true
            return manualNext.key
        }
        if (!allowAdvance) return null
        pendingNext = candidates.firstOrNull()
        return pendingNext
    }

    fun commit(endedKey: ContentKey, next: ContentKey): Boolean {
        if (currentKey != endedKey || next != pendingNext) return false
        pendingNext = null
        candidates = candidates.filter { it != next }
        visited += next
        currentKey = next
        return true
    }

    fun clear() {
        currentKey = null
        candidates = emptyList()
        visited.clear()
        lastHandledSessionGeneration = Long.MIN_VALUE
        manual.clear()
        pendingNext = null
        lastTakeWasManual = false
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
