package com.hpre.app.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.hpre.app.model.ContentKey
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Base64

internal fun videoPrefetchItemKey(contentKey: ContentKey): String = "video:${contentKey.serviceId}:${
    Base64.getUrlEncoder().withoutPadding().encodeToString(contentKey.nativeId.toByteArray(Charsets.UTF_8))
}"

internal fun visibleVideoKeys(lazyItemKeys: List<Any>, itemKeys: Map<String, ContentKey>): Set<ContentKey> = lazyItemKeys
    .mapNotNull { key -> (key as? String)?.let(itemKeys::get) }
    .toSet()

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

@Composable
internal fun VideoViewportPrefetchEffect(
    listState: LazyListState,
    orderedKeys: List<ContentKey>,
    prefetch: suspend (List<ContentKey>) -> Unit
) {
    val itemKeys = orderedKeys.associateBy(::videoPrefetchItemKey)
    LaunchedEffect(listState, orderedKeys, prefetch) {
        snapshotFlow { visibleVideoKeys(listState.layoutInfo.visibleItemsInfo.map { it.key }, itemKeys) }
            .map { selectPrefetchKeys(orderedKeys, it) }
            .distinctUntilChanged()
            .collectLatest { keys -> if (keys.isNotEmpty()) prefetch(keys) }
    }
}
