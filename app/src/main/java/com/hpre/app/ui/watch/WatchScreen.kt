package com.hpre.app.ui.watch

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.VideoViewportPrefetchEffect

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
        val v = view ?: return
        val insetsController = WindowCompat.getInsetsController(win, v)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun showSystemBars() {
        val win = window ?: return
        val v = view ?: return
        val insetsController = WindowCompat.getInsetsController(win, v)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
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
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
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
    prefetchVideos: suspend (List<ContentKey>) -> Unit = {},
    initialThumbnailUrl: String? = null,
    onRelatedVideoSelected: ((VideoSummary) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by viewModel.structuralPlaybackState.collectAsStateWithLifecycle()
    val relatedState by viewModel.relatedState.collectAsStateWithLifecycle()
    val commentsState by viewModel.commentsState.collectAsStateWithLifecycle()
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
                        onAddToPlaylist = { playlistId -> viewModel.addVideoToPlaylist(playlistId) },
                        onCreatePlaylistAndAdd = { title -> viewModel.createPlaylistAndAddVideo(title) },
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
                        prefetchVideos = prefetchVideos,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
        }
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
    onAddToPlaylist: (Long) -> Unit = {},
    onCreatePlaylistAndAdd: (String) -> Unit = {},
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
    prefetchVideos: suspend (List<ContentKey>) -> Unit = {},
    onRelatedVideoSelected: ((VideoSummary) -> Unit)? = null
) {
    val effectiveLazyListState = lazyListState ?: rememberLazyListState()
    VideoViewportPrefetchEffect(
        effectiveLazyListState,
        relatedState.value.orEmpty().map { it.key }
    ) {
        // No-op prefetch to prevent network queue congestion during playback
    }
    var isDescriptionExpanded by rememberSaveable(details.key.serviceId, details.key.nativeId) {
        mutableStateOf(false)
    }
    var showPlaylistSheet by remember { mutableStateOf(false) }

    val currentOnLoadMoreComments = rememberUpdatedState(onLoadMoreComments)
    val nextPageToken = (commentsState as? com.hpre.app.ui.common.AsyncState.Content)?.value?.nextPageToken

    var lastTriggeredToken by remember(details.key.serviceId, details.key.nativeId) {
        mutableStateOf<com.hpre.app.model.PageToken?>(null)
    }

    LaunchedEffect(effectiveLazyListState, nextPageToken, details.key, commentsExpanded, commentsPagination) {
        if (!commentsExpanded) {
            lastTriggeredToken = null
            return@LaunchedEffect
        }
        if (nextPageToken == null || commentsPagination.isLoading || commentsPagination.error != null) return@LaunchedEffect
        snapshotFlow {
            val visibleKeys = effectiveLazyListState.layoutInfo.visibleItemsInfo.map { it.key }
            visibleKeys.contains(WATCH_KEY_COMMENTS_LOAD_MORE)
        }
            .distinctUntilChanged()
            .collect { sentinelVisible ->
                if (sentinelVisible && nextPageToken != lastTriggeredToken) {
                    lastTriggeredToken = nextPageToken
                    currentOnLoadMoreComments.value()
                }
            }
    }

    LazyColumn(
        state = effectiveLazyListState,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .testTag("watch_lazy_column")
            .testTag("watch_metadata_content")
    ) {
        // Video Title
        item(key = WATCH_KEY_TITLE) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = details.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("watch_video_title")
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

        // Actions row
        item(key = WATCH_KEY_ACTIONS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("watch_action_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (details.channelKey != null) {
                    AssistChip(
                        onClick = onToggleSubscription,
                        label = {
                            Text(
                                stringResource(
                                    if (isSubscribed) R.string.watch_following else R.string.watch_follow
                                )
                            )
                        },
                        modifier = Modifier.testTag("watch_follow_button")
                    )
                }
                AssistChip(
                    onClick = { showPlaylistSheet = true },
                    label = { Text(stringResource(R.string.watch_save)) },
                    leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                    modifier = Modifier.testTag("watch_add_playlist_button")
                )
                if (ShareUrlValidator.isValid(details.canonicalUrl)) {
                    AssistChip(
                        onClick = { shareLauncher.launchShare(details.title, details.canonicalUrl) },
                        label = { Text(stringResource(R.string.watch_share)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        modifier = Modifier.testTag("watch_share_button")
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Channel card and description
        item(key = WATCH_KEY_CHANNEL_CARD) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("watch_channel_card")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (!details.channelAvatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = details.channelAvatarUrl,
                                    contentDescription = stringResource(R.string.watch_channel_avatar),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
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
                    }
                    if (!details.description.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
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
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = WATCH_KEY_DIVIDER) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
        }

        commentsItems(
            state = commentsState,
            onRetry = onRetryComments,
            expanded = commentsExpanded,
            onExpandedChange = onCommentsExpandedChange,
            pagination = commentsPagination,
            onLoadMore = onLoadMoreComments,
            onRestart = {
                lastTriggeredToken = null
                onRestartComments()
            }
        )

        item(key = "section:comments_related_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }

        relatedVideoItems(
            state = relatedState,
            onVideoClick = onRelatedVideoClick,
            onRetry = onRetryRelated,
            onRefresh = onRefreshRelated,
            onVideoSelected = onRelatedVideoSelected
        )

        item(key = "section:watch_bottom_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showPlaylistSheet) {
        AddToPlaylistDialog(
            playlists = playlists,
            onAddToPlaylist = { pId ->
                onAddToPlaylist(pId)
                showPlaylistSheet = false
            },
            onCreateNewPlaylist = { title ->
                onCreatePlaylistAndAdd(title)
                showPlaylistSheet = false
            },
            onDismiss = { showPlaylistSheet = false }
        )
    }
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<com.hpre.app.repository.LocalPlaylist>,
    onAddToPlaylist: (Long) -> Unit,
    onCreateNewPlaylist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var isCreatingNew by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isCreatingNew) R.string.watch_create_and_add_playlist
                    else R.string.watch_add_to_playlist
                )
            )
        },
        text = {
            if (isCreatingNew) {
                androidx.compose.material3.OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text(stringResource(R.string.watch_playlist_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("watch_playlist_new_title_input")
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (playlists.isEmpty()) {
                        Text(
                            text = stringResource(R.string.watch_no_playlists),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    } else {
                        playlists.forEach { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddToPlaylist(playlist.playlistId) }
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
                    TextButton(
                        onClick = { isCreatingNew = true },
                        modifier = Modifier.fillMaxWidth().testTag("watch_create_new_playlist_button")
                    ) {
                        Text(stringResource(R.string.watch_new_playlist))
                    }
                }
            }
        },
        confirmButton = {
            if (isCreatingNew) {
                TextButton(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            onCreateNewPlaylist(newTitle.trim())
                        }
                    },
                    enabled = newTitle.isNotBlank(),
                    modifier = Modifier.testTag("watch_create_playlist_confirm")
                ) {
                    Text(stringResource(R.string.watch_create_and_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
