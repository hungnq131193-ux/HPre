package com.hpre.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hpre.app.R
import com.hpre.app.update.AppUpdateManager

/** Download/install button with progress and failure feedback; hidden when no in-app APK exists. */
@Composable
fun UpdateDownloadControls(
    state: UpdateUiState.UpdateAvailable,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.apk == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        when (val download = state.download) {
            is ApkDownloadState.Downloading -> {
                Text(
                    text = stringResource(R.string.update_downloading, (download.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(
                    progress = { download.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("update_download_progress")
                )
            }
            ApkDownloadState.Idle, ApkDownloadState.Failed, ApkDownloadState.ReadyToInstall -> {
                if (download == ApkDownloadState.Failed) {
                    Text(
                        text = stringResource(R.string.update_download_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = onInstall,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("settings_install_update_button")
                ) {
                    Text(
                        stringResource(
                            if (download == ApkDownloadState.ReadyToInstall) R.string.update_action_install_now
                            else R.string.update_action_download_install
                        )
                    )
                }
            }
        }
    }
}

/** Asks once per launch whether to install a newer release found by the background check. */
@Composable
fun AppUpdatePrompt(manager: AppUpdateManager) {
    val visible by manager.launchPromptVisible.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    val available = state as? UpdateUiState.UpdateAvailable
    if (!visible || available?.apk == null) return
    val downloading = available.download is ApkDownloadState.Downloading

    AlertDialog(
        onDismissRequest = { if (!downloading) manager.dismissLaunchPrompt() },
        title = { Text(stringResource(R.string.update_prompt_title)) },
        text = {
            Column {
                Text(stringResource(R.string.update_prompt_message, available.latestVersion))
                if (downloading || available.download == ApkDownloadState.Failed) {
                    UpdateDownloadControls(
                        state = available,
                        onInstall = manager::downloadAndInstall,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (!downloading && available.download != ApkDownloadState.Failed) {
                TextButton(onClick = manager::downloadAndInstall) {
                    Text(
                        stringResource(
                            if (available.download == ApkDownloadState.ReadyToInstall) R.string.update_action_install_now
                            else R.string.update_action_download_install
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = manager::dismissLaunchPrompt) {
                Text(stringResource(R.string.update_action_later))
            }
        },
        modifier = Modifier.testTag("update_prompt_dialog")
    )
}
