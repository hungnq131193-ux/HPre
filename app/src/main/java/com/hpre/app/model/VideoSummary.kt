package com.hpre.app.model

data class VideoSummary(
    val key: ContentKey,
    val title: String,
    val canonicalUrl: String,
    val channelKey: ContentKey?,
    val channelName: String?,
    val channelAvatarUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?,
    val publishedTimestamp: Long?,
    val isLive: Boolean = false,
    val isShort: Boolean = false
)
