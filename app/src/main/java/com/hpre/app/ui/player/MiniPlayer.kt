package com.hpre.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreShapes
import com.hpre.app.core.designsystem.HPreSpacing
import com.hpre.app.core.designsystem.MinimumTouchTarget
import com.hpre.app.model.ContentKey
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.toProgress
import com.hpre.app.player.toStructuralState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.PlaybackUiCoordinator
import com.hpre.app.player.SurfaceOwner
import com.hpre.app.ui.watch.PlayerSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun MiniPlayer(
    playerController: PlayerController,
    onExpandWatch: (ContentKey) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    coordinator: PlaybackUiCoordinator? = null
) {
    val structuralFlow = remember(playerController) {
        playerController.state.map(PlaybackState::toStructuralState).distinctUntilChanged()
    }
    val state by structuralFlow.collectAsStateWithLifecycle(
        initialValue = PlaybackState()
    )
    val currentKey = state.key

    if (currentKey == null) {
        return
    }

    Card(
        shape = RoundedCornerShape(topStart = HPreShapes.Card, topEnd = HPreShapes.Card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("mini_player_container")
            .clickable {
                onExpandWatch(currentKey)
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mini-player")
        ) {
            MiniPlayerProgress(playerController)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HPreSpacing.Medium, vertical = HPreSpacing.Small)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .testTag("mini_player_thumbnail"),
                    contentAlignment = Alignment.Center
                ) {
                    PlayerSurface(
                        playerController = playerController,
                        coordinator = coordinator,
                        owner = SurfaceOwner.MINI_PLAYER,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.width(HPreSpacing.Medium))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = HPreSpacing.Small)
                ) {
                    Text(
                        text = state.title ?: stringResource(R.string.video_fallback_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("mini_player_title")
                    )
                    Text(
                        text = stringResource(
                            if (state.isPlaying) R.string.status_playing else R.string.status_paused
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { playerController.playPause() },
                    modifier = Modifier
                        .size(MinimumTouchTarget)
                        .testTag("mini_player_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.action_pause else R.string.action_play
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(MinimumTouchTarget)
                        .testTag("mini_player_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.mini_player_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerProgress(playerController: PlayerController) {
    val progressFlow = remember(playerController) {
        playerController.state.map(PlaybackState::toProgress).distinctUntilChanged()
    }
    val progressState by progressFlow.collectAsStateWithLifecycle(
        initialValue = com.hpre.app.player.PlaybackProgress()
    )
    val progress = if (progressState.durationMs > 0L) {
        (progressState.positionMs.toFloat() / progressState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .testTag("mini_player_progress"),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Transparent
    )
}
