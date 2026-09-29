package com.hpre.app.repository

import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackupManagerTest {

    private class FakeHistoryRepo : HistoryRepository {
        val items = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
        override fun observeHistory(): Flow<List<WatchHistoryItem>> = items
        override suspend fun getHistoryItem(key: ContentKey) =
            AppResult.Success(items.value.firstOrNull { it.key == key })
        override suspend fun recordHistory(
            summary: VideoSummary,
            positionMs: Long,
            watchedTimestamp: Long
        ): AppResult<Unit> {
            items.value = items.value.filterNot { it.key == summary.key } + WatchHistoryItem(
                key = summary.key,
                canonicalUrl = summary.canonicalUrl,
                title = summary.title,
                channelKey = summary.channelKey,
                channelName = summary.channelName,
                thumbnailUrl = summary.thumbnailUrl,
                durationSeconds = summary.durationSeconds,
                playbackPositionMs = positionMs,
                watchedTimestamp = watchedTimestamp
            )
            return AppResult.Success(Unit)
        }
        override suspend fun deleteHistoryItem(key: ContentKey): AppResult<Unit> {
            items.value = items.value.filterNot { it.key == key }
            return AppResult.Success(Unit)
        }
        override suspend fun clearHistory(): AppResult<Unit> {
            items.value = emptyList()
            return AppResult.Success(Unit)
        }
    }

    private class FakePlaylistRepo : PlaylistRepository {
        private var nextId = 1L
        val playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
        val entries = MutableStateFlow<Map<Long, List<LocalPlaylistEntry>>>(emptyMap())

        override fun observePlaylists(): Flow<List<LocalPlaylist>> = playlists
        override fun observePlaylistWithEntries(playlistId: Long) =
            kotlinx.coroutines.flow.combine(playlists, entries) { ps, es ->
                ps.firstOrNull { it.playlistId == playlistId }
                    ?.let { LocalPlaylistWithEntries(it, es[it.playlistId].orEmpty()) }
            }
        override suspend fun getPlaylist(playlistId: Long) =
            AppResult.Success(playlists.value.firstOrNull { it.playlistId == playlistId })
        override suspend fun createPlaylist(title: String, timestamp: Long): AppResult<Long> {
            val id = nextId++
            playlists.value = playlists.value + LocalPlaylist(id, title, timestamp, timestamp)
            return AppResult.Success(id)
        }
        override suspend fun renamePlaylist(playlistId: Long, newTitle: String, timestamp: Long) =
            AppResult.Success(Unit)
        override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> {
            playlists.value = playlists.value.filterNot { it.playlistId == playlistId }
            return AppResult.Success(Unit)
        }
        override suspend fun addEntry(
            playlistId: Long,
            video: VideoSummary,
            addedTimestamp: Long
        ): AppResult<Unit> {
            val current = entries.value[playlistId].orEmpty()
            entries.value = entries.value + (playlistId to current + LocalPlaylistEntry(
                playlistId = playlistId,
                videoKey = video.key,
                canonicalUrl = video.canonicalUrl,
                title = video.title,
                channelKey = video.channelKey,
                channelName = video.channelName,
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds,
                addedTimestamp = addedTimestamp,
                sortOrder = current.size
            ))
            return AppResult.Success(Unit)
        }
        override suspend fun removeEntry(playlistId: Long, videoKey: ContentKey, updatedTimestamp: Long) =
            AppResult.Success(Unit)
        override suspend fun reorderEntries(playlistId: Long, fromIndex: Int, toIndex: Int, updatedTimestamp: Long) =
            AppResult.Success(Unit)
    }

    private class FakeSubscriptionRepo : SubscriptionRepository {
        val subs = MutableStateFlow<List<LocalSubscription>>(emptyList())
        override fun observeSubscriptions(): Flow<List<LocalSubscription>> = subs
        override fun observeIsSubscribed(key: ContentKey) = subs.map { list -> list.any { it.channelKey == key } }
        override suspend fun isSubscribed(key: ContentKey) =
            AppResult.Success(subs.value.any { it.channelKey == key })
        override suspend fun subscribe(channel: Channel, subscribedTimestamp: Long): AppResult<Unit> {
            subs.value = subs.value.filterNot { it.channelKey == channel.key } + LocalSubscription(
                channelKey = channel.key,
                canonicalUrl = channel.canonicalUrl,
                name = channel.name,
                avatarUrl = channel.avatarUrl,
                subscribedTimestamp = subscribedTimestamp
            )
            return AppResult.Success(Unit)
        }
        override suspend fun unsubscribe(key: ContentKey): AppResult<Unit> {
            subs.value = subs.value.filterNot { it.channelKey == key }
            return AppResult.Success(Unit)
        }
        override suspend fun clearSubscriptions(): AppResult<Unit> {
            subs.value = emptyList()
            return AppResult.Success(Unit)
        }
    }

    private fun video(id: String, title: String = "T$id") = VideoSummary(
        key = ContentKey(0, id),
        title = title,
        canonicalUrl = "https://youtu.be/$id",
        channelKey = ContentKey(0, "ch$id"),
        channelName = "Chan$id",
        channelAvatarUrl = null,
        thumbnailUrl = "https://img/$id.jpg",
        durationSeconds = 100L,
        viewCount = null,
        publishedTimestamp = null
    )

    @Test
    fun export_then_import_round_trips_all_sections() = runTest {
        val history = FakeHistoryRepo()
        val playlists = FakePlaylistRepo()
        val subs = FakeSubscriptionRepo()
        history.recordHistory(video("a"), positionMs = 12_000L, watchedTimestamp = 111L)
        val pid = (playlists.createPlaylist("Fav", 55L) as AppResult.Success).value
        playlists.addEntry(pid, video("b"), addedTimestamp = 77L)
        subs.subscribe(
            Channel(ContentKey(0, "chan1"), "Chan One", "https://yt/chan1", "https://a/1.png", null, null, null),
            99L
        )

        val manager = BackupManager(history, playlists, subs) { 4242L }
        val json = (manager.exportJson() as AppResult.Success).value

        // Import into empty repos
        val history2 = FakeHistoryRepo()
        val playlists2 = FakePlaylistRepo()
        val subs2 = FakeSubscriptionRepo()
        val summary = (BackupManager(history2, playlists2, subs2).importJson(json) as AppResult.Success).value

        assertEquals(1, summary.historyCount)
        assertEquals(1, summary.playlistCount)
        assertEquals(1, summary.playlistEntryCount)
        assertEquals(1, summary.subscriptionCount)

        val restoredHistory = history2.observeHistory().first()
        assertEquals("a", restoredHistory.single().key.nativeId)
        assertEquals(12_000L, restoredHistory.single().playbackPositionMs)
        assertEquals(111L, restoredHistory.single().watchedTimestamp)

        val restoredPlaylist = playlists2.observePlaylistWithEntries(
            playlists2.playlists.value.single().playlistId
        ).first()!!
        assertEquals("Fav", restoredPlaylist.playlist.title)
        assertEquals("b", restoredPlaylist.entries.single().videoKey.nativeId)
        assertEquals(77L, restoredPlaylist.entries.single().addedTimestamp)

        assertEquals("chan1", subs2.subs.value.single().channelKey.nativeId)
        assertEquals(99L, subs2.subs.value.single().subscribedTimestamp)
    }

    @Test
    fun malformed_json_fails_without_partial_writes() = runTest {
        val history = FakeHistoryRepo()
        val manager = BackupManager(history, FakePlaylistRepo(), FakeSubscriptionRepo())
        val result = manager.importJson("{not json")
        assertTrue(result is AppResult.Failure)
        assertTrue(history.items.value.isEmpty())
    }

    @Test
    fun wrong_version_rejected() = runTest {
        val manager = BackupManager(FakeHistoryRepo(), FakePlaylistRepo(), FakeSubscriptionRepo())
        val result = manager.importJson("""{"app":"hpre","version":99,"history":[],"playlists":[],"subscriptions":[]}""")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun foreign_file_rejected() = runTest {
        val manager = BackupManager(FakeHistoryRepo(), FakePlaylistRepo(), FakeSubscriptionRepo())
        val result = manager.importJson("""{"app":"other","version":1}""")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun missing_arrays_default_to_empty() = runTest {
        val manager = BackupManager(FakeHistoryRepo(), FakePlaylistRepo(), FakeSubscriptionRepo())
        val result = manager.importJson("""{"app":"hpre","version":1}""")
        assertTrue(result is AppResult.Success)
    }

    @Test
    fun entries_with_invalid_keys_are_skipped() = runTest {
        val playlists = FakePlaylistRepo()
        val manager = BackupManager(FakeHistoryRepo(), playlists, FakeSubscriptionRepo())
        val json = """{"app":"hpre","version":1,"history":[{"serviceId":0},{"nativeId":"x","serviceId":0,"url":"u","title":"t","positionMs":5,"watchedAt":9}],"playlists":[],"subscriptions":[]}"""
        val summary = (manager.importJson(json) as AppResult.Success).value
        assertEquals(1, summary.historyCount)
    }
}
