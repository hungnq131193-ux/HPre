package com.hpre.app.benchmark

import android.content.Context
import com.hpre.app.HPreApplication
import com.hpre.app.di.AppContainer
import com.hpre.app.di.DefaultAppContainer
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.RecommendationRepository
import com.hpre.app.repository.VideoService
import com.hpre.app.ui.home.CatalogTopicFeedSource
import com.hpre.app.ui.home.TopicFeedSource
import com.hpre.app.update.AppUpdateManager

class BenchmarkHPreApplication : HPreApplication() {
    internal override fun createContainer(): AppContainer = BenchmarkAppContainer(this)
}

private class BenchmarkAppContainer(context: Context) : AppContainer by DefaultAppContainer(context) {
    override val videoService: VideoService = BenchmarkVideoService()

    override val catalogRepository: CatalogRepository by lazy {
        CatalogRepository(
            videoService = videoService,
            repositoryScope = applicationScope
        )
    }

    override val recommendationRepository: RecommendationRepository by lazy {
        RecommendationRepository(
            catalogRepository = catalogRepository,
            searchHistoryRepository = searchHistoryRepository,
            historyRepository = historyRepository,
            videoService = videoService,
            playbackPreferences = playbackPreferences
        )
    }

    override val topicFeedSource: TopicFeedSource by lazy {
        CatalogTopicFeedSource(catalogRepository)
    }

    override val appUpdateManager: AppUpdateManager? = null
}
