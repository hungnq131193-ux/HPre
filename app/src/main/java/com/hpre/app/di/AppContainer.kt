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
import com.hpre.app.repository.ShortsFeedRepository
import com.hpre.app.repository.VideoService
import com.hpre.app.ui.watch.DefaultFullscreenHostHandler
import com.hpre.app.ui.watch.FullscreenHostHandlerFactory
import com.hpre.app.settings.playbackDataStore
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
        get() = RecommendationRepository(catalogRepository, searchHistoryRepository, historyRepository)
    val shortsFeedRepository: ShortsFeedRepository
        get() = ShortsFeedRepository(catalogRepository, searchHistoryRepository, historyRepository)
    val fullscreenHostHandlerFactory: FullscreenHostHandlerFactory
    val okHttpClient: OkHttpClient
    val mediaSourceFactory: MediaSourceFactory
    val playbackPreferences: com.hpre.app.settings.PlaybackPreferences
    val settingsRepository: com.hpre.app.settings.SettingsRepository
    fun createPlayerController(): PlayerController
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
            historyRepository = historyRepository
        )
    }

    override val shortsFeedRepository: ShortsFeedRepository by lazy {
        ShortsFeedRepository(catalogRepository, searchHistoryRepository, historyRepository)
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

    private val sessionPlayerController = AppScopedPlayerControllerProvider {
        val coordinator = StreamRecoveryCoordinator(videoService = videoService)
        SessionPlayerController(
            context = appContext,
            mediaSourceFactory = mediaSourceFactory,
            recoveryCoordinator = coordinator,
            externalScope = applicationScope
        )
    }

    override fun createPlayerController(): PlayerController = sessionPlayerController.get()
}
