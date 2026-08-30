package com.hpre.app.player.cache

import com.hpre.app.model.AudioStream
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoStream
import java.security.MessageDigest

object YouTubeCacheKeyFactory {

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun buildFallbackUrlCacheKey(url: String): String {
        return "hpre:v1:url:${sha256Hex(url.trim())}"
    }

    fun buildVideoCacheKey(key: ContentKey, stream: VideoStream): String {
        val streamId = stream.streamId?.trim()
        if (streamId.isNullOrBlank()) {
            return buildFallbackUrlCacheKey(stream.url)
        }
        val raw = "video|${key.serviceId}|${key.nativeId}|$streamId|${stream.format}|${stream.resolution}|${stream.isVideoOnly}"
        return "hpre:v1:v:${sha256Hex(raw)}"
    }

    fun buildAudioCacheKey(key: ContentKey, stream: AudioStream): String {
        val streamId = stream.streamId?.trim()
        if (streamId.isNullOrBlank()) {
            return buildFallbackUrlCacheKey(stream.url)
        }
        val trackId = stream.audioTrackId?.trim() ?: "none"
        val lang = stream.language?.trim() ?: "und"
        val avgBitrate = stream.averageBitrate ?: stream.bitrate ?: -1L
        val raw = "audio|${key.serviceId}|${key.nativeId}|$streamId|$avgBitrate|$trackId|$lang"
        return "hpre:v1:a:${sha256Hex(raw)}"
    }
}
