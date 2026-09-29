package com.hpre.app.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.hpre.app.core.error.AppError
import com.hpre.app.core.network.NetworkPolicy
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.player.datasource.YouTubeMediaHttpDataSource
import com.hpre.app.player.datasource.YouTubeRequestProfile
import com.hpre.app.core.error.AppResult
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the Media3 [DownloadManager], the SimpleCache the downloaded bytes live in, and the state
 * flow the UI observes. Playback asks [buildOfflineMediaSource] to swap a completed download in for
 * the network source.
 */
@UnstableApi
class DownloadTracker(
    private val context: Context,
    okHttpClient: OkHttpClient,
    private val videoService: VideoService?,
    private val scope: CoroutineScope
) {

    data class Entry(
        val key: ContentKey,
        val title: String,
        val channel: String?,
        val thumbnailUrl: String?,
        val audioOnly: Boolean,
        val state: Int,
        /** 0..100, -1 when unknown. */
        val percent: Float,
        val bytesDownloaded: Long,
        val failureReason: Int
    )

    private val databaseProvider = StandaloneDatabaseProvider(context)
    val cache: SimpleCache = SimpleCache(
        File(context.filesDir, "downloads"),
        androidx.media3.datasource.cache.NoOpCacheEvictor(),
        databaseProvider
    )

    private val httpFactory: DataSource.Factory by lazy {
        val base = OkHttpDataSource.Factory(okHttpClient.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build())
            .setUserAgent(NetworkPolicy.DEFAULT_USER_AGENT)
        val yt = YouTubeMediaHttpDataSource.Factory(base, YouTubeRequestProfile.PROGRESSIVE)
        DefaultDataSource.Factory(context, yt)
    }

    val downloadManager: DownloadManager = DownloadManager(
        context,
        databaseProvider,
        cache,
        httpFactory,
        Executors.newSingleThreadExecutor()
    )

    private val _downloads = MutableStateFlow<List<Entry>>(emptyList())
    val downloads: StateFlow<List<Entry>> = _downloads.asStateFlow()

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) = refresh()
        override fun onDownloadsPausedChanged(downloadManager: DownloadManager, paused: Boolean) = refresh()
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: java.lang.Exception?
        ) = refresh()
        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
        override fun onIdle(downloadManager: DownloadManager) = refresh()
        override fun onRequirementsStateChanged(
            downloadManager: DownloadManager,
            requirements: androidx.media3.exoplayer.scheduler.Requirements,
            notMetRequirements: Int
        ) = refresh()
        override fun onWaitingForRequirementsChanged(
            downloadManager: DownloadManager,
            waitingForRequirements: Boolean
        ) = refresh()
    }

    init {
        downloadManager.addListener(listener)
        refresh()
    }

    /** Resolve streams and queue the media3 download tasks. Safe to call from the UI layer. */
    fun enqueue(key: ContentKey, audioOnly: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                videoService?.streamInfo(key)
                    ?: AppResult.Failure(AppError.Unknown)
            }
            val info = (result as? AppResult.Success)?.value ?: return@launch
            val requests = DownloadPlan.select(info, audioOnly) ?: return@launch
            val metadataJson = JSONObject()
                .put("title", info.title)
                .put("audioOnly", audioOnly)
                .toString()
                .toByteArray(Charsets.UTF_8)
            requests.forEach { plan ->
                val request = DownloadRequest.Builder(plan.id, android.net.Uri.parse(plan.url))
                    .setData(if (plan.id == DownloadPlan.primaryId(key)) metadataJson else null)
                    .build()
                DownloadService.sendAddDownload(
                    context, HPreDownloadService::class.java, request, /* foreground = */ false
                )
            }
        }
    }

    fun remove(key: ContentKey) {
        DownloadService.sendRemoveDownload(
            context, HPreDownloadService::class.java, DownloadPlan.primaryId(key), false
        )
        DownloadService.sendRemoveDownload(
            context, HPreDownloadService::class.java, DownloadPlan.audioId(key), false
        )
    }

    /** Completed download for [key], or null. */
    fun completedFor(key: ContentKey): List<Download>? {
        val primary = downloadManager.downloadIndex.getDownload(DownloadPlan.primaryId(key))
            ?: return null
        if (primary.state != Download.STATE_COMPLETED) return null
        val audio = downloadManager.downloadIndex.getDownload(DownloadPlan.audioId(key))
        if (audio != null && audio.state != Download.STATE_COMPLETED) return null
        return listOfNotNull(primary, audio)
    }

    /**
     * MediaSource backed by the download cache for a fully downloaded [key]. Returns null when the
     * content is not downloaded; callers fall back to the network path.
     */
    fun buildOfflineMediaSource(key: ContentKey): MediaSource? {
        val parts = completedFor(key) ?: return null
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val meta = metadataOf(parts.first())
        val primarySource = DownloadHelper.createMediaSource(parts.first().request, cacheFactory)
        return if (parts.size == 1) {
            primarySource
        } else {
            MergingMediaSource(
                primarySource,
                DownloadHelper.createMediaSource(parts[1].request, cacheFactory)
            )
        }
    }

    /**
     * Minimal [StreamInfo] reconstructed from a completed download so playback can proceed when
     * stream resolution fails entirely (device offline).
     */
    fun offlineInfoFor(key: ContentKey): StreamInfo? {
        val parts = completedFor(key) ?: return null
        val meta = metadataOf(parts.first())
        return StreamInfo(key = key, title = meta?.optString("title") ?: "", isLive = false)
    }

    private fun metadataOf(download: Download): JSONObject? =
        runCatching {
            download.request.data?.let { JSONObject(String(it, Charsets.UTF_8)) }
        }.getOrNull()

    private fun refresh() {
        val index = downloadManager.downloadIndex
        val out = ArrayList<Entry>()
        val cursor = index.getDownloads()
        try {
            while (cursor.moveToNext()) {
                val d = cursor.download
                val id = d.request.id
                if (id.endsWith("|a")) continue // secondary request folds into its primary entry
                val meta = metadataOf(d)
                val key = parseId(id) ?: continue
                out += Entry(
                    key = key,
                    title = meta?.optString("title") ?: "",
                    channel = meta?.optString("channel")?.takeIf { it.isNotBlank() },
                    thumbnailUrl = meta?.optString("thumbnail")?.takeIf { it.isNotBlank() },
                    audioOnly = meta?.optBoolean("audioOnly") ?: false,
                    state = d.state,
                    percent = d.percentDownloaded,
                    bytesDownloaded = d.bytesDownloaded,
                    failureReason = d.failureReason
                )
            }
        } finally {
            cursor.close()
        }
        _downloads.value = out
    }

    private fun parseId(id: String): ContentKey? {
        if (!id.startsWith("hpre:")) return null
        val parts = id.removePrefix("hpre:").split(":", limit = 2)
        val serviceId = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val nativeId = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return ContentKey(serviceId, nativeId)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "hpre_downloads"
    }
}

/** Player-facing download state, decoupled from media3 internals. */
enum class DownloadUiState { NONE, QUEUED, DOWNLOADING, COMPLETED, FAILED }

@androidx.annotation.OptIn(UnstableApi::class)
internal fun DownloadTracker.Entry.toUiState(): DownloadUiState = when (state) {
    Download.STATE_COMPLETED -> DownloadUiState.COMPLETED
    Download.STATE_DOWNLOADING -> DownloadUiState.DOWNLOADING
    Download.STATE_FAILED -> DownloadUiState.FAILED
    else -> DownloadUiState.QUEUED
}
