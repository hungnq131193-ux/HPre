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
            val cache = cacheManager?.cache
            return if (cache != null) {
                val sinkFactory = CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(MediaCacheConstants.FRAGMENT_SIZE_BYTES)

                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(defaultUpstreamFactory)
                    .setCacheWriteDataSinkFactory(sinkFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else {
                defaultUpstreamFactory
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
}
