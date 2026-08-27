package com.hpre.app.ui.home

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.RecommendationRequest

fun interface TopicFeedSource {
    suspend fun videos(
        query: String,
        request: RecommendationRequest
    ): AppResult<List<VideoSummary>>
}

class CatalogTopicFeedSource(
    private val catalogRepository: CatalogRepository
) : TopicFeedSource {
    override suspend fun videos(
        query: String,
        request: RecommendationRequest
    ): AppResult<List<VideoSummary>> = when (
        val result = catalogRepository.search(
            query = query,
            filter = SearchFilter.VIDEOS,
            forceRefresh = request.forceRefresh
        )
    ) {
        is AppResult.Success -> {
            val unexcluded = result.value.items
                .mapNotNull { item -> (item as? SearchResultItem.VideoItem)?.summary }
                .filter { it.key !in request.excludedKeys }
            AppResult.Success(unexcluded)
        }
        is AppResult.Failure -> result
    }
}
