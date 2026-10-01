package com.hpre.app.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * googlevideo throttles any single response larger than ~10 MB to roughly real-time speed, and an
 * open-ended POST waits ~400 ms for its first byte. Progressive and video-only streams are
 * therefore fetched as consecutive `range=` requests of [chunkSizeBytes], exposed to Media3 as one
 * continuous read.
 *
 * When [readAheadFactory] is provided the next chunk's response is opened on a background thread
 * while the current chunk drains, hiding that first-byte wait at every chunk boundary.
 */
@OptIn(UnstableApi::class)
class YouTubeMediaHttpDataSource(
    private val delegate: HttpDataSource,
    private val profile: YouTubeRequestProfile,
    private val chunkSizeBytes: Long = PROGRESSIVE_CHUNK_BYTES,
    private val readAheadFactory: HttpDataSource.Factory? = null
) : HttpDataSource {

    private val requestCounter = AtomicInteger(0)
    private var chunks: ChunkState? = null
    private var chunkSource: HttpDataSource = delegate

    private val transferListeners = CopyOnWriteArrayList<TransferListener>()
    private val requestProperties = ConcurrentHashMap<String, String>()
    private val readAheadLock = Any()
    private var readAhead: PendingChunk? = null

    private class ChunkState(
        val spec: DataSpec,
        val url: String,
        var position: Long,
        /** Exclusive end offset, or [C.LENGTH_UNSET] when the total size is unknown. */
        val end: Long,
        var chunkRemaining: Long = 0,
        var chunkBytesRead: Long = 0,
        var lastChunk: Boolean = false
    )

    /** A freshly opened chunk response owned by a read-ahead source. */
    private class OpenedChunk(
        val source: HttpDataSource,
        val chunkRemaining: Long,
        val lastChunk: Boolean
    )

    /**
     * In-flight next-chunk open. An abandoned chunk must still close its response or the pooled
     * connection stalls: the task's `done()` releases it after open lands, and [detach] covers
     * a chunk that already completed before the detach.
     */
    private inner class PendingChunk(
        val startPosition: Long,
        val task: FutureTask<OpenedChunk>,
        val detached: AtomicBoolean
    ) {
        fun detach() {
            detached.set(true)
            task.cancel(false)
            if (task.isDone) {
                try {
                    task.get().source.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val transformed = YouTubeRequestPolicy.transformDataSpec(
            dataSpec = dataSpec,
            profile = profile,
            requestNumber = requestCounter.get()
        )
        if (transformed.addedRn) requestCounter.incrementAndGet()

        val newSpecBuilder = dataSpec.buildUpon()
            .setUri(transformed.uri)
            .setHttpRequestHeaders(transformed.headers)
            .setPosition(transformed.position)
            .setLength(transformed.length)

        if (transformed.isEligibleYouTube) {
            newSpecBuilder
                .setHttpMethod(transformed.httpMethod)
                .setHttpBody(transformed.httpBody)
        }

        if (transformed.isEligibleYouTube && profile == YouTubeRequestProfile.PROGRESSIVE) {
            return openChunked(newSpecBuilder.build(), transformed.uriString)
        }

        return delegate.open(newSpecBuilder.build())
    }

    private fun openChunked(spec: DataSpec, url: String): Long {
        detachReadAhead()
        if (chunkSource !== delegate) {
            try {
                chunkSource.close()
            } catch (_: Throwable) {
            }
        }
        chunkSource = delegate
        val start = spec.position
        val end = when {
            spec.length != C.LENGTH_UNSET.toLong() -> start + spec.length
            else -> YouTubeRequestPolicy.contentLength(url) ?: C.LENGTH_UNSET.toLong()
        }
        val state = ChunkState(
            spec = spec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build(),
            url = url,
            position = start,
            end = end
        )
        openChunk(state)
        chunks = state
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - start
    }

    private fun openChunk(state: ChunkState) {
        val chunk = openChunkAt(chunkSource, state, state.position)
        applyChunk(state, chunk)
        maybePrefetchNext(state)
    }

    private fun applyChunk(state: ChunkState, chunk: OpenedChunk) {
        chunkSource = chunk.source
        state.chunkRemaining = chunk.chunkRemaining
        state.chunkBytesRead = 0
        state.lastChunk = chunk.lastChunk
    }

    private fun openChunkAt(source: HttpDataSource, state: ChunkState, position: Long): OpenedChunk {
        val chunkEnd = if (state.end == C.LENGTH_UNSET.toLong()) {
            position + chunkSizeBytes
        } else {
            minOf(state.end, position + chunkSizeBytes)
        }
        val requested = chunkEnd - position
        var url = YouTubeRequestPolicy.replaceQueryParameter(state.url, "range", "$position-${chunkEnd - 1}")
        url = YouTubeRequestPolicy.replaceQueryParameter(url, "rn", requestCounter.getAndIncrement().toString())
        val opened = source.open(state.spec.buildUpon().setUri(Uri.parse(url)).build())
        val remaining = if (opened == C.LENGTH_UNSET.toLong()) requested else minOf(opened, requested)
        return OpenedChunk(
            source = source,
            chunkRemaining = remaining,
            lastChunk = chunkEnd == state.end || remaining < requested
        )
    }

    private fun maybePrefetchNext(state: ChunkState) {
        val factory = readAheadFactory ?: return
        if (state.lastChunk || state.chunkRemaining <= 0) return
        val nextPosition = state.position + state.chunkRemaining
        if (state.end != C.LENGTH_UNSET.toLong() && nextPosition >= state.end) return

        val source = factory.createDataSource()
        transferListeners.forEach(source::addTransferListener)
        requestProperties.forEach { (name, value) -> source.setRequestProperty(name, value) }

        val detached = AtomicBoolean(false)
        val task = object : FutureTask<OpenedChunk>(
            Callable { openChunkAt(source, state, nextPosition) }
        ) {
            override fun done() {
                if (!detached.get() && !isCancelled) return
                val opened = try {
                    get()
                } catch (_: Throwable) {
                    null
                }
                try {
                    (opened?.source ?: source).close()
                } catch (_: Throwable) {
                }
            }
        }
        val pending = PendingChunk(nextPosition, task, detached)
        synchronized(readAheadLock) {
            val previous = readAhead
            readAhead = pending
            previous?.detach()
        }
        READ_AHEAD_EXECUTOR.execute(task)
    }

    /** Returns the pre-opened next chunk, or null when absent/stale/failed — callers fall back to a sync open. */
    private fun adoptReadAhead(state: ChunkState): OpenedChunk? {
        val pending = synchronized(readAheadLock) {
            val p = readAhead
            readAhead = null
            p
        } ?: return null

        if (pending.startPosition != state.position) {
            pending.detach()
            return null
        }
        return try {
            pending.task.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            pending.detach()
            null
        } catch (_: Throwable) {
            pending.detach()
            null
        }
    }

    private fun detachReadAhead() {
        synchronized(readAheadLock) {
            readAhead?.detach()
            readAhead = null
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val state = chunks ?: return delegate.read(buffer, offset, length)
        if (length == 0) return 0
        if (state.chunkRemaining == 0L) {
            if (state.lastChunk) return C.RESULT_END_OF_INPUT
            chunkSource.close()
            val adopted = adoptReadAhead(state)
            if (adopted != null) {
                applyChunk(state, adopted)
                maybePrefetchNext(state)
            } else {
                try {
                    openChunk(state)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    // Size unknown and the previous chunk ended exactly at EOF.
                    if (e.responseCode == 416 && state.end == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
                    throw e
                }
            }
            if (state.chunkRemaining == 0L) return C.RESULT_END_OF_INPUT
        }
        val read = chunkSource.read(buffer, offset, minOf(length.toLong(), state.chunkRemaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            if (state.end == C.LENGTH_UNSET.toLong()) return read
            // A truncated chunk of known-size media resumes from the current offset.
            if (state.chunkBytesRead == 0L) throw EOFException("Empty media chunk at ${state.position}")
            state.chunkRemaining = 0
            state.lastChunk = false
            return read(buffer, offset, length)
        }
        state.position += read
        state.chunkRemaining -= read
        state.chunkBytesRead += read
        return read
    }

    override fun getUri(): Uri? {
        val state = chunks
        return if (state != null && chunkSource !== delegate) chunkSource.uri else delegate.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        val state = chunks
        return if (state != null && chunkSource !== delegate) chunkSource.responseHeaders else delegate.responseHeaders
    }

    override fun getResponseCode(): Int {
        val state = chunks
        return if (state != null && chunkSource !== delegate) chunkSource.responseCode else delegate.responseCode
    }

    override fun close() {
        chunks = null
        detachReadAhead()
        if (chunkSource !== delegate) {
            try {
                chunkSource.close()
            } catch (_: Throwable) {
            }
            chunkSource = delegate
        }
        delegate.close()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate.addTransferListener(transferListener)
    }

    override fun setRequestProperty(name: String, value: String) {
        requestProperties[name] = value
        delegate.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
        delegate.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
        delegate.clearAllRequestProperties()
    }

    class Factory(
        private val delegateFactory: HttpDataSource.Factory,
        private val profile: YouTubeRequestProfile
    ) : HttpDataSource.Factory {

        override fun createDataSource(): HttpDataSource {
            return YouTubeMediaHttpDataSource(
                delegate = delegateFactory.createDataSource(),
                profile = profile,
                readAheadFactory = delegateFactory
            )
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
            delegateFactory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }

    companion object {
        /** Stays under googlevideo's ~10 MB per-response throttling threshold. */
        const val PROGRESSIVE_CHUNK_BYTES: Long = 8L * 1024 * 1024

        /** One connection open at a time per stream; parked threads die after 60 s idle. */
        private val READ_AHEAD_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "hpre-chunk-readahead").apply { isDaemon = true }
        }
    }
}
