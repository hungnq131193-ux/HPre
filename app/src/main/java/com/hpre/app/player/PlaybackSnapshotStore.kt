package com.hpre.app.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hpre.app.model.ContentKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

val Context.playbackSnapshotDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_snapshot_store")

data class PlaybackSnapshot(
    val key: ContentKey,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val selectedQuality: QualityOption? = null,
    val playbackSpeed: Float = 1.0f,
    val qualityPolicy: UserQualityPolicy = selectedQuality?.let(UserQualityPolicy::Fixed)
        ?: UserQualityPolicy.Auto()
)

class PlaybackSnapshotStore(
    private val storageDir: File?,
    private val dataStore: DataStore<Preferences>? = null,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    constructor(context: Context) : this(
        storageDir = context.filesDir,
        dataStore = context.playbackSnapshotDataStore
    )

    val writer = SnapshotWriter(storageDir, dataStore, ioScope)

    val snapshotFlow: Flow<PlaybackSnapshot?> = writer.snapshotFlow

    fun enqueueSave(): Long = writer.enqueueSave()

    fun executeSave(snapshot: PlaybackSnapshot, token: Long) {
        writer.executeSave(snapshot, token)
    }

    fun save(snapshot: PlaybackSnapshot) {
        writer.save(snapshot)
    }

    fun saveSync(snapshot: PlaybackSnapshot) {
        writer.saveSync(snapshot)
    }

    fun load(): PlaybackSnapshot? = writer.load()

    suspend fun loadAsync(): PlaybackSnapshot? = writer.loadAsync()

    suspend fun loadForServiceRestore(): PlaybackSnapshot? = writer.loadForServiceRestore()

    fun clear() {
        writer.clear()
    }
}

