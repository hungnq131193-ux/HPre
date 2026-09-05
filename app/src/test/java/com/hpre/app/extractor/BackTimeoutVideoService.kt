package com.hpre.app.extractor

import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.repository.VideoService
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.downloader.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class BackTimeoutVideoService(
    oldKey: ContentKey,
    newKey: ContentKey,
    newDetails: VideoDetails,
    newStream: StreamInfo,
    serverUrl: String,
    val oldCallStarted: CountDownLatch = CountDownLatch(1),
    val oldTimeoutFinished: CountDownLatch = CountDownLatch(1),
    val newExtractionStarted: CountDownLatch = CountDownLatch(1)
) : VideoService by NewPipeVideoService(
    ioDispatcher = ExtractorDispatcher.IO,
    operations = object : ExtractorOperations by DefaultExtractorOperations() {
        private val downloader = OkHttpDownloader(
            OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
        )

        override fun videoBundle(key: ContentKey): ExtractedVideoBundle {
            if (key == newKey) {
                newExtractionStarted.countDown()
                return ExtractedVideoBundle(newDetails, newStream, emptyList())
            }
            check(key == oldKey)
            oldCallStarted.countDown()
            try {
                downloader.execute(Request.newBuilder().url("$serverUrl/cancelled-first-call").httpMethod("GET").build())
            } catch (_: java.io.IOException) {
                Thread.interrupted()
                try {
                    downloader.execute(Request.newBuilder().url("$serverUrl/late-timeout").httpMethod("GET").build())
                } finally {
                    oldTimeoutFinished.countDown()
                }
            }
            error("Old extractor request must not return a bundle")
        }
    }
) {
    init {
        ExtractorBootstrap.init(OkHttpDownloader())
    }
}
