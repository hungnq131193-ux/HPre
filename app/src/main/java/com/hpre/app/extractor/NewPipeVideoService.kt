package com.hpre.app.extractor

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.core.performance.VideoOpenEvent
import com.hpre.app.core.performance.VideoOpenMetrics
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.CommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.PlaylistDetails
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary
import com.hpre.app.repository.VideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import com.hpre.app.model.hasUsableMediaUrls

/**
 * Isolated NewPipeExtractor implementation of VideoService.
 *
 * Requirements:
 * - Sole entrypoint to NewPipeExtractor.
 * - Thread-safe, non-blocking coroutines on injected IO dispatcher.
 * - Explicitly preserves CancellationException on cancellation / interruption.
 * - Uses ExtractorOperations interface internally for test isolation without global state resetting.
 */
class NewPipeVideoService internal constructor(
    private val ioDispatcher: CoroutineDispatcher = ExtractorDispatcher.IO,
    private val operations: ExtractorOperations = DefaultExtractorOperations(),
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val extractionCoordinator: VideoExtractionCoordinator = VideoExtractionCoordinator(
        serviceScope,
        ttlMs = 1_800_000L,
        maxEntries = 32
    ),
    private val videoOpenMetrics: VideoOpenMetrics = VideoOpenMetrics.Default
) : VideoService {

    constructor(ioDispatcher: CoroutineDispatcher = ExtractorDispatcher.IO) : this(
        ioDispatcher = ioDispatcher,
        operations = DefaultExtractorOperations()
    )

    override val serviceId: Int
        get() = operations.serviceId

    override val serviceName: String
        get() = operations.serviceName

    override val supportsShorts: Boolean
        get() = operations.supportsShorts

    override val supportsComments: Boolean
        get() = operations.supportsComments

    override val supportsSearchSuggestions: Boolean
        get() = operations.supportsSearchSuggestions

    private suspend fun <T> extract(block: () -> T): AppResult<T> = withContext(ioDispatcher) {
        if (!ExtractorBootstrap.isInitialized()) {
            return@withContext AppResult.Failure(AppError.ExtractionFailed)
        }
        try {
            val result = suspendCancellableCoroutine<T> { cont ->
                val workerThread = Thread.currentThread()
                cont.invokeOnCancellation {
                    OkHttpDownloader.cancelActiveCallForThread(workerThread.id)
                    workerThread.interrupt()
                }
                try {
                    val value = block()
                    if (cont.isActive) {
                        cont.resume(value)
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) {
                        cont.resumeWithException(t)
                    }
                }
            }
            AppResult.Success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedIOException) {
            if (currentCoroutineContext().isActive) {
                return@withContext AppResult.Failure(ExtractorErrorMapper.mapExtractorFailure(interrupted))
            }
            throw CancellationException("Operation cancelled").apply { initCause(interrupted) }
        } catch (interrupted: InterruptedException) {
            throw CancellationException("Operation cancelled").apply { initCause(interrupted) }
        } catch (error: Throwable) {
            AppResult.Failure(ExtractorErrorMapper.mapExtractorFailure(error))
        }
    }

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        pageToken: PageToken?
    ): AppResult<SearchPage> = extract {
        operations.search(query, filter, pageToken)
    }

    override suspend fun suggestions(query: String): AppResult<List<String>> = extract {
        operations.suggestions(query)
    }

    override suspend fun video(key: ContentKey): AppResult<VideoDetails> {
        if (key.serviceId != serviceId) {
            return AppResult.Failure(AppError.ExtractionFailed)
        }
        return when (val result = bundle(key)) {
            is AppResult.Success -> AppResult.Success(result.value.details)
            is AppResult.Failure -> result
        }
    }

    override suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo> {
        if (key.serviceId != serviceId) {
            return AppResult.Failure(AppError.ExtractionFailed)
        }
        val cached = extractionCoordinator.peek(key)
        if (cached != null) {
            return if (cached.streamInfo.hasUsableMediaUrls()) {
                AppResult.Success(cached.streamInfo)
            } else {
                refreshCachedStreamInfo(key, cached)
            }
        }
        return when (val result = bundle(key)) {
            is AppResult.Success -> if (result.value.streamInfo.hasUsableMediaUrls()) {
                AppResult.Success(result.value.streamInfo)
            } else {
                refreshCachedStreamInfo(key, result.value)
            }
            is AppResult.Failure -> result
        }
    }

    override suspend fun prefetch(keys: List<ContentKey>) {
        // Disabled to prevent network queue contention and keep workers free for active video playback.
    }

    override suspend fun channel(key: ContentKey): AppResult<ChannelDetails> {
        if (key.serviceId != serviceId) {
            return AppResult.Failure(AppError.ExtractionFailed)
        }
        return extract {
            operations.channel(key)
        }
    }

    override suspend fun related(key: ContentKey): AppResult<List<VideoSummary>> {
        if (key.serviceId != serviceId) {
            return AppResult.Failure(AppError.ExtractionFailed)
        }
        return when (val result = bundle(key)) {
            is AppResult.Success -> AppResult.Success(
                result.value.deferredRelatedItems
                    ?.mapNotNull { NewPipeMappers.mapStreamInfoItemToSummary(it, serviceId) }
                    ?: result.value.related
            )
            is AppResult.Failure -> result
        }
    }

    override suspend fun refreshStreamInfo(key: ContentKey): AppResult<StreamInfo> {
        if (key.serviceId != serviceId) return AppResult.Failure(AppError.ExtractionFailed)
        val cached = extractionCoordinator.peek(key)
        return if (cached != null) {
            refreshCachedStreamInfo(key, cached)
        } else when (val result = bundle(key, forceRefresh = true)) {
            is AppResult.Success -> AppResult.Success(result.value.streamInfo)
            is AppResult.Failure -> result
        }
    }

    private suspend fun refreshCachedStreamInfo(
        key: ContentKey,
        cached: ExtractedVideoBundle
    ): AppResult<StreamInfo> {
        val result = extractionCoordinator.execute(key, forceRefresh = true) {
            val metricsSession = videoOpenMetrics.activeSession(key)
            metricsSession?.let { videoOpenMetrics.mark(it, VideoOpenEvent.EXTRACTOR_START) }
            val fresh = extract { operations.refreshStreamInfo(key) }
            metricsSession?.let { videoOpenMetrics.mark(it, VideoOpenEvent.EXTRACTOR_FINISH) }
            when (fresh) {
                is AppResult.Success -> {
                    if (fresh.value.key == key) {
                        AppResult.Success(cached.copy(streamInfo = fresh.value))
                    } else {
                        AppResult.Failure(AppError.ExtractionFailed)
                    }
                }
                is AppResult.Failure -> fresh
            }
        }
        return when (result) {
            is AppResult.Success -> AppResult.Success(result.value.streamInfo)
            is AppResult.Failure -> result
        }
    }

    private suspend fun bundle(key: ContentKey, forceRefresh: Boolean = false): AppResult<ExtractedVideoBundle> {
        val metricsSession = videoOpenMetrics.activeSession(key)
        return extractionCoordinator.execute(key, forceRefresh) {
            // Only the shared cache-miss loader emits these events, not each subscriber.
            metricsSession?.let { videoOpenMetrics.mark(it, VideoOpenEvent.EXTRACTOR_START) }
            val result = extract { operations.videoBundle(key) }
            metricsSession?.let { videoOpenMetrics.mark(it, VideoOpenEvent.EXTRACTOR_FINISH) }
            result
        }
    }

    override suspend fun playlist(key: ContentKey): AppResult<PlaylistDetails> {
        if (key.serviceId != serviceId) {
            return AppResult.Failure(AppError.ExtractionFailed)
        }
        return extract {
            operations.playlist(key)
        }
    }

    override suspend fun comments(key: ContentKey, pageToken: PageToken?): AppResult<CommentPage> {
        if (key.serviceId != serviceId) {
            return AppResult.Failure(AppError.ExtractionFailed)
        }
        return extract {
            operations.comments(key, pageToken)
        }
    }

    override suspend fun trending(): AppResult<List<VideoSummary>> = extract {
        operations.trending()
    }
}
