package com.hpre.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PlaybackHistoryScheduler(
    private val scope: CoroutineScope,
    private val intervalMs: Long = HISTORY_WRITE_INTERVAL_MS,
    private val onWrite: () -> Unit
) {
    private var job: Job? = null
    private var wasPlaying = false

    fun update(isPlaying: Boolean) {
        if (!isPlaying) {
            if (wasPlaying) onWrite()
            wasPlaying = false
            cancelJob()
            return
        }
        wasPlaying = true
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                onWrite()
            }
        }
    }

    fun stop() {
        if (wasPlaying) onWrite()
        wasPlaying = false
        cancelJob()
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    companion object {
        const val HISTORY_WRITE_INTERVAL_MS = 30_000L
    }
}
