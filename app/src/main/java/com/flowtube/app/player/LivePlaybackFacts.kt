package com.flowtube.app.player

/**
 * Persistent auditable facts data structure for live playback gate.
 * Contains strictly non-sensitive execution and playback facts.
 * Absolutely no queries, IDs, titles, URLs, tokens, or response bodies.
 */
data class LivePlaybackFacts(
    val schemaVersion: Int = 1,
    val completion: Boolean,
    val actualDurationMs: Long,
    val surfaceAttached: Boolean,
    val playerViewAttached: Boolean,
    val playerViewLaidOut: Boolean,
    val playerViewGlobalVisible: Boolean,
    val playerViewHasPlayer: Boolean,
    val initialGeneration: Long,
    val initialRenderCount: Int,
    val initialPlaybackState: String,
    val initialIsPlaying: Boolean,
    val advanceDeltaMs: Long,
    val seekTargetMs: Long,
    val seekActualDeltaMs: Long,
    val postSeekDeltaMs: Long,
    val qualityAttempted: Boolean,
    val qualityStreamType: String?,
    val postSwitchGeneration: Long?,
    val postSwitchRenderDelta: Int?,
    val postSwitchPositionDeltaMs: Long?,
    val postSwitchAdvanceDeltaMs: Long?
) {
    fun validateSanitized() {
        check(schemaVersion == 1) { "Invalid schemaVersion: $schemaVersion" }
        check(completion) { "Facts completion must be true" }
        check(actualDurationMs > 5000L) { "actualDurationMs must be > 5000ms: $actualDurationMs" }
        check(surfaceAttached) { "surfaceAttached must be true" }
        check(playerViewAttached) { "playerViewAttached must be true" }
        check(playerViewLaidOut) { "playerViewLaidOut must be true" }
        check(playerViewGlobalVisible) { "playerViewGlobalVisible must be true" }
        check(playerViewHasPlayer) { "playerViewHasPlayer must be true" }
        check(initialRenderCount > 0) { "initialRenderCount must be > 0: $initialRenderCount" }
        check(initialPlaybackState == "STATE_READY") { "initialPlaybackState must be STATE_READY, got: $initialPlaybackState" }
        check(initialIsPlaying) { "initialIsPlaying must be true" }
        check(advanceDeltaMs >= 300L) { "advanceDeltaMs must be >= 300ms: $advanceDeltaMs" }
        check(seekActualDeltaMs <= 1000L) { "seekActualDeltaMs must be <= 1000ms: $seekActualDeltaMs" }
        check(postSeekDeltaMs >= 300L) { "postSeekDeltaMs must be >= 300ms: $postSeekDeltaMs" }

        // Sanitize strings: no control characters, unescaped quotes, or backslashes
        validateFieldString("initialPlaybackState", initialPlaybackState)

        if (qualityAttempted) {
            checkNotNull(qualityStreamType) { "qualityStreamType must not be null when qualityAttempted=true" }
            check(qualityStreamType == PlaybackStreamType.PROGRESSIVE.name || qualityStreamType == PlaybackStreamType.MERGED_AV.name) {
                "qualityStreamType must be PROGRESSIVE or MERGED_AV, got: $qualityStreamType"
            }
            validateFieldString("qualityStreamType", qualityStreamType)
            checkNotNull(postSwitchGeneration) { "postSwitchGeneration must not be null" }
            check(postSwitchGeneration > initialGeneration) { "postSwitchGeneration ($postSwitchGeneration) must be > initialGeneration ($initialGeneration)" }
            check(postSwitchRenderDelta != null && postSwitchRenderDelta > 0) { "postSwitchRenderDelta must be positive" }
            check(postSwitchPositionDeltaMs != null && postSwitchPositionDeltaMs <= 1500L) { "postSwitchPositionDeltaMs out of tolerance" }
            check(postSwitchAdvanceDeltaMs != null && postSwitchAdvanceDeltaMs >= 300L) { "postSwitchAdvanceDeltaMs must be >= 300ms" }
        }

        val jsonString = toJson()
        val prohibitedPatterns = listOf(
            "http://", "https://", "rtmp://", "v=", "watch", "token", "auth",
            "title", "channel", "query", "videoId", "nativeId"
        )
        for (pattern in prohibitedPatterns) {
            check(!jsonString.contains(pattern, ignoreCase = true)) {
                "Prohibited pattern detected in facts serialization: $pattern"
            }
        }

        // Verify JSON round-trips cleanly
        val roundtrip = fromJson(jsonString)
        check(roundtrip == this) { "JSON roundtrip validation failed" }
    }

    private fun validateFieldString(name: String, value: String) {
        for (ch in value) {
            check(ch != '"' && ch != '\\' && ch != '\n' && ch != '\r' && ch != '\t' && ch.code >= 32) {
                "Field $name contains illegal character: ${ch.code}"
            }
        }
    }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(schemaVersion).append(",\n")
        sb.append("  \"completion\": ").append(completion).append(",\n")
        sb.append("  \"actualDurationMs\": ").append(actualDurationMs).append(",\n")
        sb.append("  \"surfaceAttached\": ").append(surfaceAttached).append(",\n")
        sb.append("  \"playerViewAttached\": ").append(playerViewAttached).append(",\n")
        sb.append("  \"playerViewLaidOut\": ").append(playerViewLaidOut).append(",\n")
        sb.append("  \"playerViewGlobalVisible\": ").append(playerViewGlobalVisible).append(",\n")
        sb.append("  \"playerViewHasPlayer\": ").append(playerViewHasPlayer).append(",\n")
        sb.append("  \"initialGeneration\": ").append(initialGeneration).append(",\n")
        sb.append("  \"initialRenderCount\": ").append(initialRenderCount).append(",\n")
        sb.append("  \"initialPlaybackState\": ").append(escapeJsonString(initialPlaybackState)).append(",\n")
        sb.append("  \"initialIsPlaying\": ").append(initialIsPlaying).append(",\n")
        sb.append("  \"advanceDeltaMs\": ").append(advanceDeltaMs).append(",\n")
        sb.append("  \"seekTargetMs\": ").append(seekTargetMs).append(",\n")
        sb.append("  \"seekActualDeltaMs\": ").append(seekActualDeltaMs).append(",\n")
        sb.append("  \"postSeekDeltaMs\": ").append(postSeekDeltaMs).append(",\n")
        sb.append("  \"qualityAttempted\": ").append(qualityAttempted).append(",\n")
        if (qualityStreamType != null) {
            sb.append("  \"qualityStreamType\": ").append(escapeJsonString(qualityStreamType)).append(",\n")
        } else {
            sb.append("  \"qualityStreamType\": null,\n")
        }
        sb.append("  \"postSwitchGeneration\": ").append(postSwitchGeneration ?: "null").append(",\n")
        sb.append("  \"postSwitchRenderDelta\": ").append(postSwitchRenderDelta ?: "null").append(",\n")
        sb.append("  \"postSwitchPositionDeltaMs\": ").append(postSwitchPositionDeltaMs ?: "null").append(",\n")
        sb.append("  \"postSwitchAdvanceDeltaMs\": ").append(postSwitchAdvanceDeltaMs ?: "null").append("\n")
        sb.append("}")
        return sb.toString()
    }

    companion object {
        fun escapeJsonString(value: String): String {
            val sb = StringBuilder("\"")
            for (ch in value) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (ch.code < 32) {
                            sb.append(String.format("\\u%04x", ch.code))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            sb.append("\"")
            return sb.toString()
        }

        private val EXPECTED_KEYS = listOf(
            "schemaVersion",
            "completion",
            "actualDurationMs",
            "surfaceAttached",
            "playerViewAttached",
            "playerViewLaidOut",
            "playerViewGlobalVisible",
            "playerViewHasPlayer",
            "initialGeneration",
            "initialRenderCount",
            "initialPlaybackState",
            "initialIsPlaying",
            "advanceDeltaMs",
            "seekTargetMs",
            "seekActualDeltaMs",
            "postSeekDeltaMs",
            "qualityAttempted",
            "qualityStreamType",
            "postSwitchGeneration",
            "postSwitchRenderDelta",
            "postSwitchPositionDeltaMs",
            "postSwitchAdvanceDeltaMs"
        )

        fun fromJson(json: String): LivePlaybackFacts {
            val map = parseStrictJsonObject(json)

            // Validate that exact expected keys are present without extra or missing
            val expectedKeySet = EXPECTED_KEYS.toSet()
            val actualKeySet = map.keys

            val missing = expectedKeySet - actualKeySet
            require(missing.isEmpty()) { "Missing required fields in facts JSON: $missing" }

            val unknown = actualKeySet - expectedKeySet
            require(unknown.isEmpty()) { "Unknown fields in facts JSON: $unknown" }

            fun requireInt(field: String): Int {
                val v = map[field]
                require(v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE) { "Field $field must be an Integer, got: $v" }
                return v.toInt()
            }

            fun requireLong(field: String): Long {
                val v = map[field]
                require(v is Long) { "Field $field must be a Long/Int, got: $v" }
                return v
            }

            fun requireBoolean(field: String): Boolean {
                val v = map[field]
                require(v is Boolean) { "Field $field must be a Boolean, got: $v" }
                return v
            }

            fun requireString(field: String): String {
                val v = map[field]
                require(v is String) { "Field $field must be a String, got: $v" }
                return v
            }

            fun optionalString(field: String): String? {
                val v = map[field]
                if (v == null) return null
                require(v is String) { "Field $field must be a String or null, got: $v" }
                return v
            }

            fun optionalInt(field: String): Int? {
                val v = map[field]
                if (v == null) return null
                require(v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE) { "Field $field must be an Integer or null, got: $v" }
                return v.toInt()
            }

            fun optionalLong(field: String): Long? {
                val v = map[field]
                if (v == null) return null
                require(v is Long) { "Field $field must be a Long or null, got: $v" }
                return v
            }

            return LivePlaybackFacts(
                schemaVersion = requireInt("schemaVersion"),
                completion = requireBoolean("completion"),
                actualDurationMs = requireLong("actualDurationMs"),
                surfaceAttached = requireBoolean("surfaceAttached"),
                playerViewAttached = requireBoolean("playerViewAttached"),
                playerViewLaidOut = requireBoolean("playerViewLaidOut"),
                playerViewGlobalVisible = requireBoolean("playerViewGlobalVisible"),
                playerViewHasPlayer = requireBoolean("playerViewHasPlayer"),
                initialGeneration = requireLong("initialGeneration"),
                initialRenderCount = requireInt("initialRenderCount"),
                initialPlaybackState = requireString("initialPlaybackState"),
                initialIsPlaying = requireBoolean("initialIsPlaying"),
                advanceDeltaMs = requireLong("advanceDeltaMs"),
                seekTargetMs = requireLong("seekTargetMs"),
                seekActualDeltaMs = requireLong("seekActualDeltaMs"),
                postSeekDeltaMs = requireLong("postSeekDeltaMs"),
                qualityAttempted = requireBoolean("qualityAttempted"),
                qualityStreamType = optionalString("qualityStreamType"),
                postSwitchGeneration = optionalLong("postSwitchGeneration"),
                postSwitchRenderDelta = optionalInt("postSwitchRenderDelta"),
                postSwitchPositionDeltaMs = optionalLong("postSwitchPositionDeltaMs"),
                postSwitchAdvanceDeltaMs = optionalLong("postSwitchAdvanceDeltaMs")
            )
        }

        /**
         * Strict limited JSON parser (handles objects, nested objects, and arrays of primitives).
         * Enforces:
         * - No unescaped control chars (< 0x20) in strings
         * - Strict escape sequences (\", \\, \/, \b, \f, \n, \r, \t, \uXXXX)
         * - Exact syntax: no trailing commas, no duplicate keys, no trailing characters after root object
         * - Primitive values: string, boolean (true/false), null, integer numbers (no exponents or decimals)
         */
        fun parseStrictJsonObject(json: String): Map<String, Any?> {
            var i = 0
            val len = json.length

            fun skipWhitespace() {
                while (i < len) {
                    val c = json[i]
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        i++
                    } else {
                        break
                    }
                }
            }

            fun parseString(): String {
                require(i < len && json[i] == '"') { "Expected '\"' at position $i" }
                i++ // skip opening quote
                val sb = StringBuilder()
                while (i < len) {
                    val c = json[i]
                    if (c.code < 32) {
                        throw IllegalArgumentException("Unescaped control character in JSON string at position $i: ${c.code}")
                    }
                    if (c == '"') {
                        i++ // skip closing quote
                        return sb.toString()
                    }
                    if (c == '\\') {
                        i++
                        require(i < len) { "Unterminated escape sequence at end of JSON" }
                        val esc = json[i]
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
                                require(i + 4 < len) { "Incomplete unicode escape at position $i" }
                                val hex = json.substring(i + 1, i + 5)
                                val codePoint = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid hex in unicode escape: $hex")
                                sb.append(codePoint.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape sequence '\\$esc' at position $i")
                        }
                        i++
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                throw IllegalArgumentException("Unterminated string literal in JSON")
            }

            fun parseNumber(): Long {
                val start = i
                val isNegative = if (i < len && json[i] == '-') {
                    i++
                    true
                } else {
                    false
                }
                require(i < len && json[i] in '0'..'9') { "Expected digit at position $i" }
                if (json[i] == '0') {
                    i++
                    if (i < len && json[i] in '0'..'9') {
                        throw IllegalArgumentException("Leading zeros not permitted in RFC8259 JSON numbers at position $start")
                    }
                    return 0L
                }
                while (i < len && json[i] in '0'..'9') {
                    i++
                }
                val numStr = json.substring(start, i)
                return numStr.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer number literal: '$numStr'")
            }

            var parseValueRef: (() -> Any?)? = null

            fun parseObject(): Map<String, Any?> {
                require(i < len && json[i] == '{') { "Expected '{' at position $i" }
                i++ // skip '{'
                val result = mutableMapOf<String, Any?>()

                skipWhitespace()
                if (i < len && json[i] == '}') {
                    i++
                    return result
                }

                while (i < len) {
                    skipWhitespace()
                    require(i < len && json[i] == '"') { "Expected string key in object at position $i" }
                    val key = parseString()
                    require(!result.containsKey(key)) { "Duplicate key '$key' in JSON object" }

                    skipWhitespace()
                    require(i < len && json[i] == ':') { "Expected ':' after key '$key' at position $i" }
                    i++ // skip ':'

                    val value = parseValueRef!!.invoke()
                    result[key] = value

                    skipWhitespace()
                    require(i < len) { "Unterminated JSON object" }
                    if (json[i] == '}') {
                        i++ // skip '}'
                        break
                    } else if (json[i] == ',') {
                        i++ // skip ','
                        skipWhitespace()
                        require(i < len && json[i] != '}') { "Trailing comma in JSON object at position $i" }
                    } else {
                        throw IllegalArgumentException("Expected ',' or '}' after object field '$key' at position $i, got '${json[i]}'")
                    }
                }
                return result
            }

            fun parseValue(): Any? {
                skipWhitespace()
                require(i < len) { "Unexpected EOF while expecting JSON value" }
                return when (val c = json[i]) {
                    '{' -> parseObject()
                    '"' -> parseString()
                    't' -> {
                        require(json.startsWith("true", i)) { "Expected 'true' at position $i" }
                        i += 4
                        true
                    }
                    'f' -> {
                        require(json.startsWith("false", i)) { "Expected 'false' at position $i" }
                        i += 5
                        false
                    }
                    'n' -> {
                        require(json.startsWith("null", i)) { "Expected 'null' at position $i" }
                        i += 4
                        null
                    }
                    in '0'..'9', '-' -> parseNumber()
                    else -> throw IllegalArgumentException("Unexpected character '$c' while parsing value at position $i")
                }
            }

            parseValueRef = { parseValue() }

            skipWhitespace()
            require(i < len && json[i] == '{') { "Expected '{' at start of JSON object, got '${if (i < len) json[i] else "EOF"}'" }
            val root = parseObject()
            skipWhitespace()
            require(i == len) { "Unexpected trailing content after JSON root object at position $i: '${json.substring(i)}'" }
            return root
        }
    }
}
