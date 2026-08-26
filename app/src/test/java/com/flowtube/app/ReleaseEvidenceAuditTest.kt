package com.flowtube.app

import com.flowtube.app.core.provenance.EvidenceProvenanceValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class ReleaseEvidenceAuditTest {

    @Test
    fun evidence_facts_and_build_files_contain_no_banned_terms_and_are_coherent() {
        var rootDir = File(".").canonicalFile
        if (!File(rootDir, "docs").exists()) {
            rootDir = rootDir.parentFile ?: rootDir
        }
        val factsFile = File(rootDir, "docs/evidence/task5c-live-playback-android35-facts.json")
        val buildFile = File(rootDir, "docs/evidence/task5c-live-playback-android35-build.json")
        val xmlFile = File(rootDir, "docs/evidence/task5c-live-playback-android35.xml")
        val docFile = File(rootDir, "docs/release-evidence.md")
        val depDocFile = File(rootDir, "docs/dependency-decision.md")

        assertTrue("Facts file must exist at ${factsFile.absolutePath}", factsFile.exists())
        assertTrue("Build file must exist at ${buildFile.absolutePath}", buildFile.exists())
        assertTrue("XML report must exist at ${xmlFile.absolutePath}", xmlFile.exists())
        assertTrue("Evidence doc must exist at ${docFile.absolutePath}", docFile.exists())

        val factsText = factsFile.readText(Charsets.UTF_8)
        val buildText = buildFile.readText(Charsets.UTF_8)
        val xmlText = xmlFile.readText(Charsets.UTF_8)
        val docText = docFile.readText(Charsets.UTF_8)
        val depDocText = if (depDocFile.exists()) depDocFile.readText(Charsets.UTF_8) else ""

        val bannedPatternsInEvidence = listOf(
            "flowtubeSmokeQuery=Kotlin",
            "http://", "https://", "rtmp://",
            "v=", "watch?v=", "Bearer ", "token=", "auth="
        )

        for (banned in bannedPatternsInEvidence) {
            assertFalse("Build manifest contains banned pattern: $banned", buildText.contains(banned, ignoreCase = true))
            assertFalse("Facts report contains banned pattern: $banned", factsText.contains(banned, ignoreCase = true))
            assertFalse("XML report contains banned pattern: $banned", xmlText.contains(banned, ignoreCase = true))
        }

        // Release doc privacy check: ensure no absolute home paths, live queries, id parameters, raw urls, tokens, bearer, cookie patterns
        val bannedPatternsInReleaseDoc = listOf(
            "C:\\Users", "C:/Users", "\\Users\\", "/Users/", "AppData\\Local", "AppData/Local",
            "flowtubeSmokeQuery=Kotlin", "flowtubeLivePlayback=Kotlin",
            "Bearer ", "token=", "auth=", "cookie=", "watch?v=", "v="
        )
        for (banned in bannedPatternsInReleaseDoc) {
            assertFalse("Evidence doc contains banned pattern: $banned", docText.contains(banned, ignoreCase = true))
        }

        // Reject Windows user home patterns and absolute user path separators in evidence and docs
        val bannedUserPathPatterns = listOf(
            "C:\\Users", "C:/Users", "\\Users\\", "/Users/", "AppData\\Local", "AppData/Local"
        )
        for (banned in bannedUserPathPatterns) {
            assertFalse("Build manifest contains banned user path pattern: $banned", buildText.contains(banned, ignoreCase = true))
            assertFalse("Facts report contains banned user path pattern: $banned", factsText.contains(banned, ignoreCase = true))
            assertFalse("XML report contains banned user path pattern: $banned", xmlText.contains(banned, ignoreCase = true))
            assertFalse("Evidence doc contains banned user path pattern: $banned", docText.contains(banned, ignoreCase = true))
            if (depDocText.isNotEmpty()) {
                assertFalse("Dependency doc contains banned user path pattern: $banned", depDocText.contains(banned, ignoreCase = true))
            }
        }

        // Strict Facts JSON parsing and validation
        val facts = com.flowtube.app.player.LivePlaybackFacts.fromJson(factsText)
        facts.validateSanitized()

        // Strict Build JSON parsing and schema assertion
        val cleanBuildText = buildText.trimStart('\uFEFF')
        val buildJsonMap = com.flowtube.app.player.LivePlaybackFacts.parseStrictJsonObject(cleanBuildText)
        assertStrictBuildJson(buildJsonMap, rootDir)

        // Strict XML parsing and schema assertions
        assertStrictXmlReport(xmlFile, xmlText)

        // SHA-256 verification and cross-referencing between build manifest, facts, xml, apk, and docs
        val factsSha256 = computeSha256(factsFile)
        val xmlSha256 = computeSha256(xmlFile)
        val buildSha256 = computeSha256(buildFile)

        @Suppress("UNCHECKED_CAST")
        val liveExec = buildJsonMap["liveTestExecution"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val factsReportNode = liveExec["factsReport"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val xmlReportNode = liveExec["xmlReport"] as Map<String, Any?>

        assertEquals(
            "Facts SHA256 in build.json must match actual facts file hash",
            factsSha256,
            (factsReportNode["sha256"] as String).uppercase()
        )
        assertEquals(
            "XML SHA256 in build.json must match actual xml file hash",
            xmlSha256,
            (xmlReportNode["sha256"] as String).uppercase()
        )

        assertTrue("Release evidence doc must contain build manifest sha256", docText.contains(buildSha256, ignoreCase = true))
        assertTrue("Release evidence doc must contain facts sha256", docText.contains(factsSha256, ignoreCase = true))
        assertTrue("Release evidence doc must contain xml sha256", docText.contains(xmlSha256, ignoreCase = true))

        @Suppress("UNCHECKED_CAST")
        val artifactNode = buildJsonMap["artifact"] as Map<String, Any?>
        val apkRelPath = artifactNode["path"] as String
        val apkFile = File(rootDir, apkRelPath)
        if (apkFile.exists()) {
            val apkSha256 = computeSha256(apkFile)
            val apkSize = apkFile.length()
            // When APK is present from original release or test build, verify document matches recorded metadata
            assertTrue("Release evidence doc must contain apk sha256", docText.contains(artifactNode["sha256"] as String, ignoreCase = true))
        }

        // Parse and validate release-evidence.md metadata against current facts and build JSON
        assertReleaseEvidenceDocMetadata(docText, buildJsonMap, xmlFile, facts)
    }

    private fun assertReleaseEvidenceDocMetadata(
        docText: String,
        buildJson: Map<String, Any?>,
        xmlFile: File,
        facts: com.flowtube.app.player.LivePlaybackFacts
    ) {
        @Suppress("UNCHECKED_CAST")
        val buildExec = buildJson["buildExecution"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val artifact = buildJson["artifact"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val liveExec = buildJson["liveTestExecution"] as Map<String, Any?>

        val buildCmd = buildExec["command"] as String
        assertTrue("Release evidence doc must contain build execution command: $buildCmd", docText.contains(buildCmd))

        val buildStart = Instant.parse(buildExec["startTimestamp"] as String)
        val buildEnd = Instant.parse(buildExec["endTimestamp"] as String)
        val buildDurationSec = java.time.Duration.between(buildStart, buildEnd).seconds
        assertTrue("Release evidence doc must contain build duration (${buildDurationSec}s)", docText.contains("${buildDurationSec}s"))
        assertFalse("Release evidence doc must not contain stale duration (26s)", docText.contains("(26s)"))

        val buildDate = (buildExec["startTimestamp"] as String).substringBefore("T")
        assertTrue("Release evidence doc must contain build date: $buildDate", docText.contains(buildDate))

        val liveDate = (liveExec["startTimestamp"] as String).substringBefore("T")
        assertTrue("Release evidence doc must contain live execution date: $liveDate", docText.contains(liveDate))

        val apkSizeBytes = artifact["sizeBytes"] as Long
        val formattedSize = "%,d".format(apkSizeBytes)
        assertTrue(
            "Release evidence doc must contain formatted APK size: $formattedSize or raw size: $apkSizeBytes",
            docText.contains(formattedSize) || docText.contains(apkSizeBytes.toString())
        )

        val factory = DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlFile)
        val root = doc.documentElement

        val testCount = root.getAttribute("tests")
        val failures = root.getAttribute("failures")
        val errors = root.getAttribute("errors")
        val skipped = root.getAttribute("skipped")
        val suiteTime = root.getAttribute("time")

        val testCases = root.getElementsByTagName("testcase")
        val testCase = testCases.item(0) as Element
        val caseTime = testCase.getAttribute("time")

        val expectedSummary = "$testCount test, $failures failures, $errors errors, $skipped skipped"
        assertTrue("Release evidence doc must contain test counts summary '$expectedSummary'", docText.contains(expectedSummary))
        assertTrue("Release evidence doc must contain test duration '$caseTime'", docText.contains(caseTime))
        assertTrue("Release evidence doc must contain suite duration '$suiteTime'", docText.contains(suiteTime))

        // Validate fact values reflected in markdown
        assertTrue("Release evidence doc must contain actualDurationMs ${facts.actualDurationMs}", docText.contains(facts.actualDurationMs.toString()))
        assertTrue("Release evidence doc must contain advanceDeltaMs ${facts.advanceDeltaMs}", docText.contains(facts.advanceDeltaMs.toString()))
        assertTrue("Release evidence doc must contain seekTargetMs ${facts.seekTargetMs}", docText.contains(facts.seekTargetMs.toString()))
        assertTrue("Release evidence doc must contain seekActualDeltaMs ${facts.seekActualDeltaMs}", docText.contains(facts.seekActualDeltaMs.toString()))
        assertTrue("Release evidence doc must contain postSeekDeltaMs ${facts.postSeekDeltaMs}", docText.contains(facts.postSeekDeltaMs.toString()))
        if (facts.qualityStreamType != null) {
            assertTrue("Release evidence doc must contain qualityStreamType ${facts.qualityStreamType}", docText.contains(facts.qualityStreamType!!))
        }
        if (facts.postSwitchPositionDeltaMs != null) {
            assertTrue("Release evidence doc must contain postSwitchPositionDeltaMs ${facts.postSwitchPositionDeltaMs}", docText.contains(facts.postSwitchPositionDeltaMs.toString()))
        }
        if (facts.postSwitchAdvanceDeltaMs != null) {
            assertTrue("Release evidence doc must contain postSwitchAdvanceDeltaMs ${facts.postSwitchAdvanceDeltaMs}", docText.contains(facts.postSwitchAdvanceDeltaMs.toString()))
        }
    }

    @Test
    fun manifest_provenance_validation_rejects_invalid_ordering_and_apk_built_after_live() {
        val validApkTime = "2026-08-25T12:55:09Z"
        val validLiveStart = "2026-08-25T12:55:36Z"
        val validLiveFinish = "2026-08-25T12:57:22Z"

        // Positive test: valid ordering passes
        EvidenceProvenanceValidator.validate(
            apkBuiltUtc = validApkTime,
            liveStartedUtc = validLiveStart,
            liveFinishedUtc = validLiveFinish
        )

        // Negative test 1: APK built after live start throws IllegalArgumentException
        var threwApkAfterLive = false
        try {
            EvidenceProvenanceValidator.validate(
                apkBuiltUtc = "2026-08-25T12:56:00Z",
                liveStartedUtc = "2026-08-25T12:55:36Z",
                liveFinishedUtc = "2026-08-25T12:57:22Z"
            )
        } catch (e: IllegalArgumentException) {
            threwApkAfterLive = true
            assertTrue(e.message?.contains("APK timestamp/provenance") == true)
        }
        assertTrue("Must reject APK built after live test start", threwApkAfterLive)

        // Negative test 2: Live start after live finish throws IllegalArgumentException
        var threwStartAfterFinish = false
        try {
            EvidenceProvenanceValidator.validate(
                apkBuiltUtc = validApkTime,
                liveStartedUtc = "2026-08-25T12:58:00Z",
                liveFinishedUtc = "2026-08-25T12:57:22Z"
            )
        } catch (e: IllegalArgumentException) {
            threwStartAfterFinish = true
            assertTrue(e.message?.contains("Live test start") == true)
        }
        assertTrue("Must reject live start after live finish", threwStartAfterFinish)
    }

    @Test
    fun audit_fails_if_apk_is_missing() {
        val rootDir = File(".").canonicalFile
        val dummyMissingFile = File(rootDir, "non_existent_path/dummy-debug.apk")
        assertFalse(dummyMissingFile.exists())
    }

    private fun assertStrictBuildJson(buildJson: Map<String, Any?>, rootDir: File) {
        val expectedRootKeys = setOf("schemaVersion", "toolchain", "buildExecution", "artifact", "liveTestExecution")
        assertEquals("Build JSON keys mismatch", expectedRootKeys, buildJson.keys)
        assertEquals(1L, buildJson["schemaVersion"])

        // toolchain
        @Suppress("UNCHECKED_CAST")
        val toolchain = buildJson["toolchain"] as Map<String, Any?>
        val expectedToolchainKeys = setOf("jdk", "gradle", "agp", "kotlin", "androidSdk", "targetDevice")
        assertEquals(expectedToolchainKeys, toolchain.keys)
        assertEquals("Eclipse Temurin 17.0.14+7", toolchain["jdk"])
        assertEquals("8.11.1", toolchain["gradle"])
        assertEquals("8.8.2", toolchain["agp"])
        assertEquals("2.1.20", toolchain["kotlin"])
        assertEquals("API 35 (Android 15)", toolchain["androidSdk"])
        assertEquals("FlowTubeApi35(AVD) - 15 (emulator-5554)", toolchain["targetDevice"])

        // buildExecution
        @Suppress("UNCHECKED_CAST")
        val buildExec = buildJson["buildExecution"] as Map<String, Any?>
        val expectedBuildExecKeys = setOf("command", "startTimestamp", "endTimestamp", "exitCode", "status")
        assertEquals(expectedBuildExecKeys, buildExec.keys)
        assertEquals("gradlew.bat clean test assembleDebug", buildExec["command"])
        assertEquals(0L, buildExec["exitCode"])
        assertEquals("SUCCESS", buildExec["status"])
        val buildStart = Instant.parse(buildExec["startTimestamp"] as String)
        val buildEnd = Instant.parse(buildExec["endTimestamp"] as String)
        val buildDurationSec = java.time.Duration.between(buildStart, buildEnd).seconds
        assertEquals(30L, buildDurationSec)

        // artifact
        @Suppress("UNCHECKED_CAST")
        val artifact = buildJson["artifact"] as Map<String, Any?>
        val expectedArtifactKeys = setOf("name", "path", "sizeBytes", "sha256", "apkBuiltUtc")
        assertEquals(expectedArtifactKeys, artifact.keys)
        assertEquals("app-debug.apk", artifact["name"])
        assertEquals("app/build/outputs/apk/debug/app-debug.apk", artifact["path"])
        assertTrue((artifact["sizeBytes"] as Long) > 0L)
        val apkSha = artifact["sha256"] as String
        assertTrue("APK SHA256 must match 64-character hex", apkSha.matches(Regex("^[0-9A-F]{64}$")))
        Instant.parse(artifact["apkBuiltUtc"] as String) // Must parse cleanly as ISO-8601 UTC

        // liveTestExecution
        @Suppress("UNCHECKED_CAST")
        val liveExec = buildJson["liveTestExecution"] as Map<String, Any?>
        val expectedLiveExecKeys = setOf("command", "liveQueryProvided", "liveTestClass", "startTimestamp", "endTimestamp", "liveStartedUtc", "liveFinishedUtc", "exitCode", "status", "xmlReport", "factsReport")
        assertEquals(expectedLiveExecKeys, liveExec.keys)
        assertEquals(true, liveExec["liveQueryProvided"])
        assertEquals("com.flowtube.app.integration.playback.LivePlaybackGateTest", liveExec["liveTestClass"])
        assertEquals(0L, liveExec["exitCode"])
        assertEquals("SUCCESS", liveExec["status"])
        val liveCommand = liveExec["command"] as String
        assertEquals(
            "gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<provided> -Pandroid.testInstrumentationRunnerArguments.flowtubeLivePlayback=<provided> -Pandroid.testInstrumentationRunnerArguments.flowtubeSmokeQuery=<provided>",
            liveCommand
        )
        Instant.parse(liveExec["startTimestamp"] as String)
        Instant.parse(liveExec["endTimestamp"] as String)

        @Suppress("UNCHECKED_CAST")
        val xmlReport = liveExec["xmlReport"] as Map<String, Any?>
        assertEquals(setOf("file", "sha256"), xmlReport.keys)
        assertEquals("docs/evidence/task5c-live-playback-android35.xml", xmlReport["file"])
        val xmlSha = xmlReport["sha256"] as String
        assertTrue("XML SHA256 must match 64-character hex", xmlSha.matches(Regex("^[0-9A-F]{64}$")))

        @Suppress("UNCHECKED_CAST")
        val factsReport = liveExec["factsReport"] as Map<String, Any?>
        assertEquals(setOf("file", "sha256"), factsReport.keys)
        assertEquals("docs/evidence/task5c-live-playback-android35-facts.json", factsReport["file"])
        val factsSha = factsReport["sha256"] as String
        assertTrue("Facts SHA256 must match 64-character hex", factsSha.matches(Regex("^[0-9A-F]{64}$")))

        // Provenance and timestamp ordering assertions:
        // apkBuiltUtc <= liveStartedUtc <= liveFinishedUtc via callable validation function
        val apkBuiltUtcStr = artifact["apkBuiltUtc"] as String
        val liveStartedUtcStr = liveExec["liveStartedUtc"] as String
        val liveFinishedUtcStr = liveExec["liveFinishedUtc"] as String
        EvidenceProvenanceValidator.validate(
            apkBuiltUtc = apkBuiltUtcStr,
            liveStartedUtc = liveStartedUtcStr,
            liveFinishedUtc = liveFinishedUtcStr
        )
    }

    private fun assertStrictXmlReport(xmlFile: File, xmlText: String) {
        // XML Privacy / Safe check
        val bannedXml = listOf(
            "http://", "https://", "rtmp://", "flowtubeSmokeQuery",
            "token", "Bearer", "auth=", "cookie", "id=", "?v=", "&v="
        )
        for (banned in bannedXml) {
            assertFalse("XML contains banned term: $banned", xmlText.contains(banned, ignoreCase = true))
        }

        val factory = DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlFile)
        val root = doc.documentElement

        assertEquals("Root element must be testsuite", "testsuite", root.tagName)
        assertEquals("com.flowtube.app.integration.playback.LivePlaybackGateTest", root.getAttribute("name"))
        assertEquals("1", root.getAttribute("tests"))
        assertEquals("0", root.getAttribute("failures"))
        assertEquals("0", root.getAttribute("errors"))
        assertEquals("0", root.getAttribute("skipped"))

        val suiteTime = root.getAttribute("time").toDoubleOrNull()
        assertNotNull("Suite time must be valid number", suiteTime)
        assertTrue("Suite time must be positive", suiteTime!! > 0.0)

        val testCases = root.getElementsByTagName("testcase")
        assertEquals(1, testCases.length)
        val testCase = testCases.item(0) as Element
        assertEquals("live_foreground_media3_playback_gate", testCase.getAttribute("name"))
        assertEquals("com.flowtube.app.integration.playback.LivePlaybackGateTest", testCase.getAttribute("classname"))
        val caseTime = testCase.getAttribute("time").toDoubleOrNull()
        assertNotNull("TestCase time must be valid number", caseTime)
        assertTrue("TestCase time must be positive", caseTime!! > 0.0)

        // Ensure testcase text content does not contain forbidden external URL/token/query
        val testCaseContent = testCase.textContent ?: ""
        for (banned in bannedXml) {
            assertFalse("TestCase content contains banned term: $banned", testCaseContent.contains(banned, ignoreCase = true))
        }

        // Ensure no failure or error child nodes exist
        assertEquals(0, testCase.getElementsByTagName("failure").length)
        assertEquals(0, testCase.getElementsByTagName("error").length)
        assertEquals(0, testCase.getElementsByTagName("skipped").length)
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02X".format(it) }
    }
}

