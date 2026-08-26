package com.hpre.app.player

import com.hpre.app.model.ContentKey

internal object PlaybackMediaId {
    private const val PREFIX = "hpre:"

    fun encode(key: ContentKey): String = "$PREFIX${key.serviceId}:${key.nativeId}"

    fun decode(value: String?): ContentKey? {
        if (value.isNullOrBlank() || !value.startsWith(PREFIX)) return null
        val remainder = value.removePrefix(PREFIX)
        val separator = remainder.indexOf(':')
        if (separator <= 0 || separator == remainder.lastIndex) return null
        val serviceId = remainder.substring(0, separator).toIntOrNull() ?: return null
        val nativeId = remainder.substring(separator + 1).takeIf { it.isNotBlank() } ?: return null
        return ContentKey(serviceId, nativeId)
    }
}
