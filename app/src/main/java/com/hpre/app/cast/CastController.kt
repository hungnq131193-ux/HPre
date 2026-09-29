package com.hpre.app.cast

import android.content.Context
import com.hpre.app.core.error.AppResult
import com.hpre.app.download.DownloadPlan
import com.hpre.app.model.ContentKey
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns FCast discovery and the active cast session. Receivers fetch the media URL themselves, so
 * casting requires a single muxed (progressive) stream or an audio stream — split A/V and generated
 * DASH manifests cannot be handed to a receiver.
 */
class CastController(
    context: Context,
    private val videoService: VideoService?,
    private val scope: CoroutineScope
) {
    private val discovery = FCastDiscovery(context.applicationContext)
    val devices: StateFlow<List<FCastDevice>> = discovery.devices

    private val _activeDevice = MutableStateFlow<FCastDevice?>(null)
    val activeDevice: StateFlow<FCastDevice?> = _activeDevice.asStateFlow()

    /** One-shot error surfaced to the UI; consumed by [consumeError]. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var client: FCastClient? = null
    private var opJob: Job? = null

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    /** Resolve [key]'s best single-stream URL and start playback on [device]. */
    fun playOn(device: FCastDevice, key: ContentKey, startSeconds: Double?, audioOnly: Boolean = false) {
        opJob?.cancel()
        opJob = scope.launch {
            val urlAndMime = withContext(Dispatchers.IO) {
                val info = (videoService?.streamInfo(key) as? AppResult.Success)?.value
                    ?: return@withContext null
                val request = DownloadPlan.select(info, audioOnly)?.singleOrNull()
                    ?: return@withContext null
                request.url to if (request.isVideo) "video/mp4" else "audio/mp4"
            } ?: run {
                _error.value = "cast_no_playable_stream"
                return@launch
            }
            try {
                val c = withContext(Dispatchers.IO) {
                    FCastClient(device.host, device.port).also {
                        it.connect()
                        it.play(urlAndMime.second, urlAndMime.first, startSeconds, null)
                    }
                }
                disconnectInternal()
                client = c
                _activeDevice.value = device
            } catch (_: Throwable) {
                _error.value = "cast_connect_failed"
            }
        }
    }

    fun pause() = send { it.pause() }
    fun resume() = send { it.resume() }
    fun seek(seconds: Double) = send { it.seek(seconds) }

    fun disconnect() {
        opJob?.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { client?.stop() }
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        runCatching { client?.close() }
        client = null
        _activeDevice.value = null
    }

    private fun send(op: (FCastClient) -> Unit) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { op(c) }.onFailure {
                client = null
                _activeDevice.value = null
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }
}
