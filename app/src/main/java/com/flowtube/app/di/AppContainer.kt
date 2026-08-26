package com.flowtube.app.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.flowtube.app.database.FlowTubeDatabase
import com.flowtube.app.repository.DefaultHistoryRepository
import com.flowtube.app.repository.DefaultPlaylistRepository
import com.flowtube.app.repository.DefaultSearchHistoryRepository
import com.flowtube.app.repository.DefaultSubscriptionRepository
import com.flowtube.app.repository.HistoryRepository
import com.flowtube.app.repository.PlaylistRepository
import com.flowtube.app.repository.SearchHistoryRepository
import com.flowtube.app.repository.SubscriptionRepository
import com.flowtube.app.extractor.NewPipeVideoService
import com.flowtube.app.extractor.OkHttpDownloader
import com.flowtube.app.player.MediaSourceFactory
import com.flowtube.app.player.PlayerController
import com.flowtube.app.player.PlayerHttpConfig
import com.flowtube.app.player.SessionPlayerController
import com.flowtube.app.player.AppScopedPlayerControllerProvider
import com.flowtube.app.player.StreamRecoveryCoordinator
import com.flowtube.app.repository.CatalogRepository
import com.flowtube.app.repository.VideoService
import com.flowtube.app.ui.watch.DefaultFullscreenHostHandler
import com.flowtube.app.ui.watch.FullscreenHostHandlerFactory
import com.flowtube.app.settings.playbackDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Dependency injection container for application-level components.
 */
interface AppContainer {
    val applicationScope: CoroutineScope
    val database: FlowTubeDatabase
    val historyRepository: HistoryRepository
    val subscriptionRepository: SubscriptionRepository
    val playlistRepository: PlaylistRepository
    val searchHistoryRepository: SearchHistoryRepository
    val videoService: VideoService
    val catalogRepository: CatalogRepository
    val fullscreenHostHandlerFactory: FullscreenHostHandlerFactory
    val okHttpClient: OkHttpClient
    val mediaSourceFactory: MediaSourceFactory
    val playbackPreferences: com.flowtube.app.settings.PlaybackPreferences
    val settingsRepository: com.flowtube.app.settings.SettingsRepository
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

    override val database: FlowTubeDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            FlowTubeDatabase::class.java,
            "flowtube.db"
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

    override val fullscreenHostHandlerFactory: FullscreenHostHandlerFactory =
        FullscreenHostHandlerFactory { activity, savedStateHandle ->
            DefaultFullscreenHostHandler(activity, savedStateHandle)
        }

    override val playbackPreferences: com.flowtube.app.settings.PlaybackPreferences by lazy {
        settingsRepository
    }

    override val settingsRepository: com.flowtube.app.settings.SettingsRepository by lazy {
        com.flowtube.app.settings.DataStoreSettingsRepository(appContext.playbackDataStore)
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
