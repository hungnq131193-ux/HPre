package com.hpre.app.player

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.AudioStream
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoStream

object StreamSelector {

    private enum class ContainerFamily {
        MP4,
        WEBM
    }

    private enum class VideoCodecFamily {
        H264_AVC,
        VP8,
        VP9,
        AV1
    }

    private enum class AudioCodecFamily {
        AAC,
        OPUS,
        VORBIS
    }

    // Exact Regex Patterns for recognized codec syntax / profiles
    private val AVC_REGEX = Regex("""^(?:avc1\.[0-9a-fA-F]{6}|avc3\.[0-9a-fA-F]{6}|avc1|avc3|h264|h\.264)$""", RegexOption.IGNORE_CASE)
    private val VP8_REGEX = Regex("""^(?:vp8|vp08)$""", RegexOption.IGNORE_CASE)
    private val VP9_REGEX = Regex("""^(?:vp9|vp09|vp09\.0[0-3]\.(?:[1-5]\d|6[0-2])\.(?:08|10|12))$""", RegexOption.IGNORE_CASE)
    private val AV1_REGEX = Regex("""^(?:av1|av01|av01\.[0-2]\.(?:0\d|[12]\d|3[01])[MH]\.(?:08|10|12))$""", RegexOption.IGNORE_CASE)

    private val AAC_REGEX = Regex("""^(?:mp4a\.40\.\d+|mp4a\.67|mp4a|aac)$""", RegexOption.IGNORE_CASE)
    private val OPUS_REGEX = Regex("""^opus$""", RegexOption.IGNORE_CASE)
    private val VORBIS_REGEX = Regex("""^vorbis$""", RegexOption.IGNORE_CASE)

