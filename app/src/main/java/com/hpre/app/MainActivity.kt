package com.hpre.app

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.model.ContentKey
import com.hpre.app.navigation.RootScaffold
import com.hpre.app.player.PlaybackStreamType
import com.hpre.app.player.PlayerController
import com.hpre.app.settings.AppLocaleProvider
import com.hpre.app.ui.watch.PlayerSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class AutoPipPlaybackSnapshot(
    val key: ContentKey?,
    val streamType: PlaybackStreamType?,
    val isPlaying: Boolean,
    val isReady: Boolean
)

open class MainActivity : ComponentActivity() {
    private val app: HPreApplication
        get() = application as HPreApplication

    /**
     * The player, only if something already built it. Never constructs it.
     *
     * Cold start on Home must not create ExoPlayer, so every lifecycle callback here treats a null
     * controller as "nothing is playing" and does nothing.
     */
    private val activePlayerController: PlayerController?
        get() = app.container.peekPlayerController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = android.view.WindowManager.LayoutParams().apply {
                copyFrom(window.attributes)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            window.attributes = attrs
        }
        enableEdgeToEdge()
        val initialUiState = app.playbackUiCoordinator.state.value
        // Records the policy on the container so it is applied when (and if) the player is built.
        app.container.updatePlayerLifecyclePolicy(
            backgroundEnabled = initialUiState.backgroundPlaybackEnabled,
            pipActiveOrEntering = initialUiState.isInPip
        )
        setContent {
            val settings by app.container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = com.hpre.app.settings.AppSettings())
            val darkTheme = when (settings.theme) {
                com.hpre.app.settings.AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                com.hpre.app.settings.AppTheme.LIGHT -> false
                com.hpre.app.settings.AppTheme.DARK -> true
            }

