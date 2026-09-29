package com.hpre.app.sponsorblock

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonReader.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/** A sponsor segment in milliseconds of media time. */
data class SponsorSegment(val startMs: Long, val endMs: Long)

/**
 * Pure skip state machine for one media item. Each segment is skipped at most once; seeking back
 * before a segment's end re-arms it so a replay skips it again.
 */
class SponsorSkipController {
    private var segments: List<SponsorSegment> = emptyList()
    private val skipped = mutableSetOf<Int>()

    fun setSegments(newSegments: List<SponsorSegment>) {
        segments = newSegments.sortedBy { it.startMs }
        skipped.clear()
    }

    /** Returns the seek target (segment end) when [positionMs] sits inside an unskipped segment. */
    fun skipTargetFor(positionMs: Long): Long? {
        for ((index, segment) in segments.withIndex()) {
            if (index in skipped) continue
            if (positionMs >= segment.startMs && positionMs < segment.endMs) {
                skipped += index
                return segment.endMs
            }
        }
        return null
    }

    /** Backward seeks before a segment's end re-arm it. */
    fun onSeekTo(positionMs: Long) {
        skipped.removeAll { (segments.getOrNull(it)?.endMs ?: Long.MIN_VALUE) > positionMs }
    }

    fun reset() {
        segments = emptyList()
        skipped.clear()
    }
}

/**
 * Minimal SponsorBlock API client (sponsor.ajay.app). Fetches "sponsor" category segments for a
 * YouTube video id; any network or API failure yields an empty list so playback just proceeds.
 */
class SponsorBlockClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://sponsor.ajay.app"
) {
    suspend fun fetchSegments(videoId: String): List<SponsorSegment> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/skipSegments?videoID=$videoId&categories=%5B%22sponsor%22%5D")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                parseSegments(response.body?.string().orEmpty())
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            emptyList()
        }
    }

    internal fun parseSegments(json: String): List<SponsorSegment> {
        if (json.isBlank()) return emptyList()
        val out = mutableListOf<SponsorSegment>()
        try {
            val reader = JsonReader.of(Buffer().writeUtf8(json))
            reader.beginArray()
            while (reader.hasNext()) {
                var category: String? = null
                var startSec: Double? = null
                var endSec: Double? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "category" -> category = reader.nextString()
                        "segment" -> {
                            reader.beginArray()
                            if (reader.hasNext()) startSec = reader.nextDouble()
                            if (reader.hasNext()) endSec = reader.nextDouble()
                            while (reader.hasNext()) reader.skipValue()
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                val s = startSec
                val e = endSec
                if (category == "sponsor" && s != null && e != null && e > s) {
                    out += SponsorSegment((s * 1000).toLong(), (e * 1000).toLong())
                }
            }
            reader.endArray()
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }
}
