package com.flowtube.app.repository

import com.flowtube.app.core.error.AppResult
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

interface VideoService {
    val serviceId: Int
    val serviceName: String
    val supportsShorts: Boolean
    val supportsComments: Boolean
    val supportsSearchSuggestions: Boolean

    suspend fun search(query: String, filter: SearchFilter, pageToken: PageToken?): AppResult<SearchPage>
    suspend fun suggestions(query: String): AppResult<List<String>>
    suspend fun video(key: ContentKey): AppResult<VideoDetails>
    suspend fun streamInfo(key: ContentKey): AppResult<StreamInfo>
    suspend fun channel(key: ContentKey): AppResult<ChannelDetails>
    suspend fun related(key: ContentKey): AppResult<List<VideoSummary>>
    suspend fun playlist(key: ContentKey): AppResult<PlaylistDetails>
    suspend fun comments(key: ContentKey, pageToken: PageToken?): AppResult<CommentPage>
    suspend fun trending(): AppResult<List<VideoSummary>>
}
