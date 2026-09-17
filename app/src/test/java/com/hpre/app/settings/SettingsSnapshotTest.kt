package com.hpre.app.settings

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsSnapshotTest {
    @Test
    fun failed_source_releases_readiness_with_defaults() = runTest {
        val snapshot = flow<AppSettings> { throw java.io.IOException("unreadable settings") }
            .shareAppSettings(backgroundScope, StandardTestDispatcher(testScheduler))
        val reader = backgroundScope.launch { assertEquals(AppSettings(), snapshot.awaitValue()) }
        runCurrent()
        org.junit.Assert.assertTrue(reader.isCompleted)
    }

    @Test
    fun empty_source_releases_readiness_with_defaults() = runTest {
        val snapshot = kotlinx.coroutines.flow.emptyFlow<AppSettings>()
            .shareAppSettings(backgroundScope, StandardTestDispatcher(testScheduler))
        val reader = backgroundScope.launch { assertEquals(AppSettings(), snapshot.awaitValue()) }
        runCurrent()
        org.junit.Assert.assertTrue(reader.isCompleted)
    }
    @Test
    fun shared_snapshot_collects_the_repository_flow_once_for_all_readers() = runTest {
        var collections = 0
        val source = flow {
            collections++
            emit(AppSettings(defaultPlaybackSpeed = 1.5f))
            awaitCancellation()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)

        val snapshot = source.shareAppSettings(backgroundScope, dispatcher)
        runCurrent()

        assertEquals(1, collections)
        assertEquals(1.5f, snapshot.value.defaultPlaybackSpeed, 0.001f)
        assertEquals(snapshot.value, snapshot.value)
        assertEquals(1, collections)
    }
}
