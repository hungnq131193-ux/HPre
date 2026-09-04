package com.hpre.app.player

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.StreamInfo

object StartupStreamSelector {
    const val DEFAULT_MAX_HEIGHT = 720

    /**
     * Picks the stream to prepare first when opening a video.
     *
     * VOD starts from a progressive stream with audio when possible, avoiding manifest parsing and a
     * second media request. Live playback keeps adaptive manifests first. Manual quality selection can
     * still switch to merged A/V, HLS, or DASH after startup.
     */
    fun select(
        info: StreamInfo,
        maxHeight: Int = DEFAULT_MAX_HEIGHT,
        fastStart: Boolean = false
    ): AppResult<SelectedStreams> {
        if (info.isLive) {
            return StreamSelector.selectStream(info, QualityPreference.Auto)
        }

        if (fastStart) {
            lowestStartupStream(info)?.let { return it }
        }

        preferredProgressiveStream(info, maxHeight)?.let { return it }
        return StreamSelector.selectStream(info, QualityPreference.ExactOrBelow(maxHeight))
    }

    private fun preferredProgressiveStream(
        info: StreamInfo,
        maxHeight: Int
    ): AppResult<SelectedStreams>? {
        val candidates = StreamSelector.getAvailableQualities(info)
            .filter { it.streamType == PlaybackStreamType.PROGRESSIVE && it.height > 0 }
        val option = candidates.filter { it.height <= maxHeight }.maxByOrNull { it.height }
            ?: candidates.minByOrNull { it.height }
            ?: return null
        return StreamSelector.selectStream(info, QualityPreference.SpecificOption(option))
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
