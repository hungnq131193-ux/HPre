package com.flowtube.app.model

enum class SearchFilter {
    ALL,
    VIDEOS,
    CHANNELS,
    PLAYLISTS
}

sealed interface SearchResultItem {
    data class VideoItem(val summary: VideoSummary) : SearchResultItem
    data class ChannelItem(val channel: Channel) : SearchResultItem
    data class PlaylistItem(val playlist: PlaylistSummary) : SearchResultItem
}

data class PlaylistSummary(
    val key: ContentKey,
    val title: String,
    val canonicalUrl: String,
    val channelKey: ContentKey?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val videoCount: Long?
)

data class SearchPage(
    val items: List<SearchResultItem>,
    val nextPageToken: PageToken? = null
)
