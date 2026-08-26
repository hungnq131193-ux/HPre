package com.flowtube.app.model

sealed interface CatalogCacheValue {
    data class Trending(val items: List<VideoSummary>) : CatalogCacheValue
    data class Search(val page: SearchPage) : CatalogCacheValue
    data class Details(val details: VideoDetails) : CatalogCacheValue
    data class Custom(val payload: Any) : CatalogCacheValue
}
