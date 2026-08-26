package com.hpre.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LivePlaybackFactsTest {

    @Test
    fun valid_facts_produce_clean_json_and_pass_sanitization() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 120000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 3,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 40000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = true,
            qualityStreamType = "PROGRESSIVE",
            postSwitchGeneration = 2L,
            postSwitchRenderDelta = 2,
            postSwitchPositionDeltaMs = 200L,
            postSwitchAdvanceDeltaMs = 480L
        )

        val json = facts.toJson()
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"completion\": true"))
        assertTrue(json.contains("\"actualDurationMs\": 120000"))
        assertTrue(json.contains("\"surfaceAttached\": true"))
        assertTrue(json.contains("\"initialRenderCount\": 3"))
        assertTrue(json.contains("\"postSwitchRenderDelta\": 2"))
        assertTrue(json.contains("\"qualityStreamType\": \"PROGRESSIVE\""))

        // Must pass sanitization
        facts.validateSanitized()
    }

    @Test
    fun facts_disagree_and_nonconstant_values_are_preserved() {
        val facts1 = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 120000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 2,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 40000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = true,
            qualityStreamType = "PROGRESSIVE",
            postSwitchGeneration = 2L,
            postSwitchRenderDelta = 3,
            postSwitchPositionDeltaMs = 200L,
            postSwitchAdvanceDeltaMs = 480L
        )

        val facts2 = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 180000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 3L,
            initialRenderCount = 5,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 600L,
            seekTargetMs = 60000L,
            seekActualDeltaMs = 250L,
            postSeekDeltaMs = 550L,
            qualityAttempted = true,
            qualityStreamType = "MERGED_AV",
            postSwitchGeneration = 4L,
            postSwitchRenderDelta = 7,
            postSwitchPositionDeltaMs = 100L,
            postSwitchAdvanceDeltaMs = 520L
        )

        assertEquals(2, facts1.initialRenderCount)
        assertEquals(5, facts2.initialRenderCount)
        assertEquals(3, facts1.postSwitchRenderDelta)
        assertEquals(7, facts2.postSwitchRenderDelta)
        assertEquals(120000L, facts1.actualDurationMs)
        assertEquals(180000L, facts2.actualDurationMs)
        assertEquals("PROGRESSIVE", facts1.qualityStreamType)
        assertEquals("MERGED_AV", facts2.qualityStreamType)

        facts1.validateSanitized()
        facts2.validateSanitized()
    }

    @Test
    fun facts_json_escaping_and_roundtrip_verification() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 120000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 40000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = false,
            qualityStreamType = null,
            postSwitchGeneration = null,
            postSwitchRenderDelta = null,
            postSwitchPositionDeltaMs = null,
            postSwitchAdvanceDeltaMs = null
        )

        val json = facts.toJson()
        val parsed = LivePlaybackFacts.fromJson(json)
        assertEquals(facts, parsed)
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_duration_at_or_below_5000_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 5000L, // Must be > 5000L
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 2000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = false,
            qualityStreamType = null,
            postSwitchGeneration = null,
            postSwitchRenderDelta = null,
            postSwitchPositionDeltaMs = null,
            postSwitchAdvanceDeltaMs = null
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_zero_initial_render_count_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 10000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 0, // Must be > 0
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 4000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = false,
            qualityStreamType = null,
            postSwitchGeneration = null,
            postSwitchRenderDelta = null,
            postSwitchPositionDeltaMs = null,
            postSwitchAdvanceDeltaMs = null
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_seek_delta_greater_than_1000_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 10000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 4000L,
            seekActualDeltaMs = 1001L, // Must be <= 1000L
            postSeekDeltaMs = 450L,
            qualityAttempted = false,
            qualityStreamType = null,
            postSwitchGeneration = null,
            postSwitchRenderDelta = null,
            postSwitchPositionDeltaMs = null,
            postSwitchAdvanceDeltaMs = null
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_quality_attempted_but_post_gen_not_greater_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 10000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 2L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 4000L,
            seekActualDeltaMs = 100L,
            postSeekDeltaMs = 450L,
            qualityAttempted = true,
            qualityStreamType = "PROGRESSIVE",
            postSwitchGeneration = 2L, // Not > initialGeneration (2L <= 2L)
            postSwitchRenderDelta = 1,
            postSwitchPositionDeltaMs = 200L,
            postSwitchAdvanceDeltaMs = 480L
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_unescaped_special_chars_or_quotes_in_string_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 60000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY\"\n",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 20000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = false,
            qualityStreamType = null,
            postSwitchGeneration = null,
            postSwitchRenderDelta = null,
            postSwitchPositionDeltaMs = null,
            postSwitchAdvanceDeltaMs = null
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_incomplete_state_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = false,
            actualDurationMs = 120000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 40000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = false,
            qualityStreamType = null,
            postSwitchGeneration = null,
            postSwitchRenderDelta = null,
            postSwitchPositionDeltaMs = null,
            postSwitchAdvanceDeltaMs = null
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_prohibited_stream_type_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 60000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 20000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = true,
            qualityStreamType = "HLS",
            postSwitchGeneration = 2L,
            postSwitchRenderDelta = 1,
            postSwitchPositionDeltaMs = 200L,
            postSwitchAdvanceDeltaMs = 480L
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalStateException::class)
    fun facts_with_leaked_url_in_string_fails_validation() {
        val facts = LivePlaybackFacts(
            schemaVersion = 1,
            completion = true,
            actualDurationMs = 60000L,
            surfaceAttached = true,
            playerViewAttached = true,
            playerViewLaidOut = true,
            playerViewGlobalVisible = true,
            playerViewHasPlayer = true,
            initialGeneration = 1L,
            initialRenderCount = 1,
            initialPlaybackState = "STATE_READY_https://leak.url",
            initialIsPlaying = true,
            advanceDeltaMs = 500L,
            seekTargetMs = 20000L,
            seekActualDeltaMs = 150L,
            postSeekDeltaMs = 450L,
            qualityAttempted = true,
            qualityStreamType = "PROGRESSIVE",
            postSwitchGeneration = 2L,
            postSwitchRenderDelta = 1,
            postSwitchPositionDeltaMs = 200L,
            postSwitchAdvanceDeltaMs = 480L
        )

        facts.validateSanitized()
    }

    @Test(expected = IllegalArgumentException::class)
    fun facts_json_with_unknown_field_is_rejected() {
        val validJson = """
            {
              "schemaVersion": 1,
              "completion": true,
              "actualDurationMs": 120000,
              "surfaceAttached": true,
              "playerViewAttached": true,
              "playerViewLaidOut": true,
              "playerViewGlobalVisible": true,
              "playerViewHasPlayer": true,
              "initialGeneration": 1,
              "initialRenderCount": 1,
              "initialPlaybackState": "STATE_READY",
              "initialIsPlaying": true,
              "advanceDeltaMs": 500,
              "seekTargetMs": 40000,
              "seekActualDeltaMs": 150,
              "postSeekDeltaMs": 450,
              "qualityAttempted": false,
              "qualityStreamType": null,
              "postSwitchGeneration": null,
              "postSwitchRenderDelta": null,
              "postSwitchPositionDeltaMs": null,
              "postSwitchAdvanceDeltaMs": null,
              "unknownField": "bad"
            }
        """.trimIndent()
        LivePlaybackFacts.fromJson(validJson)
    }

    @Test(expected = IllegalArgumentException::class)
    fun facts_json_with_missing_field_is_rejected() {
        val jsonMissingField = """
            {
              "schemaVersion": 1,
              "completion": true,
              "actualDurationMs": 120000,
              "surfaceAttached": true,
              "playerViewAttached": true,
              "playerViewLaidOut": true,
              "playerViewGlobalVisible": true,
              "playerViewHasPlayer": true,
              "initialGeneration": 1,
              "initialRenderCount": 1,
              "initialPlaybackState": "STATE_READY",
              "initialIsPlaying": true,
              "advanceDeltaMs": 500,
              "seekTargetMs": 40000,
              "seekActualDeltaMs": 150,
              "postSeekDeltaMs": 450,
              "qualityAttempted": false,
              "qualityStreamType": null,
              "postSwitchGeneration": null,
              "postSwitchRenderDelta": null,
              "postSwitchPositionDeltaMs": null
            }
        """.trimIndent()
        LivePlaybackFacts.fromJson(jsonMissingField)
    }

    @Test(expected = IllegalArgumentException::class)
    fun facts_json_with_trailing_content_is_rejected() {
        val validJson = """
            {
              "schemaVersion": 1,
              "completion": true,
              "actualDurationMs": 120000,
              "surfaceAttached": true,
              "playerViewAttached": true,
              "playerViewLaidOut": true,
              "playerViewGlobalVisible": true,
              "playerViewHasPlayer": true,
              "initialGeneration": 1,
              "initialRenderCount": 1,
              "initialPlaybackState": "STATE_READY",
              "initialIsPlaying": true,
              "advanceDeltaMs": 500,
              "seekTargetMs": 40000,
              "seekActualDeltaMs": 150,
              "postSeekDeltaMs": 450,
              "qualityAttempted": false,
              "qualityStreamType": null,
              "postSwitchGeneration": null,
              "postSwitchRenderDelta": null,
              "postSwitchPositionDeltaMs": null,
              "postSwitchAdvanceDeltaMs": null
            } trailing_junk
        """.trimIndent()
        LivePlaybackFacts.fromJson(validJson)
    }

    @Test(expected = IllegalArgumentException::class)
    fun facts_json_with_invalid_escape_is_rejected() {
        val jsonInvalidEscape = """
            {
              "schemaVersion": 1,
              "completion": true,
              "actualDurationMs": 120000,
              "surfaceAttached": true,
              "playerViewAttached": true,
              "playerViewLaidOut": true,
              "playerViewGlobalVisible": true,
              "playerViewHasPlayer": true,
              "initialGeneration": 1,
              "initialRenderCount": 1,
              "initialPlaybackState": "STATE_READY\q",
              "initialIsPlaying": true,
              "advanceDeltaMs": 500,
              "seekTargetMs": 40000,
              "seekActualDeltaMs": 150,
              "postSeekDeltaMs": 450,
              "qualityAttempted": false,
              "qualityStreamType": null,
              "postSwitchGeneration": null,
              "postSwitchRenderDelta": null,
              "postSwitchPositionDeltaMs": null,
              "postSwitchAdvanceDeltaMs": null
            }
        """.trimIndent()
        LivePlaybackFacts.fromJson(jsonInvalidEscape)
    }

    @Test(expected = IllegalArgumentException::class)
    fun facts_json_with_duplicate_keys_is_rejected() {
        val jsonDuplicateKeys = """
            {
              "schemaVersion": 1,
              "schemaVersion": 1,
              "completion": true,
              "actualDurationMs": 120000,
              "surfaceAttached": true,
              "playerViewAttached": true,
              "playerViewLaidOut": true,
              "playerViewGlobalVisible": true,
              "playerViewHasPlayer": true,
              "initialGeneration": 1,
              "initialRenderCount": 1,
              "initialPlaybackState": "STATE_READY",
              "initialIsPlaying": true,
              "advanceDeltaMs": 500,
              "seekTargetMs": 40000,
              "seekActualDeltaMs": 150,
              "postSeekDeltaMs": 450,
              "qualityAttempted": false,
              "qualityStreamType": null,
              "postSwitchGeneration": null,
              "postSwitchRenderDelta": null,
              "postSwitchPositionDeltaMs": null,
              "postSwitchAdvanceDeltaMs": null
            }
        """.trimIndent()
        LivePlaybackFacts.fromJson(jsonDuplicateKeys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun facts_json_with_wrong_type_is_rejected() {
        val jsonWrongType = """
            {
              "schemaVersion": "1",
              "completion": true,
              "actualDurationMs": 120000,
              "surfaceAttached": true,
              "playerViewAttached": true,
              "playerViewLaidOut": true,
              "playerViewGlobalVisible": true,
              "playerViewHasPlayer": true,
              "initialGeneration": 1,
              "initialRenderCount": 1,
              "initialPlaybackState": "STATE_READY",
              "initialIsPlaying": true,
              "advanceDeltaMs": 500,
              "seekTargetMs": 40000,
              "seekActualDeltaMs": 150,
              "postSeekDeltaMs": 450,
              "qualityAttempted": false,
              "qualityStreamType": null,
              "postSwitchGeneration": null,
              "postSwitchRenderDelta": null,
              "postSwitchPositionDeltaMs": null,
              "postSwitchAdvanceDeltaMs": null
            }
        """.trimIndent()
        LivePlaybackFacts.fromJson(jsonWrongType)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rfc8259_rejects_positive_sign_number() {
        LivePlaybackFacts.parseStrictJsonObject("""{"num": +1}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rfc8259_rejects_leading_zero_number() {
        LivePlaybackFacts.parseStrictJsonObject("""{"num": 01}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rfc8259_rejects_negative_leading_zero_number() {
        LivePlaybackFacts.parseStrictJsonObject("""{"num": -01}""")
    }

    @Test
    fun rfc8259_accepts_valid_numbers() {
        val parsed0 = LivePlaybackFacts.parseStrictJsonObject("""{"num": 0}""")
        assertEquals(0L, parsed0["num"])
        val parsedNeg0 = LivePlaybackFacts.parseStrictJsonObject("""{"num": -0}""")
        assertEquals(0L, parsedNeg0["num"])
        val parsedPos = LivePlaybackFacts.parseStrictJsonObject("""{"num": 123}""")
        assertEquals(123L, parsedPos["num"])
        val parsedNeg = LivePlaybackFacts.parseStrictJsonObject("""{"num": -123}""")
        assertEquals(-123L, parsedNeg["num"])
    }
}
