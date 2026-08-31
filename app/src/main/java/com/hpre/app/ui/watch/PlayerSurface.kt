package com.hpre.app.ui.watch

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hpre.app.player.PlayerController
import com.hpre.app.player.PlaybackUiCoordinator
import com.hpre.app.player.SurfaceOwner

/**
 * Fits the complete video frame by default. Fullscreen stretches the whole frame to fill the
 * available bounds without cropping. This can change the video's proportions when its aspect
 * ratio differs from the display; Media3 recalculates the size when those bounds change.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    playerController: PlayerController,
    modifier: Modifier = Modifier,
    useController: Boolean = false,
    coordinator: PlaybackUiCoordinator? = null,
    owner: SurfaceOwner = SurfaceOwner.WATCH,
    fillScreen: Boolean = false
) {
    val surfaceResizeMode = if (fillScreen) {
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    } else {
        AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    val lease = remember(coordinator, owner) { coordinator?.beginSurfaceHandoff(owner) }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.useController = useController
                resizeMode = surfaceResizeMode
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // Handled by custom Compose overlay
            }
        },
        update = { playerView ->
            // A reused AndroidView must also switch back to FIT without restarting playback.
            if (playerView.resizeMode != surfaceResizeMode) {
                playerView.resizeMode = surfaceResizeMode
            }
            if (lease == null) {
                playerController.attachSurface(playerView)
            } else if (playerController.attachSurface(playerView, lease)) {
                coordinator?.confirmSurfaceAttached(lease)
            } else {
                coordinator?.rejectSurfaceAttach(lease)
            }
        },
        onRelease = { playerView ->
            if (lease == null) playerController.detachSurface(playerView)
            else playerController.detachSurface(playerView, lease)
        },
        onReset = { playerView ->
            if (lease == null) playerController.detachSurface(playerView)
            else playerController.detachSurface(playerView, lease)
        },
        modifier = modifier.testTag("player_surface")
    )
}
