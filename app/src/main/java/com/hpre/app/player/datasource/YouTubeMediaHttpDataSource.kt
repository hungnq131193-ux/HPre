package com.hpre.app.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.util.concurrent.atomic.AtomicInteger

/**
 * googlevideo throttles any single response larger than ~10 MB to roughly real-time speed, and an
 * open-ended POST waits ~400 ms for its first byte. Progressive and video-only streams are
 * therefore fetched as consecutive `range=` requests of [chunkSizeBytes], exposed to Media3 as one
 * continuous read.
 */
@OptIn(UnstableApi::class)
class YouTubeMediaHttpDataSource(
    private val delegate: HttpDataSource,
    private val profile: YouTubeRequestProfile,
    private val chunkSizeBytes: Long = PROGRESSIVE_CHUNK_BYTES
) : HttpDataSource {

    private val requestCounter = AtomicInteger(0)
    private var chunks: ChunkState? = null

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
        val chunkEnd = if (state.end == C.LENGTH_UNSET.toLong()) {
            state.position + chunkSizeBytes
        } else {
            minOf(state.end, state.position + chunkSizeBytes)
        }
        val requested = chunkEnd - state.position
        var url = YouTubeRequestPolicy.replaceQueryParameter(state.url, "range", "${state.position}-${chunkEnd - 1}")
        url = YouTubeRequestPolicy.replaceQueryParameter(url, "rn", requestCounter.getAndIncrement().toString())
        val opened = delegate.open(state.spec.buildUpon().setUri(Uri.parse(url)).build())
        state.chunkRemaining = if (opened == C.LENGTH_UNSET.toLong()) requested else minOf(opened, requested)
        state.chunkBytesRead = 0
        state.lastChunk = chunkEnd == state.end || state.chunkRemaining < requested
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val state = chunks ?: return delegate.read(buffer, offset, length)
        if (length == 0) return 0
        if (state.chunkRemaining == 0L) {
            if (state.lastChunk) return C.RESULT_END_OF_INPUT
            delegate.close()
            try {
                openChunk(state)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                // Size unknown and the previous chunk ended exactly at EOF.
                if (e.responseCode == 416 && state.end == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
                throw e
            }
            if (state.chunkRemaining == 0L) return C.RESULT_END_OF_INPUT
        }
        val read = delegate.read(buffer, offset, minOf(length.toLong(), state.chunkRemaining).toInt())
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
        return delegate.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return delegate.responseHeaders
    }

    override fun getResponseCode(): Int {
        return delegate.responseCode
    }

    override fun close() {
        chunks = null
        delegate.close()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun setRequestProperty(name: String, value: String) {
        delegate.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        delegate.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        delegate.clearAllRequestProperties()
    }

    class Factory(
        private val delegateFactory: HttpDataSource.Factory,
        private val profile: YouTubeRequestProfile
    ) : HttpDataSource.Factory {

        override fun createDataSource(): HttpDataSource {
            return YouTubeMediaHttpDataSource(
                delegate = delegateFactory.createDataSource(),
                profile = profile
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
    }
}
