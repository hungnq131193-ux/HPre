package com.flowtube.app.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
            SettingsSectionHeader("Appearance")

            SettingsClickableItem(
                title = "Theme",
                subtitle = when (settings.theme) {
                    AppTheme.SYSTEM -> "System default"
                    AppTheme.LIGHT -> "Light"
                    AppTheme.DARK -> "Dark"
                },
                tag = "setting_theme_item",
                onClick = { showThemeDialog = true }
            )

            HorizontalDivider()

            // Playback Section
            SettingsSectionHeader("Playback")

            SettingsSwitchItem(
                title = "Background playback",
                subtitle = "Keep playing audio when app is in background",
                checked = settings.backgroundPlaybackEnabled,
                tag = "setting_background_playback_switch",
                onCheckedChange = { viewModel.setBackgroundPlayback(it) }
            )

            SettingsSwitchItem(
                title = "Picture-in-Picture",
                subtitle = "Automatically enter PiP mode when leaving Watch screen",
                checked = settings.pipEnabled,
                tag = "setting_pip_switch",
                onCheckedChange = { viewModel.setPip(it) }
            )

            SettingsClickableItem(
                title = "Default speed",
                subtitle = "${settings.defaultPlaybackSpeed}x",
                tag = "setting_default_speed_item",
                onClick = { showSpeedDialog = true }
            )

            SettingsSwitchItem(
                title = "Autoplay next",
                subtitle = "Automatically play next video when current finishes",
                checked = settings.autoplay,
                tag = "setting_autoplay_switch",
                onCheckedChange = { viewModel.setAutoplay(it) }
            )

            HorizontalDivider()

            // Quality Section
            SettingsSectionHeader("Video Quality")

            SettingsClickableItem(
                title = "Wi-Fi video quality",
                subtitle = settings.wifiQuality.label,
                tag = "setting_wifi_quality_item",
                onClick = { showWifiQualityDialog = true }
            )

            SettingsClickableItem(
                title = "Mobile video quality",
                subtitle = settings.mobileQuality.label,
                tag = "setting_mobile_quality_item",
                onClick = { showMobileQualityDialog = true }
            )

            HorizontalDivider()

            // History Section
            SettingsSectionHeader("History & Privacy")

            SettingsSwitchItem(
                title = "Record watch history",
                subtitle = "Save watched videos locally on this device",
                checked = settings.historyEnabled,
                tag = "setting_history_switch",
                onCheckedChange = { viewModel.setHistory(it) }
            )
        }
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = "Choose Theme",
            options = listOf(
                AppTheme.SYSTEM to "System default",
                AppTheme.LIGHT to "Light",
                AppTheme.DARK to "Dark"
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
            title = "Wi-Fi Quality Preference",
            options = QualityPreferenceSetting.values().map { it to it.label },
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
            title = "Mobile Network Quality Preference",
            options = QualityPreferenceSetting.values().map { it to it.label },
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
            title = "Default Playback Speed",
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
                Text("Cancel")
            }
        }
    )
}
