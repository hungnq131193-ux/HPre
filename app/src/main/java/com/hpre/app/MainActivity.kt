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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import com.hpre.app.core.designsystem.HPreTheme
import com.hpre.app.navigation.RootScaffold
import com.hpre.app.player.PlaybackStreamType
import com.hpre.app.player.PlayerController
import com.hpre.app.ui.watch.PlayerSurface

open class MainActivity : ComponentActivity() {
    private val app: HPreApplication
        get() = application as HPreApplication

    private val playerController: PlayerController
        get() = app.container.createPlayerController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUiState = app.playbackUiCoordinator.state.value
        val activeController = playerController
        if (activeController is com.hpre.app.player.SessionPlayerController) {
            activeController.updateLifecyclePolicy(
                backgroundEnabled = initialUiState.backgroundPlaybackEnabled,
                pipActiveOrEntering = initialUiState.isInPip
            )
        }
        setContent {
            val settings by app.container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = com.hpre.app.settings.AppSettings())
            val darkTheme = when (settings.theme) {
                com.hpre.app.settings.AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                com.hpre.app.settings.AppTheme.LIGHT -> false
                com.hpre.app.settings.AppTheme.DARK -> true
            }

            SideEffect {
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

            HPreTheme(darkTheme = darkTheme) {
                val playbackUiState by app.playbackUiCoordinator.state.collectAsStateWithLifecycle()
                val playbackState by playerController.state.collectAsStateWithLifecycle()

                androidx.compose.runtime.LaunchedEffect(playbackUiState.backgroundPlaybackEnabled, playbackUiState.isInPip) {
                    val controller = playerController
                    if (controller is com.hpre.app.player.SessionPlayerController) {
                        controller.updateLifecyclePolicy(
                            backgroundEnabled = playbackUiState.backgroundPlaybackEnabled,
                            pipActiveOrEntering = playbackUiState.isInPip
                        )
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    playbackUiState.watchVisible,
                    playbackUiState.pipEnabled,
                    playbackState.key,
                    playbackState.streamType,
                    playbackState.isPlaying,
                    playbackState.isReady
                ) {
                    updateAutoPipEligibility()
                }
                if (playbackUiState.isInPip) {
                    PlayerSurface(
                        playerController = playerController,
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

    override fun onStart() {
        super.onStart()
        val activeController = playerController
        val uiState = app.playbackUiCoordinator.state.value
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (activeController is com.hpre.app.player.SessionPlayerController) {
            activeController.onLifecycleStart()
            activeController.updateLifecyclePolicy(
                backgroundEnabled = uiState.backgroundPlaybackEnabled,
                pipActiveOrEntering = inPip
            )
        } else {
            activeController.onLifecycleStart()
        }
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
        val activeController = playerController
        val isChangingConfig = isChangingConfigurations
        if (activeController is com.hpre.app.player.SessionPlayerController) {
            activeController.onLifecycleStop(
                isChangingConfigurations = isChangingConfig,
                isInPip = inPip
            )
        } else if (!isChangingConfig && !inPip) {
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

        val playbackState = playerController.state.value
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

        val playbackState = playerController.state.value
        val activeController = playerController
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

    private fun fallbackHandlePipFailure(activeController: PlayerController, backgroundEnabled: Boolean) {
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
        val activeController = playerController
        if (activeController is com.hpre.app.player.SessionPlayerController) {
            val uiState = app.playbackUiCoordinator.state.value
            activeController.updateLifecyclePolicy(
                backgroundEnabled = uiState.backgroundPlaybackEnabled,
                pipActiveOrEntering = isInPictureInPictureMode
            )
        }
        updateAutoPipEligibility()
    }
}
