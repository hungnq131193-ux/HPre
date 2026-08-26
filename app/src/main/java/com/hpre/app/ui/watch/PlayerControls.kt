package com.hpre.app.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlaybackStreamType
import com.hpre.app.player.QualityOption
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlsOverlay(
    playbackState: PlaybackState,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeekBy: (deltaMs: Long) -> Unit,
    onSeekTo: (positionMs: Long) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onQualitySelected: (QualityOption) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var isSpeedMenuOpen by remember { mutableStateOf(false) }
    var isQualityMenuOpen by remember { mutableStateOf(false) }

    // Auto-hide controls after 3.5 seconds if playing
    LaunchedEffect(controlsVisible, playbackState.isPlaying) {
        if (controlsVisible && playbackState.isPlaying) {
            delay(3500)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
            }
            .testTag("player_controls_overlay")
    ) {
        // Buffering / Loading Indicator
        if (playbackState.isLoading || playbackState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
                    .testTag("player_loading_indicator"),
                color = Color.White
            )
        }

        // Audio-only banner if applicable
        if (playbackState.streamType == PlaybackStreamType.AUDIO_ONLY) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .testTag("audio_only_badge")
            ) {
                Text(
                    text = "Audio Only Mode",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (controlsVisible) {
            // Semi-transparent scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )

            // Center play/pause & seek 10s buttons
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onSeekBy(-10_000L) },
                    modifier = Modifier.testTag("control_rewind_10")
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Rewind 10 seconds",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("control_play_pause")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                }

                IconButton(
                    onClick = { onSeekBy(10_000L) },
                    modifier = Modifier.testTag("control_forward_10")
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Fast forward 10 seconds",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Top-right controls: Speed and Quality menus
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed selection menu
                Box {
                    IconButton(
                        onClick = { isSpeedMenuOpen = true },
                        modifier = Modifier.testTag("control_speed_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Playback Speed (${playbackState.playbackSpeed}x)",
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = isSpeedMenuOpen,
                        onDismissRequest = { isSpeedMenuOpen = false },
                        modifier = Modifier.testTag("speed_menu")
                    ) {
                        listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                            val isSelected = (playbackState.playbackSpeed == speed)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${speed}x" + if (isSelected) " ✓" else "",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onSpeedSelected(speed)
                                    isSpeedMenuOpen = false
                                },
                                modifier = Modifier.testTag("speed_option_${speed}")
                            )
                        }
                    }
                }

                // Quality selection menu (only show if available candidates exist)
                if (playbackState.availableQualities.isNotEmpty()) {
                    Box {
                        IconButton(
                            onClick = { isQualityMenuOpen = true },
                            modifier = Modifier.testTag("control_quality_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = "Quality Selection (${playbackState.selectedQuality?.label ?: "Auto"})",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = isQualityMenuOpen,
                            onDismissRequest = { isQualityMenuOpen = false },
                            modifier = Modifier.testTag("quality_menu")
                        ) {
                            playbackState.availableQualities.forEach { quality ->
                                val isSelected = (playbackState.selectedQuality == quality)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = quality.label + if (isSelected) " ✓" else "",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        onQualitySelected(quality)
                                        isQualityMenuOpen = false
                                    },
                                    modifier = Modifier.testTag("quality_option_${quality.height}")
                                )
                            }
                        }
                    }
                }
            }

            // Bottom bar: Progress slider, time labels, fullscreen toggle
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Seek slider: disabled when duration <= 0 (no fake 1ms duration)
                val isSeekEnabled = playbackState.durationMs > 0L
                val duration = if (isSeekEnabled) playbackState.durationMs.toFloat() else 0f
                var isDragging by remember { mutableStateOf(false) }
                var dragPosition by remember { mutableStateOf(0f) }

                val sliderValue = if (isDragging) {
                    dragPosition
                } else if (isSeekEnabled) {
                    playbackState.currentPositionMs.toFloat().coerceIn(0f, duration)
                } else {
                    0f
                }

                Slider(
                    value = sliderValue.coerceIn(0f, if (isSeekEnabled) duration else 1f),
                    onValueChange = {
                        if (isSeekEnabled) {
                            isDragging = true
                            dragPosition = it
                        }
                    },
                    onValueChangeFinished = {
                        if (isSeekEnabled) {
                            val target = dragPosition.toLong().coerceIn(0L, playbackState.durationMs)
                            isDragging = false
                            onSeekTo(target)
                        }
                    },
                    valueRange = if (isSeekEnabled) 0f..duration else 0f..1f,
                    enabled = isSeekEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.4f),
                        disabledThumbColor = Color.Gray,
                        disabledActiveTrackColor = Color.Gray,
                        disabledInactiveTrackColor = Color.DarkGray
                    ),
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(3.dp),
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.4f),
                                disabledActiveTrackColor = Color.Gray,
                                disabledInactiveTrackColor = Color.DarkGray
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics {
                            if (isSeekEnabled) {
                                setProgress { targetValue ->
                                    val target = targetValue.toLong().coerceIn(0L, playbackState.durationMs)
                                    onSeekTo(target)
                                    true
                                }
                            }
                        }
                        .testTag("player_progress_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayPosition = if (isDragging) dragPosition.toLong() else playbackState.currentPositionMs
                    Text(
                        text = "${formatTime(displayPosition)} / ${formatTime(playbackState.durationMs)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("player_time_text")
                    )

                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.testTag("control_fullscreen_toggle")
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) "Exit Fullscreen" else "Enter Fullscreen",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        "%d:%02d:%02d".format(hours, remainingMinutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
