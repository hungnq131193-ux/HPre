package com.hpre.app.ui.common

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.unit.dp
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary

// Width-adaptive feed: portrait phones (~360-410dp) keep the same single-column
// layout; landscape/car head units (600dp+) fan out to 2-4 columns like YouTube.
val AdaptiveVideoGridCells: GridCells = GridCells.Adaptive(minSize = 340.dp)

val fullGridSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

fun LazyGridScope.videoGridItems(
    videos: List<VideoSummary>,
    onClick: (VideoSummary) -> Unit,
    onChannelClick: ((ContentKey) -> Unit)? = null,
    keyPrefix: String = ""
) {
    items(
        items = videos,
        key = { keyPrefix + videoListItemKey(it.key) },
        contentType = { "video" }
    ) { video ->
        VideoCard(
            video = video,
            onClick = { onClick(video) },
            onChannelClick = onChannelClick,
            horizontalPadding = 0.dp
        )
    }
}
