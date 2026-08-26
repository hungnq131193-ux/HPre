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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.SavedStateHandle
import coil.compose.AsyncImage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoDetails
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
        systemUiController?.hideSystemBars()
    }

    override fun exitFullscreen() {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return

        val orig = savedStateHandle?.get<Int>(KEY_ORIG_ORIENTATION)
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        act.requestedOrientation = orig
        systemUiController?.showSystemBars()
        savedStateHandle?.remove<Int>(KEY_ORIG_ORIENTATION)
    }

    override fun onConfigurationChange() {
        // Safe no-op during config change to avoid altering saved state
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    contentKey: ContentKey,
    viewModel: WatchViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreenHostHandlerFactory: FullscreenHostHandlerFactory? = null,
    onRelatedVideoClick: (ContentKey) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val relatedState by viewModel.relatedState.collectAsStateWithLifecycle()
    val commentsState by viewModel.commentsState.collectAsStateWithLifecycle()
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

    LaunchedEffect(contentKey) {
        viewModel.load(contentKey)
    }

    // Fullscreen back handler: back exits fullscreen first
    BackHandler(enabled = isFullscreen) {
        viewModel.setFullscreen(false)
    }

    var wasFullscreen by remember(hostHandler) { mutableStateOf(isFullscreen) }
    DisposableEffect(hostHandler) {
        onDispose {
            val isChangingConfig = activity?.isChangingConfigurations == true
            if (wasFullscreen && !isChangingConfig) {
                hostHandler.exitFullscreen()
            }
        }
    }

    DisposableEffect(isFullscreen, hostHandler) {
        if (wasFullscreen != isFullscreen) {
            if (isFullscreen) {
                hostHandler.enterFullscreen()
            } else {
                hostHandler.exitFullscreen()
            }
            wasFullscreen = isFullscreen
        }
        onDispose { }
    }

    if (uiState.isFullscreen) {
        // Fullscreen player view
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("watch_screen_fullscreen")
        ) {
            PlayerSurface(
                playerController = viewModel.playerController,
                modifier = Modifier.fillMaxSize()
            )

            PlayerControlsOverlay(
                playbackState = playbackState,
                isFullscreen = true,
                onPlayPause = { viewModel.playPause() },
                onSeekBy = { delta -> viewModel.seekBy(delta) },
                onSeekTo = { pos -> viewModel.seekTo(pos) },
                onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) },
                onQualitySelected = { quality -> viewModel.selectQuality(quality) },
                onToggleFullscreen = { viewModel.setFullscreen(false) }
            )
        }
    } else {
        Column(modifier = modifier.fillMaxSize().testTag("watch_screen")) {
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
                        modifier = Modifier.fillMaxSize()
                    )

                    PlayerControlsOverlay(
                        playbackState = playbackState,
                        isFullscreen = false,
                        onPlayPause = { viewModel.playPause() },
                        onSeekBy = { delta -> viewModel.seekBy(delta) },
                        onSeekTo = { pos -> viewModel.seekTo(pos) },
                        onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) },
                        onQualitySelected = { quality -> viewModel.selectQuality(quality) },
                        onToggleFullscreen = { viewModel.setFullscreen(true) }
                    )

                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(8.dp)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("watch_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    }
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
                        onRelatedVideoClick = onRelatedVideoClick,
                        onRetryRelated = viewModel::retryRelated,
                        onRetryComments = viewModel::retryComments,
                        onLoadMoreComments = viewModel::loadMoreComments,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
        }
    }
}

@Composable
fun WatchMetadataContent(
    details: VideoDetails,
    isSubscribed: Boolean = false,
    playlists: List<com.hpre.app.repository.LocalPlaylist> = emptyList(),
    onToggleSubscription: () -> Unit = {},
    onAddToPlaylist: (Long) -> Unit = {},
    onCreatePlaylistAndAdd: (String) -> Unit = {},
    shareLauncher: ShareLauncher = DefaultShareLauncher(LocalContext.current),
    relatedState: com.hpre.app.ui.common.AsyncState<List<com.hpre.app.model.VideoSummary>> = com.hpre.app.ui.common.AsyncState.Empty,
    commentsState: com.hpre.app.ui.common.AsyncState<com.hpre.app.model.CommentPage> = com.hpre.app.ui.common.AsyncState.Empty,
    onRelatedVideoClick: (ContentKey) -> Unit = {},
    onRetryRelated: () -> Unit = {},
    onRetryComments: () -> Unit = {},
    onLoadMoreComments: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isDescriptionExpanded by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("watch_metadata_content")
    ) {
        // Video Title
        Text(
            text = details.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("watch_video_title")
        )

        Spacer(modifier = Modifier.height(6.dp))

        // View count & date metadata
        val viewsText = details.viewCount?.let { "$it views" } ?: ""
        val dateText = details.publishedTimestamp?.let { "• Timestamp: $it" } ?: ""
        Text(
            text = "$viewsText $dateText".trim(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                    label = { Text(if (isSubscribed) "Following locally" else "Follow locally") },
                    modifier = Modifier.testTag("watch_follow_button")
                )
            }
            AssistChip(
                onClick = { showPlaylistSheet = true },
                label = { Text("Save") },
                leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                modifier = Modifier.testTag("watch_add_playlist_button")
            )
            if (ShareUrlValidator.isValid(details.canonicalUrl)) {
                AssistChip(
                    onClick = { shareLauncher.launchShare(details.title, details.canonicalUrl) },
                    label = { Text("Share") },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    modifier = Modifier.testTag("watch_share_button")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

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
                        contentDescription = "Channel Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column {
                    Text(
                        text = details.channelName ?: "Unknown Channel",
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
                            text = "Description",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (isDescriptionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isDescriptionExpanded) "Collapse" else "Expand"
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(modifier = Modifier.height(16.dp))

        CommentsSection(commentsState, onRetryComments, onLoadMoreComments)
        Spacer(modifier = Modifier.height(16.dp))
        RelatedVideosSection(relatedState, onRelatedVideoClick, onRetryRelated)
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
        title = { Text(if (isCreatingNew) "Create & Add to Playlist" else "Add to Playlist") },
        text = {
            if (isCreatingNew) {
                androidx.compose.material3.OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Playlist Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("watch_playlist_new_title_input")
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (playlists.isEmpty()) {
                        Text(
                            text = "No local playlists yet. Create one below!",
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
                        Text("+ New Playlist")
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
                    Text("Create & Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
