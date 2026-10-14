package com.hpre.app.update

import com.hpre.app.settings.UpdateUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateManagerTest {
    private val page = requireNotNull(
        OfficialReleasePage.parse("https://github.com/hungnq131193-ux/HPre/releases/tag/v1.0.1")
    )

    @Test
    fun launch_check_waits_for_startup_and_runs_once() = runTest {
        var calls = 0
        val manager = AppUpdateManager(
            checker = { calls++; UpdateCheckResult.UpToDate(SemanticVersion(1, 0, 0)) },
            installedVersion = "1.0.0",
            scope = this,
            launchCheckDelayMs = 1_000L
        )

        manager.checkOnLaunch()
        manager.checkOnLaunch()
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(0, calls)

        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(UpdateUiState.UpToDate("1.0.0"), manager.state.value)
        assertFalse(manager.launchPromptVisible.value)
    }

    @Test
    fun update_without_installer_falls_back_to_release_page_and_no_prompt() = runTest {
        val apk = ReleaseApk(
            "https://github.com/hungnq131193-ux/HPre/releases/download/v1.0.1/HPre-v1.0.1-release.apk",
            10,
            null
        )
        val manager = AppUpdateManager(
            checker = {
                UpdateCheckResult.UpdateAvailable(SemanticVersion(1, 0, 0), SemanticVersion(1, 0, 1), page, apk)
            },
            installedVersion = "1.0.0",
            scope = this,
            launchCheckDelayMs = 0L
        )

        manager.checkOnLaunch()
        advanceUntilIdle()

        assertEquals(UpdateUiState.UpdateAvailable("1.0.0", "1.0.1", page), manager.state.value)
        assertFalse(manager.launchPromptVisible.value)
    }

    @Test
    fun origin_island_migration_notice_shows_only_for_2_0_1() {
        org.junit.Assert.assertTrue(com.hpre.app.settings.shouldShowOriginIslandMigrationNotice("2.0.1"))
        org.junit.Assert.assertTrue(com.hpre.app.settings.shouldShowOriginIslandMigrationNotice("v2.0.1"))
        assertFalse(com.hpre.app.settings.shouldShowOriginIslandMigrationNotice("2.0.0"))
        assertFalse(com.hpre.app.settings.shouldShowOriginIslandMigrationNotice("2.0.2"))
        assertFalse(com.hpre.app.settings.shouldShowOriginIslandMigrationNotice(null))

        val apkUrl = "https://github.com/hungnq131193-ux/HPre/releases/download/v2.0.2/HPre-v2.0.2-release.apk"
        val apk = ReleaseApk(apkUrl, 100L, null)
        val stateWithApk = UpdateUiState.UpdateAvailable("2.0.1", "2.0.2", page, apk = apk)
        assertEquals(apkUrl, com.hpre.app.settings.originIslandMigrationDownloadUrl(stateWithApk))
    }
}

