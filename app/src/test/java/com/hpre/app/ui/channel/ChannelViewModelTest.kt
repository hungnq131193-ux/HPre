package com.hpre.app.ui.channel

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.ContentKey
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun load_exposes_provider_channel_content() = runTest(dispatcher) {
        val key = ContentKey(0, "channel")
        val details = ChannelDetails(
            channel = Channel(key, "Creator", "https://example.test/c", null, null, null, null)
        )
        val service = FakeVideoService(channelHandler = { AppResult.Success(details) })
        val model = ChannelViewModel(service, dispatcher)

        model.load(key)
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state is ChannelUiState.Content)
        assertEquals(details, (state as ChannelUiState.Content).details)
    }
}
