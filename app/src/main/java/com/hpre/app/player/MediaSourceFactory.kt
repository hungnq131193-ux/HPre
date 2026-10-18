package com.hpre.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hpre.app.model.SubtitleStream
import com.hpre.app.player.cache.MediaCacheConstants
import com.hpre.app.player.cache.MediaCacheManager
import com.hpre.app.player.cache.YouTubeCacheKeyFactory
import com.hpre.app.player.datasource.YouTubeMediaHttpDataSource
import com.hpre.app.player.datasource.YouTubeRequestProfile
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun interface MediaSourceCreator {
    fun createMediaSource(selected: SelectedStreams): MediaSource
}

/**
 * Player-owned HTTP configuration to decouple player from extractor details.
 */
data class PlayerHttpConfig(
    val userAgent: String = DEFAULT_USER_AGENT
) {
    companion object {
        const val DEFAULT_USER_AGENT = com.hpre.app.core.network.NetworkPolicy.DEFAULT_USER_AGENT
    }
}

/**
 * Media requests can legitimately stay open for the whole playback session. Preserve the shared
 * dispatcher, connection pool, and connect/read timeouts, but remove the app-wide total call limit.
 */
internal fun playbackHttpClient(baseClient: OkHttpClient): OkHttpClient =
    baseClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