class SnapshotWriter(
    private val storageDir: File?,
    private val dataStore: DataStore<Preferences>? = null,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        val KEY_SERVICE_ID = intPreferencesKey("snapshot_service_id")
        val KEY_NATIVE_ID = stringPreferencesKey("snapshot_native_id")
        val KEY_POSITION_MS = longPreferencesKey("snapshot_position_ms")
        val KEY_PLAY_WHEN_READY = booleanPreferencesKey("snapshot_play_when_ready")
        val KEY_PLAYBACK_SPEED = floatPreferencesKey("snapshot_playback_speed")

        // Quality fields
        val KEY_QUALITY_HEIGHT = intPreferencesKey("snapshot_quality_height")
        val KEY_QUALITY_LABEL = stringPreferencesKey("snapshot_quality_label")
        val KEY_QUALITY_IS_PROGRESSIVE = booleanPreferencesKey("snapshot_quality_is_prog")
        val KEY_QUALITY_FORMAT = stringPreferencesKey("snapshot_quality_format")
        val KEY_QUALITY_MIME = stringPreferencesKey("snapshot_quality_mime")
        val KEY_QUALITY_CODEC = stringPreferencesKey("snapshot_quality_codec")
        val KEY_QUALITY_STREAM_TYPE = stringPreferencesKey("snapshot_quality_stream_type")
        val KEY_QUALITY_PRESENT = booleanPreferencesKey("snapshot_quality_present")
        val KEY_POLICY_TYPE = stringPreferencesKey("snapshot_quality_policy_type")
        val KEY_POLICY_MAX_HEIGHT = intPreferencesKey("snapshot_quality_policy_max_height")
        val KEY_POLICY_MAX_BITRATE = intPreferencesKey("snapshot_quality_policy_max_bitrate")

        val KEY_SNAPSHOT_VERSION = longPreferencesKey("snapshot_version")
    }

    private val lock = ReentrantLock()
    @Volatile
    private var snapshotVersion: Long = 0L

    // In-memory cache synced under lock
    @Volatile
    private var inMemorySnapshot: PlaybackSnapshot? = null
    @Volatile
    private var isInitialized: Boolean = false

    val currentGeneration: Long
        get() = snapshotVersion

    private val legacySnapshotFile: File?
        get() = storageDir?.let { File(it, "playback_snapshot.json") }

    private val preferencesFile: File?
        get() = storageDir?.let { File(it, "datastore/playback_snapshot_store.preferences_pb") }

    init {
        if (dataStore != null) {
            ioScope.launch {
                try {
                    snapshotFlow.collect {}
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {}
            }
        }
    }

    val snapshotFlow: Flow<PlaybackSnapshot?> = (dataStore?.data ?: kotlinx.coroutines.flow.flowOf(emptyPreferences()))
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val parsed = parseFromPreferences(prefs)
            val prefsVersion = prefs[KEY_SNAPSHOT_VERSION] ?: 0L
            lock.withLock {
                if (parsed != null && prefsVersion >= snapshotVersion) {
                    inMemorySnapshot = parsed
                    snapshotVersion = prefsVersion
                    parsed
                } else if (parsed == null && prefs[KEY_SERVICE_ID] == null) {
                    inMemorySnapshot = null
                    null
                } else {
                    inMemorySnapshot
                }
            }
        }

    private fun parseFromPreferences(prefs: Preferences): PlaybackSnapshot? {
        val serviceId = prefs[KEY_SERVICE_ID] ?: return null
        val nativeId = prefs[KEY_NATIVE_ID] ?: return null
        val positionMs = prefs[KEY_POSITION_MS] ?: return null
        val playWhenReady = prefs[KEY_PLAY_WHEN_READY] ?: false
        val playbackSpeed = prefs[KEY_PLAYBACK_SPEED] ?: 1.0f

        if (nativeId.isBlank() || positionMs < 0L || !playbackSpeed.isFinite() || playbackSpeed !in 0.25f..3.0f) {
            return null
        }

        val hasQuality = prefs[KEY_QUALITY_PRESENT] ?: false
        val quality = if (hasQuality) {
            val height = prefs[KEY_QUALITY_HEIGHT] ?: 0
            val label = prefs[KEY_QUALITY_LABEL] ?: ""
            val isProg = prefs[KEY_QUALITY_IS_PROGRESSIVE] ?: true
            val format = prefs[KEY_QUALITY_FORMAT] ?: ""
            val mime = prefs[KEY_QUALITY_MIME]?.takeIf { it.isNotBlank() }
            val codec = prefs[KEY_QUALITY_CODEC]?.takeIf { it.isNotBlank() }
            val stName = prefs[KEY_QUALITY_STREAM_TYPE] ?: ""
            val streamType = try {
                PlaybackStreamType.valueOf(stName)
            } catch (_: Exception) {
                if (isProg) PlaybackStreamType.PROGRESSIVE else PlaybackStreamType.MERGED_AV
            }
            QualityOption(height, label, isProg, format, mime, codec, streamType)
        } else {
            null
        }

        val policy = when (prefs[KEY_POLICY_TYPE]) {
            "auto" -> UserQualityPolicy.Auto(
                maxHeight = prefs[KEY_POLICY_MAX_HEIGHT]?.takeIf { it > 0 },
                maxBitrate = prefs[KEY_POLICY_MAX_BITRATE]?.takeIf { it > 0 }
            )
            "fixed" -> quality?.let(UserQualityPolicy::Fixed) ?: UserQualityPolicy.Auto()
            else -> quality?.let(UserQualityPolicy::Fixed) ?: UserQualityPolicy.Auto()
        }
        return PlaybackSnapshot(
            key = ContentKey(serviceId, nativeId),
            positionMs = positionMs,
            playWhenReady = playWhenReady,
            selectedQuality = quality,
            qualityPolicy = policy,
            playbackSpeed = playbackSpeed
        )
    }

    private fun writeToPreferences(prefs: androidx.datastore.preferences.core.MutablePreferences, snapshot: PlaybackSnapshot, version: Long) {
        prefs[KEY_SNAPSHOT_VERSION] = version
        prefs[KEY_SERVICE_ID] = snapshot.key.serviceId
        prefs[KEY_NATIVE_ID] = snapshot.key.nativeId
        prefs[KEY_POSITION_MS] = snapshot.positionMs
        prefs[KEY_PLAY_WHEN_READY] = snapshot.playWhenReady
        prefs[KEY_PLAYBACK_SPEED] = snapshot.playbackSpeed
        val q = (snapshot.qualityPolicy as? UserQualityPolicy.Fixed)?.option
            ?: snapshot.selectedQuality
        when (val policy = snapshot.qualityPolicy) {
            is UserQualityPolicy.Auto -> {
                prefs[KEY_POLICY_TYPE] = "auto"
                policy.maxHeight?.let { prefs[KEY_POLICY_MAX_HEIGHT] = it } ?: prefs.remove(KEY_POLICY_MAX_HEIGHT)
                policy.maxBitrate?.let { prefs[KEY_POLICY_MAX_BITRATE] = it } ?: prefs.remove(KEY_POLICY_MAX_BITRATE)
            }
            is UserQualityPolicy.Fixed -> {
                prefs[KEY_POLICY_TYPE] = "fixed"
                prefs.remove(KEY_POLICY_MAX_HEIGHT)
                prefs.remove(KEY_POLICY_MAX_BITRATE)
            }
        }
        if (q != null) {
            prefs[KEY_QUALITY_PRESENT] = true
            prefs[KEY_QUALITY_HEIGHT] = q.height
            prefs[KEY_QUALITY_LABEL] = q.label
            prefs[KEY_QUALITY_IS_PROGRESSIVE] = q.isProgressive
            prefs[KEY_QUALITY_FORMAT] = q.format
            prefs[KEY_QUALITY_MIME] = q.mimeType ?: ""
            prefs[KEY_QUALITY_CODEC] = q.codec ?: ""
            prefs[KEY_QUALITY_STREAM_TYPE] = q.streamType.name
        } else {
            prefs[KEY_QUALITY_PRESENT] = false
            prefs.remove(KEY_QUALITY_HEIGHT)
            prefs.remove(KEY_QUALITY_LABEL)
            prefs.remove(KEY_QUALITY_IS_PROGRESSIVE)
            prefs.remove(KEY_QUALITY_FORMAT)
            prefs.remove(KEY_QUALITY_MIME)
            prefs.remove(KEY_QUALITY_CODEC)
            prefs.remove(KEY_QUALITY_STREAM_TYPE)
        }
    }

    fun enqueueSave(): Long {
        return lock.withLock { ++snapshotVersion }
    }

    fun executeSave(snapshot: PlaybackSnapshot, token: Long) {
        saveWithGeneration(snapshot, token)
    }

    fun save(snapshot: PlaybackSnapshot) {
        val targetGen = enqueueSave()
        saveWithGeneration(snapshot, targetGen)
    }

    fun saveSync(snapshot: PlaybackSnapshot) {
        val targetGen = enqueueSave()
        lock.withLock {
            if (targetGen != snapshotVersion) return
            inMemorySnapshot = snapshot
            try {
                storageDir?.mkdirs()
                val file = legacySnapshotFile
                if (file != null) {
                    val qualityPart = if (snapshot.selectedQuality != null) {
                        val q = snapshot.selectedQuality
                        """, "selectedQuality": {"height": ${q.height}, "label": "${escapeJson(q.label)}", "isProgressive": ${q.isProgressive}, "format": "${escapeJson(q.format)}", "mimeType": "${escapeJson(q.mimeType ?: "")}", "codec": "${escapeJson(q.codec ?: "")}", "streamType": "${q.streamType.name}"}"""
                    } else {
                        ""
                    }
                    val jsonString = """{"serviceId": ${snapshot.key.serviceId}, "nativeId": "${escapeJson(snapshot.key.nativeId)}", "positionMs": ${snapshot.positionMs}, "playWhenReady": ${snapshot.playWhenReady}, "playbackSpeed": ${snapshot.playbackSpeed}$qualityPart}"""
                    val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
                    temporary.writeText(jsonString, Charsets.UTF_8)
                    if (targetGen == snapshotVersion) {
                        if (!temporary.renameTo(file)) {
                            temporary.delete()
                        }
                    } else {
                        temporary.delete()
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {}

            if (dataStore != null) {
                ioScope.launch {
                    try {
                        dataStore.edit { prefs ->
                            lock.withLock {
                                if (targetGen == snapshotVersion) {
                                    writeToPreferences(prefs, snapshot, targetGen)
                                }
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun saveWithGeneration(snapshot: PlaybackSnapshot, generation: Long) {
        lock.withLock {
            if (generation != snapshotVersion) return
            inMemorySnapshot = snapshot
            // If storageDir exists, write atomic fallback/datastore-compatible file representation
            try {
                storageDir?.mkdirs()
                val file = legacySnapshotFile
                if (file != null) {
                    val qualityPart = if (snapshot.selectedQuality != null) {
                        val q = snapshot.selectedQuality
                        """, "selectedQuality": {"height": ${q.height}, "label": "${escapeJson(q.label)}", "isProgressive": ${q.isProgressive}, "format": "${escapeJson(q.format)}", "mimeType": "${escapeJson(q.mimeType ?: "")}", "codec": "${escapeJson(q.codec ?: "")}", "streamType": "${q.streamType.name}"}"""
                    } else {
                        ""
                    }
                    val jsonString = """{"serviceId": ${snapshot.key.serviceId}, "nativeId": "${escapeJson(snapshot.key.nativeId)}", "positionMs": ${snapshot.positionMs}, "playWhenReady": ${snapshot.playWhenReady}, "playbackSpeed": ${snapshot.playbackSpeed}$qualityPart}"""
                    val temporary = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
                    temporary.writeText(jsonString, Charsets.UTF_8)
                    if (generation == snapshotVersion) {
                        if (!temporary.renameTo(file)) {
                            temporary.delete()
                        }
                    } else {
                        temporary.delete()
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {}
        }

        if (dataStore != null) {
            ioScope.launch {
                try {
                    dataStore.edit { prefs ->
                        lock.withLock {
                            if (generation == snapshotVersion) {
                                writeToPreferences(prefs, snapshot, generation)
                            }
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {}
            }
        }
    }

    private fun escapeJson(str: String): String {
        val sb = StringBuilder()
        for (c in str) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun load(): PlaybackSnapshot? {
        lock.withLock {
            if (inMemorySnapshot != null) return inMemorySnapshot
            val legacy = loadFromLegacyJson()
            if (legacy != null) {
                inMemorySnapshot = legacy
                legacySnapshotFile?.delete()
                if (dataStore != null) {
                    val currentVer = snapshotVersion
                    ioScope.launch {
                        try {
                            dataStore.edit { prefs ->
                                lock.withLock {
                                    if (currentVer == snapshotVersion) {
                                        writeToPreferences(prefs, legacy, currentVer)
                                    }
                                }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (_: Exception) {}
                    }
                }
            }
            return inMemorySnapshot
        }
    }

    suspend fun loadAsync(): PlaybackSnapshot? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (dataStore != null) {
            try {
                val prefs = dataStore.data.catch { e ->
                    if (e is IOException) emit(emptyPreferences()) else throw e
                }.firstOrNull()
                if (prefs != null) {
                    val parsed = parseFromPreferences(prefs)
                    val prefsVersion = prefs[KEY_SNAPSHOT_VERSION] ?: 0L
                    lock.withLock {
                        if (parsed != null && prefsVersion >= snapshotVersion) {
                            inMemorySnapshot = parsed
                            snapshotVersion = prefsVersion
                            return@withContext parsed
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {}
        }
        lock.withLock {
            if (inMemorySnapshot != null) return@withContext inMemorySnapshot
            val legacy = loadFromLegacyJson()
            if (legacy != null) {
                inMemorySnapshot = legacy
            }
            inMemorySnapshot
        }
    }

    suspend fun loadForServiceRestore(): PlaybackSnapshot? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val ds = dataStore ?: return@withContext null
        try {
            val prefs = ds.data.firstOrNull() ?: return@withContext null
            val parsed = parseFromPreferences(prefs) ?: return@withContext null
            val prefsVersion = prefs[KEY_SNAPSHOT_VERSION] ?: 0L
            lock.withLock {
                inMemorySnapshot = parsed
                snapshotVersion = prefsVersion
            }
            parsed
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        val targetGen = lock.withLock {
            ++snapshotVersion
            inMemorySnapshot = null
            legacySnapshotFile?.delete()
            snapshotVersion
        }
        if (dataStore != null) {
            ioScope.launch {
                try {
                    dataStore.edit { prefs ->
                        lock.withLock {
                            if (targetGen == snapshotVersion) {
                                prefs.clear()
                            }
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadFromLegacyJson(): PlaybackSnapshot? {
        val file = legacySnapshotFile ?: return null
        return try {
            if (!file.exists()) return null
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) return null

            val json = JsonParser(text).parse() ?: return null

            val serviceId = (json["serviceId"] as? Number)?.toInt() ?: return null
            val nativeId = json["nativeId"] as? String ?: return null
            val positionMs = (json["positionMs"] as? Number)?.toLong() ?: return null
            val playWhenReady = json["playWhenReady"] as? Boolean ?: false
            val playbackSpeed = (json["playbackSpeed"] as? Number)?.toFloat() ?: 1.0f

            if (nativeId.isBlank() || positionMs < 0L || !playbackSpeed.isFinite() || playbackSpeed !in 0.25f..3.0f) {
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val qualityMap = json["selectedQuality"] as? Map<String, Any?>
            val quality = if (qualityMap != null) {
                val height = (qualityMap["height"] as? Number)?.toInt() ?: 0
                val label = qualityMap["label"] as? String ?: ""
                val isProgressive = qualityMap["isProgressive"] as? Boolean ?: true
                val format = qualityMap["format"] as? String ?: ""
                val mimeType = (qualityMap["mimeType"] as? String)?.takeIf { it.isNotBlank() }
                val codec = (qualityMap["codec"] as? String)?.takeIf { it.isNotBlank() }
                val streamTypeName = qualityMap["streamType"] as? String ?: ""
                val streamType = try {
                    PlaybackStreamType.valueOf(streamTypeName)
                } catch (_: Exception) {
                    if (isProgressive) PlaybackStreamType.PROGRESSIVE else PlaybackStreamType.MERGED_AV
                }
                QualityOption(
                    height = height,
                    label = label,
                    isProgressive = isProgressive,
                    format = format,
                    mimeType = mimeType,
                    codec = codec,
                    streamType = streamType
                )
            } else {
                null
            }

            PlaybackSnapshot(
                key = ContentKey(serviceId, nativeId),
                positionMs = positionMs,
                playWhenReady = playWhenReady,
                selectedQuality = quality,
                playbackSpeed = playbackSpeed
            )
        } catch (_: Exception) {
            null
        }
    }

    private class JsonParser(private val src: String) {
        private var idx = 0

        fun parse(): Map<String, Any?>? {
            skipWhitespace()
            if (idx >= src.length || src[idx] != '{') return null
            return parseObject()
        }

        private fun parseObject(): Map<String, Any?>? {
            if (src[idx] != '{') return null
            idx++ // skip '{'
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (idx < src.length && src[idx] == '}') {
                idx++
                return map
            }
            while (idx < src.length) {
                skipWhitespace()
                val key = parseString() ?: return null
                skipWhitespace()
                if (idx >= src.length || src[idx] != ':') return null
                idx++ // skip ':'
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (idx >= src.length) return null
                if (src[idx] == '}') {
                    idx++
                    return map
                } else if (src[idx] == ',') {
                    idx++
                } else {
                    return null
                }
            }
            return null
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (idx >= src.length) return null
            return when (src[idx]) {
                '{' -> parseObject()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseString(): String? {
            if (idx >= src.length || src[idx] != '"') return null
            idx++ // skip '"'
            val sb = StringBuilder()
            while (idx < src.length) {
                val c = src[idx++]
                if (c == '"') {
                    return sb.toString()
                } else if (c == '\\') {
                    if (idx >= src.length) return null
                    val esc = src[idx++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 > src.length) return null
                            val hex = src.substring(idx, idx + 4)
                            idx += 4
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                        }
                        else -> return null
                    }
                } else {
                    sb.append(c)
                }
            }
            return null
        }

        private fun parseBoolean(): Boolean? {
            if (src.startsWith("true", idx)) {
                idx += 4
                return true
            } else if (src.startsWith("false", idx)) {
                idx += 5
                return false
            }
            return null
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", idx)) {
                idx += 4
                return null
            }
            return null
        }

        private fun parseNumber(): Number? {
            val start = idx
            if (idx < src.length && (src[idx] == '-' || src[idx] == '+')) {
                idx++
            }
            var hasDot = false
            while (idx < src.length) {
                val c = src[idx]
                if (c.isDigit()) {
                    idx++
                } else if (c == '.' && !hasDot) {
                    hasDot = true
                    idx++
                } else {
                    break
                }
            }
            val numStr = src.substring(start, idx)
            return if (hasDot) {
                numStr.toDoubleOrNull()
            } else {
                numStr.toLongOrNull()
            }
        }

        private fun skipWhitespace() {
            while (idx < src.length && src[idx].isWhitespace()) {
                idx++
            }
        }
    }
}


