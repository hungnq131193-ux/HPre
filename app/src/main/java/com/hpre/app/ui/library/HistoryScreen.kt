package com.hpre.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.hpre.app.R
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.WatchHistoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: LibraryViewModel,
    onVideoClick: (ContentKey) -> Unit,
    onNavigateBack: () -> Unit,
    initialShortsTab: Boolean = false,
    onShortSelected: ((VideoSummary) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val historyList by viewModel.history.collectAsStateWithLifecycle()
    val regularVideoList by viewModel.regularVideoHistory.collectAsStateWithLifecycle()
    val shortsList by viewModel.shortsHistory.collectAsStateWithLifecycle()
    val historyPage by viewModel.historyPage.collectAsStateWithLifecycle()
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val mutationState by viewModel.mutationState.collectAsStateWithLifecycle()

    var selectedShortsTab by rememberSaveable(initialShortsTab) { mutableStateOf(initialShortsTab) }
    val activeList = if (selectedShortsTab) {
        shortsList
    } else {
        regularVideoList.ifEmpty { historyList.filterNot { it.isShort } }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(historyPage, selectedShortsTab) { listState.scrollToItem(0) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var itemPendingDelete by remember { mutableStateOf<ContentKey?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_history)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("history_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (activeList.isNotEmpty() || historyList.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirmDialog = true },
                            enabled = !mutationState.inFlight,
                            modifier = Modifier.testTag("history_clear_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.history_clear)
                            )
                        }
                    }
                },
                modifier = Modifier.testTag("history_top_bar")
            )
        },
        modifier = modifier.fillMaxSize().testTag("history_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Category selector tabs: Video thường vs Shorts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("history_category_tabs"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val videoCount = regularVideoList.size
                val shortsCount = shortsList.size
                FilterChip(
                    selected = !selectedShortsTab,
                    onClick = { selectedShortsTab = false },
                    label = {
                        Text(
                            text = if (videoCount > 0) {
                                "${stringResource(R.string.history_tab_videos)} ($videoCount)"
                            } else {
                                stringResource(R.string.history_tab_videos)
                            },
                            fontWeight = if (!selectedShortsTab) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("history_tab_videos")
                )
                FilterChip(
                    selected = selectedShortsTab,
                    onClick = { selectedShortsTab = true },
                    label = {
                        Text(
                            text = if (shortsCount > 0) {
                                "${stringResource(R.string.history_tab_shorts)} ($shortsCount)"
                            } else {
                                stringResource(R.string.history_tab_shorts)
                            },
                            fontWeight = if (selectedShortsTab) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("history_tab_shorts")
                )
            }

            if (activeList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedShortsTab) {
                            stringResource(R.string.history_shorts_empty)
                        } else {
                            stringResource(R.string.history_empty)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(if (selectedShortsTab) "history_shorts_list" else "history_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeList, key = { it.key.toString() }, contentType = { if (selectedShortsTab) "short_history_item" else "history_item" }) { item ->
                        if (selectedShortsTab) {
                            ShortHistoryItemRow(
                                item = item,
                                actionsEnabled = !mutationState.inFlight,
                                onClick = {
                                    if (onShortSelected != null) {
                                        onShortSelected(item.toVideoSummary())
                                    } else {
                                        onVideoClick(item.key)
                                    }
                                },
                                onDelete = { itemPendingDelete = item.key }
                            )
                        } else {
                            HistoryItemRow(
                                item = item,
                                actionsEnabled = !mutationState.inFlight,
                                onClick = { onVideoClick(item.key) },
                                onDelete = { itemPendingDelete = item.key }
                            )
                        }
                    }
                }
                if (!selectedShortsTab && historyCount > LibraryViewModel.HISTORY_PAGE_SIZE) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = viewModel::previousHistoryPage,
                            enabled = historyPage > 0,
                            modifier = Modifier.testTag("history_previous_page")
                        ) {
                            Text(stringResource(R.string.history_previous_page))
                        }
                        Text(stringResource(
                            R.string.history_page, historyPage + 1,
                            (historyCount + LibraryViewModel.HISTORY_PAGE_SIZE - 1) / LibraryViewModel.HISTORY_PAGE_SIZE
                        ))
                        TextButton(
                            onClick = viewModel::nextHistoryPage,
                            enabled = (historyPage + 1) * LibraryViewModel.HISTORY_PAGE_SIZE < historyCount,
                            modifier = Modifier.testTag("history_next_page")
                        ) {
                            Text(stringResource(R.string.history_next_page))
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        val clearOpNames = setOf("clearHistory", "clearRegularVideoHistory", "clearShortsHistory")
        AlertDialog(
            onDismissRequest = {
                if (!mutationState.inFlight) {
                    viewModel.consumeMutationResult()
                    showClearConfirmDialog = false
                }
            },
            title = {
                Text(
                    if (selectedShortsTab) {
                        stringResource(R.string.history_clear_shorts_title)
                    } else if (shortsList.isNotEmpty()) {
                        stringResource(R.string.history_clear_videos_title)
                    } else {
                        stringResource(R.string.history_clear_title)
                    }
                )
            },
            text = {
                Column {
                    Text(
                        if (selectedShortsTab) {
                            stringResource(R.string.history_clear_shorts_message)
                        } else if (shortsList.isNotEmpty()) {
                            stringResource(R.string.history_clear_videos_message)
                        } else {
                            stringResource(R.string.history_clear_message)
                        }
                    )
                    val clearError = mutationState.error.takeIf { mutationState.operation in clearOpNames }
                    if (clearError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = com.hpre.app.ui.common.appErrorMessage(clearError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("history_clear_error")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            selectedShortsTab -> viewModel.clearShortsHistory()
                            shortsList.isEmpty() -> viewModel.clearHistory()
                            else -> viewModel.clearRegularVideoHistory()
                        }
                    },
                    enabled = !mutationState.inFlight,
                    modifier = Modifier.testTag("history_clear_dialog_confirm")
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.consumeMutationResult()
                        showClearConfirmDialog = false
                    },
                    enabled = !mutationState.inFlight
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
        LaunchedEffect(mutationState.completed) {
            if (mutationState.completed && mutationState.operation in clearOpNames) {
                viewModel.consumeMutationResult()
                showClearConfirmDialog = false
            }
        }
    }

    itemPendingDelete?.let { key ->
        AlertDialog(
            onDismissRequest = {
                if (!mutationState.inFlight) {
                    viewModel.consumeMutationResult()
                    itemPendingDelete = null
                }
            },
            text = {
                Column {
                    Text(stringResource(R.string.history_remove_confirm))
                    val removeError = mutationState.error.takeIf { mutationState.operation == "deleteHistoryItem" }
                    if (removeError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = com.hpre.app.ui.common.appErrorMessage(removeError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("history_remove_error")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteHistoryItem(key) },
                    enabled = !mutationState.inFlight,
                    modifier = Modifier.testTag("history_remove_dialog_confirm")
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.consumeMutationResult()
                        itemPendingDelete = null
                    },
                    enabled = !mutationState.inFlight
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
        LaunchedEffect(mutationState.completed) {
            if (mutationState.completed && mutationState.operation == "deleteHistoryItem") {
                viewModel.consumeMutationResult()
                itemPendingDelete = null
            }
        }
    }
}

@Composable
private fun HistoryItemRow(
    item: WatchHistoryItem,
    actionsEnabled: Boolean = true,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("history_item_${item.key.nativeId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            if (!item.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxSize()
                ) {}
            }
            item.durationSeconds?.takeIf { it > 0L }?.let { duration ->
                val mins = duration / 60L
                val secs = duration % 60L
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 5.dp, bottom = 5.dp)
                ) {
                    Text(
                        text = "%d:%02d".format(mins, secs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            if (item.playbackPositionMs > 0 && HistoryRepository.shouldOfferResume(item.playbackPositionMs, item.durationSeconds)) {
                val durationMs = (item.durationSeconds ?: 0L) * 1000L
                val progress = if (durationMs > 0) (item.playbackPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .align(Alignment.BottomStart)
                ) {}
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.channelName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.playbackPositionMs > 0 && HistoryRepository.shouldOfferResume(item.playbackPositionMs, item.durationSeconds)) {
                val posSec = item.playbackPositionMs / 1000L
                val min = posSec / 60
                val sec = posSec % 60
                Text(
                    text = stringResource(R.string.history_resume_at, min, sec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        IconButton(
            onClick = onDelete,
            enabled = actionsEnabled,
            modifier = Modifier.testTag("history_delete_${item.key.nativeId}")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.history_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ShortHistoryItemRow(
    item: WatchHistoryItem,
    actionsEnabled: Boolean = true,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("history_short_item_${item.key.nativeId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(68.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!item.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Surface(
                color = Color(0xFFE53935).copy(alpha = 0.92f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = "SHORTS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.channelName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item.durationSeconds?.takeIf { it > 0L }?.let { duration ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${duration}s · Shorts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        IconButton(
            onClick = onDelete,
            enabled = actionsEnabled,
            modifier = Modifier.testTag("history_short_delete_${item.key.nativeId}")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.history_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