@OptIn(UnstableApi::class)
class MediaSourceFactory(
    private val dashDataSourceFactory: DataSource.Factory,
    private val progressiveDataSourceFactory: DataSource.Factory,
    private val hlsDataSourceFactory: DataSource.Factory,
    private val cachedProgressiveDataSourceFactory: DataSource.Factory,
    private val mediaSourceCreator: ((SelectedStreams) -> MediaSource)? = null
) : MediaSourceCreator {

    constructor(
        dataSourceFactory: DataSource.Factory,
        mediaSourceCreator: ((SelectedStreams) -> MediaSource)? = null
    ) : this(
        dashDataSourceFactory = dataSourceFactory,
        progressiveDataSourceFactory = dataSourceFactory,
        hlsDataSourceFactory = dataSourceFactory,
        cachedProgressiveDataSourceFactory = dataSourceFactory,
        mediaSourceCreator = mediaSourceCreator
    )

    constructor(
        context: Context,
        okHttpClient: OkHttpClient = OkHttpClient(),
        httpConfig: PlayerHttpConfig = PlayerHttpConfig(),
        cacheManager: MediaCacheManager? = null
    ) : this(
        dashDataSourceFactory = createProfileFactory(context, okHttpClient, httpConfig, YouTubeRequestProfile.DASH),
        progressiveDataSourceFactory = createProfileFactory(context, okHttpClient, httpConfig, YouTubeRequestProfile.PROGRESSIVE),
        hlsDataSourceFactory = createProfileFactory(context, okHttpClient, httpConfig, YouTubeRequestProfile.HLS),
        cachedProgressiveDataSourceFactory = createCachedProgressiveFactory(context, okHttpClient, httpConfig, cacheManager),
        mediaSourceCreator = null
    )

    companion object {
        private fun createProfileFactory(
            context: Context,
            okHttpClient: OkHttpClient,
            httpConfig: PlayerHttpConfig,
            profile: YouTubeRequestProfile
        ): DataSource.Factory {
            val baseHttpFactory = OkHttpDataSource.Factory(playbackHttpClient(okHttpClient))
                .setUserAgent(httpConfig.userAgent)
            val ytHttpFactory = YouTubeMediaHttpDataSource.Factory(baseHttpFactory, profile)
            return DefaultDataSource.Factory(context, ytHttpFactory)
        }

        private fun createCachedProgressiveFactory(
            context: Context,
            okHttpClient: OkHttpClient,
            httpConfig: PlayerHttpConfig,
            cacheManager: MediaCacheManager?
        ): DataSource.Factory {
            val defaultUpstreamFactory = createProfileFactory(context, okHttpClient, httpConfig, YouTubeRequestProfile.PROGRESSIVE)
            return DataSource.Factory {
                val cache = cacheManager?.cache
                if (cache == null) {
                    defaultUpstreamFactory.createDataSource()
                } else {
                    val sinkFactory = CacheDataSink.Factory()
                        .setCache(cache)
                        .setFragmentSize(MediaCacheConstants.FRAGMENT_SIZE_BYTES)

                    CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(defaultUpstreamFactory)
                        .setCacheWriteDataSinkFactory(sinkFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSource()
                }
            }
        }
    }

    private fun resolveMimeType(format: String, isVideo: Boolean, mimeTypeOverride: String? = null): String {
        if (!mimeTypeOverride.isNullOrBlank()) {
            val cleanMime = mimeTypeOverride.trim().substringBefore(";").trim().lowercase()
            return if (isVideo) {
                when (cleanMime) {
                    "video/mp4" -> MimeTypes.VIDEO_MP4
                    "video/webm" -> MimeTypes.VIDEO_WEBM
                    else -> throw IllegalArgumentException("Unsupported or invalid video mimeType: '$mimeTypeOverride'")
                }
            } else {
                when (cleanMime) {
                    "audio/mp4" -> MimeTypes.AUDIO_MP4
                    "audio/webm" -> MimeTypes.AUDIO_WEBM
                    "audio/aac" -> MimeTypes.AUDIO_AAC
                    "audio/ogg" -> MimeTypes.AUDIO_OGG
                    "audio/opus" -> MimeTypes.AUDIO_OPUS
                    else -> throw IllegalArgumentException("Unsupported or invalid audio mimeType: '$mimeTypeOverride'")
                }
            }
        }

        val normFormat = format.trim().lowercase()
        return if (isVideo) {
            when (normFormat) {
                "mp4", "m4v" -> MimeTypes.VIDEO_MP4
                "webm", "mkv" -> MimeTypes.VIDEO_WEBM
                else -> throw IllegalArgumentException("Unsupported or unknown video format: '$format'")
            }
        } else {
            when (normFormat) {
                "mp4", "m4a" -> MimeTypes.AUDIO_MP4
                "aac" -> MimeTypes.AUDIO_AAC
                "webm" -> MimeTypes.AUDIO_WEBM
                "ogg" -> MimeTypes.AUDIO_OGG
                "opus" -> MimeTypes.AUDIO_OPUS
                else -> throw IllegalArgumentException("Unsupported or unknown audio format: '$format'")
            }
        }
    }

    override fun createMediaSource(selected: SelectedStreams): MediaSource {
        if (mediaSourceCreator != null) {
            return mediaSourceCreator.invoke(selected)
        }

        val subtitleConfigs = selected.subtitles.mapNotNull { sub ->
            val mimeType = when {
                !sub.mimeType.isNullOrBlank() -> {
                    val cleanMime = sub.mimeType.trim().substringBefore(";").trim().lowercase()
                    when (cleanMime) {
                        "text/vtt" -> MimeTypes.TEXT_VTT
                        "application/ttml+xml", "text/xml" -> MimeTypes.APPLICATION_TTML
                        "application/x-subrip", "application/subrip", "text/x-subrip" -> MimeTypes.APPLICATION_SUBRIP
                        else -> null
                    }
                }
                else -> {
                    val cleanFormat = sub.format.trim().lowercase()
                    when (cleanFormat) {
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ttml" -> MimeTypes.APPLICATION_TTML
                        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                        else -> null
                    }
                }
            }
            if (mimeType != null && sub.url.isNotBlank()) {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(mimeType)
                    .setLanguage(sub.language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            } else {
                null
            }
        }

        fun mediaItemBuilder(uri: Uri, mimeType: String, customCacheKey: String? = null): MediaItem.Builder {
            val extras = android.os.Bundle().apply {
                putString("hpre_stream_type", selected.streamType.name)
            }
            val builder = MediaItem.Builder()
                .setMediaId(PlaybackMediaId.encode(selected.key))
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(selected.title.ifBlank { "HPre video" })
                        .setArtworkUri(selected.thumbnailUrl?.let(Uri::parse))
                        .setExtras(extras)
                        .build()
                )
                .setUri(uri)
                .setMimeType(mimeType)

            if (customCacheKey != null) {
                builder.setCustomCacheKey(customCacheKey)
            }

            return builder
        }

        val isCacheable = !selected.isLive

        val baseSource = when (selected.streamType) {
            PlaybackStreamType.PROGRESSIVE -> {
                val video = requireNotNull(selected.videoStream) { "Progressive stream requires non-null videoStream" }
                val uri = Uri.parse(video.url)
                val mimeType = resolveMimeType(video.format, isVideo = true, mimeTypeOverride = video.mimeType)
                val cacheKey = if (isCacheable) YouTubeCacheKeyFactory.buildVideoCacheKey(selected.key, video) else null
                val mediaItemBuilder = mediaItemBuilder(uri, mimeType, customCacheKey = cacheKey)
                if (subtitleConfigs.isNotEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                }

                val dsFactory = if (isCacheable) cachedProgressiveDataSourceFactory else progressiveDataSourceFactory
                ProgressiveMediaSource.Factory(dsFactory)
                    .createMediaSource(mediaItemBuilder.build())
            }

            PlaybackStreamType.MERGED_AV -> {
                val video = requireNotNull(selected.videoStream) { "Merged AV requires non-null videoStream" }
                val audio = requireNotNull(selected.audioStream) { "Merged AV requires non-null audioStream" }
                val videoUri = Uri.parse(video.url)
                val audioUri = Uri.parse(audio.url)

                val videoMime = resolveMimeType(video.format, isVideo = true, mimeTypeOverride = video.mimeType)
                val audioMime = resolveMimeType(audio.format, isVideo = false, mimeTypeOverride = audio.mimeType)

                val videoCacheKey = if (isCacheable) YouTubeCacheKeyFactory.buildVideoCacheKey(selected.key, video) else null
                val audioCacheKey = if (isCacheable) YouTubeCacheKeyFactory.buildAudioCacheKey(selected.key, audio) else null

                val videoMediaItemBuilder = mediaItemBuilder(videoUri, videoMime, customCacheKey = videoCacheKey)
                if (subtitleConfigs.isNotEmpty()) {
                    videoMediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                }

                val audioMediaItem = mediaItemBuilder(audioUri, audioMime, customCacheKey = audioCacheKey)
                    .build()

                val dsFactory = if (isCacheable) cachedProgressiveDataSourceFactory else progressiveDataSourceFactory
                val videoSource = ProgressiveMediaSource.Factory(dsFactory)
                    .createMediaSource(videoMediaItemBuilder.build())
                val audioSource = ProgressiveMediaSource.Factory(dsFactory)
                    .createMediaSource(audioMediaItem)

                MergingMediaSource(videoSource, audioSource)
            }

            PlaybackStreamType.AUDIO_ONLY -> {
                val audio = requireNotNull(selected.audioStream) { "Audio-only requires non-null audioStream" }
                val audioUri = Uri.parse(audio.url)
                val audioMime = resolveMimeType(audio.format, isVideo = false, mimeTypeOverride = audio.mimeType)
                val audioMediaItem = mediaItemBuilder(audioUri, audioMime, customCacheKey = null)
                    .build()

                ProgressiveMediaSource.Factory(progressiveDataSourceFactory)
                    .createMediaSource(audioMediaItem)
            }

            PlaybackStreamType.HLS -> {
                val manifestUrl = requireNotNull(selected.manifestUrl) { "HLS requires non-null manifestUrl" }
                val manifestUri = Uri.parse(manifestUrl)
                val mediaItemBuilder = mediaItemBuilder(manifestUri, MimeTypes.APPLICATION_M3U8)
                if (subtitleConfigs.isNotEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                }
                DefaultMediaSourceFactory(hlsDataSourceFactory)
                    .createMediaSource(mediaItemBuilder.build())
            }

            PlaybackStreamType.DASH -> {
                val manifestUrl = requireNotNull(selected.manifestUrl) { "DASH requires non-null manifestUrl" }
                val manifestUri = Uri.parse(manifestUrl)
                val mediaItemBuilder = mediaItemBuilder(manifestUri, MimeTypes.APPLICATION_MPD)
                if (subtitleConfigs.isNotEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                }
                DefaultMediaSourceFactory(dashDataSourceFactory)
                    .createMediaSource(mediaItemBuilder.build())
            }
        }

        return baseSource
    }

    private val resolutionLabelRegex = Regex("""^(\d{3,4})p""")

    /**
     * Returns the nominal short-side resolution (e.g., 1080 for both 1920x1080 horizontal and
     * 1080x1920 vertical Shorts, 720 for 720x1280 vertical Shorts) so vertical 9:16, 4:5, 3:4,
     * and 60fps streams (`1080p60`, `720p60`) are never misclassified as non-HD.
     */
    internal fun nominalResolution(option: QualityOption): Int {
        val fromLabel = resolutionLabelRegex.find(option.label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromLabel != null && fromLabel in 144..2160 && fromLabel != option.height) {
            return fromLabel
        }
        return when {
            option.height in listOf(1920, 1280, 854, 640, 426, 256) -> (option.height * 9 + 8) / 16
            option.height in 1700..2000 -> 1080
            option.height in 1150..1699 -> 720
            else -> option.height
        }
    }

    /**
     * Ranks codecs by Android hardware decoder universality so fast-start and Shorts playback
     * prefer H.264 (`avc1`) over VP9 and avoid AV1 (`av01`) hardware decoder failures.
     */
    private fun codecCompatibilityRank(option: QualityOption): Int {
        val codec = option.codec?.lowercase().orEmpty()
        return when {
            codec.startsWith("avc1") || codec.startsWith("h264") -> 3
            codec.startsWith("vp9") || codec.startsWith("vp09") -> 2
            codec.startsWith("av01") -> 0
            else -> 1
        }
    }

    private fun fastStartResolutionRank(resolution: Int): Int = when (resolution) {
        in 781..1150 -> 4  // 1080p Full HD
        in 680..780 -> 3   // 720p HD
        in 440..679 -> 2   // 480p
        in 320..439 -> 1   // 360p
        else -> 0
    }

    /**
     * Selects a high-definition cache-friendly stream option (1080p/720p H.264 Merged AV or HD
     * Progressive MP4) that bypasses multi-round-trip DASH manifest parsing, works with
     * [SimpleCache], and avoids 360p blurriness (`itag 18`) as well as AV1 codec failures.
     */
    fun selectFastStartQuality(streamInfo: com.hpre.app.model.StreamInfo): QualityOption? {
        val qualities = StreamSelector.getAvailableQualities(streamInfo)
        val cacheable = qualities.filter {
            it.streamType == PlaybackStreamType.MERGED_AV || it.streamType == PlaybackStreamType.PROGRESSIVE
        }
        if (cacheable.isEmpty()) {
            return qualities.firstOrNull {
                it.streamType == PlaybackStreamType.DASH || it.streamType == PlaybackStreamType.HLS
            }
        }

        val hdComparator = compareBy<QualityOption> { codecCompatibilityRank(it) }
            .thenBy { fastStartResolutionRank(nominalResolution(it)) }
            .thenBy { nominalResolution(it) }
            .thenBy { it.isProgressive }

        // 1. Prefer crisp 720p..1080p (Full HD / HD) with universally supported H.264/VP9 codec
        val hdNonAv1 = cacheable
            .filter { nominalResolution(it) in 680..1150 && codecCompatibilityRank(it) >= 1 }
            .maxWithOrNull(hdComparator)
        if (hdNonAv1 != null) return hdNonAv1

        // 2. Next prefer 480p..1080p (any compatible codec, preferring H.264/VP9)
        val sdOrHd = cacheable
            .filter { nominalResolution(it) in 440..1150 }
            .maxWithOrNull(hdComparator)
        if (sdOrHd != null) return sdOrHd

        // 3. If only <=360p progressive exists (e.g. itag 18) but an adaptive HLS/DASH manifest
        // is present, prefer adaptive HLS/DASH so playback can still reach 720p/1080p HD!
        qualities.firstOrNull {
            it.streamType == PlaybackStreamType.HLS || it.streamType == PlaybackStreamType.DASH
        }?.let { return it }

        // 4. Fallback to 360p/240p Progressive or Merged AV only when no >=480p or manifest exists
        return cacheable
            .filter { nominalResolution(it) in 240..480 }
            .maxWithOrNull(hdComparator)
            ?: cacheable.maxWithOrNull(hdComparator)
    }

    /**
     * Pre-warms the first media bytes of [streamInfo] into [SimpleCache] so that when the user
     * swipes to this Short or opens this video, ExoPlayer reads the initial keyframes from local
     * cache in 0ms without waiting on a network round-trip.
     */
    fun preCacheFirstBytes(
        streamInfo: com.hpre.app.model.StreamInfo,
        preferredQuality: QualityOption? = null,
        maxBytes: Long = 1_048_576L
    ) {
        if (streamInfo.isLive) return
        val quality = preferredQuality ?: selectFastStartQuality(streamInfo)
        val preference = quality?.let { QualityPreference.SpecificOption(it) } ?: QualityPreference.ExactOrBelow(1080)
        val selected = (StreamSelector.selectStream(streamInfo, preference) as? com.hpre.app.core.error.AppResult.Success)?.value
            ?: return

        when (selected.streamType) {
            PlaybackStreamType.PROGRESSIVE -> {
                val video = selected.videoStream ?: return
                val cacheKey = YouTubeCacheKeyFactory.buildVideoCacheKey(selected.key, video)
                warmDataSourceRange(Uri.parse(video.url), cacheKey, maxBytes)
            }
            PlaybackStreamType.MERGED_AV -> {
                val video = selected.videoStream ?: return
                val audio = selected.audioStream ?: return
                val videoCacheKey = YouTubeCacheKeyFactory.buildVideoCacheKey(selected.key, video)
                val audioCacheKey = YouTubeCacheKeyFactory.buildAudioCacheKey(selected.key, audio)
                warmDataSourceRange(Uri.parse(video.url), videoCacheKey, (maxBytes * 3) / 4)
                warmDataSourceRange(Uri.parse(audio.url), audioCacheKey, maxBytes / 4)
            }
            else -> Unit
        }
    }

    private fun warmDataSourceRange(uri: Uri, cacheKey: String, maxBytes: Long) {
        if (maxBytes <= 0L) return
        val dataSource = cachedProgressiveDataSourceFactory.createDataSource()
        val spec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(uri)
            .setPosition(0L)
            .setLength(maxBytes)
            .setKey(cacheKey)
            .build()
        try {
            dataSource.open(spec)
            val buffer = ByteArray(32 * 1024)
            var totalRead = 0L
            while (totalRead < maxBytes) {
                val toRead = minOf(buffer.size.toLong(), maxBytes - totalRead).toInt()
                val read = dataSource.read(buffer, 0, toRead)
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                totalRead += read
            }
        } catch (_: Throwable) {
            // Best-effort pre-caching; ignore network or cancellation errors
        } finally {
            runCatching { dataSource.close() }
        }
    }
}
