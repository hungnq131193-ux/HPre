package com.hpre.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import coil.compose.AsyncImage
import com.hpre.app.model.Channel
import com.hpre.app.R
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PlaylistSummary
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.VideoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onNavigateBack: () -> Unit,
    onVideoClick: (ContentKey) -> Unit,
    onChannelClick: (ContentKey) -> Unit = {},
    onPlaylistClick: (ContentKey) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val historyState by viewModel.historyState.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }

    val historyFailureMessage = stringResource(R.string.search_history_update_failed)
    LaunchedEffect(historyState.error) {
        if (historyState.error != null) {
            snackbarHostState.showSnackbar(historyFailureMessage)
            viewModel.consumeHistoryError()
        }
    }

    LaunchedEffect(Unit) {
        // Automatically request focus when opening search
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("search_screen")
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search TopBar
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.onQueryChanged(it) },
                    placeholder = { Text(text = stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            viewModel.onQuerySubmitted(query)
                        }
                    ),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChanged("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.search_clear)
                                )
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("search_text_input")
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("search_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            }
        )

        // Filter chips row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            items(SearchFilter.values()) { itemFilter ->
                FilterChip(
                    selected = (filter == itemFilter),
                    onClick = { viewModel.onFilterChanged(itemFilter) },
                    label = {
                        Text(
                            text = stringResource(
                                when (itemFilter) {
                                    SearchFilter.ALL -> R.string.search_filter_all
                                    SearchFilter.VIDEOS -> R.string.search_filter_videos
                                    SearchFilter.CHANNELS -> R.string.search_filter_channels
                                    SearchFilter.PLAYLISTS -> R.string.search_filter_playlists
                                }
                            )
                        )
                    },
                    modifier = Modifier.testTag("search_filter_${itemFilter.name}")
                )
            }
        }

        // Main Content Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val state = uiState) {
                is SearchUiState.Idle -> {
                    // Show suggestions or recent searches
                    if (suggestions.isNotEmpty()) {
                        SuggestionsList(
                            suggestions = suggestions,
                            onSuggestionClick = { selected ->
                                focusManager.clearFocus()
                                viewModel.onQuerySubmitted(selected)
                            }
                        )
                    } else if (historyState.items.isNotEmpty()) {
                        RecentQueriesList(
                            queries = historyState.items.map { it.query },
                            isMutationInFlight = historyState.isMutationInFlight,
                            onQueryClick = { selected ->
                                focusManager.clearFocus()
                                viewModel.onQuerySubmitted(selected)
                            },
                            onRemoveQuery = { viewModel.removeRecentQuery(it) },
                            onClearAll = { showClearHistoryDialog = true }
                        )
                    } else {
                        EmptyPane(message = stringResource(R.string.search_idle), testTag = "search_idle")
                    }
                }
                is SearchUiState.Loading -> {
                    LoadingPane(testTag = "search_loading")
                }
                is SearchUiState.Empty -> {
                    EmptyPane(
                        message = stringResource(R.string.search_empty, query),
                        testTag = "search_empty"
                    )
                }
                is SearchUiState.Error -> {
                    ErrorPane(
                        error = state.error,
                        onRetry = { viewModel.retry() },
                        testTag = "search_error"
                    )
                }
                is SearchUiState.Content -> {
                    val requestKey = "${filter.name}:$query"
                    SearchResultsList(
                        items = state.items,
                        requestKey = requestKey,
                        hasNextPage = state.nextPageToken != null,
                        isLoadingNextPage = state.isLoadingNextPage,
                        onLoadMore = { viewModel.loadNextPage() },
                        onVideoClick = onVideoClick,
                        onChannelClick = onChannelClick,
                        onPlaylistClick = onPlaylistClick
                    )
                }
            }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(R.string.search_history_clear_title)) },
            text = { Text(stringResource(R.string.search_history_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        viewModel.clearRecentQueries()
                    },
                    modifier = Modifier.testTag("confirm_clear_search_history")
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearHistoryDialog = false },
                    modifier = Modifier.testTag("cancel_clear_search_history")
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SuggestionsList(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("suggestions_list")) {
        items(suggestions) { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestionClick(suggestion) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("suggestion_item_$suggestion"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun RecentQueriesList(
    queries: List<String>,
    isMutationInFlight: Boolean,
    onQueryClick: (String) -> Unit,
    onRemoveQuery: (String) -> Unit,
    onClearAll: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("recent_queries_list")) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("recent_queries_header"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_history_recent),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onClearAll,
                    enabled = !isMutationInFlight,
                    modifier = Modifier.testTag("clear_search_history")
                ) {
                    Text(stringResource(R.string.search_history_clear_all))
                }
            }
        }
        items(queries) { q ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onQueryClick(q) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("recent_item_$q"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = q,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRemoveQuery(q) },
                    enabled = !isMutationInFlight,
                    modifier = Modifier.testTag("remove_recent_$q")
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.search_remove_recent),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchResultsList(
    items: List<SearchResultItem>,
    requestKey: String,
    hasNextPage: Boolean,
    isLoadingNextPage: Boolean,
    onLoadMore: () -> Unit,
    onVideoClick: (ContentKey) -> Unit,
    onChannelClick: (ContentKey) -> Unit,
    onPlaylistClick: (ContentKey) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val triggerPolicy = remember { PaginationTriggerPolicy(threshold = 3) }

    val currentHasNextPage = rememberUpdatedState(hasNextPage)
    val currentIsLoadingNextPage = rememberUpdatedState(isLoadingNextPage)
    val currentOnLoadMore = rememberUpdatedState(onLoadMore)
    val currentRequestKey = rememberUpdatedState(requestKey)

    // Reset policy explicitly when normalized query / filter generation / request key changes or list becomes empty
    LaunchedEffect(requestKey, items.isEmpty()) {
        if (items.isEmpty()) {
            triggerPolicy.reset()
        } else {
            triggerPolicy.resetForRequest(requestKey)
        }
    }

    val nestedScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            private fun checkAndTrigger(source: NestedScrollSource) {
                if (source == NestedScrollSource.UserInput) {
                    val total = listState.layoutInfo.totalItemsCount
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    val shouldTrigger = triggerPolicy.onUserInputPosition(
                        totalItemsCount = total,
                        lastVisibleItemIndex = lastVisible,
                        hasNextPage = currentHasNextPage.value,
                        isLoadingNextPage = currentIsLoadingNextPage.value,
                        requestKey = currentRequestKey.value
                    )
                    if (shouldTrigger) {
                        currentOnLoadMore.value()
                    }
                }
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                checkAndTrigger(source)
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                checkAndTrigger(source)
                return Offset.Zero
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .testTag("search_results_list")
    ) {
        items(
            items = items,
            key = { item ->
                when (item) {
                    is SearchResultItem.VideoItem -> "v_${item.summary.key.serviceId}_${item.summary.key.nativeId}"
                    is SearchResultItem.ChannelItem -> "c_${item.channel.key.serviceId}_${item.channel.key.nativeId}"
                    is SearchResultItem.PlaylistItem -> "p_${item.playlist.key.serviceId}_${item.playlist.key.nativeId}"
                }
            }
        ) { item ->
            when (item) {
                is SearchResultItem.VideoItem -> {
                    VideoCard(video = item.summary, onClick = onVideoClick)
                }
                is SearchResultItem.ChannelItem -> {
                    ChannelResultCard(
                        channel = item.channel,
                        onClick = { onChannelClick(item.channel.key) }
                    )
                }
                is SearchResultItem.PlaylistItem -> {
                    PlaylistResultCard(
                        playlist = item.playlist,
                        onClick = { onPlaylistClick(item.playlist.key) }
                    )
                }
            }
        }

        if (isLoadingNextPage) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ChannelResultCard(
    channel: Channel,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag("channel_card_${channel.key.nativeId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (channel.avatarUrl != null) {
            AsyncImage(
                model = channel.avatarUrl,
                contentDescription = channel.name,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            val subCount = channel.subscriberCountText
            if (subCount != null) {
                Text(
                    text = subCount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlaylistResultCard(
    playlist: PlaylistSummary,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag("playlist_card_${playlist.key.nativeId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 48.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            val count = playlist.videoCount
            if (count != null) {
                Text(
                    text = pluralStringResource(R.plurals.video_count, count.toInt(), count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

