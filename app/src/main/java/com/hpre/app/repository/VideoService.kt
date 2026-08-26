package com.hpre.app.repository

import com.hpre.app.core.error.AppResult
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
