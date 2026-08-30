package com.hpre.app.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
class YouTubeMediaHttpDataSource(
    private val delegate: HttpDataSource,
    private val profile: YouTubeRequestProfile
) : HttpDataSource {

    private val requestCounter = AtomicInteger(0)

    override fun open(dataSpec: DataSpec): Long {
        val transformed = YouTubeRequestPolicy.transformDataSpec(
            dataSpec = dataSpec,
            profile = profile,
            requestNumber = requestCounter.getAndIncrement()
        )

        val newSpecBuilder = dataSpec.buildUpon()
            .setUri(transformed.uri)
            .setHttpRequestHeaders(transformed.headers)

        if (transformed.isEligibleYouTube) {
            newSpecBuilder
                .setHttpMethod(transformed.httpMethod)
                .setHttpBody(transformed.httpBody)
        }

        return delegate.open(newSpecBuilder.build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.read(buffer, offset, length)
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
}
