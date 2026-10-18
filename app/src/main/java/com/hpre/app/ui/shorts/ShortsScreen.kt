package com.hpre.app.ui.shorts

import android.content.Intent
import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreLiveBadge
import com.hpre.app.core.designsystem.HPreOnLiveBadge
import com.hpre.app.model.Comment
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.SessionPlayerController
import com.hpre.app.ui.common.DelayedLoadingPane
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.VideoFormat
import com.hpre.app.ui.watch.PlayerSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortsScreen(
    viewModel: ShortsViewModel,
    onSearchClick: () -> Unit,
    onChannelClick: (ContentKey) -> Unit,
    onOpenFullWatch: (VideoSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val commentsSheet by viewModel.commentsSheet.collectAsStateWithLifecycle()
    val playbackState by viewModel.playerController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var handedOffToWatch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.consumePendingSeedIfPresent()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(viewModel.playerController) {
        onDispose {
            viewModel.onScreenDisposed()
            if (!handedOffToWatch) {
                val controller = viewModel.playerController
                if (controller is SessionPlayerController) {
                    controller.clearMedia()
                } else {
                    controller.pause()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("shorts_screen")
    ) {
        when (val state = uiState) {
            is ShortsUiState.Loading -> {
                DelayedLoadingPane(testTag = "shorts_loading")
            }
            is ShortsUiState.Empty -> {
                EmptyPane(
                    message = stringResource(R.string.shorts_empty),
                    testTag = "shorts_empty"
                )
            }
            is ShortsUiState.Error -> {
                ErrorPane(
                    error = state.error,
                    onRetry = { viewModel.retry() },
                    testTag = "shorts_error"
                )
            }
            is ShortsUiState.Content -> {
                val pagerState = rememberPagerState(
                    initialPage = state.currentIndex.coerceIn(0, (state.items.size - 1).coerceAtLeast(0)),
                    pageCount = { state.items.size }
                )

                LaunchedEffect(state.currentIndex, state.items.size) {
                    if (state.currentIndex in state.items.indices && pagerState.currentPage != state.currentIndex) {
                        pagerState.scrollToPage(state.currentIndex)
                    }
                }

                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { page ->
                            viewModel.onPageSettled(page)
                        }
                }

                // Poll playback progress for the thin progress bar and auto-looping
                var progressFraction by remember { mutableFloatStateOf(0f) }
                val activeVideo = state.items.getOrNull(pagerState.currentPage)
                LaunchedEffect(activeVideo?.key, playbackState.key, playbackState.isPlaying) {
                    progressFraction = 0f
                    if (activeVideo == null) return@LaunchedEffect
                    while (true) {
                        val progress = runCatching { viewModel.playerController.readProgress() }.getOrNull()
                        if (progress != null && progress.durationMs > 0L) {
                            progressFraction = (progress.positionMs.toFloat() / progress.durationMs.toFloat())
                                .coerceIn(0f, 1f)
                            if (progress.positionMs >= progress.durationMs - 320L) {
                                viewModel.playerController.seekTo(0L)
                                viewModel.playerController.play()
                            }
                        } else if (
                            playbackState.key == activeVideo.key &&
                            !playbackState.isPlaying &&
                            !playbackState.isBuffering &&
                            playbackState.durationMs > 0L &&
                            playbackState.currentPositionMs >= playbackState.durationMs - 400L
                        ) {
                            viewModel.playerController.seekTo(0L)
                            viewModel.playerController.play()
                        }
                        delay(200L)
                    }
                }

                VerticalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    key = { index -> state.items[index].key.let { "${it.serviceId}:${it.nativeId}" } },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shorts_vertical_pager")
                ) { pageIndex ->
                    val video = state.items[pageIndex]
                    val isCurrentPage = pageIndex == pagerState.currentPage
                    val isReadyForVideo = isCurrentPage &&
                        playbackState.key == video.key &&
                        state.streamError == null

                    ShortPageItem(
                        video = video,
                        isCurrentPage = isCurrentPage,
                        isReadyForVideo = isReadyForVideo,
                        isStreamLoading = isCurrentPage && (state.isStreamLoading || playbackState.isBuffering),
                        isPlaying = isCurrentPage && playbackState.isPlaying,
                        streamError = if (isCurrentPage) state.streamError ?: playbackState.error else null,
                        progressFraction = if (isCurrentPage) progressFraction else 0f,
                        isLandscape = isLandscape,
                        viewModel = viewModel,
                        onChannelClick = onChannelClick,
                        onCommentsClick = { viewModel.openComments(video.key) },
                        onShareClick = {
                            val shareUrl = "https://www.youtube.com/shorts/${video.key.nativeId}"
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "${video.title}\n$shareUrl")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        },
                        onOpenFullWatch = {
                            handedOffToWatch = true
                            onOpenFullWatch(video)
                        }
                    )
                }
            }
        }

        // Floating top bar (Shorts header + Refresh & Search actions)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = HPreLiveBadge,
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SlowMotionVideo,
                    contentDescription = null,
                    tint = HPreOnLiveBadge,
                    modifier = Modifier.padding(4.dp).size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.nav_shorts),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { viewModel.refresh() },
                modifier = Modifier.testTag("shorts_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                    tint = Color.White
                )
            }
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.testTag("shorts_search_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.action_search),
                    tint = Color.White
                )
            }
        }
    }

    if (commentsSheet.isVisible) {
        ShortsCommentsBottomSheet(
            state = commentsSheet,
            onDismiss = { viewModel.dismissComments() },
            onLoadMore = { viewModel.loadMoreComments() },
            onChannelClick = { key ->
                viewModel.dismissComments()
                onChannelClick(key)
            }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ShortPageItem(
    video: VideoSummary,
    isCurrentPage: Boolean,
    isReadyForVideo: Boolean,
    isStreamLoading: Boolean,
    isPlaying: Boolean,
    streamError: com.hpre.app.core.error.AppError?,
    progressFraction: Float,
    isLandscape: Boolean,
    viewModel: ShortsViewModel,
    onChannelClick: (ContentKey) -> Unit,
    onCommentsClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenFullWatch: () -> Unit
) {
    var showTapFeedback by remember { mutableStateOf(false) }
    var showDelayedSpinner by remember { mutableStateOf(false) }

    LaunchedEffect(showTapFeedback) {
        if (showTapFeedback) {
            delay(550L)
            showTapFeedback = false
        }
    }

    LaunchedEffect(isCurrentPage, isStreamLoading) {
        if (isCurrentPage && isStreamLoading) {
            showDelayedSpinner = false
            delay(450L)
            showDelayedSpinner = true
        } else {
            showDelayedSpinner = false
        }
    }

    val fallbackThumb = remember(video.key.nativeId, video.thumbnailUrl) {
        val raw = video.thumbnailUrl
        if (raw.isNullOrBlank() || raw.contains("hqdefault.jpg") || raw.contains("mqdefault.jpg")) {
            "https://i.ytimg.com/vi/${video.key.nativeId}/oar2.jpg"
        } else {
            raw
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isCurrentPage && streamError == null) {
                    viewModel.playerController.playPause()
                    showTapFeedback = true
                }
            }
            .testTag("short_page_${video.key.nativeId}")
    ) {
        // Background thumbnail while loading or when swiping between pages
        AsyncImage(
            model = fallbackThumb,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Attach single shared ExoPlayer surface only to the current page
        if (isCurrentPage) {
            PlayerSurface(
                playerController = viewModel.playerController,
                coordinator = null,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Cover thumbnail until playback key matches current Short
        if (!isReadyForVideo) {
            AsyncImage(
                model = fallbackThumb,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom gradient scrim for readable title & channel info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        // Center loading spinner or play/pause tap feedback or retry button
        if (streamError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.video_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { viewModel.retry() }) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        } else if (showDelayedSpinner) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
            )
        } else {
            AnimatedVisibility(
                visible = showTapFeedback || (!isStreamLoading && !isPlaying),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(Color.Black.copy(alpha = 0.52f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        }

        // Right-side vertical action rail (like YouTube Shorts)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ShortsActionButton(
                icon = Icons.AutoMirrored.Filled.Comment,
                label = stringResource(R.string.comments_title),
                onClick = onCommentsClick,
                testTag = "shorts_action_comments"
            )
            ShortsActionButton(
                icon = Icons.Default.Share,
                label = stringResource(R.string.watch_share),
                onClick = onShareClick,
                testTag = "shorts_action_share"
            )
            ShortsActionButton(
                icon = Icons.Default.OndemandVideo,
                label = stringResource(R.string.shorts_open_watch),
                onClick = onOpenFullWatch,
                testTag = "shorts_action_open_watch"
            )
        }

        // Bottom-left metadata (Channel avatar + @name + Short title + views)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.80f)
                .padding(start = 16.dp, end = 12.dp, bottom = 22.dp)
        ) {
            val displayChannelName = video.channelName
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("HPre", ignoreCase = true) }
                ?.let { if (it.startsWith("@")) it else "@$it" }
                ?: "@YouTube Shorts"

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        viewModel.onChannelRequested(video, onChannelClick)
                    }
                    .padding(vertical = 4.dp, horizontal = 2.dp)
                    .testTag("shorts_channel_button")
            ) {
                if (!video.channelAvatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = video.channelAvatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.DarkGray)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = displayChannelName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = HPreLiveBadge.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.shorts_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = HPreOnLiveBadge,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (video.title.isNotBlank()) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val viewsText = VideoFormat.viewCount(video.viewCount)
            if (viewsText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = viewsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
        }

        // Bottom thin progress bar
        if (isCurrentPage && progressFraction > 0f) {
            LinearProgressIndicator(
                progress = { progressFraction },
                color = HPreLiveBadge,
                trackColor = Color.White.copy(alpha = 0.24f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun ShortsActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortsCommentsBottomSheet(
    state: ShortsCommentsSheetState,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onChannelClick: (ContentKey) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 520.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.comments_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close)
                    )
                }
            }
            HorizontalDivider()

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.comments_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.comments.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.comments_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.comments, key = { it.commentId }) { comment ->
                            ShortsCommentRow(
                                comment = comment,
                                onChannelClick = onChannelClick
                            )
                        }
                        if (state.nextPageToken != null) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.isAppending) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        TextButton(onClick = onLoadMore) {
                                            Text(stringResource(R.string.comments_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortsCommentRow(
    comment: Comment,
    onChannelClick: (ContentKey) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (!comment.authorAvatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = comment.authorAvatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(enabled = comment.channelKey != null) {
                        comment.channelKey?.let(onChannelClick)
                    }
            )
        } else {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = comment.authorName.ifBlank { stringResource(R.string.watch_unknown_channel) },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = comment.commentText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if ((comment.likeCount ?: 0L) > 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = comment.likeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
