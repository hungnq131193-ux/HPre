package com.hpre.app.repository

import com.hpre.app.core.error.AppError
import com.hpre.app.core.error.AppResult
import com.hpre.app.model.Channel
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonReader.Token
import com.squareup.moshi.JsonWriter
import kotlinx.coroutines.flow.first
import okio.Buffer

data class BackupImportSummary(
    val historyCount: Int,
    val playlistCount: Int,
    val playlistEntryCount: Int,
    val subscriptionCount: Int
)

/**
 * User-data backup: watch history, local playlists and subscriptions serialized as JSON for
 * Storage Access Framework export/import. Import merges into existing data (upsert by key);
 * malformed files and unsupported versions fail with a Failure instead of writing partial state.
 */
class BackupManager(
    private val historyRepository: HistoryRepository,
    private val playlistRepository: PlaylistRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun exportJson(): AppResult<String> {
        return try {
            val buffer = Buffer()
            JsonWriter.of(buffer).use { writer ->
                writer.beginObject()
                writer.name("app").value("hpre")
                writer.name("version").value(FORMAT_VERSION)
                writer.name("exportedAt").value(clock())
                writer.name("history").beginArray()
                for (item in historyRepository.observeHistory().first()) {
                    writeVideo(writer, item.toVideoSummary()) {
                        name("positionMs").value(item.playbackPositionMs)
                        name("watchedAt").value(item.watchedTimestamp)
                    }
                }
                writer.endArray()
                writer.name("playlists").beginArray()
                for (list in collectPlaylists()) {
                    writer.beginObject()
                    writer.name("title").value(list.playlist.title)
                    writer.name("createdAt").value(list.playlist.createdTimestamp)
                    writer.name("entries").beginArray()
                    for (entry in list.entries) {
                        writeVideo(writer, entry.toVideoSummary()) {
                            name("addedAt").value(entry.addedTimestamp)
                            name("sortOrder").value(entry.sortOrder)
                        }
                    }
                    writer.endArray()
                    writer.endObject()
                }
                writer.endArray()
                writer.name("subscriptions").beginArray()
                for (sub in subscriptionRepository.observeSubscriptions().first()) {
                    writer.beginObject()
                    writer.name("channelServiceId").value(sub.channelKey.serviceId)
                    writer.name("channelNativeId").value(sub.channelKey.nativeId)
                    writer.name("url").value(sub.canonicalUrl)
                    writer.name("name").value(sub.name)
                    sub.avatarUrl?.let { writer.name("avatar").value(it) }
                    writer.name("subscribedAt").value(sub.subscribedTimestamp)
                    writer.endObject()
                }
                writer.endArray()
                writer.endObject()
            }
            AppResult.Success(buffer.readUtf8())
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppResult.Failure(AppError.Unknown)
        }
    }

    suspend fun importJson(json: String): AppResult<BackupImportSummary> {
        val data = try {
            parse(json)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return AppResult.Failure(AppError.UnsupportedFormat)
        } ?: return AppResult.Failure(AppError.UnsupportedFormat)

        var playlistEntries = 0
        try {
            for (item in data.history) {
                historyRepository.recordHistory(item.video, item.positionMs, item.watchedTimestamp)
            }
            for (sub in data.subscriptions) {
                subscriptionRepository.subscribe(sub.channel, sub.subscribedTimestamp)
            }
            for (playlist in data.playlists) {
                val id = (playlistRepository.createPlaylist(playlist.title, playlist.createdTimestamp) as?
                    AppResult.Success)?.value ?: continue
                for (entry in playlist.entries) {
                    if (playlistRepository.addEntry(id, entry.video, entry.addedTimestamp) is AppResult.Success) {
                        playlistEntries++
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return AppResult.Failure(AppError.Unknown)
        }
        return AppResult.Success(
            BackupImportSummary(
                historyCount = data.history.size,
                playlistCount = data.playlists.size,
                playlistEntryCount = playlistEntries,
                subscriptionCount = data.subscriptions.size
            )
        )
    }

    private suspend fun collectPlaylists(): List<LocalPlaylistWithEntries> =
        playlistRepository.observePlaylists().first().mapNotNull {
            playlistRepository.observePlaylistWithEntries(it.playlistId).first()
        }

    private fun writeVideo(
        writer: JsonWriter,
        video: VideoSummary,
        extras: JsonWriter.() -> Unit = {}
    ) {
        writer.beginObject()
        writer.name("serviceId").value(video.key.serviceId)
        writer.name("nativeId").value(video.key.nativeId)
        writer.name("url").value(video.canonicalUrl)
        writer.name("title").value(video.title)
        video.channelKey?.let { key ->
            writer.name("channelServiceId").value(key.serviceId)
            writer.name("channelNativeId").value(key.nativeId)
        }
        video.channelName?.let { writer.name("channelName").value(it) }
        video.thumbnailUrl?.let { writer.name("thumbnail").value(it) }
        video.durationSeconds?.let { writer.name("durationSeconds").value(it) }
        writer.extras()
        writer.endObject()
    }

    private fun WatchHistoryItem.toVideoSummary() = VideoSummary(
        key = key,
        title = title,
        canonicalUrl = canonicalUrl,
        channelKey = channelKey,
        channelName = channelName,
        channelAvatarUrl = null,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = null,
        publishedTimestamp = null
    )

    private fun LocalPlaylistEntry.toVideoSummary() = VideoSummary(
        key = videoKey,
        title = title,
        canonicalUrl = canonicalUrl,
        channelKey = channelKey,
        channelName = channelName,
        channelAvatarUrl = null,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = null,
        publishedTimestamp = null
    )

    internal data class ParsedHistory(val video: VideoSummary, val positionMs: Long, val watchedTimestamp: Long)
    internal data class ParsedEntry(val video: VideoSummary, val addedTimestamp: Long, val sortOrder: Int)
    internal data class ParsedPlaylist(val title: String, val createdTimestamp: Long, val entries: List<ParsedEntry>)
    internal data class ParsedSubscription(val channel: Channel, val subscribedTimestamp: Long)
    internal data class ParsedBackup(
        val history: List<ParsedHistory>,
        val playlists: List<ParsedPlaylist>,
        val subscriptions: List<ParsedSubscription>
    )

    companion object {
        const val FORMAT_VERSION = 1

        internal fun parse(json: String): ParsedBackup? {
            val reader = JsonReader.of(Buffer().writeUtf8(json))
            var version = 0
            var app: String? = null
            val history = mutableListOf<ParsedHistory>()
            val playlists = mutableListOf<ParsedPlaylist>()
            val subscriptions = mutableListOf<ParsedSubscription>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "app" -> app = reader.nextString()
                    "version" -> version = reader.nextInt()
                    "history" -> readEntries(reader) { video, extra ->
                        history += ParsedHistory(
                            video,
                            positionMs = extra["positionMs"] ?: 0L,
                            watchedTimestamp = extra["watchedAt"] ?: 0L
                        )
                    }
                    "playlists" -> readPlaylists(reader, playlists)
                    "subscriptions" -> readSubscriptions(reader, subscriptions)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (app != "hpre" || version != FORMAT_VERSION) return null
            return ParsedBackup(history, playlists, subscriptions)
        }

        private fun readPlaylists(reader: JsonReader, out: MutableList<ParsedPlaylist>) {
            if (reader.peek() == Token.NULL) { reader.skipValue(); return }
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == Token.NULL) { reader.skipValue(); continue }
                var title: String? = null
                var createdAt = 0L
                val entries = mutableListOf<ParsedEntry>()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "title" -> title = reader.nextString()
                        "createdAt" -> createdAt = reader.nextLong()
                        "entries" -> readEntries(reader) { video, extra ->
                            entries += ParsedEntry(
                                video,
                                addedTimestamp = extra["addedAt"] ?: 0L,
                                sortOrder = (extra["sortOrder"] ?: 0L).toInt()
                            )
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (!title.isNullOrBlank()) out += ParsedPlaylist(title, createdAt, entries)
            }
            reader.endArray()
        }

        private fun readSubscriptions(reader: JsonReader, out: MutableList<ParsedSubscription>) {
            if (reader.peek() == Token.NULL) { reader.skipValue(); return }
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == Token.NULL) { reader.skipValue(); continue }
                var sid: Int? = null
                var nid: String? = null
                var url = ""
                var name = ""
                var avatar: String? = null
                var subscribedAt = 0L
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "channelServiceId" -> sid = reader.nextInt()
                        "channelNativeId" -> nid = reader.nextString()
                        "url" -> url = reader.nextString()
                        "name" -> name = reader.nextString()
                        "avatar" -> avatar = reader.nextString()
                        "subscribedAt" -> subscribedAt = reader.nextLong()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (sid != null && !nid.isNullOrBlank()) {
                    out += ParsedSubscription(
                        channel = Channel(
                            key = ContentKey(sid, nid),
                            name = name,
                            canonicalUrl = url,
                            avatarUrl = avatar,
                            bannerUrl = null,
                            subscriberCountText = null,
                            description = null
                        ),
                        subscribedTimestamp = subscribedAt
                    )
                }
            }
            reader.endArray()
        }

        /**
         * Reads an array of video objects, collecting any extra numeric fields (positionMs,
         * watchedAt, addedAt, sortOrder) into the extras map handed to [emit].
         */
        private fun readEntries(reader: JsonReader, emit: (VideoSummary, Map<String, Long>) -> Unit) {
            if (reader.peek() == Token.NULL) { reader.skipValue(); return }
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == Token.NULL) { reader.skipValue(); continue }
                var sid: Int? = null
                var nid: String? = null
                var url = ""
                var title = ""
                var channelSid: Int? = null
                var channelNid: String? = null
                var channelName: String? = null
                var thumbnail: String? = null
                var durationSeconds: Long? = null
                val extra = HashMap<String, Long>()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (val field = reader.nextName()) {
                        "serviceId" -> sid = reader.nextInt()
                        "nativeId" -> nid = reader.nextString()
                        "url" -> url = reader.nextString()
                        "title" -> title = reader.nextString()
                        "channelServiceId" -> channelSid = reader.nextInt()
                        "channelNativeId" -> channelNid = reader.nextString()
                        "channelName" -> channelName = reader.nextString()
                        "thumbnail" -> thumbnail = reader.nextString()
                        "durationSeconds" -> durationSeconds = reader.nextLong()
                        "positionMs", "watchedAt", "addedAt", "sortOrder" ->
                            if (reader.peek() == Token.NUMBER) extra[field] = reader.nextLong() else reader.skipValue()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (sid != null && !nid.isNullOrBlank()) {
                    emit(
                        VideoSummary(
                            key = ContentKey(sid, nid),
                            title = title,
                            canonicalUrl = url,
                            channelKey = if (channelSid != null && !channelNid.isNullOrBlank()) {
                                ContentKey(channelSid, channelNid)
                            } else {
                                null
                            },
                            channelName = channelName,
                            channelAvatarUrl = null,
                            thumbnailUrl = thumbnail,
                            durationSeconds = durationSeconds,
                            viewCount = null,
                            publishedTimestamp = null
                        ),
                        extra
                    )
                }
            }
            reader.endArray()
        }
    }
}
