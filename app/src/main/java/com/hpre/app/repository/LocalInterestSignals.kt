package com.hpre.app.repository

import com.hpre.app.model.ContentKey

data class LocalInterestSignals(
    val recentQueries: List<String>,
    val watchedChannelFrequency: Map<String, Int>,
    val recentlyWatched: Set<ContentKey>
)
