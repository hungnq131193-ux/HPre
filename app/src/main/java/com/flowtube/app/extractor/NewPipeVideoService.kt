package com.flowtube.app.extractor

import com.flowtube.app.core.error.AppError
import com.flowtube.app.core.error.AppResult
import com.flowtube.app.model.ChannelDetails
import com.flowtube.app.model.CommentPage
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.PageToken
import com.flowtube.app.model.PlaylistDetails
import com.flowtube.app.model.SearchFilter
import com.flowtube.app.model.SearchPage
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary
import com.flowtube.app.repository.VideoService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private val operations: ExtractorOperations = DefaultExtractorOperations()
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
        return extract {
            operations.video(key)
        }
    }

    override suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo> {
        if (key.serviceId != serviceId) {
            return AppResult.Failure(AppError.ExtractionFailed)
        }
        return extract {
            operations.streamInfo(key)
        }
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
        return extract {
            operations.related(key)
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

