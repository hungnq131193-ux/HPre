package com.hpre.app.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlayerPackageBoundaryTest {

    private fun findMainSourceRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            val candidate = File(current, "app/src/main/java")
            if (candidate.exists() && candidate.isDirectory) {
                return candidate
            }
            val directCandidate = File(current, "src/main/java")
            if (directCandidate.exists() && directCandidate.isDirectory) {
                return directCandidate
            }
            current = current.parentFile
        }
        throw IllegalStateException("Could not locate main source root")
    }

    private fun findTestSourceRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            val candidate = File(current, "app/src/test/java")
            if (candidate.exists() && candidate.isDirectory) {
                return candidate
            }
            val directCandidate = File(current, "src/test/java")
            if (directCandidate.exists() && directCandidate.isDirectory) {
                return directCandidate
            }
            current = current.parentFile
        }
        throw IllegalStateException("Could not locate test source root")
    }

    @Test
    fun player_package_main_and_test_sources_do_not_import_extractor_or_org_schabi() {
        val mainSourceRoot = findMainSourceRoot()
        val testSourceRoot = findTestSourceRoot()

        val playerMainDir = File(mainSourceRoot, "com/hpre/app/player")
        val playerTestDir = File(testSourceRoot, "com/hpre/app/player")

        assertTrue("Player main directory must exist", playerMainDir.exists())
        assertTrue("Player test directory must exist", playerTestDir.exists())

        val mainFiles = playerMainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

        val testFiles = playerTestDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

        val allFiles = mainFiles + testFiles
        val violations = mutableListOf<String>()
        val forbiddenPattern = Regex("""\b(com\.HPre\.app\.extractor|org\.schabi)\b""")

        for (file in allFiles) {
            if (file.name == "PlayerPackageBoundaryTest.kt") {
                continue
            }
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                    if (forbiddenPattern.containsMatchIn(line)) {
                        violations.add("${file.name}:${index + 1}: $line")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Player layer (main and unit tests) must not import extractor or org.schabi packages:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun ui_watch_package_does_not_directly_import_or_invoke_raw_exoplayer() {
        val mainSourceRoot = findMainSourceRoot()
        val watchUiDir = File(mainSourceRoot, "com/hpre/app/ui/watch")

        assertTrue("Watch UI directory must exist", watchUiDir.exists())

        val uiFiles = watchUiDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

        val violations = mutableListOf<String>()
        val forbiddenPattern = Regex("""\b(androidx\.media3\.exoplayer\.ExoPlayer)\b""")

        for (file in uiFiles) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                    if (forbiddenPattern.containsMatchIn(line)) {
                        violations.add("${file.name}:${index + 1}: $line")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("UI Watch package must not directly import raw ExoPlayer (must use controller APIs instead):\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun HPre_playback_service_is_sole_exoplayer_owner_and_releaser_in_main_sources() {
        val mainSourceRoot = findMainSourceRoot()
        val allMainFiles = mainSourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

        val violations = mutableListOf<String>()
        val builderPattern = Regex("""\bExoPlayer\.Builder\b""")
        val playerReleasePattern = Regex("""\b(exoPlayer|player)\.release\(\)""")

        for (file in allMainFiles) {
            if (file.name == "HPrePlaybackService.kt") {
                continue
            }
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                    if (builderPattern.containsMatchIn(line)) {
                        violations.add("${file.name}:${index + 1}: contains ExoPlayer.Builder -> $line")
                    }
                    if (playerReleasePattern.containsMatchIn(line)) {
                        violations.add("${file.name}:${index + 1}: calls player release -> $line")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Only HPrePlaybackService is allowed to construct ExoPlayer or release player in main source:\n" + violations.joinToString("\n"))
        }
    }
}

