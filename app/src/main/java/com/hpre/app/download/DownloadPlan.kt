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
        if (muxed != null) {
            return listOf(Request(primaryId(info.key), muxed.url, isVideo = true))
        }

        val video = info.videoStreams
            .filter { it.isVideoOnly }
            .maxByOrNull { it.height ?: 0 }
            ?: return null
        val audio = info.audioStreams.maxByOrNull { it.averageBitrate ?: it.bitrate ?: 0L }
            ?: return null
        return listOf(
            Request(primaryId(info.key), video.url, isVideo = true),
            Request(audioId(info.key), audio.url, isVideo = false)
        )
    }
}
