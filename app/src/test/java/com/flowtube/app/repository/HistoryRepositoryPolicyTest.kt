package com.flowtube.app.repository

import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.settings.PlaybackPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRepositoryPolicyTest {

    @Test
    fun resume_threshold_suppresses_resume_at_95_percent_or_greater() {
        // Duration: 100 seconds (100_000 ms)
        val durationSeconds = 100L

        // 0 ms -> no resume (not started / at beginning)
        assertFalse(HistoryRepository.shouldOfferResume(0L, durationSeconds))

        // 10 seconds (10%) -> should resume
        assertTrue(HistoryRepository.shouldOfferResume(10_000L, durationSeconds))

        // 94 seconds (94%) -> should resume (< 95%)
        assertTrue(HistoryRepository.shouldOfferResume(94_000L, durationSeconds))

        // 94.9 seconds (94.9%) -> should resume (< 95%)
        assertTrue(HistoryRepository.shouldOfferResume(94_900L, durationSeconds))

        // 95 seconds (95%) -> should NOT resume (threshold reached)
        assertFalse(HistoryRepository.shouldOfferResume(95_000L, durationSeconds))

        // 99 seconds (99%) -> should NOT resume
        assertFalse(HistoryRepository.shouldOfferResume(99_000L, durationSeconds))

        // 100 seconds (100%) -> should NOT resume
        assertFalse(HistoryRepository.shouldOfferResume(100_000L, durationSeconds))
    }

    @Test
    fun resume_threshold_with_null_or_zero_duration_allows_positive_position() {
        assertTrue(HistoryRepository.shouldOfferResume(15_000L, null))
        assertTrue(HistoryRepository.shouldOfferResume(15_000L, 0L))
        assertFalse(HistoryRepository.shouldOfferResume(0L, null))
        assertFalse(HistoryRepository.shouldOfferResume(-100L, null))
    }

    @Test
    fun search_history_normalizes_whitespace_and_trims_and_lowercases() {
        assertEquals("hello world", SearchHistoryRepository.normalizeQuery("   hello    world   "))
        assertEquals("test query", SearchHistoryRepository.normalizeQuery("test\n\t query"))
        assertEquals("", SearchHistoryRepository.normalizeQuery("   \t\n  "))
        assertEquals("kotlin", SearchHistoryRepository.normalizeQuery("Kotlin"))
        assertEquals("kotlin flow", SearchHistoryRepository.normalizeQuery("  Kotlin   FLOW  "))
    }
}
