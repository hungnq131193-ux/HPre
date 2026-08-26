package com.hpre.app.testing

import com.hpre.app.HPreApplication
import com.hpre.app.di.AppContainer
import com.hpre.app.di.DefaultAppContainer

/**
 * Dedicated test application that uses the real production AppContainer path.
 * Selected by HPreTestRunner when hpreLivePlayback=true.
 */
class LivePlaybackTestApplication : HPreApplication() {
    override fun createContainer(): AppContainer {
        return DefaultAppContainer(this)
    }
}
