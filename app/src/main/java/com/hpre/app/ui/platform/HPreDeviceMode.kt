package com.hpre.app.ui.platform

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider

enum class HPreInputMode {
    TOUCH,
    REMOTE,
}

data class HPreDeviceFeatures(
    val hasLeanback: Boolean,
    val hasAutomotive: Boolean,
    val hasTouchscreen: Boolean,
)

object HPreDeviceModePolicy {
    fun select(features: HPreDeviceFeatures): HPreInputMode =
        if (features.hasLeanback || features.hasAutomotive || !features.hasTouchscreen) {
            HPreInputMode.REMOTE
        } else {
            HPreInputMode.TOUCH
        }

    fun fromPackageManager(packageManager: PackageManager): HPreInputMode = select(
        HPreDeviceFeatures(
            hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            hasAutomotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
            hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        ),
    )
}

val LocalHPreInputMode = compositionLocalOf { HPreInputMode.TOUCH }

@Composable
fun ProvideHPreInputMode(mode: HPreInputMode, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHPreInputMode provides mode, content = content)
}
