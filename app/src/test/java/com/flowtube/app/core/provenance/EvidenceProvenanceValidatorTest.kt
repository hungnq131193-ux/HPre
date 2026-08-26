package com.flowtube.app.core.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeParseException

class EvidenceProvenanceValidatorTest {

    @Test
    fun `validate passes when apkBuiltUtc is before liveStartedUtc and liveStartedUtc is before liveFinishedUtc`() {
        val apkBuilt = "2026-08-25T12:55:09Z"
        val liveStarted = "2026-08-25T12:55:36Z"
        val liveFinished = "2026-08-25T12:57:22Z"

        // Should not throw
        EvidenceProvenanceValidator.validate(
            apkBuiltUtc = apkBuilt,
            liveStartedUtc = liveStarted,
            liveFinishedUtc = liveFinished
        )
    }

    @Test
    fun `validate passes when timestamps are equal`() {
        val timestamp = "2026-08-25T12:55:00Z"

        EvidenceProvenanceValidator.validate(
            apkBuiltUtc = timestamp,
            liveStartedUtc = timestamp,
            liveFinishedUtc = timestamp
        )
    }

    @Test
    fun `validate throws IllegalArgumentException when apkBuiltUtc is after liveStartedUtc`() {
        val apkBuilt = "2026-08-25T12:56:00Z"
        val liveStarted = "2026-08-25T12:55:36Z"
        val liveFinished = "2026-08-25T12:57:22Z"

        val exception = assertThrows(IllegalArgumentException::class.java) {
            EvidenceProvenanceValidator.validate(
                apkBuiltUtc = apkBuilt,
                liveStartedUtc = liveStarted,
                liveFinishedUtc = liveFinished
            )
        }
        assertTrue(exception.message?.contains("APK timestamp/provenance") == true)
    }

    @Test
    fun `validate throws IllegalArgumentException when liveStartedUtc is after liveFinishedUtc`() {
        val apkBuilt = "2026-08-25T12:55:09Z"
        val liveStarted = "2026-08-25T12:58:00Z"
        val liveFinished = "2026-08-25T12:57:22Z"

        val exception = assertThrows(IllegalArgumentException::class.java) {
            EvidenceProvenanceValidator.validate(
                apkBuiltUtc = apkBuilt,
                liveStartedUtc = liveStarted,
                liveFinishedUtc = liveFinished
            )
        }
        assertTrue(exception.message?.contains("Live test start") == true)
    }

    @Test
    fun `validate throws DateTimeParseException when input is not valid ISO instant`() {
        assertThrows(DateTimeParseException::class.java) {
            EvidenceProvenanceValidator.validate(
                apkBuiltUtc = "invalid-date",
                liveStartedUtc = "2026-08-25T12:55:36Z",
                liveFinishedUtc = "2026-08-25T12:57:22Z"
            )
        }
    }
}
