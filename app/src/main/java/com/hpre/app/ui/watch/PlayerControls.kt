package com.hpre.app.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpre.app.R
import com.hpre.app.core.designsystem.MinimumTouchTarget
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlaybackStreamType
import com.hpre.app.player.QualityOption
import kotlinx.coroutines.delay

/** How long the double-tap seek badge stays on screen. */
private const val SEEK_FEEDBACK_VISIBLE_MS = 600L

/** Slim seek bar metrics: a hairline track with a small dot instead of Material's 16dp pill. */
private val SEEK_TRACK_HEIGHT = 3.dp
private val SEEK_THUMB_IDLE = 10.dp
private val SEEK_THUMB_ACTIVE = 14.dp

/**
 * Pure auto-hide rules for the playback control overlay, kept separate so the behaviour is unit
 * testable without a device.
 */
object PlayerControlsPolicy {
    const val AUTO_HIDE_DELAY_MS = 3500L

    /**
     * Controls only fade out while playback actually advances and the user is not busy: an open
     * menu or an in-progress scrub must keep them on screen.
     */
    fun shouldAutoHide(
        controlsVisible: Boolean,
        isPlaying: Boolean,
        isMenuOpen: Boolean,
        isScrubbing: Boolean
    ): Boolean = controlsVisible && isPlaying && !isMenuOpen && !isScrubbing
}

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
    modifier: Modifier = Modifier,
    onMinimizeToHome: () -> Unit = {},
    minimizeEnabled: Boolean = true,
    isInPip: Boolean = false
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var isSpeedMenuOpen by remember { mutableStateOf(false) }
    var isQualityMenuOpen by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    // Bumped on every interaction so the auto-hide countdown restarts instead of firing mid-use.
    var interactionNonce by remember { mutableIntStateOf(0) }
    // Transient double-tap seek badge. The nonce lets repeated taps on the same side restart the
    // dismiss timer instead of being swallowed as an unchanged state.
    var seekFeedback by remember { mutableStateOf(SeekGesture.NONE) }
    var feedbackNonce by remember { mutableIntStateOf(0) }

    // Map of active protected control bounds in local overlay coordinates
    val protectedControlBounds = remember { mutableMapOf<String, Rect>() }
    var overlayLayoutCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val registerProtectedBounds: (String, LayoutCoordinates) -> Unit = { key, coords ->
        val parentCoords = overlayLayoutCoordinates
        if (parentCoords != null && parentCoords.isAttached && coords.isAttached) {
            protectedControlBounds[key] = parentCoords.localBoundingBoxOf(coords, clipBounds = false)
        } else if (coords.isAttached) {
            protectedControlBounds[key] = coords.boundsInParent()
        }
    }

    val unregisterProtectedBounds: (String) -> Unit = { key ->
        protectedControlBounds.remove(key)
    }

    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) {
            protectedControlBounds.clear()
        }
    }

    LaunchedEffect(seekFeedback, feedbackNonce) {
        if (seekFeedback != SeekGesture.NONE) {
            delay(SEEK_FEEDBACK_VISIBLE_MS)
            seekFeedback = SeekGesture.NONE
        }
    }

    val isMenuOpen = isSpeedMenuOpen || isQualityMenuOpen
    val keepControlsAlive: () -> Unit = {
        controlsVisible = true
        interactionNonce++
    }

    LaunchedEffect(
        controlsVisible,
        playbackState.isPlaying,
        isMenuOpen,
        isDragging,
        interactionNonce
    ) {
        if (
            PlayerControlsPolicy.shouldAutoHide(
                controlsVisible = controlsVisible,
                isPlaying = playbackState.isPlaying,
                isMenuOpen = isMenuOpen,
                isScrubbing = isDragging
            )
        ) {
            delay(PlayerControlsPolicy.AUTO_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    val density = LocalDensity.current
    val currentOnPlayPause = rememberUpdatedState(onPlayPause)
    val currentOnSeekBy = rememberUpdatedState(onSeekBy)
    val currentOnMinimizeToHome = rememberUpdatedState(onMinimizeToHome)

    val isMinimizeAllowed = PlayerGesturePolicy.isMinimizeGestureAllowed(
        isFullscreen = isFullscreen,
        isInPip = isInPip,
        minimizeEnabled = minimizeEnabled
    )

    var lastUpUptime by remember { mutableLongStateOf(0L) }
    var lastUpPosition by remember { mutableStateOf(Offset.Zero) }
    val currentControlsVisible = rememberUpdatedState(controlsVisible)
    val currentDurationMs = rememberUpdatedState(playbackState.durationMs)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayLayoutCoordinates = coordinates
            }
            .pointerInput(isMinimizeAllowed) {
                val touchSlopPx = viewConfiguration.touchSlop
                val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
                val doubleTapMinTimeMs = viewConfiguration.doubleTapMinTimeMillis
                val gestureConfig = PlayerGestureConfig(
                    touchSlopPx = touchSlopPx,
                    minimizeDistancePx = with(density) { PlayerGesturePolicy.DEFAULT_MINIMIZE_DISTANCE_DP.toPx() },
                    minimizeVelocityPxPerSecond = with(density) { PlayerGesturePolicy.DEFAULT_MINIMIZE_VELOCITY_DP_PER_SECOND.toPx() }
                )

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPosition = down.position
                    val downUptime = down.uptimeMillis

                    // If down pointer is already consumed or inside protected controls when visible, ignore
                    val startedInProtected = down.isConsumed || (currentControlsVisible.value && PlayerGesturePolicy.isPointInProtectedRegion(
                        downPosition.x,
                        downPosition.y,
                        protectedControlBounds.values
                    ))

                    if (startedInProtected) {
                        // Reset double-tap chain and wait for pointer release without acting
                        lastUpUptime = 0L
                        lastUpPosition = Offset.Zero
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    var isDoubleTap = false
                    val timeSinceLastUp = downUptime - lastUpUptime
                    if (timeSinceLastUp in doubleTapMinTimeMs..doubleTapTimeoutMs) {
                        val slopSquare = touchSlopPx * touchSlopPx
                        val distSquare = (downPosition.x - lastUpPosition.x) * (downPosition.x - lastUpPosition.x) +
                                (downPosition.y - lastUpPosition.y) * (downPosition.y - lastUpPosition.y)
                        if (distSquare <= slopSquare) {
                            isDoubleTap = true
                        }
                    }

                    var totalX = 0f
                    var totalY = 0f
                    var decision = PlayerDragDecision.UNDECIDED
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    val pointerId = down.id
                    var isCancelled = false
                    var confirmedUpChange: androidx.compose.ui.input.pointer.PointerInputChange? = null

                    while (true) {
                        val event = awaitPointerEvent()

                        // Reject multi-touch: if multiple pointers are down, reset double-tap chain and cancel gesture
                        if (event.changes.count { it.pressed } > 1) {
                            isCancelled = true
                            lastUpUptime = 0L
                            lastUpPosition = Offset.Zero
                            break
                        }

                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null) {
                            // Pointer disappeared / untracked
                            isCancelled = true
                            lastUpUptime = 0L
                            lastUpPosition = Offset.Zero
                            break
                        }

                        if (change.isConsumed && decision == PlayerDragDecision.UNDECIDED) {
                            // Child consumed the event before drag classification -> abort coordinator action
                            isCancelled = true
                            lastUpUptime = 0L
                            lastUpPosition = Offset.Zero
                            break
                        }

                        if (change.pressed != change.previousPressed && !change.pressed) {
                            // Confirmed pointer UP
                            confirmedUpChange = change
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            break
                        }

                        val posChange = change.positionChange()
                        totalX += posChange.x
                        totalY += posChange.y
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        if (decision == PlayerDragDecision.UNDECIDED) {
                            decision = PlayerGesturePolicy.classifyDrag(totalX, totalY, touchSlopPx)
                        }

                        if (decision == PlayerDragDecision.VERTICAL_DOWN && isMinimizeAllowed) {
                            // Downward drag classified: consume event so parent scroll/views don't steal
                            change.consume()
                        }
                    }

                    if (isCancelled || confirmedUpChange == null) {
                        return@awaitEachGesture
                    }

                    val totalVelocity = velocityTracker.calculateVelocity()
                    val velocityY = totalVelocity.y

                    if (decision == PlayerDragDecision.VERTICAL_DOWN && isMinimizeAllowed) {
                        val shouldTrigger = PlayerGesturePolicy.shouldMinimize(
                            totalY = totalY,
                            velocityY = velocityY,
                            config = gestureConfig,
                            enabled = true,
                            startedInProtectedRegion = startedInProtected
                        )
                        if (shouldTrigger) {
                            currentOnMinimizeToHome.value()
                        }
                        // Drag completed, clear double-tap state
                        lastUpUptime = 0L
                        lastUpPosition = Offset.Zero
                    } else if (decision == PlayerDragDecision.UNDECIDED) {
                        // Movement was within touch slop -> classified as tap or double-tap
                        if (isDoubleTap) {
                            lastUpUptime = 0L
                            lastUpPosition = Offset.Zero
                            if (PlayerGesturePolicy.isSeekAllowed(currentDurationMs.value)) {
                                when (PlayerGesturePolicy.gestureForTap(downPosition.x, size.width.toFloat())) {
                                    SeekGesture.REWIND -> {
                                        seekFeedback = SeekGesture.REWIND
                                        feedbackNonce++
                                        currentOnSeekBy.value(-PlayerGesturePolicy.SEEK_STEP_MS)
                                    }
                                    SeekGesture.FORWARD -> {
                                        seekFeedback = SeekGesture.FORWARD
                                        feedbackNonce++
                                        currentOnSeekBy.value(PlayerGesturePolicy.SEEK_STEP_MS)
                                    }
                                    SeekGesture.NONE -> keepControlsAlive()
                                }
                            }
                        } else {
                            // Record confirmed UP position and uptime
                            lastUpUptime = confirmedUpChange.uptimeMillis
                            lastUpPosition = confirmedUpChange.position
                            if (currentControlsVisible.value) {
                                controlsVisible = false
                            } else {
                                keepControlsAlive()
                            }
                        }
                    } else {
                        // Horizontal or rejected drag
                        lastUpUptime = 0L
                        lastUpPosition = Offset.Zero
                    }
                }
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
                    text = stringResource(R.string.playback_audio_only),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Double-tap seek feedback. Lives outside the controls block on purpose: the gesture works
        // with the controls hidden, and without an on-screen cue the video just appears to jump.
        if (seekFeedback != SeekGesture.NONE) {
            val isRewind = seekFeedback == SeekGesture.REWIND
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier = Modifier
                    .align(if (isRewind) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 32.dp)
                    .testTag(
                        if (isRewind) "seek_feedback_rewind" else "seek_feedback_forward"
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isRewind) {
                            Icons.Default.Replay10
                        } else {
                            Icons.Default.Forward10
                        },
                        contentDescription = stringResource(
                            if (isRewind) R.string.action_rewind_10 else R.string.action_forward_10
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.seek_step_seconds, 10),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (controlsVisible) {
            // Semi-transparent scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )

            // Fullscreen needs its own way out plus context about what is playing, because the
            // watch screen's back button and title are not rendered in this mode.
            if (isFullscreen) {
                DisposableEffect(Unit) {
                    onDispose { unregisterProtectedBounds("fullscreen_top_start") }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .displayCutoutPadding()
                        .padding(8.dp)
                        .onGloballyPositioned { coordinates ->
                            registerProtectedBounds("fullscreen_top_start", coordinates)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            keepControlsAlive()
                            onToggleFullscreen()
                        },
                        modifier = Modifier.testTag("control_fullscreen_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_exit_fullscreen),
                            tint = Color.White
                        )
                    }

                    val fullscreenTitle = playbackState.title
                    if (!fullscreenTitle.isNullOrBlank()) {
                        Text(
                            text = fullscreenTitle,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 4.dp, end = 96.dp)
                                .testTag("player_fullscreen_title")
                        )
                    }
                }
            }

            // Center play/pause & seek 10s buttons
            DisposableEffect(Unit) {
                onDispose {
                    unregisterProtectedBounds("control_rewind_10")
                    unregisterProtectedBounds("control_play_pause")
                    unregisterProtectedBounds("control_forward_10")
                    unregisterProtectedBounds("center_controls")
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .onGloballyPositioned { coordinates ->
                        registerProtectedBounds("center_controls", coordinates)
                    },
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        keepControlsAlive()
                        onSeekBy(-10_000L)
                    },
                    modifier = Modifier
                        .testTag("control_rewind_10")
                        .onGloballyPositioned { coords ->
                            registerProtectedBounds("control_rewind_10", coords)
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.action_rewind_10),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = {
                        keepControlsAlive()
                        onPlayPause()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("control_play_pause")
                        .onGloballyPositioned { coords ->
                            registerProtectedBounds("control_play_pause", coords)
                        }
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (playbackState.isPlaying) R.string.action_pause else R.string.action_play
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                }

                IconButton(
                    onClick = {
                        keepControlsAlive()
                        onSeekBy(10_000L)
                    },
                    modifier = Modifier
                        .testTag("control_forward_10")
                        .onGloballyPositioned { coords ->
                            registerProtectedBounds("control_forward_10", coords)
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = stringResource(R.string.action_forward_10),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Top-right controls: Speed and Quality menus
            DisposableEffect(Unit) {
                onDispose {
                    unregisterProtectedBounds("top_end_menus")
                    unregisterProtectedBounds("control_speed_button")
                    unregisterProtectedBounds("control_quality_button")
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // In landscape fullscreen the camera cutout sits over this corner, so keep the
                    // buttons clear of it.
                    .then(if (isFullscreen) Modifier.displayCutoutPadding() else Modifier)
                    .padding(8.dp)
                    .onGloballyPositioned { coordinates ->
                        registerProtectedBounds("top_end_menus", coordinates)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed selection menu
                Box {
                    IconButton(
                        onClick = {
                            keepControlsAlive()
                            isSpeedMenuOpen = true
                        },
                        modifier = Modifier
                            .testTag("control_speed_button")
                            .onGloballyPositioned { coords ->
                                registerProtectedBounds("control_speed_button", coords)
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = stringResource(
                                R.string.playback_speed,
                                playbackState.playbackSpeed.toString()
                            ),
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
                                    keepControlsAlive()
                                },
                                modifier = Modifier.testTag("speed_option_${speed}")
                            )
                        }
                    }
                }

                // Quality selection menu (only show if available candidates exist)
                if (playbackState.availableQualities.isNotEmpty()) {
                    DisposableEffect(Unit) {
                        onDispose { unregisterProtectedBounds("control_quality_button") }
                    }
                    Box {
                        IconButton(
                            onClick = {
                                keepControlsAlive()
                                isQualityMenuOpen = true
                            },
                            modifier = Modifier
                                .testTag("control_quality_button")
                                .onGloballyPositioned { coords ->
                                    registerProtectedBounds("control_quality_button", coords)
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = stringResource(
                                    R.string.playback_quality,
                                    playbackState.selectedQuality?.label
                                        ?: stringResource(R.string.quality_auto)
                                ),
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
                                        keepControlsAlive()
                                    },
                                    modifier = Modifier.testTag("quality_option_${quality.height}")
                                )
                            }
                        }
                    }
                }
            }

            // Bottom bar: Progress slider, time labels, fullscreen toggle
            DisposableEffect(Unit) {
                onDispose {
                    unregisterProtectedBounds("bottom_bar")
                    unregisterProtectedBounds("player_progress_slider")
                    unregisterProtectedBounds("control_fullscreen_toggle")
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Transient system bars can reappear on swipe; keep the seek bar reachable and
                    // clear of the cutout in landscape.
                    .then(if (isFullscreen) Modifier.displayCutoutPadding() else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onGloballyPositioned { coordinates ->
                        registerProtectedBounds("bottom_bar", coordinates)
                    }
            ) {
                // Seek slider: disabled when duration <= 0 (no fake 1ms duration)
                val isSeekEnabled = playbackState.durationMs > 0L
                val duration = if (isSeekEnabled) playbackState.durationMs.toFloat() else 0f

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
                            keepControlsAlive()
                        }
                    },
                    onValueChangeFinished = {
                        if (isSeekEnabled) {
                            val target = dragPosition.toLong().coerceIn(0L, playbackState.durationMs)
                            isDragging = false
                            keepControlsAlive()
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
                    // Material3's default Track applies its own 16dp height after the caller's
                    // modifier, so passing height(3.dp) to it has no effect. Draw a slim track and
                    // a small round thumb instead of the default 4x44dp pill.
                    track = { sliderState ->
                        val fraction = if (isSeekEnabled && duration > 0f) {
                            (sliderState.value / duration).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SEEK_TRACK_HEIGHT)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (isSeekEnabled) {
                                        Color.White.copy(alpha = 0.32f)
                                    } else {
                                        Color.White.copy(alpha = 0.18f)
                                    }
                                )
                                .testTag("player_progress_track")
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(SEEK_TRACK_HEIGHT)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(
                                        if (isSeekEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.Gray
                                        }
                                    )
                            )
                        }
                    },
                    thumb = {
                        val thumbSize = if (isDragging) SEEK_THUMB_ACTIVE else SEEK_THUMB_IDLE
                        // Keep the 48dp touch target while drawing a small visual dot.
                        Box(
                            modifier = Modifier
                                .size(MinimumTouchTarget)
                                .testTag("player_progress_thumb"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(thumbSize)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSeekEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.Gray
                                        }
                                    )
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .onGloballyPositioned { coords ->
                            registerProtectedBounds("player_progress_slider", coords)
                        }
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
                        onClick = {
                            keepControlsAlive()
                            onToggleFullscreen()
                        },
                        modifier = Modifier
                            .testTag("control_fullscreen_toggle")
                            .onGloballyPositioned { coords ->
                                registerProtectedBounds("control_fullscreen_toggle", coords)
                            }
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = stringResource(
                                if (isFullscreen) R.string.action_exit_fullscreen
                                else R.string.action_enter_fullscreen
                            ),
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
