package com.hpre.app.repository

import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationRankerTest {
    private fun video(id: String, title: String, channel: String = "Channel") = VideoSummary(
        key = ContentKey(0, id), title = title, canonicalUrl = "https://example.test/$id",
        channelKey = null, channelName = channel, channelAvatarUrl = null, thumbnailUrl = null,
        durationSeconds = 120, viewCount = null, publishedTimestamp = null
    )

    @Test fun ranks_recent_query_and_title_match_first() {
        val older = video("old", "Kotlin tutorial")
        val recent = video("new", "Compose tutorial")
        val result = RecommendationRanker.rank(
            listOf(older, recent),
            LocalInterestSignals(listOf("compose", "kotlin"), emptyMap(), emptySet())
        )
        assertEquals(listOf("new", "old"), result.map { it.key.nativeId })
    }

    @Test fun adds_watched_channel_affinity() {
        val result = RecommendationRanker.rank(
            listOf(video("a", "Other", "Frequent"), video("b", "Other", "Rare")),
            LocalInterestSignals(emptyList(), mapOf("frequent" to 3), emptySet())
        )
        assertEquals("a", result.first().key.nativeId)
    }

    @Test fun deduplicates_and_excludes_watched_when_alternatives_exist() {
        val watched = video("seen", "Compose")
        val fresh = video("fresh", "Compose")
        val result = RecommendationRanker.rank(
            listOf(watched, watched.copy(title = "Duplicate"), fresh),
            LocalInterestSignals(listOf("compose"), emptyMap(), setOf(watched.key))
        )
        assertEquals(listOf("fresh"), result.map { it.key.nativeId })
    }

    @Test fun permits_watched_candidates_when_exclusion_would_empty_feed() {
        val watched = video("seen", "Compose")
        assertEquals(
            listOf(watched),
            RecommendationRanker.rank(
                listOf(watched), LocalInterestSignals(emptyList(), emptyMap(), setOf(watched.key))
            )
        )
    }

    @Test fun stable_input_order_breaks_equal_scores_and_limit_is_applied() {
        val candidates = listOf(video("a", "A"), video("b", "B"), video("c", "C"))
        assertEquals(
            listOf("a", "b"),
            RecommendationRanker.rank(
                candidates, LocalInterestSignals(emptyList(), emptyMap(), emptySet()), limit = 2
            ).map { it.key.nativeId }
        )
    }

    @Test fun punctuation_separates_tokens_and_partial_words_do_not_match() {
        val exact = video("exact", "Kotlin, Compose tutorial")
        val partial = video("partial", "A composed tutorial")
        val result = RecommendationRanker.rank(
            listOf(partial, exact),
            LocalInterestSignals(listOf("compose"), emptyMap(), emptySet())
        )
        assertEquals(listOf("exact", "partial"), result.map { it.key.nativeId })
    }

    @Test fun normalized_channel_collisions_are_aggregated() {
        val result = RecommendationRanker.rank(
            listOf(video("other", "Other", "Other"), video("target", "Other", "Channel")),
            LocalInterestSignals(
                emptyList(),
                mapOf("Channel" to 2, " channel " to 3, "CHANNEL" to -10),
                emptySet()
            )
        )
        assertEquals("target", result.first().key.nativeId)
    }

    @Test fun contiguous_multi_token_phrase_beats_reordered_tokens() {
        val phrase = video("phrase", "Kotlin Compose tutorial")
        val reordered = video("reordered", "Compose with Kotlin tutorial")
        val result = RecommendationRanker.rank(
            listOf(reordered, phrase),
            LocalInterestSignals(listOf("kotlin compose"), emptyMap(), emptySet())
        )
        assertEquals(listOf("phrase", "reordered"), result.map { it.key.nativeId })
    }
}
