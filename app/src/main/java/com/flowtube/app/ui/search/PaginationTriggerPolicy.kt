package com.flowtube.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PaginationTriggerPolicy(val threshold: Int = 3) {
    private var currentRequestKey: String? by mutableStateOf(null)
    private var lastTriggeredPosition: Int? by mutableStateOf(null)
    private var _triggerCount: Int by mutableStateOf(0)

    internal val triggerCountForTest: Int
        get() = _triggerCount

    /**
     * Resets policy state for a new search request key / filter generation.
     */
    fun resetForRequest(requestKey: String? = null) {
        currentRequestKey = requestKey
        lastTriggeredPosition = null
        _triggerCount = 0
    }

    /**
     * Resets all policy state.
     */
    fun reset() {
        currentRequestKey = null
        lastTriggeredPosition = null
        _triggerCount = 0
    }

    /**
     * Evaluates pagination threshold synchronously when a UserInput event occurs.
     * Deduplicates callbacks at the same visible item index to prevent duplicate loads within a single drag/position.
     * Layout updates, recomposition, programmatic scroll, state restoration, and page append updates do not call this.
     */
    fun onUserInputPosition(
        totalItemsCount: Int,
        lastVisibleItemIndex: Int,
        hasNextPage: Boolean,
        isLoadingNextPage: Boolean,
        requestKey: String? = null
    ): Boolean {
        if (requestKey != null && currentRequestKey != requestKey) {
            currentRequestKey = requestKey
            lastTriggeredPosition = null
        }

        if (!hasNextPage || isLoadingNextPage || totalItemsCount <= 0 || lastVisibleItemIndex < 0) {
            return false
        }

        val isAtThreshold = lastVisibleItemIndex >= totalItemsCount - threshold
        if (!isAtThreshold) {
            return false
        }

        if (lastTriggeredPosition == lastVisibleItemIndex) {
            return false
        }

        lastTriggeredPosition = lastVisibleItemIndex
        _triggerCount++
        return true
    }
}




