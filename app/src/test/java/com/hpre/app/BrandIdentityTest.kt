package com.hpre.app

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandIdentityTest {
    @Test
    fun Vietnamese_navigation_resources_match_product_copy() {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val stringsXml = File(root, "app/src/main/res/values/strings.xml").readText()

        assertTrue(stringsXml.contains("<string name=\"app_name\">HPre</string>"))
        assertTrue(stringsXml.contains("<string name=\"nav_home\">Trang chủ</string>"))
        assertTrue(stringsXml.contains("<string name=\"nav_subscriptions\">Kênh đăng ký</string>"))
        assertTrue(stringsXml.contains("<string name=\"nav_library\">Thư viện</string>"))
    }

    @Test
    fun prospective_tracked_files_and_paths_do_not_contain_old_brand() {
        val banned = "flow" + "tube"
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        assertNotNull("Could not locate repository root from ${File(".").canonicalPath}", root)

        val process = ProcessBuilder(
            "git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"
        ).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes()
        val exitCode = process.waitFor()
        assertTrue(
            "git ls-files failed with exit code $exitCode: ${output.toString(Charsets.UTF_8)}",
            exitCode == 0
        )

        val textExtensions = setOf("kt", "kts", "md", "json", "xml", "toml", "properties", "pro")
        val files = output.toString(Charsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotBlank)
            .map { relativePath -> relativePath to File(root!!, relativePath) }
            .filter { (_, file) -> file.isFile }

        val violations = files.flatMap { (relativePath, file) ->
            buildList {
                if (relativePath.contains(banned, ignoreCase = true)) {
                    add("path: $relativePath")
                }
                if (file.extension.lowercase() in textExtensions &&
                    file.readText().contains(banned, ignoreCase = true)
                ) {
                    add("content: $relativePath")
                }
            }
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }
}
