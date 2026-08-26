package com.flowtube.app

import android.app.Application
import com.flowtube.app.core.network.NetworkPolicy
import com.flowtube.app.di.AppContainer
import com.flowtube.app.di.DefaultAppContainer
import com.flowtube.app.extractor.ExtractorBootstrap
import com.flowtube.app.extractor.OkHttpDownloader
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

open class FlowTubeApplication : Application() {
    val playbackUiCoordinator = com.flowtube.app.player.PlaybackUiCoordinator()
    private val appClient: OkHttpClient by lazy {
        NetworkPolicy(
            connectTimeoutSeconds = 15,
            readTimeoutSeconds = 20,
            callTimeoutSeconds = 30,
            maxIdleConnections = 5,
            keepAliveDurationMinutes = 5
        ).createOkHttpClient()
    }

    private val appDownloader: OkHttpDownloader by lazy {
        OkHttpDownloader(client = appClient)
    }

    private lateinit var _container: AppContainer

    val container: AppContainer
        get() = _container

    /**
     * Internal open hook called during [onCreate] to instantiate the [AppContainer].
     * Test applications in internal/test source sets can override this hook to provide a test container.
     */
    internal open fun createContainer(): AppContainer {
        return DefaultAppContainer(
            context = this,
            okHttpClient = appClient
        )
    }

    override fun onCreate() {
        super.onCreate()
        ExtractorBootstrap.init(appDownloader)
        _container = createContainer()

        // Collect DataStore playback preferences and sync with coordinator & controller
        container.applicationScope.launch {
            container.playbackPreferences.isBackgroundPlaybackEnabled.collect { bgEnabled ->
                playbackUiCoordinator.setBackgroundPlaybackEnabled(bgEnabled)
                val controller = container.createPlayerController()
                if (controller is com.flowtube.app.player.SessionPlayerController) {
                    controller.updateLifecyclePolicy(
                        backgroundEnabled = bgEnabled,
                        pipActiveOrEntering = playbackUiCoordinator.state.value.isInPip
                    )
                }
            }
        }
        container.applicationScope.launch {
            container.playbackPreferences.isPipEnabled.collect { pipPrefEnabled ->
                playbackUiCoordinator.setPipEnabled(pipPrefEnabled)
            }
        }
    }
}