            SideEffect {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            AppLocaleProvider(language = settings.language) {
                HPreTheme(darkTheme = darkTheme) {
                    val playbackUiState by app.playbackUiCoordinator.state.collectAsStateWithLifecycle()
                    // Auto-PiP only depends on four playback facts. Project the frequently-updating
                    // PlaybackState to those facts so position/buffering/quality ticks do not
                    // recompose the activity root and all of its navigation content.
                    val autoPipPlaybackFlow = remember(app.container) {
                        app.container.playbackState
                            .map { state ->
                                AutoPipPlaybackSnapshot(
                                    key = state.key,
                                    streamType = state.streamType,
                                    isPlaying = state.isPlaying,
                                    isReady = state.isReady
                                )
                            }
                            .distinctUntilChanged()
                    }
                    val autoPipPlayback by autoPipPlaybackFlow.collectAsStateWithLifecycle(
                        initialValue = AutoPipPlaybackSnapshot(
                            key = null,
                            streamType = null,
                            isPlaying = false,
                            isReady = false
                        )
                    )

                    androidx.compose.runtime.LaunchedEffect(playbackUiState.backgroundPlaybackEnabled, playbackUiState.isInPip) {
                        app.container.updatePlayerLifecyclePolicy(
                            backgroundEnabled = playbackUiState.backgroundPlaybackEnabled,
                            pipActiveOrEntering = playbackUiState.isInPip
                        )
                    }
                    androidx.compose.runtime.LaunchedEffect(
                        playbackUiState.watchVisible,
                        playbackUiState.pipEnabled,
                        autoPipPlayback.key,
                        autoPipPlayback.streamType,
                        autoPipPlayback.isPlaying,
                        autoPipPlayback.isReady
                    ) {
                        updateAutoPipEligibility()
                    }
                    // Being in PiP implies playback already started, so the controller exists. The
                    // null check is a guard rather than an expected path; falling back to the
                    // scaffold is better than constructing a player to render an empty surface.
                    val pipController = if (playbackUiState.isInPip) activePlayerController else null
                    if (pipController != null) {
                        PlayerSurface(
                            playerController = pipController,
                            coordinator = app.playbackUiCoordinator,
                            owner = com.hpre.app.player.SurfaceOwner.SYSTEM_PIP,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        RootScaffold(container = app.container)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val uiState = app.playbackUiCoordinator.state.value
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        activePlayerController?.onLifecycleStart()
        // Recorded on the container so it also applies to a player created later in this session.
        app.container.updatePlayerLifecyclePolicy(
            backgroundEnabled = uiState.backgroundPlaybackEnabled,
            pipActiveOrEntering = inPip
        )
        updateAutoPipEligibility()
    }

    override fun onResume() {
        super.onResume()
        updateAutoPipEligibility()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPictureInPictureIfEligible()
    }

    override fun onStop() {
        super.onStop()
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip) {
            app.playbackUiCoordinator.setWatchVisible(false)
        }
        val activeController = activePlayerController
        val isChangingConfig = isChangingConfigurations
        if (activeController is com.hpre.app.player.SessionPlayerController) {
            activeController.onLifecycleStop(
                isChangingConfigurations = isChangingConfig,
                isInPip = inPip
            )
        } else if (activeController != null && !isChangingConfig && !inPip) {
            activeController.onLifecycleStop()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateAutoPipEligibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.playbackUiCoordinator.setWatchVisible(false)
    }

    open fun isPipSupported(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    fun isPipEligible(): Boolean {
        if (!isPipSupported()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val playbackState = app.container.playbackState.value
        val uiState = app.playbackUiCoordinator.state.value
        return com.hpre.app.player.PlaybackPolicy.canEnterPip(
            com.hpre.app.player.PipEligibility(
                supported = isPipSupported(),
                enabled = uiState.pipEnabled,
                watchVisible = uiState.watchVisible,
                alreadyInPip = isInPictureInPictureMode,
                hasVideo = playbackState.key != null && playbackState.streamType != null &&
                    playbackState.streamType != PlaybackStreamType.AUDIO_ONLY,
                isPlaying = playbackState.isPlaying,
                isReady = playbackState.isReady
            )
        )
    }

    fun updateAutoPipEligibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isPipSupported()) {
            val eligible = isPipEligible()
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(eligible)
            try {
                setPictureInPictureParams(builder.build())
            } catch (_: Throwable) {}
        }
    }

    fun enterPictureInPictureIfEligible(): Boolean {
        if (!isPipSupported()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val activeController = activePlayerController
        val uiState = app.playbackUiCoordinator.state.value
        val eligible = isPipEligible()
        if (!eligible) {
            fallbackHandlePipFailure(activeController, uiState.backgroundPlaybackEnabled)
            return false
        }

        return try {
            val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            val params = builder.build()
            if (activeController is com.hpre.app.player.SessionPlayerController) {
                activeController.updateLifecyclePolicy(uiState.backgroundPlaybackEnabled, pipActiveOrEntering = true)
            }
            val entered = enterPictureInPictureMode(params)
            if (!entered) {
                fallbackHandlePipFailure(activeController, uiState.backgroundPlaybackEnabled)
            }
            entered
        } catch (_: Throwable) {
            fallbackHandlePipFailure(activeController, uiState.backgroundPlaybackEnabled)
            false
        }
    }

    private fun fallbackHandlePipFailure(activeController: PlayerController?, backgroundEnabled: Boolean) {
        // No controller means nothing is playing, so there is nothing to pause or clear.
        if (activeController == null) return
        if (activeController is com.hpre.app.player.SessionPlayerController) {
            activeController.updateLifecyclePolicy(backgroundEnabled, pipActiveOrEntering = false)
            if (!com.hpre.app.player.PlaybackPolicy.shouldContinueInBackground(backgroundEnabled, enteringPip = false)) {
                activeController.pause()
                activeController.clearMedia()
            }
        } else {
            if (!com.hpre.app.player.PlaybackPolicy.shouldContinueInBackground(backgroundEnabled, enteringPip = false)) {
                activeController.pause()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        app.playbackUiCoordinator.setInPip(isInPictureInPictureMode)
        val uiState = app.playbackUiCoordinator.state.value
        app.container.updatePlayerLifecyclePolicy(
            backgroundEnabled = uiState.backgroundPlaybackEnabled,
            pipActiveOrEntering = isInPictureInPictureMode
        )
        updateAutoPipEligibility()
    }
}
