package com.hpre.app.ui.watch
import androidx.compose.foundation.layout.heightIn

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.SavedStateHandle
import coil.compose.AsyncImage
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreShapes
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.ui.common.ErrorPane

/**
 * Abstraction for controlling system UI bars (status / navigation bars).
 */
interface WindowSystemUiController {
    fun hideSystemBars()
    fun showSystemBars()
}

class DefaultWindowSystemUiController(
    private val window: Window?,
    private val view: View?
) : WindowSystemUiController {
    override fun hideSystemBars() {
        val win = window ?: return
        val v = view ?: win.decorView
        val insetsController = WindowCompat.getInsetsController(win, v)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        win.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun showSystemBars() {
        val win = window ?: return
        val v = view ?: win.decorView
        val insetsController = WindowCompat.getInsetsController(win, v)
        insetsController.show(WindowInsetsCompat.Type.systemBars())

        @Suppress("DEPRECATION")
        win.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

/**
 * Handles activity orientation and fullscreen system UI transitions.
 */
interface FullscreenHostHandler {
    fun enterFullscreen()
    fun exitFullscreen()
    fun onConfigurationChange()

    /**
     * Re-applies the immersive window state while fullscreen is already active.
     *
     * Needed because a host recreation (process death restore, or a configuration change the
     * activity does not handle itself) hands back a window with the system bars visible again,
     * while the restored fullscreen flag equals the previous one, so no enter transition fires.
     * Must never capture or clear the saved original orientation.
     */
    fun reapplyFullscreen() {}
}

class DefaultFullscreenHostHandler(
    private val activity: Activity?,
    private val savedStateHandle: SavedStateHandle? = null,
    private val systemUiController: WindowSystemUiController? = activity?.let {
        DefaultWindowSystemUiController(it.window, it.window.decorView)
    }
) : FullscreenHostHandler {

    companion object {
        const val KEY_ORIG_ORIENTATION = "fullscreen_orig_orientation"
    }

    override fun enterFullscreen() {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return

        if (savedStateHandle != null) {
            if (!savedStateHandle.contains(KEY_ORIG_ORIENTATION)) {
                savedStateHandle[KEY_ORIG_ORIENTATION] = act.requestedOrientation
            }
        }
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = android.view.WindowManager.LayoutParams().apply {
                copyFrom(act.window.attributes)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            act.window.attributes = attrs
        }
        systemUiController?.hideSystemBars()
    }

    override fun exitFullscreen() {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return

        val orig = savedStateHandle?.get<Int>(KEY_ORIG_ORIENTATION)
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        act.requestedOrientation = orig
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = android.view.WindowManager.LayoutParams().apply {
                copyFrom(act.window.attributes)
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            act.window.attributes = attrs
        }
        systemUiController?.showSystemBars()
        savedStateHandle?.remove<Int>(KEY_ORIG_ORIENTATION)
    }

    override fun onConfigurationChange() {
        // Safe no-op during config change to avoid altering saved state
    }

    override fun reapplyFullscreen() {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return

        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = android.view.WindowManager.LayoutParams().apply {
                copyFrom(act.window.attributes)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            act.window.attributes = attrs
        }
        systemUiController?.hideSystemBars()
    }
}

fun interface FullscreenHostHandlerFactory {
    fun create(activity: Activity?, savedStateHandle: SavedStateHandle?): FullscreenHostHandler
}

fun interface IntentLauncher {
    fun startActivity(intent: Intent)
}

class ContextIntentLauncher(private val context: Context) : IntentLauncher {
    override fun startActivity(intent: Intent) {
        context.startActivity(intent)
    }
}

fun interface ShareLauncher {
    fun launchShare(title: String, canonicalUrl: String)
}

class DefaultShareLauncher(
    private val context: Context,
    private val intentLauncher: IntentLauncher = ContextIntentLauncher(context)
) : ShareLauncher {
    override fun launchShare(title: String, canonicalUrl: String) {
        if (!ShareUrlValidator.isValid(canonicalUrl)) return
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, canonicalUrl)
            }
            val chooser = Intent.createChooser(shareIntent, "Share video")
            if (context !is Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intentLauncher.startActivity(chooser)
        } catch (_: android.content.ActivityNotFoundException) {
            // Graceful safe catch for missing handler
        } catch (_: Throwable) {
            // Safe fallback
        }
    }
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Internal/test composition locals for isolated testing.
 */
@get:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal val LocalFullscreenHostHandlerFactory =
    androidx.compose.runtime.compositionLocalOf<FullscreenHostHandlerFactory?> { null }

@get:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal val LocalShareLauncher =
    androidx.compose.runtime.compositionLocalOf<ShareLauncher?> { null }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    contentKey: ContentKey,
    viewModel: WatchViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreenHostHandlerFactory: FullscreenHostHandlerFactory? = null,
    onRelatedVideoClick: (ContentKey) -> Unit = {},
    onMinimizeToHome: () -> Unit = {},
    isInPip: Boolean = false,
    playbackUiCoordinator: com.hpre.app.player.PlaybackUiCoordinator? = null,
    initialThumbnailUrl: String? = null,
    onRelatedVideoSelected: ((VideoSummary) -> Unit)? = null,
    onChannelClick: ((ContentKey) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by viewModel.structuralPlaybackState.collectAsStateWithLifecycle()
    val relatedState by viewModel.relatedState.collectAsStateWithLifecycle()
    val commentsState by viewModel.commentsState.collectAsStateWithLifecycle()
    val downloadUiState by viewModel.downloadUiState.collectAsStateWithLifecycle()
    val castDevices by viewModel.castDevices.collectAsStateWithLifecycle()
    val castActiveDevice by viewModel.castActiveDevice.collectAsStateWithLifecycle()
    val castError by viewModel.castError.collectAsStateWithLifecycle()
    var isCastSheetOpen by remember { mutableStateOf(false) }
    val commentsPagination by viewModel.commentsPagination.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isFullscreen = uiState.isFullscreen

    val injectedFactory = fullscreenHostHandlerFactory ?: LocalFullscreenHostHandlerFactory.current
    val injectedShareLauncher = LocalShareLauncher.current

    val activity = context.findActivity()
    val hostHandler = remember(injectedFactory, activity, viewModel) {
        if (injectedFactory != null) {
            injectedFactory.create(activity, viewModel.savedStateHandle)
        } else {
            DefaultFullscreenHostHandler(activity, viewModel.savedStateHandle)
        }
    }
    val launcher = remember(injectedShareLauncher, context) {
        injectedShareLauncher ?: DefaultShareLauncher(context)
    }

    LaunchedEffect(contentKey, initialThumbnailUrl) {
        viewModel.load(contentKey, initialThumbnailUrl = initialThumbnailUrl)
    }
    // With background playback off, stopping the activity clears the media; reload it on return
    // (a no-op while the same video is still active).
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        viewModel.load(contentKey, initialThumbnailUrl = initialThumbnailUrl)
    }

    // Fullscreen back handler: back exits fullscreen first
    BackHandler {
        if (isFullscreen) viewModel.setFullscreen(false) else onNavigateBack()
    }

    var wasFullscreen by remember(hostHandler) { mutableStateOf(isFullscreen) }
    // Tracks whether this specific host instance ran an enter transition itself.
    val hostRanEnter = remember(hostHandler) { mutableStateOf(false) }
    DisposableEffect(hostHandler) {
        onDispose {
            val isChangingConfig = activity?.isChangingConfigurations == true
            if (wasFullscreen && !isChangingConfig) {
                hostHandler.exitFullscreen()
            }
        }
    }

    // A recreated host is handed a window with the system bars visible again while the restored
    // fullscreen flag matches the previous one, so no enter transition fires and the status bar
    // would overlap the video. Re-apply the immersive state once per fresh host instead.
    LaunchedEffect(hostHandler) {
        if (isFullscreen && !hostRanEnter.value) {
            hostHandler.reapplyFullscreen()
        }
    }

    DisposableEffect(isFullscreen, hostHandler) {
        if (wasFullscreen != isFullscreen) {
            if (isFullscreen) {
                hostRanEnter.value = true
                hostHandler.enterFullscreen()
            } else {
                hostHandler.exitFullscreen()
            }
            wasFullscreen = isFullscreen
        }
        onDispose { }
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    if (uiState.isFullscreen) {
        // Fullscreen player view
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("watch_screen_fullscreen")
        ) {
            val fullscreenResizeMode = if (isInPip) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            } else {
                uiState.fullScreenResizeMode.toMedia3ResizeMode()
            }
            PlayerSurface(
                playerController = viewModel.playerController,
                coordinator = playbackUiCoordinator,
                owner = com.hpre.app.player.SurfaceOwner.WATCH,
                resizeMode = fullscreenResizeMode,
                modifier = Modifier.fillMaxSize()
            )

            uiState.thumbnailUrl?.let { thumbnail ->
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color.Black).testTag("player_thumbnail_cover")
                )
            }

            WatchPlayerControls(
                structuralState = playbackState,
                readProgress = { viewModel.playerController.readProgress() },
                isPlayerLoading = uiState.isPlayerLoading,
                isFullscreen = true,
                resizeMode = uiState.fullScreenResizeMode,
                onResizeModeSelected = { mode -> viewModel.setFullScreenResizeMode(mode) },
                onPlayPause = { viewModel.playPause() },
                onSeekBy = { delta -> viewModel.seekBy(delta) },
                onSeekTo = { pos -> viewModel.seekTo(pos) },
                onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) },
                onQualitySelected = { quality -> viewModel.selectQuality(quality) },
                sleepTimerEndsAtMs = playbackState.sleepTimerEndsAtMs,
                onSleepTimerSelected = { duration -> viewModel.setSleepTimer(duration) },
                playQueue = playbackState.playQueue,
                onQueueItemClick = { index ->
                    playbackState.playQueue.getOrNull(index)?.let { item ->
                        viewModel.skipQueueTo(index)
                        onRelatedVideoClick(item.key)
                    }
                },
                onQueueItemRemove = { index -> viewModel.removeFromQueue(index) },
                onSubtitleSelected = { lang -> viewModel.selectSubtitle(lang) },
                onAudioLanguageSelected = { lang -> viewModel.selectAudioLanguage(lang) },
                downloadState = downloadUiState.first,
                downloadProgressPercent = downloadUiState.second,
                onDownloadVideo = { viewModel.downloadCurrent(audioOnly = false) },
                onDownloadAudio = { viewModel.downloadCurrent(audioOnly = true) },
                onDownloadRemove = { viewModel.removeCurrentDownload() },
                onCastClick = {
                    viewModel.startCastDiscovery()
                    isCastSheetOpen = true
                },
                isCasting = castActiveDevice != null,
                onToggleFullscreen = { viewModel.setFullscreen(false) },
                onMinimizeToHome = onMinimizeToHome,
                minimizeEnabled = false,
                isInPip = isInPip
            )
        }
    } else {
        // The watch route has no top bar and the host scaffold passes zero content insets, so
        // without this the 16:9 player starts at y=0 and the translucent status bar crops its top
        // edge. Padding here lets the full frame be visible instead.
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .testTag("watch_screen")
        ) {
                // Video Player Container (16:9 aspect ratio)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                        .testTag("player_container")
                ) {
                    PlayerSurface(
                        playerController = viewModel.playerController,
                        coordinator = playbackUiCoordinator,
                        owner = com.hpre.app.player.SurfaceOwner.WATCH,
                        modifier = Modifier.fillMaxSize()
                    )

                    uiState.thumbnailUrl?.let { thumbnail ->
                        AsyncImage(
                            model = thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(Color.Black).testTag("player_thumbnail_cover")
                        )
                    }

                    WatchPlayerControls(
                        structuralState = playbackState,
                        readProgress = { viewModel.playerController.readProgress() },
                        isPlayerLoading = uiState.isPlayerLoading,
                        isFullscreen = false,
                        onPlayPause = { viewModel.playPause() },
                        onSeekBy = { delta -> viewModel.seekBy(delta) },
                        onSeekTo = { pos -> viewModel.seekTo(pos) },
                        onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) },
                        onQualitySelected = { quality -> viewModel.selectQuality(quality) },
                        sleepTimerEndsAtMs = playbackState.sleepTimerEndsAtMs,
                        onSleepTimerSelected = { duration -> viewModel.setSleepTimer(duration) },
                        playQueue = playbackState.playQueue,
                        onQueueItemClick = { index ->
                            playbackState.playQueue.getOrNull(index)?.let { item ->
                                viewModel.skipQueueTo(index)
                                onRelatedVideoClick(item.key)
                            }
                        },
                        onQueueItemRemove = { index -> viewModel.removeFromQueue(index) },
                        onSubtitleSelected = { lang -> viewModel.selectSubtitle(lang) },
                        onAudioLanguageSelected = { lang -> viewModel.selectAudioLanguage(lang) },
                        downloadState = downloadUiState.first,
                        downloadProgressPercent = downloadUiState.second,
                        onDownloadVideo = { viewModel.downloadCurrent(audioOnly = false) },
                        onDownloadAudio = { viewModel.downloadCurrent(audioOnly = true) },
                        onDownloadRemove = { viewModel.removeCurrentDownload() },
                        onCastClick = {
                            viewModel.startCastDiscovery()
                            isCastSheetOpen = true
                        },
                        isCasting = castActiveDevice != null,
                        onToggleFullscreen = { viewModel.setFullscreen(true) },
                        onMinimizeToHome = onMinimizeToHome,
                        minimizeEnabled = isPortrait,
                        isInPip = isInPip
                    )
                }

                // Metadata, loading, or error content below player
                val error = uiState.error ?: playbackState.error
                if (error != null) {
                    ErrorPane(
                        error = error,
                        onRetry = { viewModel.retry() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (uiState.isLoading && uiState.details == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("watch_loading_indicator"),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.details != null) {
                    val isSubscribed by viewModel.isSubscribed.collectAsStateWithLifecycle()
                    val playlists by viewModel.localPlaylists.collectAsStateWithLifecycle()

                    WatchMetadataContent(
                        details = uiState.details!!,
                        isSubscribed = isSubscribed,
                        playlists = playlists,
                        onToggleSubscription = { viewModel.toggleSubscription() },
                        onAddToPlaylist = { playlistId, result -> viewModel.addVideoToPlaylist(playlistId, result) },
                        onCreatePlaylistAndAdd = { title, result -> viewModel.createPlaylistAndAddVideo(title, result) },
                        shareLauncher = launcher,
                        relatedState = relatedState,
                        commentsState = commentsState,
                        commentsExpanded = uiState.commentsExpanded,
                        commentsPagination = commentsPagination,
                        onCommentsExpandedChange = viewModel::setCommentsExpanded,
                        onRestartComments = viewModel::restartComments,
                        onRelatedVideoClick = onRelatedVideoClick,
                        onRelatedVideoSelected = onRelatedVideoSelected,
                        onRetryRelated = viewModel::retryRelated,
                        onRefreshRelated = viewModel::refreshRelated,
                        onRetryComments = viewModel::retryComments,
                        onLoadMoreComments = viewModel::loadMoreComments,
                        onChannelClick = onChannelClick,
                        onEnqueueVideo = { video, playNext ->
                            viewModel.enqueue(com.hpre.app.player.QueuedItem(video.key, video.title), playNext)
                        },
                        allowSheets = !isInPip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
        }
    }

    if (isCastSheetOpen) {
        CastDeviceDialog(
            devices = castDevices,
            activeDevice = castActiveDevice,
            error = castError,
            isPlaying = playbackState.isPlaying,
            onSelect = { device -> viewModel.castTo(device) },
            onPause = { viewModel.castPause() },
            onResume = { viewModel.castResume() },
            onDisconnect = { viewModel.castDisconnect() },
            onDismiss = {
                isCastSheetOpen = false
                viewModel.stopCastDiscovery()
                viewModel.consumeCastError()
            }
        )
    }
}

@Composable
private fun WatchPlayerControls(
    structuralState: com.hpre.app.player.PlaybackState,
    readProgress: suspend () -> com.hpre.app.player.PlaybackProgress,
    isPlayerLoading: Boolean,
    isFullscreen: Boolean,
    resizeMode: FullScreenResizeMode = FullScreenResizeMode.FIT,
    onResizeModeSelected: (FullScreenResizeMode) -> Unit = {},
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onQualitySelected: (com.hpre.app.player.QualityOption) -> Unit,
    sleepTimerEndsAtMs: Long? = null,
    onSleepTimerSelected: (Long?) -> Unit = {},
    playQueue: List<com.hpre.app.player.QueuedItem> = emptyList(),
    onQueueItemClick: (Int) -> Unit = {},
    onQueueItemRemove: (Int) -> Unit = {},
    onSubtitleSelected: (String?) -> Unit = {},
    onAudioLanguageSelected: (String?) -> Unit = {},
    downloadState: com.hpre.app.download.DownloadUiState = com.hpre.app.download.DownloadUiState.NONE,
    downloadProgressPercent: Int = 0,
    onDownloadVideo: () -> Unit = {},
    onDownloadAudio: () -> Unit = {},
    onDownloadRemove: () -> Unit = {},
    onCastClick: () -> Unit = {},
    isCasting: Boolean = false,
    onToggleFullscreen: () -> Unit,
    onMinimizeToHome: () -> Unit,
    minimizeEnabled: Boolean,
    isInPip: Boolean
) {
    PlayerControlsOverlay(
        playbackState = structuralState.copy(
            isLoading = structuralState.isLoading || isPlayerLoading
        ),
        isFullscreen = isFullscreen,
        resizeMode = resizeMode,
        onResizeModeSelected = onResizeModeSelected,
        onPlayPause = onPlayPause,
        onSeekBy = onSeekBy,
        onSeekTo = onSeekTo,
        onSpeedSelected = onSpeedSelected,
        onQualitySelected = onQualitySelected,
        sleepTimerEndsAtMs = sleepTimerEndsAtMs,
        onSleepTimerSelected = onSleepTimerSelected,
        playQueue = playQueue,
        onQueueItemClick = onQueueItemClick,
        onQueueItemRemove = onQueueItemRemove,
        onSubtitleSelected = onSubtitleSelected,
        onAudioLanguageSelected = onAudioLanguageSelected,
        downloadState = downloadState,
        downloadProgressPercent = downloadProgressPercent,
        onDownloadVideo = onDownloadVideo,
        onDownloadAudio = onDownloadAudio,
        onDownloadRemove = onDownloadRemove,
        onCastClick = onCastClick,
        isCasting = isCasting,
        onToggleFullscreen = onToggleFullscreen,
        readProgress = readProgress,
        onMinimizeToHome = onMinimizeToHome,
        minimizeEnabled = minimizeEnabled,
        isInPip = isInPip
    )
}

const val WATCH_KEY_TITLE = "section:watch_title"
const val WATCH_KEY_VIEWS_DATE = "section:watch_views_date"
const val WATCH_KEY_ACTIONS = "section:watch_actions"
const val WATCH_KEY_CHANNEL_CARD = "section:watch_channel_card"
const val WATCH_KEY_DIVIDER = "section:watch_divider"

@Composable
fun WatchMetadataContent(
    details: VideoDetails,
    isSubscribed: Boolean = false,
    playlists: List<com.hpre.app.repository.LocalPlaylist> = emptyList(),
    onToggleSubscription: () -> Unit = {},
    onAddToPlaylist: (Long, (Boolean) -> Unit) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
    shareLauncher: ShareLauncher = DefaultShareLauncher(LocalContext.current),
    relatedState: RefreshableAsyncState<List<com.hpre.app.model.VideoSummary>> = RefreshableAsyncState.initial(),
    commentsState: com.hpre.app.ui.common.AsyncState<com.hpre.app.model.CommentPage> = com.hpre.app.ui.common.AsyncState.Empty,
    onRelatedVideoClick: (ContentKey) -> Unit = {},
    onRetryRelated: () -> Unit = {},
    onRefreshRelated: () -> Unit = {},
    onRetryComments: () -> Unit = {},
    onLoadMoreComments: () -> Unit = {},
    commentsExpanded: Boolean = false,
    commentsPagination: CommentsPaginationState = CommentsPaginationState(),
    onCommentsExpandedChange: (Boolean) -> Unit = {},
    onRestartComments: () -> Unit = {},
    modifier: Modifier = Modifier,
    lazyListState: LazyListState? = null,
    onRelatedVideoSelected: ((VideoSummary) -> Unit)? = null,
    onChannelClick: ((ContentKey) -> Unit)? = null,
    onEnqueueVideo: ((VideoSummary, Boolean) -> Unit)? = null,
    allowSheets: Boolean = true
) {
    val effectiveLazyListState = lazyListState ?: rememberLazyListState()
    var isDescriptionExpanded by rememberSaveable(details.key.serviceId, details.key.nativeId) {
        mutableStateOf(false)
    }
    var isTitleExpanded by rememberSaveable(details.key.serviceId, details.key.nativeId) {
        mutableStateOf(false)
    }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var playlistSaveFailed by remember { mutableStateOf(false) }
    var playlistSaving by remember { mutableStateOf(false) }

    val nextPageToken = (commentsState as? com.hpre.app.ui.common.AsyncState.Content)?.value?.nextPageToken

    // Per-video dedupe for the comment sheet's footer sentinel. Lives here, not in the sheet, so
    // closing and reopening the sheet does not re-request a page already asked for.
    var lastTriggeredToken by remember(details.key.serviceId, details.key.nativeId) {
        mutableStateOf<com.hpre.app.model.PageToken?>(null)
    }

    LazyColumn(
        state = effectiveLazyListState,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .testTag("watch_lazy_column")
            .testTag("watch_metadata_content")
    ) {
        // Video Title — clamped to 2 lines, tap to expand
        item(key = WATCH_KEY_TITLE) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = details.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (isTitleExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable { isTitleExpanded = !isTitleExpanded }
                    .testTag("watch_video_title")
            )
        }

        // View count & date metadata
        item(key = WATCH_KEY_VIEWS_DATE) {
            Spacer(modifier = Modifier.height(6.dp))
            val viewsText = details.viewCount?.let {
                pluralStringResource(R.plurals.watch_view_count, it.toInt(), it)
            } ?: ""
            Text(
                text = viewsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Channel card: avatar + name + subscribers, with the Follow action on the same row.
        item(key = WATCH_KEY_CHANNEL_CARD) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(HPreShapes.Card),
                modifier = Modifier.fillMaxWidth().testTag("watch_channel_card")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (onChannelClick != null && details.channelKey != null) {
                                    Modifier.clickable { onChannelClick.invoke(details.channelKey!!) }
                                } else {
                                    Modifier
                                }
                            )
                            .testTag("watch_channel_area")
                    ) {
                        if (!details.channelAvatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = details.channelAvatarUrl,
                                contentDescription = stringResource(R.string.watch_channel_avatar),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        Column {
                            Text(
                                text = details.channelName ?: stringResource(R.string.watch_unknown_channel),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("watch_channel_name")
                            )
                            if (!details.subscriberCountText.isNullOrBlank()) {
                                Text(
                                    text = details.subscriberCountText ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (details.channelKey != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val followLabel = stringResource(
                            if (isSubscribed) R.string.watch_following else R.string.watch_follow
                        )
                        if (isSubscribed) {
                            FilledTonalButton(
                                onClick = onToggleSubscription,
                                modifier = Modifier.testTag("watch_follow_button")
                            ) { Text(followLabel) }
                        } else {
                            Button(
                                onClick = onToggleSubscription,
                                modifier = Modifier.testTag("watch_follow_button")
                            ) { Text(followLabel) }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Actions row: Save / Share
        item(key = WATCH_KEY_ACTIONS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("watch_action_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { showPlaylistSheet = true },
                    label = { Text(stringResource(R.string.watch_save)) },
                    leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                    shape = CircleShape,
                    border = null,
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.testTag("watch_add_playlist_button")
                )
                if (ShareUrlValidator.isValid(details.canonicalUrl)) {
                    AssistChip(
                        onClick = { shareLauncher.launchShare(details.title, details.canonicalUrl) },
                        label = { Text(stringResource(R.string.watch_share)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        shape = CircleShape,
                        border = null,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier.testTag("watch_share_button")
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (!details.description.isNullOrBlank()) {
            item(key = "section:watch_description") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isDescriptionExpanded = !isDescriptionExpanded }
                        .testTag("watch_description_container")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.watch_description),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (isDescriptionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (isDescriptionExpanded) R.string.watch_collapse else R.string.watch_expand
                            )
                        )
                    }

                    Text(
                        text = details.description ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("watch_description_text")
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        item(key = WATCH_KEY_DIVIDER) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Comments entry — the actual list lives in a bottom sheet so it cannot push related
        // videos hundreds of rows down.
        item(key = WATCH_KEY_COMMENTS_HEADER) {
            Surface(
                onClick = { onCommentsExpandedChange(true) },
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(HPreShapes.Card),
                modifier = Modifier.fillMaxWidth().testTag("comments_section")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.comments_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).testTag("comments_toggle")
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.comments_expand)
                    )
                }
            }
        }

        item(key = "section:comments_related_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }

        relatedVideoItems(
            state = relatedState,
            onVideoClick = onRelatedVideoClick,
            onRetry = onRetryRelated,
            onRefresh = onRefreshRelated,
            onVideoSelected = onRelatedVideoSelected,
            onChannelClick = onChannelClick,
            onEnqueueVideo = onEnqueueVideo
        )

        item(key = "section:watch_bottom_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (commentsExpanded && allowSheets) {
        CommentsSheet(
            state = commentsState,
            pagination = commentsPagination,
            onRetry = onRetryComments,
            onLoadMore = onLoadMoreComments,
            onRestart = {
                lastTriggeredToken = null
                onRestartComments()
            },
            onSentinelReached = {
                val token = nextPageToken
                if (token != null && token != lastTriggeredToken) {
                    lastTriggeredToken = token
                    onLoadMoreComments()
                }
            },
            onDismiss = { onCommentsExpandedChange(false) }
        )
    }

    if (showPlaylistSheet) {
        AddToPlaylistSheet(
            playlists = playlists,
            saving = playlistSaving,
            saveFailed = playlistSaveFailed,
            onAddToPlaylist = { pId ->
                if (!playlistSaving) {
                    playlistSaving = true
                    playlistSaveFailed = false
                    onAddToPlaylist(pId) { success ->
                        playlistSaving = false
                        playlistSaveFailed = !success
                        if (success) showPlaylistSheet = false
                    }
                }
            },
            onCreateNewPlaylist = { title ->
                if (!playlistSaving) {
                    playlistSaving = true
                    playlistSaveFailed = false
                    onCreatePlaylistAndAdd(title) { success ->
                        playlistSaving = false
                        playlistSaveFailed = !success
                        if (success) showPlaylistSheet = false
                    }
                }
            },
            onDismiss = {
                if (!playlistSaving) {
                    showPlaylistSheet = false
                    playlistSaveFailed = false
                }
            }
        )
    }
}

/**
 * Save-to-playlist sheet. Saving state and failures render inside the sheet — never a second
 * dialog stacked on top — and the caller only closes it after a successful write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    playlists: List<com.hpre.app.repository.LocalPlaylist>,
    saving: Boolean,
    saveFailed: Boolean,
    onAddToPlaylist: (Long) -> Unit,
    onCreateNewPlaylist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var isCreatingNew by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("add_to_playlist_sheet")
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(
                    if (isCreatingNew) R.string.watch_create_and_add_playlist
                    else R.string.watch_add_to_playlist
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (saving) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("watch_playlist_saving")
                )
            }
            if (saveFailed) {
                Text(
                    text = stringResource(R.string.watch_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .testTag("watch_playlist_save_error")
                )
            }

            if (isCreatingNew) {
                androidx.compose.material3.OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text(stringResource(R.string.watch_playlist_title)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("watch_playlist_new_title_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { if (!saving) isCreatingNew = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            if (newTitle.isNotBlank()) {
                                onCreateNewPlaylist(newTitle.trim())
                            }
                        },
                        enabled = newTitle.isNotBlank() && !saving,
                        modifier = Modifier.testTag("watch_create_playlist_confirm")
                    ) {
                        Text(stringResource(R.string.watch_create_and_save))
                    }
                }
            } else {
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.watch_no_playlists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    ) {
                        items(playlists.size, key = { playlists[it].playlistId }) { index ->
                            val playlist = playlists[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !saving) { onAddToPlaylist(playlist.playlistId) }
                                    .padding(vertical = 10.dp)
                                    .testTag("watch_playlist_option_${playlist.playlistId}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = playlist.title,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { isCreatingNew = true },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("watch_create_new_playlist_button")
                ) {
                    Text(stringResource(R.string.watch_new_playlist))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}


@Composable
private fun CastDeviceDialog(
    devices: List<com.hpre.app.cast.FCastDevice>,
    activeDevice: com.hpre.app.cast.FCastDevice?,
    error: String?,
    isPlaying: Boolean,
    onSelect: (com.hpre.app.cast.FCastDevice) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(stringResource(R.string.cast_devices_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (error != null) {
                    Text(
                        text = stringResource(
                            when (error) {
                                "cast_no_playable_stream" -> R.string.cast_error_no_stream
                                else -> R.string.cast_error_connect
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (activeDevice != null) {
                    Text(
                        text = stringResource(R.string.cast_connected_to, activeDevice.name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        if (isPlaying) {
                            TextButton(onClick = onPause, modifier = Modifier.testTag("cast_pause")) {
                                Text(stringResource(R.string.action_pause))
                            }
                        } else {
                            TextButton(onClick = onResume, modifier = Modifier.testTag("cast_resume")) {
                                Text(stringResource(R.string.action_play))
                            }
                        }
                        TextButton(onClick = onDisconnect, modifier = Modifier.testTag("cast_disconnect")) {
                            Text(stringResource(R.string.cast_disconnect))
                        }
                    }
                } else if (devices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cast_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("cast_empty")
                    )
                } else {
                    devices.forEach { device ->
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(device) }
                                .padding(vertical = 12.dp)
                                .testTag("cast_device_${device.name}")
                        )
                    }
                }
            }
        }
    )
}
