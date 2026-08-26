package com.hpre.app.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hpre.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showWifiQualityDialog by remember { mutableStateOf(false) }
    var showMobileQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

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
                },
                tag = "setting_theme_item",
                onClick = { showThemeDialog = true }
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
        }
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_choose_theme),
            options = listOf(
                AppTheme.SYSTEM to stringResource(R.string.settings_theme_system),
                AppTheme.LIGHT to stringResource(R.string.settings_theme_light),
                AppTheme.DARK to stringResource(R.string.settings_theme_dark)
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
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
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
            onCheckedChange = onCheckedChange,
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
                            .clickable { onOptionSelected(option) }
                            .padding(vertical = 8.dp)
                            .testTag("${tagPrefix}_$option"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selectedOption,
                            onClick = { onOptionSelected(option) }
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
