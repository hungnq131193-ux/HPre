package com.hpre.app.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hpre.app.model.ContentKey
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreSpacing
import com.hpre.app.ui.common.EmptyPane
import com.hpre.app.ui.common.ErrorPane
import com.hpre.app.ui.common.LoadingPane
import com.hpre.app.ui.common.VideoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    key: ContentKey,
    viewModel: ChannelViewModel,
    onVideoClick: (ContentKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(key) { viewModel.load(key) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? ChannelUiState.Content)?.details?.channel?.name
                            ?: stringResource(R.string.screen_channel)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        modifier = modifier.testTag("channel_screen")
    ) { padding ->
        when (val current = state) {
            ChannelUiState.Loading -> LoadingPane(Modifier.padding(padding), "channel_loading")
            ChannelUiState.Empty -> EmptyPane(
                stringResource(R.string.channel_empty),
                Modifier.padding(padding),
                "channel_empty"
            )
            is ChannelUiState.Error -> ErrorPane(current.error, viewModel::retry, Modifier.padding(padding), "channel_error")
            is ChannelUiState.Content -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("channel_content"),
                verticalArrangement = Arrangement.spacedBy(HPreSpacing.Medium)
            ) {
                item { ChannelHeader(current.details.channel) }
                items(current.details.videos, key = { it.key.toString() }, contentType = { "video" }) { video ->
                    VideoCard(video, onVideoClick)
                }
                items(current.details.shorts, key = { "short:${it.key}" }, contentType = { "video" }) { video ->
                    VideoCard(video, onVideoClick)
                }
            }
        }
    }
}

@Composable
private fun ChannelHeader(channel: com.hpre.app.model.Channel) {
    var descriptionExpanded by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var descriptionOverflows by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    Column(Modifier.fillMaxWidth().testTag("channel_header")) {
        if (!channel.bannerUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = channel.bannerUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .testTag("channel_banner")
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HPreSpacing.Large, vertical = HPreSpacing.Medium),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            if (!channel.avatarUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = channel.avatarUrl,
                    contentDescription = stringResource(R.string.watch_channel_avatar),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .testTag("channel_avatar")
                )
            } else {
                androidx.compose.material3.Surface(
                    modifier = Modifier.size(64.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    androidx.compose.foundation.layout.Box(
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            text = channel.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("channel_name")
                )
                channel.subscriberCount?.let { subs ->
                    Text(
                        text = com.hpre.app.ui.common.subscriberCountLabel(subs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("channel_subscribers")
                    )
                }
            }
        }
        if (!channel.description.isNullOrBlank()) {
            Column(Modifier.padding(horizontal = HPreSpacing.Large)) {
                Text(
                    text = channel.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    onTextLayout = { result -> descriptionOverflows = result.hasVisualOverflow },
                    modifier = Modifier.testTag("channel_description")
                )
                if (descriptionOverflows || descriptionExpanded) {
                    androidx.compose.material3.TextButton(
                        onClick = { descriptionExpanded = !descriptionExpanded },
                        modifier = Modifier.testTag("channel_description_toggle")
                    ) {
                        Text(
                            stringResource(
                                if (descriptionExpanded) R.string.watch_collapse else R.string.watch_expand
                            )
                        )
                    }
                }
            }
        }
    }
}
