package com.flowtube.app.model

data class PlaylistDetails(
    val key: ContentKey,
    val title: String,
    val canonicalUrl: String,
    val channelKey: ContentKey?,
    val channelName: String?,
    val channelAvatarUrl: String?,
    val thumbnailUrl: String?,
    val description: String?,
    val videoCount: Long?,
    val videos: List<VideoSummary>,
    val nextPageToken: PageToken? = null
)
