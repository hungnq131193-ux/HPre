package com.flowtube.app.core.provenance

import java.time.Instant

object EvidenceProvenanceValidator {

    /**
     * Validates that the timestamps representing release artifacts and live verification
     * respect strict causality/provenance order:
     * apkBuiltUtc <= liveStartedUtc <= liveFinishedUtc
     *
     * @param apkBuiltUtc ISO-8601 UTC timestamp of APK build
     * @param liveStartedUtc ISO-8601 UTC timestamp of live test start
     * @param liveFinishedUtc ISO-8601 UTC timestamp of live test finish
     * @throws java.time.format.DateTimeParseException if any timestamp is not a valid ISO-8601 instant
     * @throws IllegalArgumentException if the chronological ordering is violated
     */
    fun validate(
        apkBuiltUtc: String,
        liveStartedUtc: String,
        liveFinishedUtc: String
    ) {
        val apkBuiltInstant = Instant.parse(apkBuiltUtc)
        val liveStartedInstant = Instant.parse(liveStartedUtc)
        val liveFinishedInstant = Instant.parse(liveFinishedUtc)

        require(!apkBuiltInstant.isAfter(liveStartedInstant)) {
            "APK timestamp/provenance ($apkBuiltInstant) cannot be after live test start ($liveStartedInstant)"
        }
        require(!liveStartedInstant.isAfter(liveFinishedInstant)) {
            "Live test start ($liveStartedInstant) cannot be after live test finish ($liveFinishedInstant)"
        }
    }
}
