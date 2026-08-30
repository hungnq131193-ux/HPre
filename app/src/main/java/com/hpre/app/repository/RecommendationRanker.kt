package com.hpre.app.repository

import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import java.util.Locale

data class RecommendationContext(
    val currentKey: ContentKey? = null,
    val currentChannelName: String? = null,
    val providerRelatedKeys: Set<ContentKey> = emptySet(),
    val nowEpochSeconds: Long? = null
)

object RecommendationRanker {
    private val tokenSeparator = Regex("[^\\p{L}\\p{N}]+")
    fun rank(
        candidates: List<VideoSummary>,
        signals: LocalInterestSignals,
        context: RecommendationContext = RecommendationContext(),
        limit: Int = 30
    ): List<VideoSummary> {
        if (limit <= 0) return emptyList()

        val deduplicated = candidates
            .filterNot { it.key == context.currentKey }
            .distinctBy(VideoSummary::key)
        val hasFreshCandidates = deduplicated.any { it.key !in signals.recentlyWatched }
        val normalizedQueries = signals.recentQueries.map(::tokens).filter(List<String>::isNotEmpty)
        val currentChannel = context.currentChannelName?.let(::normalize)
        val channelFrequency = buildMap {
            signals.watchedChannelFrequency.forEach { (channel, count) ->
                if (count > 0) {
                    val normalized = normalize(channel)
                    if (normalized.isNotBlank()) {
                        put(normalized, (get(normalized) ?: 0) + count)
                    }
                }
            }
        }

        val ranked = deduplicated.withIndex()
            .map { indexed ->
                val video = indexed.value
                val titleTokens = tokens(video.title)
                val queryScore = normalizedQueries.mapIndexed { index, queryTokens ->
                    val recencyWeight = normalizedQueries.size - index
                    val matchingTokens = queryTokens.count { token ->
                        token in titleTokens
                    }
                    (if (titleTokens.containsSequence(queryTokens)) recencyWeight * 10 else 0) +
                        matchingTokens * recencyWeight * 3
                }.sum()
                val normalizedChannel = normalize(video.channelName.orEmpty())
                val affinity = channelFrequency[normalizedChannel] ?: 0
                val relatedScore = if (video.key in context.providerRelatedKeys) RELATED_SCORE else 0
                val currentChannelScore = if (
                    currentChannel != null && normalizedChannel == currentChannel
                ) CURRENT_CHANNEL_SCORE else 0
                val freshnessScore = freshnessScore(video.publishedTimestamp, context.nowEpochSeconds)
                RankedVideo(
                    video,
                    queryScore + affinity + relatedScore + currentChannelScore + freshnessScore,
                    indexed.index,
                    normalizedChannel,
                    isFallbackWatched = hasFreshCandidates && video.key in signals.recentlyWatched
                )
            }
            .sortedWith(
                compareBy<RankedVideo> { it.isFallbackWatched }
                    .thenByDescending { it.score }
                    .thenBy { it.inputIndex }
            )
        return diversify(ranked, limit)
    }

    private fun tokens(value: String): List<String> = value
        .lowercase(Locale.ROOT)
        .split(tokenSeparator)
        .filter(String::isNotBlank)

    private fun normalize(value: String): String = tokens(value).joinToString(" ")

    private fun List<String>.containsSequence(sequence: List<String>): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        return (0..size - sequence.size).any { start ->
            sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
        }
    }

    private fun diversify(ranked: List<RankedVideo>, limit: Int): List<VideoSummary> {
        val remaining = ranked.toMutableList()
        val result = mutableListOf<VideoSummary>()
        val recentChannels = ArrayDeque<String>(2)
        while (remaining.isNotEmpty() && result.size < limit) {
            val currentFallbackGroup = remaining.first().isFallbackWatched
            val eligibleRange = remaining.takeWhile {
                it.isFallbackWatched == currentFallbackGroup
            }
            val previousChannels = recentChannels.toList()
            val nextIndex = if (
                previousChannels.size == 2 &&
                previousChannels[0].isNotBlank() &&
                previousChannels[0] == previousChannels[1]
            ) {
                eligibleRange.indexOfFirst {
                    it.normalizedChannel != previousChannels[0]
                }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            val next = remaining.removeAt(nextIndex)
            result += next.video
            if (recentChannels.size == 2) recentChannels.removeFirst()
            recentChannels.addLast(next.normalizedChannel)
        }
        return result
    }

    private fun freshnessScore(publishedTimestamp: Long?, nowEpochSeconds: Long?): Int {
        if (publishedTimestamp == null || nowEpochSeconds == null) return 0
        val publishedEpochSeconds = if (publishedTimestamp > 10_000_000_000L) {
            publishedTimestamp / 1000L
        } else {
            publishedTimestamp
        }
        val ageDays = ((nowEpochSeconds - publishedEpochSeconds).coerceAtLeast(0L) / 86_400L)
        return when {
            ageDays <= 7 -> 8
            ageDays <= 30 -> 5
            ageDays <= 180 -> 3
            ageDays <= 365 -> 1
            else -> 0
        }
    }

    private data class RankedVideo(
        val video: VideoSummary,
        val score: Int,
        val inputIndex: Int,
        val normalizedChannel: String,
        val isFallbackWatched: Boolean
    )

    private const val RELATED_SCORE = 150
    private const val CURRENT_CHANNEL_SCORE = 40
}
