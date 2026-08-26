package com.flowtube.app.extractor

import com.flowtube.app.model.ChannelDetails
import com.flowtube.app.model.CommentPage
import com.flowtube.app.model.ContentKey
import com.flowtube.app.model.PageToken
import com.flowtube.app.model.PlaylistDetails
import com.flowtube.app.model.SearchFilter
import com.flowtube.app.model.SearchPage
import com.flowtube.app.model.StreamInfo
import com.flowtube.app.model.VideoDetails
import com.flowtube.app.model.VideoSummary

/**
 * Internal abstraction isolating raw static/global NewPipe calls.
 * Used internally within extractor package to allow deterministic fixture testing.
 */
internal interface ExtractorOperations {
    val serviceId: Int
    val serviceName: String
    val supportsShorts: Boolean
    val supportsComments: Boolean
    val supportsSearchSuggestions: Boolean

    fun search(query: String, filter: SearchFilter, pageToken: PageToken?): SearchPage
    fun suggestions(query: String): List<String>
    fun video(key: ContentKey): VideoDetails
    fun streamInfo(key: ContentKey): StreamInfo
    fun channel(key: ContentKey): ChannelDetails
    fun playlist(key: ContentKey): PlaylistDetails
    fun related(key: ContentKey): List<VideoSummary>
    fun comments(key: ContentKey, pageToken: PageToken?): CommentPage
    fun trending(): List<VideoSummary>
}
