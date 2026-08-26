package com.hpre.app.extractor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upstream smoke test for NewPipeExtractor integration.
 *
 * Rules:
 * - Deterministic / non-gating for CI unless test instrumentation argument `hpreSmokeQuery` is provided (skipped via Assume.assumeTrue).
 * - When query is provided, query must be non-blank.
 * - On success, asserts non-empty video search results, valid ContentKey, non-blank title, details valid key, and streamInfo contains at least one usable stream.
 * - If search, video, or streamInfo fails (even with a safe mapped AppError), fail() is explicitly invoked.
 * - NEVER hardcodes video IDs or stream URLs, and NEVER treats tautology/empty success as pass.
 *
 * Run with:
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.hpreSmokeQuery=<query>`
 */
@RunWith(AndroidJUnit4::class)
class UpstreamSmokeTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            ExtractorBootstrap.init()
        }
    }

    @Test
    fun smoke_test_search_video_and_stream_info_when_argument_provided() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val smokeQuery = arguments.getString("hpreSmokeQuery")

        if (smokeQuery == null) {
            Assume.assumeTrue(
                "hpreSmokeQuery argument not provided; skipping live upstream test.",
                false
            )
            return@runBlocking
        }

        if (smokeQuery.isBlank()) {
            fail("hpreSmokeQuery was supplied but blank; failing per smoke testing policy.")
        }

        val service = NewPipeVideoService()

        when (val result = UpstreamSmokeCandidateEvaluator.evaluate(service, smokeQuery!!.trim(), maxCandidates = 5, maxStreamProbesPerCandidate = 3)) {
            is UpstreamSmokeCandidateEvaluator.EvaluationResult.Success -> {
                val candidate = result.candidate
                assertTrue("ContentKey serviceId must be valid", candidate.details.key.serviceId >= 0)
                assertTrue("ContentKey nativeId must be non-blank", candidate.details.key.nativeId.isNotBlank())
                assertTrue("Video title must be non-blank", candidate.details.title.isNotBlank())
                assertEquals("StreamInfo key must match candidate key", candidate.details.key, candidate.streamInfo.key)

                val validVideoCandidates = candidate.streamInfo.videoStreams.filter { NewPipeMappers.isValidHttpUrl(it.url) }
                val validAudioCandidates = candidate.streamInfo.audioStreams.filter { NewPipeMappers.isValidHttpUrl(it.url) }
                val validHls = candidate.streamInfo.hlsManifestUrl?.takeIf { NewPipeMappers.isValidHttpUrl(it) }
                val validDash = candidate.streamInfo.dashManifestUrl?.takeIf { NewPipeMappers.isValidHttpUrl(it) }

                val hasProductionValidCandidate = validVideoCandidates.isNotEmpty() ||
                        validAudioCandidates.isNotEmpty() ||
                        validHls != null ||
                        validDash != null
                assertTrue("StreamInfo must contain at least one production-valid audio/video candidate or manifest", hasProductionValidCandidate)
            }
            is UpstreamSmokeCandidateEvaluator.EvaluationResult.SearchFailed -> {
                fail("Upstream smoke search failed with category: ${result.errorCategory}")
            }
            is UpstreamSmokeCandidateEvaluator.EvaluationResult.EmptyVideoResults -> {
                fail("Expected non-empty video results on successful search")
            }
            is UpstreamSmokeCandidateEvaluator.EvaluationResult.AllCandidatesFailed -> {
                val summary = UpstreamSmokeCandidateEvaluator.formatFailureSummary(result.failures)
                fail("All evaluated video candidates (up to 5) failed upstream smoke test: $summary")
            }
        }
    }
}


