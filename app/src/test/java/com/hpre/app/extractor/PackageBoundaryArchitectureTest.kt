package com.hpre.app.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PackageBoundaryArchitectureTest {

    private fun findMainSourceRoot(): File {
        // Walk ancestors from current directory or test class resource to locate app/src/main/java
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
        throw IllegalStateException("Could not locate app/src/main/java by walking ancestors from ${System.getProperty("user.dir")}")
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
        throw IllegalStateException("Could not locate app/src/test/java by walking ancestors from ${System.getProperty("user.dir")}")
    }

    companion object {
        fun resolvePackage(file: File, sourceRoot: File): String {
            if (file.exists()) {
                val lines = file.readLines()
                val packageLine = lines.firstOrNull { it.trim().startsWith("package ") }
                if (packageLine != null) {
                    return packageLine.trim().removePrefix("package ").substringBefore(";").trim()
                }
            }
            val relative = file.parentFile?.relativeTo(sourceRoot)?.invariantSeparatorsPath ?: ""
            return relative.replace('/', '.')
        }

        fun isExtractorFile(file: File, sourceRoot: File): Boolean {
            val pkg = resolvePackage(file, sourceRoot)
            return pkg == "com.hpre.app.extractor"
        }

        fun scanViolations(files: List<File>, sourceRoot: File? = null): List<String> {
            val violations = mutableListOf<String>()
            val orgSchabiRegex = Regex("""\borg\.schabi\b""")
            for (file in files) {
                if (sourceRoot != null && isExtractorFile(file, sourceRoot)) {
                    continue
                }
                if (file.name.endsWith("BoundaryTest.kt") || file.name.endsWith("BoundaryArchitectureTest.kt") || file.name == "PageTokenTest.kt") {
                    continue
                }
                val lines = file.readLines()
                for ((index, line) in lines.withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    if (orgSchabiRegex.containsMatchIn(line)) {
                        violations.add("${file.name}:${index + 1}: $line")
                    }
                }
            }
            return violations
        }
    }

    @Test
    fun verify_no_org_schabi_usages_outside_extractor_package() {
        val mainSourceRoot = findMainSourceRoot()
        val testSourceRoot = findTestSourceRoot()
        assertTrue("Main source root must exist: ${mainSourceRoot.absolutePath}", mainSourceRoot.exists())
        assertTrue("Test source root must exist: ${testSourceRoot.absolutePath}", testSourceRoot.exists())

        val outsideMainFiles = mainSourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { !isExtractorFile(it, mainSourceRoot) }
            .toList()

        val outsideTestFiles = testSourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { !isExtractorFile(it, testSourceRoot) }
            .toList()

        val allOutsideFiles = outsideMainFiles + outsideTestFiles

        assertTrue("Must have scanned production and test source files outside extractor", allOutsideFiles.isNotEmpty())

        val violations = scanViolations(outsideMainFiles, mainSourceRoot) + scanViolations(outsideTestFiles, testSourceRoot)

        if (violations.isNotEmpty()) {
            fail("Found illegal NewPipeExtractor 'org.schabi' usages outside 'extractor/' package:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun scanner_detects_violations_in_fixture_files_outside_extractor_and_accepts_extractor_fixtures() {
        val tempOutsideFile = File.createTempFile("ViolationFixture", ".kt")
        val tempExtractorFile = File.createTempFile("ExtractorFixture", ".kt")
        try {
            tempOutsideFile.writeText(
                """
                package com.hpre.app.model
                import org.schabi.newpipe.extractor.stream.StreamInfo
                class ViolationFixture {
                    val info: org.schabi.newpipe.extractor.stream.StreamInfo? = null
                }
                """.trimIndent()
            )
            tempExtractorFile.writeText(
                """
                package com.hpre.app.extractor
                import org.schabi.newpipe.extractor.stream.StreamInfo
                class ExtractorFixture {
                    val info: org.schabi.newpipe.extractor.stream.StreamInfo? = null
                }
                """.trimIndent()
            )

            // Outside fixture must trigger violations
            val outsideViolations = scanViolations(listOf(tempOutsideFile))
            assertEquals(2, outsideViolations.size)

            // Extractor fixture must not trigger violations when resolved
            val sourceRoot = findMainSourceRoot()
            val extractorViolations = scanViolations(listOf(tempExtractorFile), sourceRoot)
            assertEquals(0, extractorViolations.size)
        } finally {
            tempOutsideFile.delete()
            tempExtractorFile.delete()
        }
    }

    @Test
    fun isExtractorFile_correctly_identifies_exact_package_and_rejects_deceptive_paths() {
        val sourceRoot = findMainSourceRoot()
        val extractorFile = File(sourceRoot, "com/HPre/app/extractor/OkHttpDownloader.kt")
        val outsideFile = File(sourceRoot, "com/HPre/app/repository/VideoService.kt")
        val deceptiveFile = File(sourceRoot, "com/HPre/app/extractors/FakeService.kt")

        assertTrue(isExtractorFile(extractorFile, sourceRoot))
        assertFalse(isExtractorFile(outsideFile, sourceRoot))
        assertFalse(isExtractorFile(deceptiveFile, sourceRoot))
    }
}



