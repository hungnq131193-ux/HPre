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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpre.app.model.ContentKey
import com.hpre.app.R
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.HistoryRepository
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.repository.LocalPlaylistEntry
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.repository.WatchHistoryItem

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onNavigateToHistory: () -> Unit,
    onNavigateToSubscriptions: () -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onVideoClick: (ContentKey) -> Unit,
    onVideoSelected: ((ContentKey, String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val historyList by viewModel.recentHistory.collectAsStateWithLifecycle()
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val subscriptionsList by viewModel.subscriptions.collectAsStateWithLifecycle()
    val playlistsList by viewModel.playlists.collectAsStateWithLifecycle()

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("library_screen")
    ) {
        // Section: History Header & Recent Horizontal Row
        LibrarySectionHeader(
            title = stringResource(R.string.library_history),
            count = historyCount,
            onSeeAllClick = onNavigateToHistory,
            tag = "library_history_header"
        )

        if (historyList.isEmpty()) {
            Text(
                text = stringResource(R.string.library_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .testTag("library_recent_history_row"),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(historyList, key = { it.key.toString() }) { item ->
                    RecentHistoryCard(
                        item = item,
                        onClick = {
                            if (onVideoSelected != null) {
                                onVideoSelected(item.key, item.thumbnailUrl)
                            } else {
                                onVideoClick(item.key)
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()

        // Section: Playlists Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onNavigateToPlaylists() }
            ) {
                Text(
                    text = stringResource(R.string.screen_playlists),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (playlistsList.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${playlistsList.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                IconButton(
                    onClick = { showCreatePlaylistDialog = true },
                    modifier = Modifier.testTag("library_create_playlist_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.playlist_new)
                    )
                }
                TextButton(
                    onClick = onNavigateToPlaylists,
                    modifier = Modifier.testTag("library_see_all_playlists_button")
                ) {
                    Text(stringResource(R.string.action_see_all))
                }
            }
        }

        if (playlistsList.isEmpty()) {
            Text(
                text = stringResource(R.string.library_playlists_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                playlistsList.take(5).forEach { playlist ->
                    PlaylistItemRow(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist.playlistId) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()

        // Section: Subscriptions Header
        LibrarySectionHeader(
            title = stringResource(R.string.nav_subscriptions),
            count = subscriptionsList.size,
            onSeeAllClick = onNavigateToSubscriptions,
            tag = "library_subscriptions_header"
        )

        if (subscriptionsList.isEmpty()) {
            Text(
                text = stringResource(R.string.library_subscriptions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("library_subscriptions_row"),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(subscriptionsList, key = { it.channelKey.toString() }) { sub ->
                    SubscriptionAvatarItem(sub = sub)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onCreate = { title ->
                viewModel.createPlaylist(title)
                showCreatePlaylistDialog = false
            },
            onDismiss = { showCreatePlaylistDialog = false }
        )
    }
}

@Composable
private fun LibrarySectionHeader(
    title: String,
    count: Int,
    onSeeAllClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onSeeAllClick() }
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(tag)
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "($count)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(
            onClick = onSeeAllClick,
            modifier = Modifier.testTag("${tag}_see_all")
        ) {
            Text(stringResource(R.string.action_see_all))
        }
    }
}

@Composable
private fun RecentHistoryCard(
    item: WatchHistoryItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick)
            .testTag("recent_history_card_${item.key.nativeId}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
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
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.channelName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.channelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistItemRow(
    playlist: LocalPlaylist,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("playlist_item_${playlist.playlistId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.video_count,
                    playlist.entryCount,
                    playlist.entryCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SubscriptionAvatarItem(sub: LocalSubscription) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .testTag("subscription_avatar_${sub.channelKey.nativeId}")
    ) {
        if (!sub.avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = sub.avatarUrl,
                contentDescription = sub.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            )
        } else {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = sub.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = sub.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_new)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.playlist_title)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playlist_title_input")
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onCreate(title.trim())
                    }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("playlist_dialog_create_button")
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
