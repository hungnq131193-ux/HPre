package com.hpre.app.extractor

import com.hpre.app.model.ChannelDetails
import com.hpre.app.model.CommentPage
import com.hpre.app.model.ContentKey
import com.hpre.app.model.PageToken
import com.hpre.app.model.PlaylistDetails
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchPage
import com.hpre.app.model.StreamInfo
import com.hpre.app.model.VideoDetails
import com.hpre.app.model.VideoSummary

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
    fun videoBundle(key: ContentKey): ExtractedVideoBundle
    fun video(key: ContentKey): VideoDetails = videoBundle(key).details
    fun streamInfo(key: ContentKey): StreamInfo = videoBundle(key).streamInfo
    fun channel(key: ContentKey): ChannelDetails
    fun playlist(key: ContentKey): PlaylistDetails
    fun related(key: ContentKey): List<VideoSummary> = videoBundle(key).related
    fun comments(key: ContentKey, pageToken: PageToken?): CommentPage
    fun trending(): List<VideoSummary>
}
