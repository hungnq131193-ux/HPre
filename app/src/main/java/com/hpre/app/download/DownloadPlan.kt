package com.hpre.app.download

import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo

/**
 * Pure stream-selection logic for offline downloads. Split from the tracker so it can run in plain
 * JVM unit tests without Android or Media3 types.
 */
object DownloadPlan {

    /** One physical file fetch for the Media3 DownloadManager. */
    data class Request(
        val id: String,
        val url: String,
        val isVideo: Boolean
    )

    /** Primary request id used to look a download up later. */
    fun primaryId(key: ContentKey): String = "hpre:${key.serviceId}:${key.nativeId}"

    fun audioId(key: ContentKey): String = "${primaryId(key)}|a"

    /**
     * Pick the stream set to persist.
     *
     * Audio-only downloads take the highest-bitrate audio stream. Video downloads prefer a muxed
     * (progressive) stream containing audio; when none exists the best video-only stream is paired
     * with the best audio stream as a second request played back through MergingMediaSource.
     */
    fun select(info: StreamInfo, audioOnly: Boolean): List<Request>? {
        if (audioOnly) {
            val audio = info.audioStreams.maxByOrNull { it.averageBitrate ?: it.bitrate ?: 0L }
                ?: return null
            return listOf(Request(primaryId(info.key), audio.url, isVideo = false))
        }

        val muxed = info.videoStreams
            .filter { !it.isVideoOnly }
            .maxByOrNull { it.height ?: 0 }
        if (muxed != null && (muxed.height ?: 0) >= 720) {
            return listOf(Request(primaryId(info.key), muxed.url, isVideo = true))
        }

        val videoCandidates = info.videoStreams.filter { it.isVideoOnly }
        val cappedCandidates = videoCandidates.filter {
            val shortSide = minOf(it.width ?: Int.MAX_VALUE, it.height ?: Int.MAX_VALUE)
            val nominal = if (shortSide in 1..4320) shortSide else (it.height ?: 0)
            nominal <= 1080
        }.ifEmpty { videoCandidates }

        val video = cappedCandidates.maxWithOrNull(
            compareBy<com.hpre.app.model.VideoStream> {
                val codec = it.codec?.lowercase().orEmpty()
                when {
                    codec.startsWith("avc1") || codec.startsWith("h264") -> 2
                    codec.startsWith("av01") -> 0
                    else -> 1
                }
            }.thenBy { it.height ?: 0 }.thenBy { it.bitrate ?: 0L }
        )
        val audio = info.audioStreams.maxByOrNull { it.averageBitrate ?: it.bitrate ?: 0L }
        if (video != null && audio != null) {
            return listOf(
                Request(primaryId(info.key), video.url, isVideo = true),
                Request(audioId(info.key), audio.url, isVideo = false)
            )
        }

        if (muxed != null) {
            return listOf(Request(primaryId(info.key), muxed.url, isVideo = true))
        }
        return null
    }
}
