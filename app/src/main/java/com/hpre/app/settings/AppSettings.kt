package com.hpre.app.settings

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AppLanguage(val code: String) {
    VIETNAMESE("vi"),
    ENGLISH("en")
}

enum class QualityPreferenceSetting(val label: String, val maxResolution: Int?) {
    AUTO("Auto", null),
    HIGH_1080P("1080p (High)", 1080),
    MEDIUM_720P("720p (Medium)", 720),
    LOW_360P("360p (Low)", 360)
}

data class AppSettings(
    val theme: AppTheme = AppTheme.DARK,
    val language: AppLanguage = AppLanguage.VIETNAMESE,
    val backgroundPlaybackEnabled: Boolean = true,
    val pipEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val wifiQuality: QualityPreferenceSetting = QualityPreferenceSetting.AUTO,
    val mobileQuality: QualityPreferenceSetting = QualityPreferenceSetting.AUTO,
    val defaultPlaybackSpeed: Float = 1.0f,
    val autoplay: Boolean = true
)
