package com.flowtube.app.model

data class VideoDetails(
    val key: ContentKey,
    val title: String,
    val canonicalUrl: String,
    val description: String?,
    val channelKey: ContentKey?,
    val channelName: String?,
    val channelAvatarUrl: String?,
    val subscriberCountText: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?,
    val likeCount: Long?,
    val publishedTimestamp: Long?,
    val isLive: Boolean = false,
    val isShort: Boolean = false
)
