package com.hpre.app.ui.watch

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.hpre.app.player.PlayerController

@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    playerController: PlayerController,
    modifier: Modifier = Modifier,
    useController: Boolean = false
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.useController = useController
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // Handled by custom Compose overlay
            }
        },
        update = { playerView ->
            playerController.attachSurface(playerView)
        },
        onRelease = { playerView ->
            playerController.detachSurface(playerView)
        },
        onReset = { playerView ->
            playerController.detachSurface(playerView)
        },
        modifier = modifier.testTag("player_surface")
    )
}
