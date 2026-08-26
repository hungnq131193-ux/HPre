package com.hpre.app.ui.shorts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpre.app.ui.watch.DefaultShareLauncher
import com.hpre.app.ui.watch.PlayerSurface
import com.hpre.app.ui.watch.ShareLauncher
import com.hpre.app.ui.watch.ShareUrlValidator
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.UnavailablePane

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(
    viewModel: ShortsViewModel,
    modifier: Modifier = Modifier,
    shareLauncher: ShareLauncher = DefaultShareLauncher(LocalContext.current)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playbackState by viewModel.playerController.state.collectAsStateWithLifecycle()
    val saveErrors by viewModel.saveErrors.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    when (val current = state) {
        ShortsUiState.Loading -> LoadingPane(modifier, "shorts_loading")
        ShortsUiState.Unavailable -> UnavailablePane("Shorts", modifier, "shorts_unavailable")
        ShortsUiState.Empty -> EmptyPane("No Shorts available", modifier, "shorts_empty")
        is ShortsUiState.Error -> ErrorPane(current.error, viewModel::retry, modifier, "shorts_error")
        is ShortsUiState.Content -> {
            val pagerState = rememberPagerState(pageCount = { current.videos.size })
            LaunchedEffect(pagerState.currentPage, current.videos) {
                current.videos.getOrNull(pagerState.currentPage)?.let(viewModel::activate)
            }
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = modifier.fillMaxSize().testTag("shorts_pager")
            ) { page ->
                val video = current.videos[page]
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .testTag("shorts_page_${video.key.nativeId}"),
                    contentAlignment = Alignment.BottomStart
                ) {
                    if (page == pagerState.currentPage) {
                        PlayerSurface(
                            playerController = viewModel.playerController,
                            modifier = Modifier.fillMaxSize().testTag("shorts_active_player")
                        )
                    } else if (video.thumbnailUrl != null) {
                        AsyncImage(
                            model = video.thumbnailUrl,
                            contentDescription = video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().testTag("shorts_thumbnail_${video.key.nativeId}")
                        )
                    }

                    if (page == pagerState.currentPage) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(16.dp)
                                .testTag("shorts_metadata")
                        ) {
                            Text(video.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                            video.channelName?.let {
                                Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                formatShortDuration(video.durationSeconds),
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium
                            )
                            saveErrors[video.key]?.let {
                                Text(
                                    "Could not save video",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("shorts_save_error")
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = viewModel::playPause,
                                    modifier = Modifier.testTag("shorts_play_pause")
                                ) {
                                    Icon(
                                        if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (ShareUrlValidator.isValid(video.canonicalUrl)) {
                                            shareLauncher.launchShare(video.title, video.canonicalUrl)
                                        }
                                    },
                                    modifier = Modifier.testTag("shorts_share")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                                }
                                IconButton(
                                    onClick = { viewModel.save(video) },
                                    modifier = Modifier.testTag("shorts_save")
                                ) {
                                    Icon(Icons.Default.BookmarkBorder, contentDescription = "Save", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatShortDuration(seconds: Long?): String {
    val safe = seconds?.coerceAtLeast(0) ?: 0
    return "%d:%02d".format(safe / 60, safe % 60)
}
