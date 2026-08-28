package com.hpre.app.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hpre.app.database.HPreDatabase
import com.hpre.app.repository.DefaultHistoryRepository
import com.hpre.app.repository.DefaultPlaylistRepository
import com.hpre.app.repository.DefaultSearchHistoryRepository
import com.hpre.app.repository.DefaultSubscriptionRepository
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.repository.SearchHistoryRepository
import com.hpre.app.repository.SubscriptionRepository
import com.hpre.app.extractor.NewPipeVideoService
import com.hpre.app.extractor.OkHttpDownloader
import com.hpre.app.player.MediaSourceFactory
import com.hpre.app.player.PlayerController
import com.hpre.app.player.PlayerHttpConfig
import com.hpre.app.player.SessionPlayerController
import com.hpre.app.player.AppScopedPlayerControllerProvider
import com.hpre.app.player.StreamRecoveryCoordinator
import com.hpre.app.repository.CatalogRepository
import com.hpre.app.repository.RecommendationRepository
import com.hpre.app.repository.VideoService
import com.hpre.app.repository.WatchStateCache
import com.hpre.app.ui.watch.DefaultFullscreenHostHandler
import com.hpre.app.ui.watch.FullscreenHostHandlerFactory
import com.hpre.app.settings.playbackDataStore
import com.hpre.app.ui.home.CatalogTopicFeedSource
import com.hpre.app.ui.home.TopicFeedSource
import com.hpre.app.update.AppUpdateChecker
import com.hpre.app.update.GitHubReleaseUpdateChecker
import com.hpre.app.update.UpdateCheckResult
import com.hpre.app.update.UpdateUnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Dependency injection container for application-level components.
 */
interface AppContainer {
    val applicationScope: CoroutineScope
    val database: HPreDatabase
    val historyRepository: HistoryRepository
    val subscriptionRepository: SubscriptionRepository
    val playlistRepository: PlaylistRepository
    val searchHistoryRepository: SearchHistoryRepository
    val videoService: VideoService
    val catalogRepository: CatalogRepository
    val recommendationRepository: RecommendationRepository
        get() = RecommendationRepository(
            catalogRepository,
            searchHistoryRepository,
            historyRepository,
            videoService,
            playbackPreferences
        )
    val topicFeedSource: TopicFeedSource
        get() = CatalogTopicFeedSource(catalogRepository)
    val fullscreenHostHandlerFactory: FullscreenHostHandlerFactory
    val okHttpClient: OkHttpClient
    val mediaSourceFactory: MediaSourceFactory
    val playbackPreferences: com.hpre.app.settings.PlaybackPreferences
    val settingsRepository: com.hpre.app.settings.SettingsRepository
    val watchStateCache: WatchStateCache
    val appUpdateChecker: AppUpdateChecker
        get() = AppUpdateChecker {
            UpdateCheckResult.Unavailable(UpdateUnavailableReason.NETWORK)
        }
    fun createPlayerController(): PlayerController
    fun updatePlayerLifecyclePolicy(
        backgroundEnabled: Boolean,
        pipActiveOrEntering: Boolean
    ) = Unit
}

/**
 * Internal factory interface used to construct the AppContainer.
 */
internal fun interface ApplicationContainerFactory {
    fun createContainer(application: Application): AppContainer
}

class DefaultAppContainer(
    context: Context,
    override val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    override val okHttpClient: OkHttpClient = OkHttpDownloader.defaultClient()
) : AppContainer {
    private val appContext = context.applicationContext

    override val database: HPreDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            HPreDatabase::class.java,
            "hpre.db"
        ).build()
    }

    override val historyRepository: HistoryRepository by lazy {
        DefaultHistoryRepository(
            historyDao = database.historyDao(),
            playbackPreferences = playbackPreferences
        )
    }

    override val subscriptionRepository: SubscriptionRepository by lazy {
        DefaultSubscriptionRepository(
            subscriptionDao = database.subscriptionDao()
        )
    }

    override val playlistRepository: PlaylistRepository by lazy {
        DefaultPlaylistRepository(
            playlistDao = database.playlistDao()
        )
    }

    override val searchHistoryRepository: SearchHistoryRepository by lazy {
        DefaultSearchHistoryRepository(
            searchHistoryDao = database.searchHistoryDao()
        )
    }

    override val mediaSourceFactory: MediaSourceFactory by lazy {
        MediaSourceFactory(
            context = appContext,
            okHttpClient = okHttpClient,
            httpConfig = PlayerHttpConfig()
        )
    }

    override val videoService: VideoService by lazy {
        NewPipeVideoService()
    }

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

    override val fullscreenHostHandlerFactory: FullscreenHostHandlerFactory =
        FullscreenHostHandlerFactory { activity, savedStateHandle ->
            DefaultFullscreenHostHandler(activity, savedStateHandle)
        }

    override val playbackPreferences: com.hpre.app.settings.PlaybackPreferences by lazy {
        settingsRepository
    }

    override val settingsRepository: com.hpre.app.settings.SettingsRepository by lazy {
        com.hpre.app.settings.DataStoreSettingsRepository(appContext.playbackDataStore)
    }

    override val watchStateCache: WatchStateCache by lazy {
        WatchStateCache(ttlMs = 300_000L, maxEntries = 10)
    }

    override val appUpdateChecker: AppUpdateChecker by lazy {
        GitHubReleaseUpdateChecker(okHttpClient)
    }

    @Volatile
    private var backgroundPlaybackEnabled = true
    @Volatile
    private var pipActiveOrEntering = false

    private val sessionPlayerController = AppScopedPlayerControllerProvider {
        val coordinator = StreamRecoveryCoordinator(videoService = videoService)
        SessionPlayerController(
            context = appContext,
            mediaSourceFactory = mediaSourceFactory,
            recoveryCoordinator = coordinator,
            externalScope = applicationScope
        ).also { controller ->
            controller.updateLifecyclePolicy(
                backgroundEnabled = backgroundPlaybackEnabled,
                pipActiveOrEntering = pipActiveOrEntering
            )
        }
    }

    override fun createPlayerController(): PlayerController = sessionPlayerController.get()

    override fun updatePlayerLifecyclePolicy(
        backgroundEnabled: Boolean,
        pipActiveOrEntering: Boolean
    ) {
        backgroundPlaybackEnabled = backgroundEnabled
        this.pipActiveOrEntering = pipActiveOrEntering
        sessionPlayerController.getIfInitialized()?.updateLifecyclePolicy(
            backgroundEnabled = backgroundEnabled,
            pipActiveOrEntering = pipActiveOrEntering
        )
    }
}
