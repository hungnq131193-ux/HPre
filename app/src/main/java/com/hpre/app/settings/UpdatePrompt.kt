package com.hpre.app.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hpre.app.R
import com.hpre.app.update.AppUpdateManager

internal const val LEGACY_ORIGIN_ISLAND_PACKAGE = "com.kugou.android.lite"
internal const val V202_DIRECT_APK_URL =
    "https://github.com/hungnq131193-ux/HPre/releases/download/v2.0.2/HPre-v2.0.2-release.apk"

/** Only users on v2.0.1 need the OriginOS 7 package-name migration notice and download link. */
internal fun shouldShowOriginIslandMigrationNotice(installedVersion: String?): Boolean {
    val normalized = installedVersion?.trim()?.removePrefix("v")?.removePrefix("V") ?: return false
    return normalized == "2.0.1"
}

internal fun originIslandMigrationDownloadUrl(state: UpdateUiState.UpdateAvailable): String =
    state.apk?.downloadUrl ?: state.releasePage.url

@Composable
fun OriginIslandV201MigrationNotice(
    downloadUrl: String,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth().testTag("origin_os7_v201_migration_notice")) {
        Text(
            text = stringResource(R.string.update_origin_os7_v201_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.update_origin_os7_v201_download_link, downloadUrl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable {
                    runCatching { uriHandler.openUri(downloadUrl) }
                }
                .testTag("origin_os7_v201_download_link")
        )
    }
}

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
                if (shouldShowOriginIslandMigrationNotice(available.installedVersion)) {
                    OriginIslandV201MigrationNotice(
                        downloadUrl = originIslandMigrationDownloadUrl(available),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
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

/**
 * If the user is running standard com.hpre.app alongside an existing 2.0.1 Origin Island package,
 * prompt them once per session to uninstall the old 2.0.1 build.
 */
@Composable
fun OriginIslandLegacyInstalledPrompt() {
    val context = LocalContext.current
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed || context.packageName == LEGACY_ORIGIN_ISLAND_PACKAGE) return

    val legacyVersion = runCatching {
        context.packageManager.getPackageInfo(LEGACY_ORIGIN_ISLAND_PACKAGE, 0).versionName
    }.getOrNull()
    if (!shouldShowOriginIslandMigrationNotice(legacyVersion)) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.update_prompt_title)) },
        text = {
            OriginIslandV201MigrationNotice(downloadUrl = V202_DIRECT_APK_URL)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    dismissed = true
                    runCatching {
                        val intent = Intent(
                            Intent.ACTION_DELETE,
                            Uri.parse("package:$LEGACY_ORIGIN_ISLAND_PACKAGE")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
            ) {
                Text(stringResource(R.string.update_origin_os7_uninstall_old))
            }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) {
                Text(stringResource(R.string.update_action_later))
            }
        },
        modifier = Modifier.testTag("legacy_v201_uninstall_dialog")
    )
}

