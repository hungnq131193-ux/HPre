package com.flowtube.app.model

data class Channel(
    val key: ContentKey,
    val name: String,
    val canonicalUrl: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val subscriberCountText: String?,
    val description: String?
)

data class ChannelDetails(
    val channel: Channel,
    val videos: List<VideoSummary> = emptyList(),
    val shorts: List<VideoSummary> = emptyList(),
    val nextPageToken: PageToken? = null
)
