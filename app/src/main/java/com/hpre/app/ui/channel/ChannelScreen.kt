package com.hpre.app.ui.channel

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
                item {
                    Column(Modifier.fillMaxWidth().padding(HPreSpacing.Large)) {
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
