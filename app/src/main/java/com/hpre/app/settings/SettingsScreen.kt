package com.hpre.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hpre.app.R
import com.hpre.app.update.OfficialReleasePage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenReleasePage: (OfficialReleasePage) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val videoCacheClearState by viewModel.videoCacheClearState.collectAsStateWithLifecycle()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showWifiQualityDialog by remember { mutableStateOf(false) }
    var showMobileQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showClearVideoCacheDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                modifier = Modifier.testTag("settings_top_bar")
            )
        },
        modifier = modifier.fillMaxSize().testTag("settings_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // General / Appearance Section
            SettingsSectionHeader(stringResource(R.string.settings_appearance))

            SettingsClickableItem(
                title = stringResource(R.string.settings_theme),
                subtitle = when (settings.theme) {
                    AppTheme.SYSTEM -> stringResource(R.string.settings_theme_system)
                    AppTheme.LIGHT -> stringResource(R.string.settings_theme_light)
                    AppTheme.DARK -> stringResource(R.string.settings_theme_dark)
                    AppTheme.AMOLED -> stringResource(R.string.settings_theme_amoled)
                },
                tag = "setting_theme_item",
                onClick = { showThemeDialog = true }
            )

            SettingsClickableItem(
                title = stringResource(R.string.settings_language),
                subtitle = when (settings.language) {
                    AppLanguage.VIETNAMESE -> "Tiếng Việt"
                    AppLanguage.ENGLISH -> "English"
                },
                tag = "setting_language_item",
                onClick = { showLanguageDialog = true }
            )

            HorizontalDivider()

            // Playback Section
            SettingsSectionHeader(stringResource(R.string.settings_playback))

            SettingsSwitchItem(
                title = stringResource(R.string.settings_background_playback),
                subtitle = stringResource(R.string.settings_background_playback_summary),
                checked = settings.backgroundPlaybackEnabled,
                tag = "setting_background_playback_switch",
                onCheckedChange = { viewModel.setBackgroundPlayback(it) }
            )

            SettingsSwitchItem(
                title = stringResource(R.string.settings_pip),
                subtitle = stringResource(R.string.settings_pip_summary),
                checked = settings.pipEnabled,
                tag = "setting_pip_switch",
                onCheckedChange = { viewModel.setPip(it) }
            )

            SettingsClickableItem(
                title = stringResource(R.string.settings_default_speed),
                subtitle = "${settings.defaultPlaybackSpeed}x",
                tag = "setting_default_speed_item",
                onClick = { showSpeedDialog = true }
            )

            SettingsSwitchItem(
                title = stringResource(R.string.settings_autoplay),
                subtitle = stringResource(R.string.settings_autoplay_summary),
                checked = settings.autoplay,
                tag = "setting_autoplay_switch",
                onCheckedChange = { viewModel.setAutoplay(it) }
            )

            SettingsSwitchItem(
                title = stringResource(R.string.settings_sponsorblock),
                subtitle = stringResource(R.string.settings_sponsorblock_summary),
                checked = settings.sponsorBlockEnabled,
                tag = "setting_sponsorblock_switch",
                onCheckedChange = { viewModel.setSponsorBlock(it) }
            )

            HorizontalDivider()

            // Quality Section
            SettingsSectionHeader(stringResource(R.string.settings_video_quality))

            SettingsClickableItem(
                title = stringResource(R.string.settings_wifi_quality),
                subtitle = qualityLabel(settings.wifiQuality),
                tag = "setting_wifi_quality_item",
                onClick = { showWifiQualityDialog = true }
            )

            SettingsClickableItem(
                title = stringResource(R.string.settings_mobile_quality),
                subtitle = qualityLabel(settings.mobileQuality),
                tag = "setting_mobile_quality_item",
                onClick = { showMobileQualityDialog = true }
            )

            SettingsClickableItem(
                title = stringResource(R.string.settings_clear_video_cache),
                subtitle = stringResource(R.string.settings_clear_video_cache_summary),
                tag = "setting_clear_video_cache_item",
                onClick = { showClearVideoCacheDialog = true }
            )

            if (videoCacheClearState == VideoCacheClearUiState.Error) {
                Text(
                    text = stringResource(R.string.settings_clear_video_cache_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            HorizontalDivider()

            // History Section
            SettingsSectionHeader(stringResource(R.string.settings_history_privacy))

            SettingsSwitchItem(
                title = stringResource(R.string.settings_record_history),
                subtitle = stringResource(R.string.settings_record_history_summary),
                checked = settings.historyEnabled,
                tag = "setting_history_switch",
                onCheckedChange = { viewModel.setHistory(it) }
            )

            HorizontalDivider()

            // Backup & restore
            SettingsSectionHeader(stringResource(R.string.settings_backup))

            val context = LocalContext.current
            val backupState by viewModel.backupState.collectAsStateWithLifecycle()
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                uri?.let { viewModel.exportBackup(context.contentResolver, it) }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let { viewModel.importBackup(context.contentResolver, it) }
            }

            SettingsClickableItem(
                title = stringResource(R.string.settings_export_backup),
                subtitle = stringResource(R.string.settings_export_backup_summary),
                tag = "setting_export_backup_item",
                onClick = { exportLauncher.launch("hpre-backup.json") }
            )

            SettingsClickableItem(
                title = stringResource(R.string.settings_import_backup),
                subtitle = stringResource(R.string.settings_import_backup_summary),
                tag = "setting_import_backup_item",
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
            )

            when (val state = backupState) {
                BackupUiState.Idle -> Unit
                BackupUiState.Running -> CircularProgressIndicator(
                    modifier = Modifier
                        .padding(16.dp)
                        .testTag("settings_backup_progress")
                )
                BackupUiState.Exported -> Text(
                    text = stringResource(R.string.settings_backup_exported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("settings_backup_exported")
                )
                is BackupUiState.Imported -> Text(
                    text = stringResource(
                        R.string.settings_backup_imported,
                        state.summary.historyCount,
                        state.summary.playlistCount,
                        state.summary.subscriptionCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("settings_backup_imported")
                )
                BackupUiState.Error -> Text(
                    text = stringResource(R.string.settings_backup_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("settings_backup_error")
                )
            }

            HorizontalDivider()

            SettingsSectionHeader(
                title = stringResource(R.string.settings_updates),
                modifier = Modifier.testTag("settings_update_section")
            )

            Text(
                text = stringResource(
                    R.string.settings_current_version,
                    viewModel.installedVersion
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SettingsClickableItem(
                title = stringResource(R.string.settings_check_updates),
                subtitle = when (updateState) {
                    UpdateUiState.Checking -> stringResource(R.string.settings_update_checking)
                    else -> stringResource(R.string.settings_check_updates_summary)
                },
                tag = "setting_check_update_item",
                onClick = viewModel::checkForUpdates
            )

            when (val state = updateState) {
                UpdateUiState.Idle -> Unit
                UpdateUiState.Checking -> CircularProgressIndicator(
                    modifier = Modifier
                        .padding(16.dp)
                        .testTag("settings_update_progress")
                )
                is UpdateUiState.UpToDate -> Text(
                    text = stringResource(R.string.settings_update_current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("settings_update_current")
                )
                is UpdateUiState.UpdateAvailable -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("settings_update_available")
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_update_available,
                            state.latestVersion
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (shouldShowOriginIslandMigrationNotice(state.installedVersion)) {
                        OriginIslandV201MigrationNotice(
                            downloadUrl = originIslandMigrationDownloadUrl(state),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    UpdateDownloadControls(
                        state = state,
                        onInstall = viewModel::downloadAndInstallUpdate
                    )
                    TextButton(
                        onClick = { onOpenReleasePage(state.releasePage) },
                        modifier = Modifier.testTag("settings_open_release_button")
                    ) {
                        Text(stringResource(R.string.settings_open_release))
                    }
                    if (state.openError) {
                        Text(
                            text = stringResource(R.string.settings_open_release_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                is UpdateUiState.Error -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("settings_update_error")
                ) {
                    Text(
                        text = stringResource(R.string.settings_update_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = viewModel::checkForUpdates) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }

            HorizontalDivider()

            // Donate / Support the Developer Section
            SettingsSectionHeader(
                title = stringResource(R.string.settings_donate_section),
                modifier = Modifier.testTag("settings_donate_section")
            )

            androidx.compose.material3.Surface(
                onClick = { showDonateDialog = true },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("setting_donate_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_donate_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_donate_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.Button(
                        onClick = { showDonateDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setting_donate_button")
                    ) {
                        Text(
                            text = stringResource(R.string.settings_donate_button),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDonateDialog) {
        DonateCoffeeDialog(onDismiss = { showDonateDialog = false })
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_choose_theme),
            options = listOf(
                AppTheme.SYSTEM to stringResource(R.string.settings_theme_system),
                AppTheme.LIGHT to stringResource(R.string.settings_theme_light),
                AppTheme.DARK to stringResource(R.string.settings_theme_dark),
                AppTheme.AMOLED to stringResource(R.string.settings_theme_amoled)
            ),
            selectedOption = settings.theme,
            tagPrefix = "theme_option",
            onOptionSelected = {
                viewModel.setTheme(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_choose_language),
            options = listOf(
                AppLanguage.VIETNAMESE to "Tiếng Việt",
                AppLanguage.ENGLISH to "English"
            ),
            selectedOption = settings.language,
            tagPrefix = "language_option",
            onOptionSelected = {
                viewModel.setLanguage(it)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showWifiQualityDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_choose_wifi_quality),
            options = QualityPreferenceSetting.values().map { it to qualityLabel(it) },
            selectedOption = settings.wifiQuality,
            tagPrefix = "wifi_quality_option",
            onOptionSelected = {
                viewModel.setWifiQuality(it)
                showWifiQualityDialog = false
            },
            onDismiss = { showWifiQualityDialog = false }
        )
    }

    if (showMobileQualityDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_choose_mobile_quality),
            options = QualityPreferenceSetting.values().map { it to qualityLabel(it) },
            selectedOption = settings.mobileQuality,
            tagPrefix = "mobile_quality_option",
            onOptionSelected = {
                viewModel.setMobileQuality(it)
                showMobileQualityDialog = false
            },
            onDismiss = { showMobileQualityDialog = false }
        )
    }

    if (showSpeedDialog) {
        val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        SingleChoiceDialog(
            title = stringResource(R.string.settings_choose_speed),
            options = speedOptions.map { it to "${it}x" },
            selectedOption = settings.defaultPlaybackSpeed,
            tagPrefix = "speed_option",
            onOptionSelected = {
                viewModel.setDefaultPlaybackSpeed(it)
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false }
        )
    }

    if (showClearVideoCacheDialog) {
        val clearing = videoCacheClearState == VideoCacheClearUiState.Clearing
        AlertDialog(
            onDismissRequest = {
                if (!clearing) {
                    viewModel.consumeVideoCacheResult()
                    showClearVideoCacheDialog = false
                }
            },
            title = { Text(stringResource(R.string.settings_clear_video_cache_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_clear_video_cache_message))
                    if (videoCacheClearState == VideoCacheClearUiState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_clear_video_cache_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("settings_clear_video_cache_dialog_error")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearVideoCache() },
                    enabled = !clearing,
                    modifier = Modifier.testTag("settings_clear_video_cache_confirm")
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.consumeVideoCacheResult()
                        showClearVideoCacheDialog = false
                    },
                    enabled = !clearing,
                    modifier = Modifier.testTag("settings_clear_video_cache_cancel")
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("settings_clear_video_cache_dialog")
        )
        androidx.compose.runtime.LaunchedEffect(videoCacheClearState) {
            if (videoCacheClearState == VideoCacheClearUiState.Success) {
                viewModel.consumeVideoCacheResult()
                showClearVideoCacheDialog = false
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun qualityLabel(quality: QualityPreferenceSetting): String = stringResource(
    when (quality) {
        QualityPreferenceSetting.AUTO -> R.string.quality_auto
        QualityPreferenceSetting.HIGH_1080P -> R.string.quality_high
        QualityPreferenceSetting.MEDIUM_720P -> R.string.quality_medium
        QualityPreferenceSetting.LOW_360P -> R.string.quality_low
    }
)

@Composable
private fun SettingsClickableItem(
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    // Single semantics node: the row is the toggle (role=Switch); the inner Switch is
    // visual only — prevents TalkBack double-announce and nested-click double toggles.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.testTag("${tag}_control")
        )
    }
}

@Composable
private fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selectedOption: T,
    tagPrefix: String,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selectedOption,
                                role = Role.RadioButton,
                                onClick = { onOptionSelected(option) }
                            )
                            .padding(vertical = 8.dp)
                            .testTag("${tagPrefix}_$option"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selectedOption,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DonateCoffeeDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val copiedToastText = stringResource(R.string.settings_donate_copied_toast)
    val savedQrToastText = stringResource(R.string.settings_donate_saved_qr_toast)
    val saveQrErrorToastText = stringResource(R.string.settings_donate_save_qr_error_toast)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_donate_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.settings_donate_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.donate_qr_mbbank),
                    contentDescription = "MB Bank VietQR 50.000 VND",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("donate_qr_image")
                )

                Spacer(modifier = Modifier.height(10.dp))

                androidx.compose.material3.Button(
                    onClick = {
                        val saved = saveDonateQrToGallery(context)
                        android.widget.Toast.makeText(
                            context,
                            if (saved) savedQrToastText else saveQrErrorToastText,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("donate_save_qr_button")
                ) {
                    Text(
                        text = stringResource(R.string.settings_donate_save_qr),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.settings_donate_qr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.settings_donate_bank_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_donate_account_name_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_donate_account_number_label),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.FilledTonalButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as? android.content.ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText("MB Bank Account", "89988889993")
                                    )
                                    android.widget.Toast.makeText(
                                        context,
                                        copiedToastText,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.testTag("donate_copy_account_button")
                            ) {
                                Text(stringResource(R.string.settings_donate_copy_account))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_donate_amount_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_donate_content_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("donate_dialog_close_button")
            ) {
                Text(stringResource(R.string.action_close))
            }
        },
        modifier = Modifier.testTag("donate_coffee_dialog")
    )
}

private fun saveDonateQrToGallery(context: android.content.Context): Boolean {
    return runCatching {
        val bitmap = android.graphics.BitmapFactory.decodeResource(
            context.resources,
            R.drawable.donate_qr_mbbank
        ) ?: return false

        val fileName = "HPre_VietQR_MBBank_50k_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/HPre"
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return false

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)) {
                    throw java.io.IOException("Failed to compress QR bitmap")
                }
            } ?: return false

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val completeValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, completeValues, null, null)
            }
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }.getOrDefault(false)
}


