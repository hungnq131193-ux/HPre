package com.hpre.app.extractor

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Base64
import java.util.Locale

/**
 * YouTube serves VOD renditions as separate byte-addressable files instead of a DASH manifest.
 * Describing them as a local on-demand MPD (SegmentBase init + sidx ranges) lets Media3's adaptive
 * track selection switch quality with network conditions, and every request stays a small
 * byte range, which googlevideo does not throttle.
 */
internal object YouTubeDashManifestFactory {
    const val DATA_URI_PREFIX = "data:application/dash+xml;base64,"

    fun buildDataUri(
        videoOnlyStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        durationSecondsFallback: Long
    ): String? {
        val mpd = build(videoOnlyStreams, audioStreams, durationSecondsFallback) ?: return null
        return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(mpd.toByteArray(Charsets.UTF_8))
    }

    fun build(
        videoOnlyStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        durationSecondsFallback: Long
    ): String? {
        val indexedVideo = videoOnlyStreams.filter { it.isVideoOnly() && it.isIndexed() && it.height > 0 }
        // One codec family per adaptation set keeps switches seamless; AVC decodes in hardware everywhere.
        val video = indexedVideo.filter { it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc1") }
            .ifEmpty { indexedVideo.filter { it.format == MediaFormat.WEBM && it.codec.orEmpty().startsWith("vp") } }
            .distinctBy { it.itag }
        val audio = selectAudioGroups(audioStreams)
        if (video.isEmpty() || audio.isEmpty()) return null

        val durationMs = (video + audio.flatMap { it.second }).maxOf { it.itagItem?.approxDurationMs ?: 0L }
            .takeIf { it > 0 } ?: (durationSecondsFallback * 1000).takeIf { it > 0 } ?: return null

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" """)
            append("""mediaPresentationDuration="PT${seconds(durationMs)}S" minBufferTime="PT1.500S"><Period>""")

            append("""<AdaptationSet id="0" contentType="video" mimeType="${video.first().format!!.mimeType}" subsegmentAlignment="true">""")
            for (stream in video.sortedBy { it.bitrate }) {
                append("""<Representation id="${stream.itag}" bandwidth="${bandwidth(stream)}" codecs="${xml(stream.codec)}" """)
                append("""width="${stream.width}" height="${stream.height}"""")
                if (stream.fps > 0) append(""" frameRate="${stream.fps}"""")
                append(">")
                appendSegmentBase(stream)
                append("</Representation>")
            }
            append("</AdaptationSet>")

            audio.forEachIndexed { index, group ->
                val groupStreams = group.second
                val audioMime = groupStreams.first().format!!.mimeType
                append("""<AdaptationSet id="${index + 1}" contentType="audio" mimeType="$audioMime" subsegmentAlignment="true"""")
                groupStreams.first().audioLocale?.language?.let { append(""" lang="${xml(it)}"""") }
                append(">")
                if (group.first == null) {
                    append("""<Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>""")
                }
                for (stream in groupStreams.sortedBy { it.bitrate }) {
                    append("""<Representation id="${stream.itag}" bandwidth="${bandwidth(stream)}" codecs="${xml(stream.codec)}"""")
                    stream.itagItem?.sampleRate?.takeIf { it > 0 }?.let { append(""" audioSamplingRate="$it"""") }
                    append(">")
                    stream.itagItem?.audioChannels?.takeIf { it > 0 }?.let {
                        append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="$it"/>""")
                    }
                    appendSegmentBase(stream)
                    append("</Representation>")
                }
                append("</AdaptationSet>")
            }
            append("</Period></MPD>")
        }
    }

    // Groups audio tracks by audioTrackId so multi-language videos emit one AdaptationSet per
    // language; null key is the original/default track and always leads.
    private fun selectAudioGroups(streams: List<AudioStream>): List<Pair<String?, List<AudioStream>>> {
        val indexed = streams.filter { it.isIndexed() && it.itagItem?.isDrc != true }
        val groups = LinkedHashMap<String?, MutableList<AudioStream>>()
        for (stream in indexed) {
            val key = if (stream.audioTrackType == AudioTrackType.ORIGINAL) null else stream.audioTrackId
            groups.getOrPut(key) { mutableListOf() }.add(stream)
        }
        val ordered = LinkedHashMap<String?, MutableList<AudioStream>>()
        groups.remove(null)?.let { ordered[null] = it }
        ordered.putAll(groups)
        if (ordered.isEmpty()) return emptyList()
        return ordered.map { (trackId, group) ->
            trackId to group.filter { it.format == MediaFormat.M4A }
                .ifEmpty { group.filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA } }
                .distinctBy { it.itag }
        }.filter { it.second.isNotEmpty() }
    }

    private fun Stream.isIndexed(): Boolean {
        val (initStart, initEnd, indexStart, indexEnd) = ranges() ?: return false
        return deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            isUrl && content.orEmpty().startsWith("https://") &&
            format != null && itagItem != null &&
            initStart >= 0 && initEnd > initStart && indexEnd > indexStart && indexStart > initEnd
    }

    private fun Stream.ranges(): List<Int>? = when (this) {
        is VideoStream -> listOf(initStart, initEnd, indexStart, indexEnd)
        is AudioStream -> listOf(initStart, initEnd, indexStart, indexEnd)
        else -> null
    }

    private fun StringBuilder.appendSegmentBase(stream: Stream) {
        val (initStart, initEnd, indexStart, indexEnd) = stream.ranges()!!
        append("<BaseURL>").append(xml(stream.content)).append("</BaseURL>")
        append("""<SegmentBase indexRange="$indexStart-$indexEnd"><Initialization range="$initStart-$initEnd"/></SegmentBase>""")
    }

    private fun bandwidth(stream: Stream): Int {
        val (bitrate, average) = when (stream) {
            is VideoStream -> stream.bitrate to (stream.itagItem?.averageBitrate ?: 0)
            is AudioStream -> stream.bitrate to stream.averageBitrate
            else -> 0 to 0
        }
        return bitrate.takeIf { it > 0 } ?: average.takeIf { it > 0 } ?: 1
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)

    private fun xml(value: String?) = value.orEmpty()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
