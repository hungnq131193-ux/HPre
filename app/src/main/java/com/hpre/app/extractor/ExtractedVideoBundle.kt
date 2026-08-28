package com.hpre.app.extractor

import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary

internal data class ExtractedVideoBundle(
    val details: VideoDetails,
    val streamInfo: StreamInfo,
    val related: List<VideoSummary>
)
