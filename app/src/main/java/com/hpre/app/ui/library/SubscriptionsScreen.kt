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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpre.app.model.ContentKey
import com.hpre.app.repository.LocalSubscription
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.VideoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    viewModel: LibraryViewModel,
    feedViewModel: SubscriptionFeedViewModel? = null,
    onChannelClick: (ContentKey) -> Unit,
    onVideoClick: (ContentKey) -> Unit = {},
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subscriptionsList by viewModel.subscriptions.collectAsStateWithLifecycle()
    val feedState by (feedViewModel?.state ?: kotlinx.coroutines.flow.MutableStateFlow<SubscriptionFeedUiState>(SubscriptionFeedUiState.Empty)).collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("subscriptions_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                modifier = Modifier.testTag("subscriptions_top_bar")
            )
        },
        modifier = modifier.fillMaxSize().testTag("subscriptions_screen")
    ) { innerPadding ->
        if (subscriptionsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No subscriptions",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("subscriptions_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(subscriptionsList, key = { it.channelKey.toString() }) { sub ->
                    SubscriptionListItem(
                        sub = sub,
                        onClick = { onChannelClick(sub.channelKey) },
                        onUnsubscribe = { viewModel.unsubscribe(sub.channelKey) }
                    )
                }
                when (val feed = feedState) {
                    SubscriptionFeedUiState.Loading -> item { LoadingPane(testTag = "subscription_feed_loading") }
                    SubscriptionFeedUiState.Empty -> Unit
                    is SubscriptionFeedUiState.Error -> item {
                        ErrorPane(feed.error, { feedViewModel?.refresh() }, testTag = "subscription_feed_error")
                    }
                    is SubscriptionFeedUiState.Content -> {
                        if (feed.failedChannels.isNotEmpty()) {
                            item {
                                Text(
                                    "Some local subscriptions could not be refreshed.",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(16.dp).testTag("subscription_feed_partial_error")
                                )
                            }
                        }
                        items(feed.videos, key = { "feed:${it.key}" }) { video ->
                            VideoCard(video, onVideoClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionListItem(
    sub: LocalSubscription,
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
                text = "Followed locally",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        OutlinedButton(
            onClick = onUnsubscribe,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.testTag("unsubscribe_button_${sub.channelKey.nativeId}")
        ) {
            Text("Following locally")
        }
    }
}
