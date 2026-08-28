package com.hpre.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.hpre.app.core.network.NetworkPolicy
import com.hpre.app.di.AppContainer
import com.hpre.app.di.DefaultAppContainer
import com.hpre.app.extractor.ExtractorBootstrap
import com.hpre.app.extractor.OkHttpDownloader
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

open class HPreApplication : Application(), ImageLoaderFactory {
    val playbackUiCoordinator = com.hpre.app.player.PlaybackUiCoordinator()
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
                container.updatePlayerLifecyclePolicy(
                    backgroundEnabled = bgEnabled,
                    pipActiveOrEntering = playbackUiCoordinator.state.value.isInPip
                )
            }
        }
        container.applicationScope.launch {
            container.playbackPreferences.isPipEnabled.collect { pipPrefEnabled ->
                playbackUiCoordinator.setPipEnabled(pipPrefEnabled)
            }
        }
    }

    /**
     * Coil's defaults give no disk cache and a conservative memory cache, so scrolling back through a
     * feed re-downloads and re-decodes every thumbnail. That decode work lands on a background
     * dispatcher but the resulting allocation churn and GC pauses show up as scroll jank.
     *
     * Sharing [appClient] also lets thumbnails reuse the existing connection pool instead of Coil
     * building a second OkHttp client.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { appClient }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        // Thumbnails are decoded straight to the display size; ARGB_8888 upgrades add memory pressure
        // without a visible difference at these dimensions.
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
