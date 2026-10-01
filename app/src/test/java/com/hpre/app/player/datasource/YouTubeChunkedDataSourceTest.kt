package com.hpre.app.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

class YouTubeChunkedDataSourceTest {

    private class FakeServer(
        private val media: ByteArray,
        private val truncateFirstResponseAt: Int? = null,
        private val reportLength: Boolean = true
    ) : HttpDataSource {
        val ranges = mutableListOf<String>()
        val rangeHeaders = mutableListOf<String?>()
        private var body = ByteArray(0)
        private var offset = 0
        private var lastUri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            lastUri = dataSpec.uri
            val range = dataSpec.uri.toString().substringAfter("range=").substringBefore("&")
            ranges += range
            rangeHeaders += dataSpec.httpRequestHeaders["Range"]
            val start = range.substringBefore("-").toInt()
            val end = range.substringAfter("-").toInt()
            if (start >= media.size) {
                throw HttpDataSource.InvalidResponseCodeException(416, null, null, emptyMap(), dataSpec, ByteArray(0))
            }
            body = media.copyOfRange(start, minOf(end + 1, media.size))
            val length = body.size.toLong()
            if (ranges.size == 1 && truncateFirstResponseAt != null) body = body.copyOf(truncateFirstResponseAt)
            offset = 0
            return if (reportLength) length else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (this.offset >= body.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, body.size - this.offset)
            System.arraycopy(body, this.offset, buffer, offset, n)
            this.offset += n
            return n
        }

        override fun getUri(): Uri? = lastUri
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun getResponseCode(): Int = 200
        override fun close() = Unit
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun setRequestProperty(name: String, value: String) = Unit
        override fun clearRequestProperty(name: String) = Unit
        override fun clearAllRequestProperties() = Unit
    }

    private val media = ByteArray(20) { it.toByte() }
    private val base = "https://rr1---sn-4g5ednks.googlevideo.com/videoplayback?expire=1&itag=18"

    private fun readAll(source: YouTubeMediaHttpDataSource, spec: DataSpec): Pair<Long, ByteArray> {
        val opened = source.open(spec)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(5)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        source.close()
        return opened to out.toByteArray()
    }

    private fun spec(url: String, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(Uri.parse(url)).setPosition(position).setLength(length).build()

    @Test
    fun knownLength_isFetchedAsBoundedRangeChunks() {
        val server = FakeServer(media)
        val source = YouTubeMediaHttpDataSource(server, YouTubeRequestProfile.PROGRESSIVE, chunkSizeBytes = 8)

        val (opened, bytes) = readAll(source, spec("$base&clen=20"))

        assertEquals(20L, opened)
        assertArrayEquals(media, bytes)
        assertEquals(listOf("0-7", "8-15", "16-19"), server.ranges)
        assertEquals(listOf<String?>(null, null, null), server.rangeHeaders)
    }

    @Test
    fun unknownLength_endsOnShortChunk() {
        val server = FakeServer(media)
        val source = YouTubeMediaHttpDataSource(server, YouTubeRequestProfile.PROGRESSIVE, chunkSizeBytes = 8)

        val (opened, bytes) = readAll(source, spec(base))

        assertEquals(C.LENGTH_UNSET.toLong(), opened)
        assertArrayEquals(media, bytes)
        assertEquals(listOf("0-7", "8-15", "16-23"), server.ranges)
    }

    @Test
    fun unknownLength_endingOnChunkBoundary_treats416AsEndOfInput() {
        val server = FakeServer(media.copyOf(16))
        val source = YouTubeMediaHttpDataSource(server, YouTubeRequestProfile.PROGRESSIVE, chunkSizeBytes = 8)

        val (_, bytes) = readAll(source, spec(base))

        assertArrayEquals(media.copyOf(16), bytes)
        assertEquals(listOf("0-7", "8-15", "16-23"), server.ranges)
    }

    @Test
    fun seekWithinKnownLength_requestsOnlyTheRemainingWindow() {
        val server = FakeServer(media)
        val source = YouTubeMediaHttpDataSource(server, YouTubeRequestProfile.PROGRESSIVE, chunkSizeBytes = 8)

        val (opened, bytes) = readAll(source, spec("$base&clen=20", position = 5, length = 6))

        assertEquals(6L, opened)
        assertArrayEquals(media.copyOfRange(5, 11), bytes)
        assertEquals(listOf("5-10"), server.ranges)
    }

    @Test
    fun truncatedChunk_resumesFromCurrentOffset() {
        val server = FakeServer(media, truncateFirstResponseAt = 3)
        val source = YouTubeMediaHttpDataSource(server, YouTubeRequestProfile.PROGRESSIVE, chunkSizeBytes = 8)

        val (_, bytes) = readAll(source, spec("$base&clen=20"))

        assertArrayEquals(media, bytes)
        assertEquals(listOf("0-7", "3-10", "11-18", "19-19"), server.ranges)
    }

    @Test
    fun chunkedTransferWithoutLength_readsUntilServerEnds() {
        val server = FakeServer(media, reportLength = false)
        val source = YouTubeMediaHttpDataSource(server, YouTubeRequestProfile.PROGRESSIVE, chunkSizeBytes = 8)

        val (_, bytes) = readAll(source, spec("$base&clen=20"))

        assertArrayEquals(media, bytes)
    }

    @Test
    fun dashProfile_isNotChunked() {
        val server = FakeServer(media)
        val source = YouTubeMediaHttpDataSource(server, YouTubeRequestProfile.DASH, chunkSizeBytes = 8)

        readAll(source, spec("$base&clen=20", position = 0, length = 20))

        assertEquals(listOf("0-19"), server.ranges)
        assertFalse(server.ranges.size > 1)
    }

    @Test
    fun readAhead_servesLaterChunksFromPrefetchedConnections() {
        val delegate = FakeServer(media)
        val prefetched = mutableListOf<FakeServer>()
        val factory = object : HttpDataSource.Factory {
            override fun createDataSource() = FakeServer(media).also { prefetched += it }
            override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>) = this
        }
        val source = YouTubeMediaHttpDataSource(
            delegate,
            YouTubeRequestProfile.PROGRESSIVE,
            chunkSizeBytes = 8,
            readAheadFactory = factory
        )

        val (opened, bytes) = readAll(source, spec("$base&clen=20"))

        assertEquals(20L, opened)
        assertArrayEquals(media, bytes)
        assertEquals(listOf("0-7"), delegate.ranges)
        assertEquals(listOf("8-15", "16-19"), prefetched.flatMap { it.ranges })
    }

    @Test
    fun readAheadFailure_fallsBackToSynchronousOpen() {
        val delegate = FakeServer(media)
        // Every prefetched request 416s (empty media), so adoption fails at each boundary.
        val factory = object : HttpDataSource.Factory {
            override fun createDataSource() = FakeServer(ByteArray(0))
            override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>) = this
        }
        val source = YouTubeMediaHttpDataSource(
            delegate,
            YouTubeRequestProfile.PROGRESSIVE,
            chunkSizeBytes = 8,
            readAheadFactory = factory
        )

        val (opened, bytes) = readAll(source, spec("$base&clen=20"))

        assertEquals(20L, opened)
        assertArrayEquals(media, bytes)
        assertEquals(listOf("0-7", "8-15", "16-19"), delegate.ranges)
    }
}
