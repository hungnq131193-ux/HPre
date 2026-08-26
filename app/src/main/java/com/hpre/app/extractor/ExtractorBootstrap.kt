package com.hpre.app.extractor

import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.atomic.AtomicBoolean

object ExtractorBootstrap {
    private val initialized = AtomicBoolean(false)
    private val lock = Any()

    fun isInitialized(): Boolean = initialized.get()

    fun init(downloader: OkHttpDownloader = OkHttpDownloader()) {
        if (initialized.get()) return
        synchronized(lock) {
            if (!initialized.get()) {
                // Request Vietnamese metadata and VN content region so provider-side
                // feeds (trending kiosk, search) are localized for Vietnam.
                NewPipe.init(
                    downloader,
                    ExtractorLocalization.LOCALIZATION,
                    ExtractorLocalization.CONTENT_COUNTRY
                )
                initialized.set(true)
            }
        }
    }

    internal fun initForTesting(initializer: () -> Unit) {
        if (initialized.get()) return
        synchronized(lock) {
            if (!initialized.get()) {
                initializer()
                initialized.set(true)
            }
        }
    }
}


