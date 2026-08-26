package com.flowtube.app.testing

import com.flowtube.app.FlowTubeApplication
import com.flowtube.app.di.AppContainer
import com.flowtube.app.di.DefaultAppContainer

/**
 * Dedicated test application that uses the real production AppContainer path.
 * Selected by FlowTubeTestRunner when flowtubeLivePlayback=true.
 */
class LivePlaybackTestApplication : FlowTubeApplication() {
    override fun createContainer(): AppContainer {
        return DefaultAppContainer(this)
    }
}
