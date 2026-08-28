package com.hpre.app.player

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.StreamInfo

object StartupStreamSelector {
    const val DEFAULT_MAX_HEIGHT = 720

    /**
     * Picks the stream to prepare first when opening a video.
     *
     * With [fastStart] enabled (the default for a fresh open) this deliberately chooses the *lowest*
     * usable rendition rather than the best one, so the first frame arrives quickly and the watch page
     * can render recommendations and comments without waiting on a large initial buffer.
     * [StartupQualityPolicy] then raises quality once playback is running.
     *
     * Adaptive manifests are still preferred when present: they start low on their own via ABR and the
     * service applies an additional height cap, so no rebuffer is needed to climb.
     */
    fun select(
        info: StreamInfo,
        maxHeight: Int = DEFAULT_MAX_HEIGHT,
        fastStart: Boolean = true
    ): AppResult<SelectedStreams> {
        // Prefer a real adaptive manifest at startup. The height cap is enforced by the service's
        // track selector; progressive streams cannot provide continuous ABR.
        val automatic = StreamSelector.selectStream(info, QualityPreference.Auto)
        val automaticStreams = (automatic as? AppResult.Success)?.value
        if (automaticStreams?.streamType == PlaybackStreamType.HLS ||
            automaticStreams?.streamType == PlaybackStreamType.DASH
        ) {
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

    /**
     * Lowest progressive rendition (falling back to merged video+audio) at or above
     * [StartupQualityPolicy.FAST_START_MIN_HEIGHT]. When every rendition sits below that floor, the
     * best available one is used instead so quality never drops below what the source offers.
     *
     * Returns null when no non-adaptive video rendition can be resolved, letting the caller fall
     * through to the regular cascade.
     */
    private fun lowestStartupStream(info: StreamInfo): AppResult<SelectedStreams>? {
        val available = StreamSelector.getAvailableQualities(info)

        for (streamType in listOf(PlaybackStreamType.PROGRESSIVE, PlaybackStreamType.MERGED_AV)) {
            val candidates = available.filter { it.streamType == streamType && it.height > 0 }
            if (candidates.isEmpty()) continue

            val option = candidates
                .filter { it.height >= StartupQualityPolicy.FAST_START_MIN_HEIGHT }
                .minByOrNull { it.height }
                ?: candidates.maxByOrNull { it.height }
                ?: continue

            val result = StreamSelector.selectStream(info, QualityPreference.SpecificOption(option))
            if (result is AppResult.Success) return result
        }
        return null
    }
}
