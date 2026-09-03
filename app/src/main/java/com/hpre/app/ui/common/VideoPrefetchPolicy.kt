package com.hpre.app.ui.common

import com.hpre.app.model.ContentKey

internal fun selectPrefetchKeys(
    orderedKeys: List<ContentKey>,
    visibleKeys: Set<ContentKey>,
    limit: Int = 3
): List<ContentKey> {
    val distinctKeys = orderedKeys.distinct()
    val firstVisible = distinctKeys.indexOfFirst { it in visibleKeys }
    if (firstVisible < 0) return emptyList()
    return distinctKeys.drop(firstVisible).take(limit.coerceAtLeast(0))
}
