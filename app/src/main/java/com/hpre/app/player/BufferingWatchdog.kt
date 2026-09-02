package com.hpre.app.player

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class BufferingWatchdog(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val timeoutMs: Long = HPrePlaybackService.BUFFERING_WATCHDOG_TIMEOUT_MS,
    private val onTimeout: (sessionGen: Long, mediaGen: Long) -> Unit
) {
    private var activeJob: Job? = null
    private var activeSessionGen: Long = 0L
    private var activeMediaGen: Long = 0L

    val isArmed: Boolean
        get() = activeJob?.isActive == true

    fun onPrepare(sessionGen: Long, mediaGen: Long) {
        reset()
        activeSessionGen = sessionGen
        activeMediaGen = mediaGen
    }

    fun onPlaybackStateOrRenderChanged(
        playbackState: Int,
        renderedFirstFrameCount: Int,
        streamType: PlaybackStreamType?
    ) {
        if (shouldDisarmBufferingWatchdog(renderedFirstFrameCount, streamType, playbackState)) {
            cancelJob()
            return
        }

        if (playbackState != Player.STATE_BUFFERING) {
            cancelJob()
            return
        }

        if (activeJob == null && activeSessionGen > 0L) {
            val sessionToken = activeSessionGen
            val mediaToken = activeMediaGen
            activeJob = scope.launch(dispatcher) {
                delay(timeoutMs)
                if (activeSessionGen == sessionToken && activeMediaGen == mediaToken) {
                    activeJob = null
                    onTimeout(sessionToken, mediaToken)
                }
            }
        }
    }

    fun reset() {
        cancelJob()
        activeSessionGen = 0L
        activeMediaGen = 0L
    }

    private fun cancelJob() {
        activeJob?.cancel()
        activeJob = null
    }
}
