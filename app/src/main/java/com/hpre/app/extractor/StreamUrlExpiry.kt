package com.hpre.app.extractor

import com.hpre.app.model.AudioStream
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.SubtitleStream
import com.hpre.app.model.VideoStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun StreamInfo.earliestUrlExpiryMs(): Long? = buildList {
    addAll(videoStreams.map(VideoStream::url))
    addAll(audioStreams.map(AudioStream::url))
    addAll(subtitles.map(SubtitleStream::url))
    hlsManifestUrl?.let(::add)
    dashManifestUrl?.let(::add)
}.asSequence()
    .flatMap(::expiryValues)
    .mapNotNull(::parseExpiryMs)
    .minOrNull()

private fun expiryValues(url: String): Sequence<String> = sequence {
    val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return@sequence
    for (pair in query.split('&')) {
        val parts = pair.split('=', limit = 2)
        if (parts.size != 2) continue
        val name = decode(parts[0]) ?: continue
        if (name.equals("expire", true) || name.equals("expires", true) || name.equals("expiry", true)) {
            decode(parts[1])?.let { yield(it) }
        }
    }
}

private fun decode(value: String): String? = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrNull()

private fun parseExpiryMs(value: String): Long? {
    val parsed = value.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return if (parsed < EPOCH_MILLISECONDS_THRESHOLD) {
        runCatching { Math.multiplyExact(parsed, 1_000L) }.getOrNull()
    } else {
        parsed
    }
}

private const val EPOCH_MILLISECONDS_THRESHOLD = 10_000_000_000L
