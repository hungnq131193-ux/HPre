package com.hpre.app.player

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.StreamInfo

object StartupStreamSelector {
    const val DEFAULT_MAX_HEIGHT = 720

    fun select(
        info: StreamInfo,
        maxHeight: Int = DEFAULT_MAX_HEIGHT
    ): AppResult<SelectedStreams> {
        // Prefer a real adaptive manifest at startup. The maxHeight is enforced by the service's
        // track selector; progressive streams cannot provide continuous ABR.
        val automatic = StreamSelector.selectStream(info, QualityPreference.Auto)
        val automaticStreams = (automatic as? AppResult.Success)?.value
        if (automaticStreams?.streamType == PlaybackStreamType.HLS ||
            automaticStreams?.streamType == PlaybackStreamType.DASH
        ) {
            return automatic
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
}
