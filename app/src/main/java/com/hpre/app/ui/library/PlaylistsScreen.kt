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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.R
import com.hpre.app.core.designsystem.HPreGradientTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    viewModel: LibraryViewModel,
    onPlaylistClick: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlistsList by viewModel.playlists.collectAsStateWithLifecycle()
    val mutationState by viewModel.mutationState.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<LocalPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<LocalPlaylist?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_playlists)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("playlists_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.testTag("playlists_create_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.playlist_create)
                        )
                    }
                },
                modifier = Modifier.testTag("playlists_top_bar")
            )
        },
        modifier = modifier.fillMaxSize().testTag("playlists_screen")
    ) { innerPadding ->
        if (playlistsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.playlists_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("playlists_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(playlistsList, key = { it.playlistId }, contentType = { "playlist" }) { playlist ->
                    PlaylistManagementRow(
                        playlist = playlist,
                        actionsEnabled = !mutationState.inFlight,
                        onClick = { onPlaylistClick(playlist.playlistId) },
                        onRename = { playlistToRename = playlist },
                        onDelete = { playlistToDelete = playlist }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            isSaving = mutationState.inFlight,
            error = mutationState.error.takeIf { mutationState.operation == "createPlaylist" },
            onCreate = { title -> viewModel.createPlaylist(title) },
            onDismiss = {
                viewModel.consumeMutationResult()
                showCreateDialog = false
            }
        )
        androidx.compose.runtime.LaunchedEffect(mutationState.completed) {
            if (mutationState.completed && mutationState.operation == "createPlaylist") {
                viewModel.consumeMutationResult()
                showCreateDialog = false
            }
        }
    }

    playlistToRename?.let { playlist ->
        RenamePlaylistDialog(
            currentTitle = playlist.title,
            isSaving = mutationState.inFlight,
            error = mutationState.error.takeIf { mutationState.operation == "renamePlaylist" },
            onRename = { newTitle -> viewModel.renamePlaylist(playlist.playlistId, newTitle) },
            onDismiss = {
                viewModel.consumeMutationResult()
                playlistToRename = null
            }
        )
        androidx.compose.runtime.LaunchedEffect(mutationState.completed) {
            if (mutationState.completed && mutationState.operation == "renamePlaylist") {
                viewModel.consumeMutationResult()
                playlistToRename = null
            }
        }
    }

    playlistToDelete?.let { playlist ->
        DeletePlaylistDialog(
            playlistTitle = playlist.title,
            isSaving = mutationState.inFlight,
            error = mutationState.error.takeIf { mutationState.operation == "deletePlaylist" },
            onDelete = { viewModel.deletePlaylist(playlist.playlistId) },
            onDismiss = {
                viewModel.consumeMutationResult()
                playlistToDelete = null
            }
        )
        androidx.compose.runtime.LaunchedEffect(mutationState.completed) {
            if (mutationState.completed && mutationState.operation == "deletePlaylist") {
                viewModel.consumeMutationResult()
                playlistToDelete = null
            }
        }
    }
}

@Composable
private fun PlaylistManagementRow(
    playlist: LocalPlaylist,
    actionsEnabled: Boolean = true,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("playlist_row_${playlist.playlistId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HPreGradientTile(icon = Icons.AutoMirrored.Filled.PlaylistPlay)
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
        IconButton(
            onClick = onRename,
            enabled = actionsEnabled,
            modifier = Modifier.testTag("playlist_rename_button_${playlist.playlistId}")
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.playlist_rename),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onDelete,
            enabled = actionsEnabled,
            modifier = Modifier.testTag("playlist_delete_button_${playlist.playlistId}")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun RenamePlaylistDialog(
    currentTitle: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
    error: com.hpre.app.core.error.AppError? = null
) {
    var title by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.playlist_title)) },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_rename_input")
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = com.hpre.app.ui.common.appErrorMessage(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("playlist_rename_error")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onRename(title.trim())
                    }
                },
                enabled = title.isNotBlank() && !isSaving,
                modifier = Modifier.testTag("playlist_rename_dialog_confirm")
            ) {
                Text(stringResource(R.string.playlist_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun DeletePlaylistDialog(
    playlistTitle: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
    error: com.hpre.app.core.error.AppError? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title, playlistTitle)) },
        text = {
            Column {
                Text(stringResource(R.string.playlist_delete_message))
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = com.hpre.app.ui.common.appErrorMessage(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("playlist_delete_error")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                enabled = !isSaving,
                modifier = Modifier.testTag("playlist_delete_dialog_confirm")
            ) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
