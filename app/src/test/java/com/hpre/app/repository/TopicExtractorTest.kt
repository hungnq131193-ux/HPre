package com.hpre.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicExtractorTest {

    @Test
    fun keywords_drop_packaging_noise_that_makes_titles_unsearchable() {
        // A real-world shaped title: the genre words are buried in packaging noise.
        val keywords = TopicExtractor.keywords("[Vietsub] Official MV - Nhạc Trẻ Remix 2024 Full HD")
        assertFalse("vietsub is packaging, not genre", keywords.contains("vietsub"))
        assertFalse("official is packaging", keywords.contains("official"))
        assertFalse("mv is packaging", keywords.contains("mv"))
        assertFalse("full is packaging", keywords.contains("full"))
        assertFalse("hd is packaging", keywords.contains("hd"))
        assertFalse("a bare year describes an instance, not a genre", keywords.contains("2024"))
        assertTrue("genre words must survive: $keywords", keywords.contains("nhạc"))
    }

    @Test
    fun keywords_drop_vietnamese_function_words() {
        val keywords = TopicExtractor.keywords("Hướng dẫn nấu ăn của người Việt")
        assertFalse(keywords.contains("của"))
        assertFalse(keywords.contains("người"))
        assertTrue(keywords.contains("hướng"))
    }

    @Test
    fun keywords_are_capped_so_the_query_stays_broad_enough_to_match() {
        val keywords = TopicExtractor.keywords(
            "Hướng dẫn lập trình Kotlin Android Compose nâng cao chuyên sâu"
        )
        assertEquals(TopicExtractor.MAX_TOKENS_PER_TITLE, keywords.size)
    }

    @Test
    fun keywords_preserve_title_order_so_the_query_reads_naturally() {
        assertEquals(listOf("kotlin", "compose"), TopicExtractor.keywords("Kotlin Compose", limit = 2))
        assertEquals(listOf("compose", "kotlin"), TopicExtractor.keywords("Compose Kotlin", limit = 2))
    }

    @Test
    fun single_letters_and_duplicates_are_discarded() {
        val keywords = TopicExtractor.keywords("A a Kotlin kotlin KOTLIN Compose")
        assertEquals(listOf("kotlin", "compose"), keywords)
    }

    @Test
    fun a_title_of_pure_noise_yields_no_keywords_instead_of_a_junk_query() {
        assertTrue(TopicExtractor.keywords("Full HD 4K Official 2024").isEmpty())
        assertTrue(TopicExtractor.keywords("!!! ... ???").isEmpty())
        assertTrue(TopicExtractor.keywords("").isEmpty())
    }

    @Test
    fun history_topics_use_the_whole_history_not_only_the_newest_entry() {
        // This is the regression that made recommendations feel narrow: only the most recent
        // video contributed a topic.
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = listOf(
                "Hướng dẫn Kotlin cơ bản",
                "Nấu ăn món Việt ngon",
                "Bóng đá Ngoại hạng Anh"
            )
        )
        assertEquals(3, topics.size)
        val joined = topics.joinToString(" ") { it.query }
        assertTrue("Kotlin topic missing: $joined", joined.contains("kotlin"))
        assertTrue("Cooking topic missing: $joined", joined.contains("nấu"))
        assertTrue("Football topic missing: $joined", joined.contains("bóng"))
    }

    @Test
    fun recent_titles_outweigh_older_ones() {
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = listOf("Kotlin tutorial", "Nấu ăn ngon")
        )
        val kotlin = topics.first { it.query.contains("kotlin") }
        val cooking = topics.first { it.query.contains("nấu") }
        assertTrue(
            "The newest title must rank above older ones (${kotlin.weight} vs ${cooking.weight})",
            kotlin.weight > cooking.weight
        )
    }

    @Test
    fun frequently_watched_channels_become_their_own_topics_ordered_by_count() {
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = emptyList(),
            channelFrequency = mapOf("Rare Channel" to 1, "Favourite Studio" to 9)
        )
        assertEquals(2, topics.size)
        assertTrue(
            "The most watched channel must lead: ${topics.map { it.query }}",
            topics.first().query.contains("favourite")
        )
    }

    @Test
    fun channels_with_no_watches_are_ignored() {
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = emptyList(),
            channelFrequency = mapOf("Zero" to 0, "Negative" to -3, "Blank" to 5, "" to 100)
        )
        assertEquals(listOf("blank"), topics.map { it.query })
    }

    @Test
    fun duplicate_topics_are_collapsed_so_the_same_query_is_not_fetched_twice() {
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = listOf("Kotlin tutorial", "Kotlin tutorial", "Kotlin tutorial")
        )
        assertEquals(1, topics.size)
    }

    @Test
    fun topic_counts_are_bounded_to_limit_network_fan_out() {
        // Distinct genres, as a real history would be.
        val manyTitles = listOf(
            "Hướng dẫn Kotlin cơ bản",
            "Nấu ăn món Việt",
            "Bóng đá Ngoại hạng",
            "Du lịch Đà Nẵng",
            "Phim hành động Mỹ",
            "Học tiếng Nhật"
        )
        val manyChannels = (1..50).associate { "Kênh$it" to it }
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = manyTitles,
            channelFrequency = manyChannels,
            maxTitleTopics = 4,
            maxChannelTopics = 2
        )
        assertEquals(6, topics.size)
    }

    @Test
    fun titles_reducing_to_the_same_keywords_collapse_into_one_query() {
        // Near-duplicates, not exact ones: the episode number is what differs, and it is dropped
        // as an instance marker. Fetching the same query once per episode would waste requests.
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = listOf(
                "Hướng dẫn Kotlin tập 1",
                "Hướng dẫn Kotlin tập 2",
                "Hướng dẫn Kotlin tập 3"
            )
        )
        assertEquals(1, topics.size)
        assertEquals("hướng dẫn kotlin", topics.first().query)
    }

    @Test
    fun zero_limits_yield_no_topics() {
        val topics = TopicExtractor.topicsFromHistory(
            recentTitles = listOf("Kotlin tutorial"),
            channelFrequency = mapOf("Studio" to 5),
            maxTitleTopics = 0,
            maxChannelTopics = 0
        )
        assertTrue(topics.isEmpty())
    }

    @Test
    fun empty_history_yields_no_topics_so_the_caller_can_fall_back_to_trending() {
        assertTrue(TopicExtractor.topicsFromHistory(emptyList()).isEmpty())
    }

    @Test
    fun tokenize_splits_on_punctuation_and_emoji() {
        assertEquals(
            listOf("kotlin", "compose", "android"),
            TopicExtractor.tokenize("Kotlin, Compose 🎉 Android!")
        )
    }
}
