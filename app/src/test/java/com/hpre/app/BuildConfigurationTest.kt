package com.hpre.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigurationTest {
    @Test fun application_id_is_hpre() {
        assertEquals("com.hpre.app", BuildConfig.APPLICATION_ID)
    }

    @Test fun release_version_is_1_0_16_code_17_and_shrinks_resources() {
        assertEquals("1.0.16", BuildConfig.VERSION_NAME)
        assertEquals(17, BuildConfig.VERSION_CODE)

        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val buildScript = File(root, "app/build.gradle.kts").readText()
        assertTrue(buildScript.contains("isShrinkResources = true"))
    }

    @Test fun compose_host_is_edge_to_edge() {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val activity = File(root, "app/src/main/java/com/hpre/app/MainActivity.kt").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val themes = File(root, "app/src/main/res/values/themes.xml").readText()

        assertTrue(activity.contains("enableEdgeToEdge"))
        assertTrue(manifest.contains("@style/Theme.HPre"))
        assertTrue(themes.contains("name=\"Theme.HPre\""))
    }
}
