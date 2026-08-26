package com.hpre.app.ui.home

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.CatalogRepository

fun interface TopicFeedSource {
    suspend fun videos(query: String, forceRefresh: Boolean): AppResult<List<VideoSummary>>
}

class CatalogTopicFeedSource(
    private val catalogRepository: CatalogRepository
) : TopicFeedSource {
    override suspend fun videos(
        query: String,
        forceRefresh: Boolean
    ): AppResult<List<VideoSummary>> = when (
        val result = catalogRepository.search(
            query = query,
            filter = SearchFilter.VIDEOS,
            forceRefresh = forceRefresh
        )
    ) {
        is AppResult.Success -> AppResult.Success(
            result.value.items.mapNotNull { item ->
                (item as? SearchResultItem.VideoItem)?.summary
            }
        )
        is AppResult.Failure -> result
    }
}
