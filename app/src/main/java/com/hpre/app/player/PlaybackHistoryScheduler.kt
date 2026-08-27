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

    fun update(isPlaying: Boolean) {
        if (!isPlaying) {
            cancelJob()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                onWrite()
            }
        }
    }

    fun stop() = cancelJob()

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    companion object {
        const val HISTORY_WRITE_INTERVAL_MS = 10_000L
    }
}
