package com.hpre.app.player

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.StreamInfo

object StartupStreamSelector {
    const val DEFAULT_MAX_HEIGHT = 720

    /**
     * Picks the stream to prepare first when opening a video.
     *
     * Progressive/merged playback chooses a usable rendition within [maxHeight] once and keeps it.
     * Explicit [fastStart] callers may request the lowest rendition; neither path schedules a source
     * rebuild just to raise quality. A manual quality choice remains available.
     *
     * Adaptive manifests are still preferred when present: they start low on their own via ABR and the
     * service applies an additional height cap, so no rebuffer is needed to climb.
     */
    fun select(
        info: StreamInfo,
        maxHeight: Int = DEFAULT_MAX_HEIGHT,
        fastStart: Boolean = false
    ): AppResult<SelectedStreams> {
        if (!info.isLive) {
            // Direct streams avoid a manifest round trip and start the lowest supported rendition first.
            lowestStartupStream(info)?.let { return it }
        }

        // Prefer a real adaptive manifest at startup. The height cap is enforced by the service's
        // track selector; progressive streams cannot provide continuous ABR.
        val automatic = StreamSelector.selectStream(info, QualityPreference.Auto)
        val automaticStreams = (automatic as? AppResult.Success)?.value
        if (info.isLive && (automaticStreams?.streamType == PlaybackStreamType.HLS ||
            automaticStreams?.streamType == PlaybackStreamType.DASH
        )) {
            return automatic
        }

        if (fastStart) {
            lowestStartupStream(info)?.let { return it }
        }

        val preferred = StreamSelector.selectStream(
            info,
            QualityPreference.ExactOrBelow(maxHeight)
        )
        val preferredStreams = (preferred as? AppResult.Success)?.value
        if (preferredStreams?.streamType == PlaybackStreamType.PROGRESSIVE) {
            return preferred
        }

        if (automaticStreams?.streamType == PlaybackStreamType.PROGRESSIVE) {
            return automatic
        }

        if (preferredStreams != null) {
            if (preferredStreams.streamType != PlaybackStreamType.AUDIO_ONLY) {
                return preferred
            }
            if (automaticStreams?.videoStream != null) {
                return automatic
            }
            return preferred
        }

        return automatic
    }

    /** Lowest non-adaptive video rendition, prioritizing time to first frame. */
    private fun lowestStartupStream(info: StreamInfo): AppResult<SelectedStreams>? {
        val available = StreamSelector.getAvailableQualities(info)

        for (streamType in listOf(PlaybackStreamType.PROGRESSIVE, PlaybackStreamType.MERGED_AV)) {
            val candidates = available.filter { it.streamType == streamType && it.height > 0 }
            if (candidates.isEmpty()) continue

            val option = candidates.minByOrNull { it.height } ?: continue

            val result = StreamSelector.selectStream(info, QualityPreference.SpecificOption(option))
            if (result is AppResult.Success) return result
        }
        return null
    }
}
