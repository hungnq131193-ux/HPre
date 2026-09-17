package com.hpre.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.StreamInfo
import com.hpre.app.settings.AppSettings
import com.hpre.app.settings.QualityPreferenceSetting

internal fun interface WifiConnectionProvider {
    fun isWifi(): Boolean
}

internal class AndroidWifiConnectionProvider(context: Context) : WifiConnectionProvider {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isWifi(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}

internal data class PlaybackStartDefaults(
    val initialQuality: QualityOption?,
    val qualityPolicy: UserQualityPolicy,
    val playbackSpeed: Float
)

internal object PlaybackSettingsPolicy {
    fun resolve(settings: AppSettings, isWifi: Boolean, info: StreamInfo): PlaybackStartDefaults {
        val preference = if (isWifi) settings.wifiQuality else settings.mobileQuality
        val maxHeight = preference.maxResolution
        val quality = if (maxHeight == null) {
            (StartupStreamSelector.select(info) as? AppResult.Success)?.value
                ?.let { selected -> findOption(selected, StreamSelector.getAvailableQualities(info)) }
        } else {
            selectWithCeiling(info, maxHeight)
        }
        return PlaybackStartDefaults(
            initialQuality = quality,
            qualityPolicy = UserQualityPolicy.Auto(maxHeight = maxHeight),
            playbackSpeed = normalizePlaybackSpeed(settings.defaultPlaybackSpeed)
        )
    }

    private fun selectWithCeiling(info: StreamInfo, maxHeight: Int): QualityOption? {
        val available = StreamSelector.getAvailableQualities(info)
        if (info.isLive) {
            return available.firstOrNull { it.streamType == PlaybackStreamType.HLS }
                ?: available.firstOrNull { it.streamType == PlaybackStreamType.DASH }
        }
        for (type in listOf(PlaybackStreamType.PROGRESSIVE, PlaybackStreamType.MERGED_AV)) {
            val options = available.filter { it.streamType == type && it.height > 0 }
            if (options.isNotEmpty()) {
                return options.filter { it.height <= maxHeight }.maxByOrNull { it.height }
                    ?: options.minByOrNull { it.height }
            }
        }
        return available.firstOrNull { it.streamType == PlaybackStreamType.HLS }
            ?: available.firstOrNull { it.streamType == PlaybackStreamType.DASH }
    }

    private fun findOption(selected: SelectedStreams, available: List<QualityOption>): QualityOption? =
        when (selected.streamType) {
            PlaybackStreamType.HLS,
            PlaybackStreamType.DASH -> available.firstOrNull { it.streamType == selected.streamType }
            PlaybackStreamType.PROGRESSIVE,
            PlaybackStreamType.MERGED_AV -> available.firstOrNull {
                it.streamType == selected.streamType && it.height == selected.videoStream?.height
            }
            PlaybackStreamType.AUDIO_ONLY -> null
        }
}

internal fun normalizePlaybackSpeed(speed: Float): Float =
    speed.takeIf(Float::isFinite)?.coerceIn(0.25f, 3.0f) ?: 1.0f

internal fun resolvePrepareDefaults(
    isNewSession: Boolean,
    requestedQuality: QualityOption?,
    current: PlaybackState,
    defaults: PlaybackStartDefaults
): PlaybackStartDefaults {
    if (isNewSession) {
        return if (requestedQuality == null) defaults else defaults.copy(
            initialQuality = requestedQuality,
            qualityPolicy = UserQualityPolicy.Fixed(requestedQuality)
        )
    }
    return PlaybackStartDefaults(
        initialQuality = requestedQuality ?: current.selectedQuality,
        qualityPolicy = current.qualityPolicy,
        playbackSpeed = normalizePlaybackSpeed(current.playbackSpeed)
    )
}