    private fun normalizeMime(mimeType: String): Pair<String, String>? {
        val trimmed = mimeType.trim()
        if (trimmed.isEmpty()) return null
        val cleanMime = trimmed.substringBefore(";").trim().lowercase()
        val parts = cleanMime.split("/")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null
        }
        return Pair(parts[0], parts[1])
    }

    private fun parseCodecTokens(rawCodec: String?): List<String> {
        if (rawCodec.isNullOrBlank()) return emptyList()
        return rawCodec.split(",")
    }

    private fun detectVideoCodecToken(token: String): VideoCodecFamily? {
        val trimmed = token.trim()
        return when {
            AVC_REGEX.matches(trimmed) -> VideoCodecFamily.H264_AVC
            VP8_REGEX.matches(trimmed) -> VideoCodecFamily.VP8
            VP9_REGEX.matches(trimmed) -> VideoCodecFamily.VP9
            AV1_REGEX.matches(trimmed) -> VideoCodecFamily.AV1
            else -> null
        }
    }

    private fun detectAudioCodecToken(token: String): AudioCodecFamily? {
        val trimmed = token.trim()
        return when {
            AAC_REGEX.matches(trimmed) -> AudioCodecFamily.AAC
            OPUS_REGEX.matches(trimmed) -> AudioCodecFamily.OPUS
            VORBIS_REGEX.matches(trimmed) -> AudioCodecFamily.VORBIS
            else -> null
        }
    }

    private fun parseVideoCodec(codec: String?): VideoCodecFamily? {
        if (codec.isNullOrBlank()) return null
        val tokens = parseCodecTokens(codec)
        if (tokens.size != 1 || tokens[0].trim().isBlank()) return null
        return detectVideoCodecToken(tokens[0])
    }

    private fun parseAudioCodec(codec: String?): AudioCodecFamily? {
        if (codec.isNullOrBlank()) return null
        val tokens = parseCodecTokens(codec)
        if (tokens.size != 1 || tokens[0].trim().isBlank()) return null
        return detectAudioCodecToken(tokens[0])
    }

    private fun detectContainerFamily(format: String, mimeType: String?, isVideo: Boolean): ContainerFamily? {
        if (!mimeType.isNullOrBlank()) {
            val parsed = normalizeMime(mimeType) ?: return null
            val expectedType = if (isVideo) "video" else "audio"
            if (parsed.first != expectedType) {
                return null
            }
            return if (isVideo) {
                when (parsed.second) {
                    "mp4" -> ContainerFamily.MP4
                    "webm" -> ContainerFamily.WEBM
                    else -> null
                }
            } else {
                when (parsed.second) {
                    "mp4", "aac" -> ContainerFamily.MP4
                    "webm", "ogg", "opus" -> ContainerFamily.WEBM
                    else -> null
                }
            }
        }

        val normFormat = format.trim().lowercase()
        return if (isVideo) {
            when (normFormat) {
                "mp4", "m4v" -> ContainerFamily.MP4
                "webm" -> ContainerFamily.WEBM
                else -> null
            }
        } else {
            when (normFormat) {
                "mp4", "m4a", "aac" -> ContainerFamily.MP4
                "webm", "ogg", "opus" -> ContainerFamily.WEBM
                else -> null
            }
        }
    }

    private fun isProgressiveValid(video: VideoStream): Boolean {
        if (video.isVideoOnly || !isValidUrl(video.url)) return false
        val container = detectContainerFamily(video.format, video.mimeType, isVideo = true) ?: return false
        val codecTokens = parseCodecTokens(video.codec)
        if (codecTokens.isEmpty() || codecTokens.any { it.trim().isEmpty() }) return false
        val videoCodecs = codecTokens.mapNotNull(::detectVideoCodecToken)
        val audioCodecs = codecTokens.mapNotNull(::detectAudioCodecToken)
        if (videoCodecs.size != 1 || audioCodecs.size > 1 || videoCodecs.size + audioCodecs.size != codecTokens.size) return false
        val vCodec = videoCodecs.single()
        val aCodec = audioCodecs.singleOrNull()
        when (container) {
            ContainerFamily.MP4 -> if (vCodec != VideoCodecFamily.H264_AVC || aCodec != null && aCodec != AudioCodecFamily.AAC) return false
            ContainerFamily.WEBM -> if (
                vCodec != VideoCodecFamily.VP8 && vCodec != VideoCodecFamily.VP9 && vCodec != VideoCodecFamily.AV1 ||
                aCodec != null && aCodec != AudioCodecFamily.OPUS && aCodec != AudioCodecFamily.VORBIS
            ) return false
        }
        return true
    }

    private fun isAudioValid(audio: AudioStream): Boolean {
        if (!isValidUrl(audio.url)) return false
        val container = detectContainerFamily(audio.format, audio.mimeType, isVideo = false) ?: return false
        if (!audio.codec.isNullOrBlank()) {
            val aCodec = parseAudioCodec(audio.codec) ?: return false
            when (container) {
                ContainerFamily.MP4 -> if (aCodec != AudioCodecFamily.AAC) return false
                ContainerFamily.WEBM -> if (aCodec != AudioCodecFamily.OPUS && aCodec != AudioCodecFamily.VORBIS) return false
            }
        }
        return true
    }

    private fun areStreamsCompatible(video: VideoStream, audio: AudioStream): Boolean {
        val vContainer = detectContainerFamily(video.format, video.mimeType, isVideo = true) ?: return false
        val aContainer = detectContainerFamily(audio.format, audio.mimeType, isVideo = false) ?: return false
        if (vContainer != aContainer) return false

        val vCodec = parseVideoCodec(video.codec) ?: return false
        val aCodec = parseAudioCodec(audio.codec) ?: return false

        return when (vContainer) {
            ContainerFamily.MP4 -> {
                vCodec == VideoCodecFamily.H264_AVC && aCodec == AudioCodecFamily.AAC
            }
            ContainerFamily.WEBM -> {
                (vCodec == VideoCodecFamily.VP8 || vCodec == VideoCodecFamily.VP9 || vCodec == VideoCodecFamily.AV1) &&
                        (aCodec == AudioCodecFamily.OPUS || aCodec == AudioCodecFamily.VORBIS)
            }
        }
    }

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun matchesOption(video: VideoStream, opt: QualityOption): Boolean {
        if ((video.height ?: 0) != opt.height) return false
        if (!video.format.equals(opt.format, ignoreCase = true)) return false
        
        // Exact canonical equality for mimeType and codec (no null bypass)
        val videoMimeNorm = video.mimeType?.trim()?.lowercase()
        val optMimeNorm = opt.mimeType?.trim()?.lowercase()
        if (optMimeNorm != videoMimeNorm) return false

        val videoCodecNorm = video.codec?.trim()?.lowercase()
        val optCodecNorm = opt.codec?.trim()?.lowercase()
        if (optCodecNorm != videoCodecNorm) return false

        return true
    }

    fun selectStream(
        info: StreamInfo,
        preference: QualityPreference = QualityPreference.Auto
    ): AppResult<SelectedStreams> {
        when (preference) {
            is QualityPreference.SpecificOption -> {
                val opt = preference.option
                if (opt.streamType == PlaybackStreamType.HLS) {
                    val canonicalHls = getAvailableQualities(info).firstOrNull { it.streamType == PlaybackStreamType.HLS }
                    if (canonicalHls != null && canonicalHls == opt && isValidUrl(info.hlsManifestUrl)) {
                        return AppResult.Success(
                            SelectedStreams(
                                key = info.key,
                                streamType = PlaybackStreamType.HLS,
                                manifestUrl = info.hlsManifestUrl,
                                subtitles = info.subtitles,
                                isLive = info.isLive
                            )
                        )
                    } else {
                        return AppResult.Failure(AppError.UnsupportedFormat)
                    }
                } else if (opt.streamType == PlaybackStreamType.DASH) {
                    val canonicalDash = getAvailableQualities(info).firstOrNull { it.streamType == PlaybackStreamType.DASH }
                    if (canonicalDash != null && canonicalDash == opt && isValidUrl(info.dashManifestUrl)) {
                        return AppResult.Success(
                            SelectedStreams(
                                key = info.key,
                                streamType = PlaybackStreamType.DASH,
                                manifestUrl = info.dashManifestUrl,
                                subtitles = info.subtitles,
                                isLive = info.isLive
                            )
                        )
                    } else {
                        return AppResult.Failure(AppError.UnsupportedFormat)
                    }
                } else if (opt.isProgressive) {
                    val matching = info.videoStreams
                        .filter { isProgressiveValid(it) }
                        .filter { matchesOption(it, opt) }
                        .maxByOrNull { it.bitrate ?: 0L }

                    if (matching != null) {
                        return AppResult.Success(
                            SelectedStreams(
                                key = info.key,
                                streamType = PlaybackStreamType.PROGRESSIVE,
                                videoStream = matching,
                                audioStream = null,
                                subtitles = info.subtitles,
                                isLive = info.isLive
                            )
                        )
                    } else {
                        return AppResult.Failure(AppError.UnsupportedFormat)
                    }
                } else {
                    val matchingVideo = info.videoStreams
                        .filter { it.isVideoOnly && isValidUrl(it.url) }
                        .filter { matchesOption(it, opt) }
                        .maxByOrNull { it.bitrate ?: 0L }

                    if (matchingVideo != null) {
                        val audioCandidates = info.audioStreams
                            .filter { isValidUrl(it.url) }
                            .sortedByDescending { it.bitrate ?: 0L }

                        val compatibleAudio = audioCandidates.firstOrNull { audio ->
                            areStreamsCompatible(matchingVideo, audio)
                        }

                        if (compatibleAudio != null) {
                            return AppResult.Success(
                                SelectedStreams(
                                    key = info.key,
                                    streamType = PlaybackStreamType.MERGED_AV,
                                    videoStream = matchingVideo,
                                    audioStream = compatibleAudio,
                                    subtitles = info.subtitles,
                                    isLive = info.isLive
                                )
                            )
                        }
                    }
                    return AppResult.Failure(AppError.UnsupportedFormat)
                }
            }
            else -> { /* Proceed to default cascade */ }
        }

        val maxHeight = when (preference) {
            is QualityPreference.Auto -> Int.MAX_VALUE
            is QualityPreference.ExactOrBelow -> preference.maxHeight
            is QualityPreference.SpecificOption -> preference.option.height
        }

        // Auto is genuinely adaptive only when Media3 receives a manifest. Fixed quality
        // preferences keep the existing deterministic progressive/merged selection below.
        if (preference is QualityPreference.Auto) {
            if (isValidUrl(info.hlsManifestUrl)) {
                return AppResult.Success(
                    SelectedStreams(
                        key = info.key,
                        streamType = PlaybackStreamType.HLS,
                        manifestUrl = info.hlsManifestUrl,
                        subtitles = info.subtitles,
                        isLive = info.isLive
                    )
                )
            }
            if (isValidUrl(info.dashManifestUrl)) {
                return AppResult.Success(
                    SelectedStreams(
                        key = info.key,
                        streamType = PlaybackStreamType.DASH,
                        manifestUrl = info.dashManifestUrl,
                        subtitles = info.subtitles,
                        isLive = info.isLive
                    )
                )
            }
        }

        // 1. Try progressive (A/V merged together in one stream)
        val progressiveCandidates = info.videoStreams
            .filter { isProgressiveValid(it) }
            .filter { (it.height ?: 0) <= maxHeight }
            .sortedByDescending { it.height ?: 0 }

        if (progressiveCandidates.isNotEmpty()) {
            val chosen = progressiveCandidates.first()
            return AppResult.Success(
                SelectedStreams(
                    key = info.key,
                    streamType = PlaybackStreamType.PROGRESSIVE,
                    videoStream = chosen,
                    audioStream = null,
                    subtitles = info.subtitles,
                    isLive = info.isLive
                )
            )
        }

        // 2. Try separate Video-only + Audio-only
        val videoCandidates = info.videoStreams
            .filter { it.isVideoOnly && isValidUrl(it.url) }
            .filter { (it.height ?: 0) <= maxHeight }
            .sortedByDescending { it.height ?: 0 }

        val audioCandidates = info.audioStreams
            .filter { isValidUrl(it.url) }
            .sortedByDescending { it.bitrate ?: 0L }

        for (video in videoCandidates) {
            val compatibleAudio = audioCandidates.firstOrNull { audio ->
                areStreamsCompatible(video, audio)
            }
            if (compatibleAudio != null) {
                return AppResult.Success(
                    SelectedStreams(
                        key = info.key,
                        streamType = PlaybackStreamType.MERGED_AV,
                        videoStream = video,
                        audioStream = compatibleAudio,
                        subtitles = info.subtitles,
                        isLive = info.isLive
                    )
                )
            }
        }

        // 3. Fallback to HLS manifest if available
        if (isValidUrl(info.hlsManifestUrl)) {
            return AppResult.Success(
                SelectedStreams(
                    key = info.key,
                    streamType = PlaybackStreamType.HLS,
                    manifestUrl = info.hlsManifestUrl,
                    subtitles = info.subtitles,
                    isLive = info.isLive
                )
            )
        }

        // 4. Fallback to DASH manifest if available
        if (isValidUrl(info.dashManifestUrl)) {
            return AppResult.Success(
                SelectedStreams(
                    key = info.key,
                    streamType = PlaybackStreamType.DASH,
                    manifestUrl = info.dashManifestUrl,
                    subtitles = info.subtitles,
                    isLive = info.isLive
                )
            )
        }

        // 5. Fallback to audio-only if available and valid
        val validAudioCandidates = audioCandidates.filter { isAudioValid(it) }
        if (validAudioCandidates.isNotEmpty()) {
            val chosenAudio = validAudioCandidates.first()
            return AppResult.Success(
                SelectedStreams(
                    key = info.key,
                    streamType = PlaybackStreamType.AUDIO_ONLY,
                    videoStream = null,
                    audioStream = chosenAudio,
                    subtitles = info.subtitles,
                    isLive = info.isLive
                )
            )
        }

        // 6. No valid stream candidates
        return AppResult.Failure(AppError.UnsupportedFormat)
    }

    fun getAvailableQualities(info: StreamInfo): List<QualityOption> {
        val manifestQualities = mutableListOf<QualityOption>()
        if (isValidUrl(info.hlsManifestUrl)) {
            manifestQualities.add(
                QualityOption(
                    height = 0,
                    label = "Auto (HLS)",
                    isProgressive = false,
                    format = "hls",
                    mimeType = "application/x-mpegURL",
                    codec = null,
                    streamType = PlaybackStreamType.HLS
                )
            )
        }
        if (isValidUrl(info.dashManifestUrl)) {
            manifestQualities.add(
                QualityOption(
                    height = 0,
                    label = "Auto (DASH)",
                    isProgressive = false,
                    format = "dash",
                    mimeType = "application/dash+xml",
                    codec = null,
                    streamType = PlaybackStreamType.DASH
                )
            )
        }

        val progressive = info.videoStreams
            .filter { isProgressiveValid(it) && it.height != null }
            .map {
                val height = it.height ?: 0
                QualityOption(
                    height = height,
                    label = it.resolution.ifBlank { "${height}p" },
                    isProgressive = true,
                    format = it.format,
                    mimeType = it.mimeType,
                    codec = it.codec,
                    streamType = PlaybackStreamType.PROGRESSIVE
                )
            }

        val videoCandidates = info.videoStreams
            .filter { it.isVideoOnly && isValidUrl(it.url) && it.height != null }

        val audioCandidates = info.audioStreams.filter { isValidUrl(it.url) }

        val videoOnlyCompatible = videoCandidates.filter { video ->
            audioCandidates.any { audio -> areStreamsCompatible(video, audio) }
        }.map {
            val height = it.height ?: 0
            val baseRes = it.resolution.ifBlank { "${height}p" }
            QualityOption(
                height = height,
                label = "$baseRes (adaptive)",
                isProgressive = false,
                format = it.format,
                mimeType = it.mimeType,
                codec = it.codec,
                streamType = PlaybackStreamType.MERGED_AV
            )
        }

        val distinctMediaQualities = (progressive + videoOnlyCompatible)
            .distinctBy { "${it.height}_${it.isProgressive}_${it.format.lowercase()}_${it.mimeType?.lowercase().orEmpty()}_${it.codec?.lowercase().orEmpty()}" }
            .sortedWith(
                compareByDescending<QualityOption> { it.height }
                    .thenByDescending { it.isProgressive }
                    .thenBy { it.format }
                    .thenBy { it.codec.orEmpty() }
            )

        return manifestQualities + distinctMediaQualities
    }
}



