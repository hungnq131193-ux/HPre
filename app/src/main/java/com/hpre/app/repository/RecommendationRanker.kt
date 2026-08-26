package com.hpre.app.repository

import com.hpre.app.model.VideoSummary
import java.util.Locale

object RecommendationRanker {
    fun rank(
        candidates: List<VideoSummary>,
        signals: LocalInterestSignals,
        limit: Int = 30
    ): List<VideoSummary> {
        if (limit <= 0) return emptyList()

        val deduplicated = candidates.distinctBy(VideoSummary::key)
        val fresh = deduplicated.filterNot { it.key in signals.recentlyWatched }
        val eligible = fresh.ifEmpty { deduplicated }
        val normalizedQueries = signals.recentQueries.map(::tokens).filter(List<String>::isNotEmpty)
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

        return eligible.withIndex()
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
                val affinity = channelFrequency[normalize(video.channelName.orEmpty())] ?: 0
                RankedVideo(video, queryScore + affinity, indexed.index)
            }
            .sortedWith(compareByDescending<RankedVideo> { it.score }.thenBy { it.inputIndex })
            .take(limit)
            .map(RankedVideo::video)
    }

    private fun tokens(value: String): List<String> = value
        .lowercase(Locale.ROOT)
        .split("[^\\p{L}\\p{N}]+".toRegex())
        .filter(String::isNotBlank)

    private fun normalize(value: String): String = tokens(value).joinToString(" ")

    private fun List<String>.containsSequence(sequence: List<String>): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        return windowed(sequence.size).any { it == sequence }
    }

    private data class RankedVideo(
        val video: VideoSummary,
        val score: Int,
        val inputIndex: Int
    )
}
