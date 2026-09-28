package com.hpre.app.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.InlineErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.VideoCard

@Composable
fun SubscriptionsScreen(
    viewModel: LibraryViewModel,
    feedViewModel: SubscriptionFeedViewModel? = null,
    onChannelClick: (ContentKey) -> Unit,
    onVideoClick: (ContentKey) -> Unit = {},
    onVideoSelected: ((VideoSummary) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val subscriptionsList by viewModel.subscriptions.collectAsStateWithLifecycle()
    val mutationState by viewModel.mutationState.collectAsStateWithLifecycle()
    val feedState by (feedViewModel?.state ?: kotlinx.coroutines.flow.MutableStateFlow<SubscriptionFeedUiState>(SubscriptionFeedUiState.Empty)).collectAsStateWithLifecycle()
    var showManageSheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var subPendingUnsubscribe by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<LocalSubscription?>(null) }

    Box(modifier = modifier.fillMaxSize().testTag("subscriptions_screen")) {
        if (subscriptionsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.subscriptions_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("subscriptions_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("subscriptions_channels_row"),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(subscriptionsList, key = { it.channelKey.toString() }, contentType = { "channel_chip" }) { sub ->
                                SubscriptionAvatarChip(
                                    sub = sub,
                                    onClick = { onChannelClick(sub.channelKey) }
                                )
                            }
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { showManageSheet = true },
                            modifier = Modifier.testTag("subscriptions_manage_button")
                        ) {
                            Text(stringResource(R.string.subscriptions_manage))
                        }
                    }
                }
                item {
                    androidx.compose.material3.TextButton(
                        onClick = { feedViewModel?.refresh() },
                        modifier = Modifier.testTag("subscriptions_refresh_button")
                    ) {
                        Text(stringResource(R.string.action_refresh))
                    }
                }
                when (val feed = feedState) {
                    SubscriptionFeedUiState.Loading -> item { LoadingPane(testTag = "subscription_feed_loading") }
                    SubscriptionFeedUiState.Empty -> Unit
                    is SubscriptionFeedUiState.Error -> item {
                        InlineErrorPane(
                            feed.error,
                            { feedViewModel?.refresh() },
                            testTag = "subscription_feed_error"
                        )
                    }
                    is SubscriptionFeedUiState.Content -> {
                        if (feed.isRefreshing) {
                            item {
                                androidx.compose.material3.LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().testTag("subscription_feed_refreshing")
                                )
                            }
                        }
                        feed.refreshError?.let { refreshError ->
                            item {
                                InlineErrorPane(
                                    refreshError,
                                    { feedViewModel?.refresh() },
                                    testTag = "subscription_feed_refresh_error"
                                )
                            }
                        }
                        if (feed.failedChannels.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.subscriptions_partial_error),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(16.dp).testTag("subscription_feed_partial_error")
                                )
                            }
                        }
                        items(feed.videos, key = { "feed:${it.key}" }, contentType = { "video" }) { video ->
                            VideoCard(
                                video = video,
                                onClick = { if (onVideoSelected != null) onVideoSelected(video) else onVideoClick(it) },
                                onChannelClick = { onChannelClick(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showManageSheet) {
        SubscriptionsManageSheet(
            subscriptions = subscriptionsList,
            actionsEnabled = !mutationState.inFlight,
            onChannelClick = onChannelClick,
            onUnsubscribe = { subPendingUnsubscribe = it },
            onDismiss = { showManageSheet = false }
        )
    }

    subPendingUnsubscribe?.let { sub ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!mutationState.inFlight) {
                    viewModel.consumeMutationResult()
                    subPendingUnsubscribe = null
                }
            },
            text = {
                Column {
                    Text(stringResource(R.string.subscription_unsubscribe_confirm, sub.name))
                    val unsubError = mutationState.error.takeIf { mutationState.operation == "unsubscribe" }
                    if (unsubError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = com.hpre.app.ui.common.appErrorMessage(unsubError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("subscription_unsubscribe_error")
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.unsubscribe(sub.channelKey) },
                    enabled = !mutationState.inFlight,
                    modifier = Modifier.testTag("subscription_unsubscribe_confirm")
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.consumeMutationResult()
                        subPendingUnsubscribe = null
                    },
                    enabled = !mutationState.inFlight
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
        androidx.compose.runtime.LaunchedEffect(mutationState.completed) {
            if (mutationState.completed && mutationState.operation == "unsubscribe") {
                viewModel.consumeMutationResult()
                subPendingUnsubscribe = null
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionsManageSheet(
    subscriptions: List<LocalSubscription>,
    actionsEnabled: Boolean,
    onChannelClick: (ContentKey) -> Unit,
    onUnsubscribe: (LocalSubscription) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("subscriptions_manage_sheet")
    ) {
        Text(
            text = stringResource(R.string.subscriptions_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("subscriptions_manage_list")
        ) {
            items(subscriptions, key = { it.channelKey.toString() }, contentType = { "subscription_row" }) { sub ->
                SubscriptionListItem(
                    sub = sub,
                    actionsEnabled = actionsEnabled,
                    onClick = {
                        onDismiss()
                        onChannelClick(sub.channelKey)
                    },
                    onUnsubscribe = { onUnsubscribe(sub) }
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SubscriptionAvatarChip(
    sub: LocalSubscription,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
            .testTag("subscription_chip_${sub.channelKey.nativeId}")
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
private fun SubscriptionListItem(
    sub: LocalSubscription,
    actionsEnabled: Boolean = true,
    onClick: () -> Unit,
    onUnsubscribe: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("subscription_row_${sub.channelKey.nativeId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!sub.avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = sub.avatarUrl,
                contentDescription = sub.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            )
        } else {
            Surface(
                modifier = Modifier.size(48.dp),
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
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sub.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.subscription_followed_local),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        OutlinedButton(
            onClick = onUnsubscribe,
            enabled = actionsEnabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.testTag("unsubscribe_button_${sub.channelKey.nativeId}")
        ) {
            Text(stringResource(R.string.subscription_following_local))
        }
    }
}
