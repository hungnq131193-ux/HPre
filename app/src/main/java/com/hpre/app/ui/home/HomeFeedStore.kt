package com.hpre.app.ui.home

import android.content.Context
import com.hpre.app.model.ContentKey
import com.hpre.app.model.VideoSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Small disk-backed snapshot used only to paint Home before the network returns. */
class HomeFeedStore internal constructor(
    private val readEncoded: (String) -> String?,
    private val writeEncoded: (String, String) -> Unit,
    private val removeEncoded: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this(
        readEncoded = { cacheKey ->
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(storageKey(cacheKey), null)
        },
        writeEncoded = { cacheKey, value ->
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(storageKey(cacheKey), value)
                .apply()
        },
        removeEncoded = { cacheKey ->
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(storageKey(cacheKey))
                .apply()
        },
        ioDispatcher = Dispatchers.IO
    )

    suspend fun load(cacheKey: String): List<VideoSummary>? = withContext(ioDispatcher) {
        runCatching { decode(readEncoded(cacheKey) ?: return@withContext null) }.getOrNull()
    }

    suspend fun save(cacheKey: String, videos: List<VideoSummary>) = withContext(ioDispatcher) {
        if (videos.isEmpty()) {
            runCatching { removeEncoded(cacheKey) }
        } else {
            runCatching { writeEncoded(cacheKey, encode(videos)) }
        }
        Unit
    }

    suspend fun remove(cacheKey: String) = withContext(ioDispatcher) {
        runCatching { removeEncoded(cacheKey) }
        Unit
    }

    private fun decode(encoded: String): List<VideoSummary>? {
        if (encoded.length > MAX_ENCODED_BYTES) return null
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
        if (input.readInt() != FORMAT_VERSION) return null
        val savedAtMs = input.readLong()
        val ageMs = nowMs() - savedAtMs
        if (ageMs < 0L || ageMs > maxAgeMs) return null
        val count = input.readInt()
        if (count !in 1..MAX_ITEMS) return null
        return List(count) { input.readVideoSummary() }
    }

    private fun encode(videos: List<VideoSummary>): String {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeLong(nowMs())
                val retained = videos.take(MAX_ITEMS)
                output.writeInt(retained.size)
                retained.forEach { output.writeVideoSummary(it) }
            }
            buffer.toByteArray()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun DataOutputStream.writeVideoSummary(video: VideoSummary) {
        writeContentKey(video.key)
        writeUTF(video.title)
        writeUTF(video.canonicalUrl)
        writeNullableContentKey(video.channelKey)
        writeNullableString(video.channelName)
        writeNullableString(video.channelAvatarUrl)
        writeNullableString(video.thumbnailUrl)
        writeNullableLong(video.durationSeconds)
        writeNullableLong(video.viewCount)
        writeNullableLong(video.publishedTimestamp)
        writeBoolean(video.isLive)
        writeBoolean(video.isShort)
    }

    private fun DataInputStream.readVideoSummary(): VideoSummary = VideoSummary(
        key = readContentKey(),
        title = readUTF(),
        canonicalUrl = readUTF(),
        channelKey = readNullableContentKey(),
        channelName = readNullableString(),
        channelAvatarUrl = readNullableString(),
        thumbnailUrl = readNullableString(),
        durationSeconds = readNullableLong(),
        viewCount = readNullableLong(),
        publishedTimestamp = readNullableLong(),
        isLive = readBoolean(),
        isShort = readBoolean()
    )

    private fun DataOutputStream.writeContentKey(key: ContentKey) {
        writeInt(key.serviceId)
        writeUTF(key.nativeId)
    }

    private fun DataInputStream.readContentKey(): ContentKey = ContentKey(readInt(), readUTF())

    private fun DataOutputStream.writeNullableContentKey(key: ContentKey?) {
        writeBoolean(key != null)
        if (key != null) writeContentKey(key)
    }

    private fun DataInputStream.readNullableContentKey(): ContentKey? =
        if (readBoolean()) readContentKey() else null

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private companion object {
        const val PREFERENCES_NAME = "hpre_home_feed"
        const val FORMAT_VERSION = 1
        const val MAX_ITEMS = 100
        const val MAX_ENCODED_BYTES = 512 * 1024
        const val DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

        fun storageKey(cacheKey: String): String = "feed_" + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(cacheKey.toByteArray(Charsets.UTF_8))
    }
}
