package com.hpre.app.repository

import java.util.Locale

/** A weighted interest topic used to fetch recommendation candidates. */
data class InterestTopic(
    val query: String,
    val weight: Int
)

/**
 * Turns watch history into short, genre-shaped search queries.
 *
 * Searching a raw video title rarely works: titles carry episode numbers, upload years, tags like
 * "vietsub" or "official mv", and filler words, so the provider matches almost nothing back. What
 * finds more of the same genre is a handful of salient words, which is what this extractor keeps.
 */
object TopicExtractor {
    /** Salient words kept per title. Enough to pin a genre, short enough to stay broad. */
    const val MAX_TOKENS_PER_TITLE = 3

    /** Single letters carry no genre signal and blow up the candidate set. */
    const val MIN_TOKEN_LENGTH = 2

    /**
     * Words that say nothing about genre: Vietnamese and English function words, plus packaging
     * noise common to video titles.
     */
    private val STOPWORDS: Set<String> = setOf(
        // Vietnamese function words
        "và", "của", "là", "có", "không", "được", "cho", "với", "các", "những", "này", "đó",
        "khi", "đã", "sẽ", "tại", "về", "từ", "một", "hai", "ba", "người", "như", "để", "thì",
        "mà", "nhưng", "cũng", "rất", "quá", "lắm", "nhất", "hơn", "bị", "ra", "vào", "lên",
        "xuống", "đi", "đến", "trong", "ngoài", "trên", "dưới", "sau", "trước", "nếu", "vì",
        "do", "bởi", "tôi", "bạn", "anh", "chị", "em", "họ", "nó", "ai", "gì", "sao", "thế",
        "nào", "bao", "nhiêu", "mình", "chúng", "ta", "còn", "nữa", "hết", "cả", "vẫn",
        "đang", "hay", "hoặc", "nên", "phải", "cần", "muốn", "biết", "làm", "thấy", "nói",
        // Video packaging noise
        "full", "hd", "fhd", "4k", "official", "mv", "video", "clip", "tập", "phần", "vietsub",
        "sub", "thuyết", "minh", "lồng", "tiếng", "trailer", "teaser", "part", "ep", "season",
        "new", "hot", "top", "best", "review", "reaction", "shorts", "short", "live", "remix",
        "audio", "lyrics", "lyric", "cover", "playlist", "album", "mix",
        // English function words
        "the", "and", "for", "with", "that", "this", "you", "your", "are", "was", "were", "from",
        "his", "her", "its", "our", "their", "have", "has", "had", "will", "can", "how", "what",
        "why", "who", "when", "where", "all", "not", "but", "out", "about", "into", "than",
        "then", "them", "she", "him"
    )

    /** Splits on anything that is not a letter or digit, so punctuation and emoji fall away. */
    fun tokenize(value: String): List<String> = value
        .lowercase(Locale.ROOT)
        .split("[^\\p{L}\\p{N}]+".toRegex())
        .filter(String::isNotBlank)

    /**
     * Salient words of a single title, in their original order so the query still reads naturally.
     */
    fun keywords(title: String, limit: Int = MAX_TOKENS_PER_TITLE): List<String> {
        if (limit <= 0) return emptyList()
        return tokenize(title)
            .filter { token ->
                token.length >= MIN_TOKEN_LENGTH &&
                    token !in STOPWORDS &&
                    // Episode numbers and years describe an instance, not a genre.
                    !token.all(Char::isDigit)
            }
            .distinct()
            .take(limit)
    }

    /**
     * Builds search topics from watch history.
     *
     * Recent titles are weighted above older ones, and frequently watched channels are added as
     * their own topics because a channel name is often the tightest genre signal available.
     *
     * @param recentTitles watched video titles, most recent first
     * @param channelFrequency how often each channel was watched
     */
    fun topicsFromHistory(
        recentTitles: List<String>,
        channelFrequency: Map<String, Int> = emptyMap(),
        maxTitleTopics: Int = 4,
        maxChannelTopics: Int = 2
    ): List<InterestTopic> {
        val titleTopics = recentTitles
            .asSequence()
            .mapNotNull { title ->
                keywords(title).takeIf(List<String>::isNotEmpty)?.joinToString(" ")
            }
            .distinct()
            .take(maxTitleTopics.coerceAtLeast(0))
            .toList()
            .mapIndexed { index, query ->
                // Earlier entries are more recent, so they outrank later ones.
                InterestTopic(query, weight = titleTopicWeight(index))
            }

        val channelTopics = channelFrequency
            .asSequence()
            .filter { (channel, count) -> count > 0 && channel.isNotBlank() }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .mapNotNull { (channel, count) ->
                keywords(channel, limit = MAX_TOKENS_PER_TITLE)
                    .takeIf(List<String>::isNotEmpty)
                    ?.let { InterestTopic(it.joinToString(" "), weight = count) }
            }
            .take(maxChannelTopics.coerceAtLeast(0))
            .toList()

        return (titleTopics + channelTopics)
            .distinctBy(InterestTopic::query)
            .sortedByDescending(InterestTopic::weight)
    }

    private fun titleTopicWeight(index: Int): Int = (MAX_TITLE_WEIGHT - index).coerceAtLeast(1)

    private const val MAX_TITLE_WEIGHT = 8
}
