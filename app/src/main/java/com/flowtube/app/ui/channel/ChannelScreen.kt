package com.flowtube.app.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flowtube.app.model.ContentKey
import com.flowtube.app.ui.common.EmptyPane
import com.flowtube.app.ui.common.ErrorPane
import com.flowtube.app.ui.common.LoadingPane
import com.flowtube.app.ui.common.VideoCard

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
                title = { Text((state as? ChannelUiState.Content)?.details?.channel?.name ?: "Channel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier.testTag("channel_screen")
    ) { padding ->
        when (val current = state) {
            ChannelUiState.Loading -> LoadingPane(Modifier.padding(padding), "channel_loading")
            ChannelUiState.Empty -> EmptyPane("No channel content", Modifier.padding(padding), "channel_empty")
            is ChannelUiState.Error -> ErrorPane(current.error, viewModel::retry, Modifier.padding(padding), "channel_error")
            is ChannelUiState.Content -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("channel_content"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(current.details.channel.name, style = MaterialTheme.typography.headlineSmall)
                        current.details.channel.subscriberCountText?.let { Text(it) }
                        current.details.channel.description?.let { Text(it) }
                    }
                }
                items(current.details.videos, key = { it.key.toString() }) { video ->
                    VideoCard(video, onVideoClick)
                }
                items(current.details.shorts, key = { "short:${it.key}" }) { video ->
                    VideoCard(video, onVideoClick)
                }
            }
        }
    }
}
