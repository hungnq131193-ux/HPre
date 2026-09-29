package com.hpre.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hpre.app.R
import com.hpre.app.download.DownloadTracker
import com.hpre.app.download.DownloadUiState
import com.hpre.app.download.toUiState
import com.hpre.app.model.ContentKey

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    tracker: DownloadTracker?,
    onVideoClick: (ContentKey) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by (tracker?.downloads ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_downloads)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("downloads_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        modifier = modifier.testTag("downloads_screen")
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(entries, key = { it.key.nativeId }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onClick = {
                            if (entry.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) {
                                onVideoClick(entry.key)
                            }
                        },
                        onRemove = { tracker?.remove(entry.key) }
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DownloadRow(
    entry: DownloadTracker.Entry,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val uiState = entry.toUiState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = uiState == DownloadUiState.COMPLETED, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("download_row_${entry.key.nativeId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.audioOnly) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(20.dp).padding(end = 2.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.key.nativeId },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val stateLabel = when (uiState) {
                DownloadUiState.COMPLETED -> stringResource(R.string.download_state_downloaded)
                DownloadUiState.FAILED -> stringResource(R.string.download_state_failed)
                DownloadUiState.DOWNLOADING -> stringResource(
                    R.string.download_state_downloading,
                    entry.percent.coerceAtLeast(0f).toInt()
                )
                else -> stringResource(R.string.download_state_queued)
            }
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState == DownloadUiState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (uiState == DownloadUiState.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { (entry.percent.coerceAtLeast(0f) / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.testTag("download_remove_${entry.key.nativeId}")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.download_remove)
            )
        }
    }
}
