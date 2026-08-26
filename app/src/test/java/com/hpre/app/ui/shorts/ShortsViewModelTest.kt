package com.hpre.app.ui.shorts

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.ContentKey
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoSummary
import com.hpre.app.player.PlaybackState
import com.hpre.app.player.PlayerController
import com.hpre.app.player.QualityOption
import com.hpre.app.repository.ShortsFeedSource
import com.hpre.app.repository.LocalPlaylist
import com.hpre.app.repository.LocalPlaylistWithEntries
import com.hpre.app.repository.PlaylistRepository
import com.hpre.app.testing.FakeVideoService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShortsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun load_maps_feed_content_empty_and_error() = runTest(dispatcher) {
        val item = video("short")
        val source = MutableShortsSource(AppResult.Success(listOf(item)))
        val model = ShortsViewModel(source, FakeVideoService(), FakePlayer(), FakePlaylists(), dispatcher)
        model.load()
        advanceUntilIdle()
        assertEquals(listOf(item), (model.state.value as ShortsUiState.Content).videos)

        source.result = AppResult.Success(emptyList())
        model.retry()
        advanceUntilIdle()
        assertEquals(ShortsUiState.Empty, model.state.value)

        source.result = AppResult.Failure(AppError.NetworkError)
        model.retry()
        advanceUntilIdle()
        assertEquals(ShortsUiState.Error(AppError.NetworkError), model.state.value)
    }

    @Test fun stale_activation_cannot_prepare_previous_page() = runTest(dispatcher) {
        val first = video("first")
        val second = video("second")
        val firstStream = CompletableDeferred<AppResult<StreamInfo>>()
        val service = FakeVideoService(streamInfoHandler = { key ->
            if (key == first.key) withContext(NonCancellable) { firstStream.await() }
            else AppResult.Success(StreamInfo(key, "second"))
        })
        val player = FakePlayer()
        val model = ShortsViewModel(
            MutableShortsSource(AppResult.Success(listOf(first, second))), service, player, FakePlaylists(), dispatcher
        )

        model.activate(first)
        runCurrent()
        model.activate(second)
        runCurrent()
        firstStream.complete(AppResult.Success(StreamInfo(first.key, "first")))
        advanceUntilIdle()

        assertEquals(listOf(second.key), player.preparedKeys)
    }

    @Test fun unsupported_feed_maps_to_unavailable_and_save_failure_is_exposed() = runTest(dispatcher) {
        val playlists = FakePlaylists(failAdd = true)
        val item = video("save")
        val source = MutableShortsSource(AppResult.Failure(AppError.UnsupportedFormat))
        val model = ShortsViewModel(source, FakeVideoService(), FakePlayer(), playlists, dispatcher)

        model.load()
        advanceUntilIdle()
        assertEquals(ShortsUiState.Unavailable, model.state.value)

        source.result = AppResult.Success(listOf(item))
        model.load()
        advanceUntilIdle()
        model.save(item)
        advanceUntilIdle()
        assertEquals(AppError.Unknown, model.saveErrors.value[item.key])
    }

    @Test fun later_successful_save_does_not_clear_previous_video_failure() = runTest(dispatcher) {
        val first = video("first_save")
        val second = video("second_save")
        val playlists = FakePlaylists(failKeys = setOf(first.key))
        val model = ShortsViewModel(
            MutableShortsSource(AppResult.Success(listOf(first, second))),
            FakeVideoService(), FakePlayer(), playlists, dispatcher
        )

        model.save(first)
        model.save(second)
        advanceUntilIdle()

        assertEquals(AppError.Unknown, model.saveErrors.value[first.key])
        assertEquals(null, model.saveErrors.value[second.key])
    }

    @Test fun stream_unsupported_maps_to_unavailable_and_exception_maps_to_error() = runTest(dispatcher) {
        val item = video("stream")
        val service = FakeVideoService(
            streamInfoHandler = { AppResult.Failure(AppError.UnsupportedFormat) }
        )
        val model = ShortsViewModel(
            MutableShortsSource(AppResult.Success(listOf(item))), service,
            FakePlayer(), FakePlaylists(), dispatcher
        )
        model.activate(item)
        advanceUntilIdle()
        assertEquals(ShortsUiState.Unavailable, model.state.value)

        service.streamInfoHandler = { throw IllegalStateException("boom") }
        model.activate(item)
        advanceUntilIdle()
        assertEquals(ShortsUiState.Error(AppError.Unknown), model.state.value)
    }

    private class MutableShortsSource(var result: AppResult<List<VideoSummary>>) : ShortsFeedSource {
        override suspend fun load(forceRefresh: Boolean) = result
    }

    private class FakePlayer : PlayerController {
        override val state = MutableStateFlow(PlaybackState())
        val preparedKeys = mutableListOf<ContentKey>()
        override fun prepare(key: ContentKey, streamInfo: StreamInfo, startPositionMs: Long, playWhenReady: Boolean, initialQuality: QualityOption?) { preparedKeys += key }
        override fun attachSurface(playerView: androidx.media3.ui.PlayerView) = Unit
        override fun detachSurface(playerView: androidx.media3.ui.PlayerView) = Unit
        override fun onLifecycleStart() = Unit
        override fun onLifecycleStop() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun playPause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun selectQuality(quality: QualityOption) = Unit
        override fun release() = Unit
    }

    private class FakePlaylists(
        private val failAdd: Boolean = false,
        private val failKeys: Set<ContentKey> = emptySet()
    ) : PlaylistRepository {
        override fun observePlaylists(): Flow<List<LocalPlaylist>> = flowOf(emptyList())
        override fun observePlaylistWithEntries(playlistId: Long): Flow<LocalPlaylistWithEntries?> = flowOf(null)
        override suspend fun getPlaylist(playlistId: Long) = AppResult.Success(null)
        override suspend fun createPlaylist(title: String, timestamp: Long) = AppResult.Success(1L)
        override suspend fun renamePlaylist(playlistId: Long, newTitle: String, timestamp: Long) = AppResult.Success(Unit)
        override suspend fun deletePlaylist(playlistId: Long) = AppResult.Success(Unit)
        override suspend fun addEntry(playlistId: Long, video: VideoSummary, addedTimestamp: Long) =
            if (failAdd || video.key in failKeys) AppResult.Failure(AppError.Unknown)
            else AppResult.Success(Unit)
        override suspend fun removeEntry(playlistId: Long, videoKey: ContentKey, updatedTimestamp: Long) = AppResult.Success(Unit)
        override suspend fun reorderEntries(playlistId: Long, fromIndex: Int, toIndex: Int, updatedTimestamp: Long) = AppResult.Success(Unit)
    }

    private fun video(id: String) = VideoSummary(
        key = ContentKey(0, id), title = id, canonicalUrl = "https://example.test/$id",
        channelKey = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
        durationSeconds = 10, viewCount = null, publishedTimestamp = null
    )
}
