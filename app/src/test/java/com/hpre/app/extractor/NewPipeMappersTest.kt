package com.hpre.app.extractor

import com.hpre.app.model.ContentKey
import com.hpre.app.model.SearchFilter
import com.hpre.app.model.SearchResultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

class NewPipeMappersTest {
    @Test
    fun image_variant_uses_the_smallest_adequate_width_and_preserves_large_fallback() {
        val images = listOf(
            Image("https://example.com/48.jpg", 48, 48, Image.ResolutionLevel.LOW),
            Image("https://example.com/160.jpg", 160, 160, Image.ResolutionLevel.MEDIUM),
            Image("https://example.com/1024.jpg", 1024, 1024, Image.ResolutionLevel.HIGH)
        )
        assertEquals("https://example.com/160.jpg", NewPipeMappers.selectPreferredImage(images, 160))
        assertEquals("https://example.com/1024.jpg", NewPipeMappers.selectPreferredImage(images, 1920))
        assertEquals("https://example.com/1024.jpg", NewPipeMappers.selectPreferredImage(images))
    }

    @Test
    fun extractNativeVideoId_parses_various_url_formats_and_encoded_queries() {
        assertEquals("abc12345678", NewPipeMappers.extractNativeVideoId("https://www.youtube.com/watch?v=abc12345678"))
        assertEquals("abc12345678", NewPipeMappers.extractNativeVideoId("https://www.youtube.com/watch?v=abc%31%32%3345678&t=10s"))
        assertEquals("abc12345678", NewPipeMappers.extractNativeVideoId("https://youtu.be/abc12345678"))
        assertEquals("abc12345678", NewPipeMappers.extractNativeVideoId("https://www.youtube.com/shorts/abc12345678"))
        assertEquals("abc12345678", NewPipeMappers.extractNativeVideoId("https://www.youtube.com/embed/abc12345678"))
        assertEquals("abc12345678", NewPipeMappers.extractNativeVideoId("https://www.youtube.com/v/abc12345678"))
        assertEquals("abc12345678", NewPipeMappers.extractNativeVideoId("abc12345678"))

        // Malformed, empty, wrong length, or arbitrary path rejections
        assertNull(NewPipeMappers.extractNativeVideoId(null))
        assertNull(NewPipeMappers.extractNativeVideoId(""))
        assertNull(NewPipeMappers.extractNativeVideoId("   "))
        assertNull(NewPipeMappers.extractNativeVideoId("abc12345")) // Not 11 chars
        assertNull(NewPipeMappers.extractNativeVideoId("abc1234567890")) // 13 chars
        assertNull(NewPipeMappers.extractNativeVideoId("abc 12345678")) // Contains space
        assertNull(NewPipeMappers.extractNativeVideoId("https://unknown.com/video/abc12345678"))
        assertNull(NewPipeMappers.extractNativeVideoId("invalid/path/with/slashes"))
    }

    @Test
    fun extractNativeChannelId_parses_various_formats() {
        assertEquals("UC1234567890123456789012", NewPipeMappers.extractNativeChannelId("https://www.youtube.com/channel/UC1234567890123456789012"))
        assertEquals("UC1234567890123456789012", NewPipeMappers.extractNativeChannelId("https://www.youtube.com/c/UC1234567890123456789012"))
        assertEquals("UC1234567890123456789012", NewPipeMappers.extractNativeChannelId("UC1234567890123456789012"))

        // Rejects handles, non-UC channels, wrong length
        assertNull(NewPipeMappers.extractNativeChannelId("https://www.youtube.com/@HPre"))
        assertNull(NewPipeMappers.extractNativeChannelId("HPre"))
        assertNull(NewPipeMappers.extractNativeChannelId("UC12345")) // Not 24 chars
        assertNull(NewPipeMappers.extractNativeChannelId(null))
        assertNull(NewPipeMappers.extractNativeChannelId(""))
        assertNull(NewPipeMappers.extractNativeChannelId("https://othersite.com/channel/UC1234567890123456789012"))
    }

    @Test
    fun extractNativePlaylistId_parses_urls_and_ids() {
        assertEquals("PL1234567890abcdef", NewPipeMappers.extractNativePlaylistId("https://www.youtube.com/playlist?list=PL1234567890abcdef"))
        assertEquals("PL1234567890abcdef", NewPipeMappers.extractNativePlaylistId("https://www.youtube.com/watch?v=abc12345678&list=PL1234567890abcdef"))
        assertEquals("PL1234567890abcdef", NewPipeMappers.extractNativePlaylistId("PL1234567890abcdef"))
        assertEquals("LL1234567890", NewPipeMappers.extractNativePlaylistId("LL1234567890"))
        assertEquals("UU1234567890", NewPipeMappers.extractNativePlaylistId("UU1234567890"))
        assertEquals("FL1234567890", NewPipeMappers.extractNativePlaylistId("FL1234567890"))
        assertEquals("RD1234567890", NewPipeMappers.extractNativePlaylistId("RD1234567890"))

        // Invalid prefixes or malformed
        assertNull(NewPipeMappers.extractNativePlaylistId("XX1234567890"))
        assertNull(NewPipeMappers.extractNativePlaylistId("PL"))
        assertNull(NewPipeMappers.extractNativePlaylistId(null))
        assertNull(NewPipeMappers.extractNativePlaylistId(""))
        assertNull(NewPipeMappers.extractNativePlaylistId("https://othersite.com/playlist?list=PL1234567890abcdef"))
    }

    @Test
    fun drops_streams_and_subtitles_with_blank_or_invalid_urls() {
        val videoStreamValid = VideoStream.Builder()
            .setId("v1")
            .setContent("https://video.HPre/v.mp4", true)
            .setIsVideoOnly(false)
            .setResolution("1080p")
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .build()
        val videoStreamInvalid = VideoStream.Builder()
            .setId("v2")
            .setContent("", true)
            .setIsVideoOnly(false)
            .setResolution("1080p")
            .build()
        val videoStreamMalformed = VideoStream.Builder()
            .setId("v3")
            .setContent("not_a_valid_url", true)
            .setIsVideoOnly(false)
            .setResolution("1080p")
            .build()

        val mappedVideo = NewPipeMappers.mapVideoStream(videoStreamValid)
        assertNotNull(mappedVideo)
        assertEquals("v1", mappedVideo?.streamId)
        assertEquals(com.hpre.app.model.VideoDeliveryMethod.PROGRESSIVE_HTTP, mappedVideo?.deliveryMethod)

        assertNull(NewPipeMappers.mapVideoStream(videoStreamInvalid))
        assertNull(NewPipeMappers.mapVideoStream(videoStreamMalformed))

        val videoStreamSentinel = VideoStream.Builder()
            .setId("UNKNOWN")
            .setContent("https://video.HPre/v_unknown.mp4", true)
            .setIsVideoOnly(false)
            .setResolution("720p")
            .build()
        val mappedSentinelVideo = NewPipeMappers.mapVideoStream(videoStreamSentinel)
        assertNotNull(mappedSentinelVideo)
        assertNull(mappedSentinelVideo?.streamId)

        val audioValid = AudioStream.Builder()
            .setId("a1")
            .setContent("https://audio.HPre/a.m4a", true)
            .setAudioTrackId("en-main")
            .build()
        val audioInvalid = AudioStream.Builder()
            .setId("a2")
            .setContent("", true)
            .build()
        val mappedAudio = NewPipeMappers.mapAudioStream(audioValid)
        assertNotNull(mappedAudio)
        assertEquals("a1", mappedAudio?.streamId)
        assertEquals("en-main", mappedAudio?.audioTrackId)
        assertNull(NewPipeMappers.mapAudioStream(audioInvalid))

        val subValid = SubtitlesStream.Builder()
            .setId("s1")
            .setContent("https://sub.HPre/sub.vtt", true)
            .setLanguageCode("en")
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.VTT)
            .setAutoGenerated(false)
            .build()
        val subInvalid = SubtitlesStream.Builder()
            .setId("s2")
            .setContent("", true)
            .setLanguageCode("en")
            .setAutoGenerated(false)
            .build()
        assertNotNull(NewPipeMappers.mapSubtitleStream(subValid))
        assertNull(NewPipeMappers.mapSubtitleStream(subInvalid))
    }

    @Test
    fun mapped_video_summary_never_exposes_extractor_types_and_rejects_missing_key() {
        val streamItem = StreamInfoItem(0, "https://youtube.com/watch?v=dQw4w9WgXcQ", "Test Video", StreamType.VIDEO_STREAM).apply {
            uploaderName = "Test Creator"
            uploaderUrl = "https://youtube.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg"
            duration = 120
            viewCount = 1000
            thumbnails = listOf(Image("https://img.HPre/thumb.jpg", 1080, 1920, Image.ResolutionLevel.HIGH))
        }

        val summary = NewPipeMappers.mapStreamInfoItemToSummary(streamItem, fallbackServiceId = 0)
        assertNotNull(summary)
        summary!!

        assertEquals(ContentKey(0, "dQw4w9WgXcQ"), summary.key)
        assertEquals("Test Video", summary.title)
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ", summary.canonicalUrl)
        assertEquals("Test Creator", summary.channelName)
        assertEquals(ContentKey(0, "UCuCKox3vgM_q8p1Ufx9kGqg"), summary.channelKey)
        assertEquals("https://img.HPre/thumb.jpg", summary.thumbnailUrl)
        assertEquals(120L, summary.durationSeconds)
        assertEquals(1000L, summary.viewCount)

        // Invalid URL with no ID should return null
        val invalidItem = StreamInfoItem(0, "https://unknown.com/bad", "Bad", StreamType.VIDEO_STREAM)
        assertNull(NewPipeMappers.mapStreamInfoItemToSummary(invalidItem, fallbackServiceId = 0))
    }

    @Test
    fun mapped_stream_info_maps_streams_cleanly_and_filters_invalid_urls() {
        val streamInfo = StreamInfo(0, "https://youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?v=dQw4w9WgXcQ", StreamType.VIDEO_STREAM, "dQw4w9WgXcQ", "Stream Title", 0)
        streamInfo.dashMpdUrl = "https://manifest.HPre/dash.mpd"
        streamInfo.hlsUrl = "https://manifest.HPre/hls.m3u8"
        streamInfo.videoStreams = listOf(
            VideoStream.Builder().setId("v1").setContent("https://video.HPre/v1.mp4", true).setIsVideoOnly(false).setResolution("1080p").build(),
            VideoStream.Builder().setId("v2").setContent("", true).setIsVideoOnly(false).setResolution("1080p").build() // Should be dropped
        )
        streamInfo.audioStreams = listOf(
            AudioStream.Builder().setId("a1").setContent("https://audio.HPre/a1.m4a", true).build(),
            AudioStream.Builder().setId("a2").setContent("invalid-url", true).build() // Should be dropped
        )

        val mapped = NewPipeMappers.mapStreamInfo(streamInfo, fallbackServiceId = 0)
        assertNotNull(mapped)
        mapped!!

        assertEquals(ContentKey(0, "dQw4w9WgXcQ"), mapped.key)
        assertEquals("Stream Title", mapped.title)
        assertEquals("https://manifest.HPre/dash.mpd", mapped.dashManifestUrl)
        assertEquals("https://manifest.HPre/hls.m3u8", mapped.hlsManifestUrl)
        assertEquals(1, mapped.videoStreams.size)
        assertEquals("https://video.HPre/v1.mp4", mapped.videoStreams[0].url)
        assertEquals(1, mapped.audioStreams.size)
        assertEquals("https://audio.HPre/a1.m4a", mapped.audioStreams[0].url)
    }

    @Test
    fun mapPageToPageToken_and_reconstituteNewPipePage_handle_both_direct_url_and_opaque_id() {
        // 1. NewPipe Page with valid direct HTTP URL
        val urlPage = org.schabi.newpipe.extractor.Page("https://youtube.com/continuation?token=123", "ignored_id")
        val urlToken = NewPipeMappers.mapPageToPageToken(urlPage)
        assertTrue(urlToken is com.hpre.app.model.PageToken.Url)
        assertEquals("https://youtube.com/continuation?token=123", (urlToken as com.hpre.app.model.PageToken.Url).url)

        // Reconstitute from URL PageToken: direct URL must be used as continuation URL rather than treated as page id
        val reconstitutedUrlPage = NewPipeMappers.reconstituteNewPipePage(urlToken, baseUrl = "https://youtube.com/base")
        assertNotNull(reconstitutedUrlPage)
        assertEquals("https://youtube.com/continuation?token=123", reconstitutedUrlPage!!.url)
        assertNull(reconstitutedUrlPage.id)

        // 2. NewPipe Page with only opaque ID (or invalid/blank URL)
        val idPage = org.schabi.newpipe.extractor.Page(null, "opaque_token_456")
        val idToken = NewPipeMappers.mapPageToPageToken(idPage)
        assertTrue(idToken is com.hpre.app.model.PageToken.Id)
        assertEquals("opaque_token_456", (idToken as com.hpre.app.model.PageToken.Id).id)

        // Reconstitute from ID PageToken: uses original baseUrl + id
        val reconstitutedIdPage = NewPipeMappers.reconstituteNewPipePage(idToken, baseUrl = "https://youtube.com/base")
        assertNotNull(reconstitutedIdPage)
        assertEquals("https://youtube.com/base", reconstitutedIdPage!!.url)
        assertEquals("opaque_token_456", reconstitutedIdPage.id)

        // 3. Null handling
        assertNull(NewPipeMappers.mapPageToPageToken(null))
        assertNull(NewPipeMappers.reconstituteNewPipePage(null, baseUrl = "https://youtube.com/base"))
    }

    @Test
    fun mapped_channel_info_item_maps_to_domain_channel() {
        val channelItem = ChannelInfoItem(0, "https://youtube.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg", "Channel 999").apply {
            subscriberCount = 5000
            description = "Channel description"
            thumbnails = listOf(Image("https://img.HPre/avatar.jpg", 200, 200, Image.ResolutionLevel.MEDIUM))
        }

        val channel = NewPipeMappers.mapChannelInfoItemToChannel(channelItem, fallbackServiceId = 0)
        assertNotNull(channel)
        channel!!

        assertEquals(ContentKey(0, "UCuCKox3vgM_q8p1Ufx9kGqg"), channel.key)
        assertEquals("Channel 999", channel.name)
        assertEquals("https://img.HPre/avatar.jpg", channel.avatarUrl)
        assertEquals("5000 subscribers", channel.subscriberCountText)
        assertEquals("Channel description", channel.description)
    }

    @Test
    fun mapped_playlist_info_item_maps_to_summary() {
        val playlistItem = PlaylistInfoItem(0, "https://youtube.com/playlist?list=PL_TEST_1234567890", "Test Playlist").apply {
            uploaderName = "Playlist Author"
            uploaderUrl = "https://youtube.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg"
            streamCount = 25
            thumbnails = listOf(Image("https://img.HPre/playlist.jpg", 400, 400, Image.ResolutionLevel.MEDIUM))
        }

        val playlist = NewPipeMappers.mapPlaylistInfoItemToSummary(playlistItem, fallbackServiceId = 0)
        assertNotNull(playlist)
        playlist!!

        assertEquals(ContentKey(0, "PL_TEST_1234567890"), playlist.key)
        assertEquals("Test Playlist", playlist.title)
        assertEquals("Playlist Author", playlist.channelName)
        assertEquals(25L, playlist.videoCount)
        assertEquals("https://img.HPre/playlist.jpg", playlist.thumbnailUrl)
    }

    @Test
    fun mapped_playlist_info_maps_to_details_with_videos() {
        val dummyDownloader = object : org.schabi.newpipe.extractor.downloader.Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
                return org.schabi.newpipe.extractor.downloader.Response(200, "OK", emptyMap(), "", "")
            }
        }
        org.schabi.newpipe.extractor.NewPipe.init(dummyDownloader)

        val linkHandler = org.schabi.newpipe.extractor.linkhandler.ListLinkHandler(
            "https://youtube.com/playlist?list=PL_TEST_DETAILS_1",
            "https://youtube.com/playlist?list=PL_TEST_DETAILS_1",
            "PL_TEST_DETAILS_1",
            emptyList(),
            ""
        )
        val extractor = object : org.schabi.newpipe.extractor.playlist.PlaylistExtractor(org.schabi.newpipe.extractor.ServiceList.YouTube, linkHandler) {
            override fun onFetchPage(downloader: org.schabi.newpipe.extractor.downloader.Downloader) {}
            override fun getId(): String = "PL_TEST_DETAILS_1"
            override fun getName(): String = "Full Playlist"
            override fun getOriginalUrl(): String = "https://youtube.com/playlist?list=PL_TEST_DETAILS_1"
            override fun getUrl(): String = "https://youtube.com/playlist?list=PL_TEST_DETAILS_1"
            override fun getUploaderUrl(): String = "https://youtube.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg"
            override fun getUploaderName(): String = "Creator"
            override fun getUploaderAvatars(): List<Image> = emptyList()
            override fun isUploaderVerified(): Boolean = false
            override fun getStreamCount(): Long = 1
            override fun getDescription(): Description = Description("Playlist notes", Description.PLAIN_TEXT)
            override fun getThumbnails(): List<Image> = listOf(Image("https://img.HPre/thumb.jpg", 200, 200, Image.ResolutionLevel.MEDIUM))
            override fun getInitialPage(): org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<StreamInfoItem> {
                return org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage(
                    listOf(
                        StreamInfoItem(0, "https://youtube.com/watch?v=dQw4w9WgXcQ", "Song 1", StreamType.VIDEO_STREAM).apply {
                            uploaderName = "Artist"
                            uploaderUrl = "https://youtube.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg"
                            duration = 200
                        }
                    ),
                    null,
                    emptyList()
                )
            }
            override fun getPage(page: org.schabi.newpipe.extractor.Page?): org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<StreamInfoItem> {
                return getInitialPage()
            }
        }
        extractor.fetchPage()
        val playlistInfo = PlaylistInfo.getInfo(extractor)

        val details = NewPipeMappers.mapPlaylistDetails(playlistInfo, fallbackServiceId = 0)
        assertNotNull(details)
        details!!

        assertEquals(ContentKey(0, "PL_TEST_DETAILS_1"), details.key)
        assertEquals("Full Playlist", details.title)
        assertEquals("Creator", details.channelName)
        assertEquals(ContentKey(0, "UCuCKox3vgM_q8p1Ufx9kGqg"), details.channelKey)
        assertEquals("Playlist notes", details.description)
        assertEquals(1L, details.videoCount)
        assertEquals(1, details.videos.size)
        assertEquals("Song 1", details.videos[0].title)
    }

    @Test
    fun isValidHttpUrl_rejects_userinfo_localhost_private_ips_and_accepts_valid_hosts() {
        // Valid
        assertTrue(NewPipeMappers.isValidHttpUrl("https://youtube.com/watch?v=123"))
        assertTrue(NewPipeMappers.isValidHttpUrl("http://example.com/video.mp4"))
        assertTrue(NewPipeMappers.isValidHttpUrl("https://googlevideo.com/videoplayback"))

        // UserInfo rejection
        assertFalse(NewPipeMappers.isValidHttpUrl("https://user:password@example.com/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://admin@example.com"))

        // Localhost rejection
        assertFalse(NewPipeMappers.isValidHttpUrl("http://localhost/stream"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://test.localhost/stream"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://127.0.0.1:8080/stream"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::1]/stream"))

        // Malformed numeric IPv4 literals
        assertFalse(NewPipeMappers.isValidHttpUrl("http://999.999.999.999/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://256.0.0.1/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://1.2.3.4.5/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://192.168.1/video.mp4"))

        // Private IP literal rejections
        assertFalse(NewPipeMappers.isValidHttpUrl("http://10.0.0.1/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://172.16.0.1/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://172.31.255.255/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://192.168.1.1/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://169.254.1.1/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://0.0.0.0/video.mp4"))

        // IPv6 literal rejections (loopback ::1, unspecified ::, linklocal fe80::, unique local fc00:: / fd00::)
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[0:0:0:0:0:0:0:0]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[0:0:0:0:0:0:0:1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[fe80::1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[fc00::1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[fd00::1]/video.mp4"))

        // IPv4-mapped IPv6 literals
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:127.0.0.1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:10.0.0.1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:192.168.1.1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:169.254.1.1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:0.0.0.0]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:7f00:1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:0a00:1]/video.mp4"))
        assertFalse(NewPipeMappers.isValidHttpUrl("http://[::ffff:999.999.999.999]/video.mp4"))

        // Public IPv4-mapped IPv6 accepted if parser reliably supports it
        assertTrue(NewPipeMappers.isValidHttpUrl("http://[::ffff:8.8.8.8]/video.mp4"))
        assertTrue(NewPipeMappers.isValidHttpUrl("http://[::ffff:0808:0808]/video.mp4"))
    }

    @Test
    fun canonical_host_parsing_strictly_enforces_boundaries_and_rejects_subdomain_spoofing() {
        // Valid exact / canonical subdomains
        assertEquals("dQw4w9WgXcQ", NewPipeMappers.extractNativeVideoId("https://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", NewPipeMappers.extractNativeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", NewPipeMappers.extractNativeVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", NewPipeMappers.extractNativeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", NewPipeMappers.extractNativeVideoId("https://youtube-nocookie.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", NewPipeMappers.extractNativeVideoId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", NewPipeMappers.extractNativeVideoId("https://youtu.be/dQw4w9WgXcQ"))

        // Rejections: contains substring spoofing without dot boundary
        assertNull(NewPipeMappers.extractNativeVideoId("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(NewPipeMappers.extractNativeVideoId("https://youtube.com.attacker.com/watch?v=dQw4w9WgXcQ"))
        assertNull(NewPipeMappers.extractNativeVideoId("https://evil-youtu.be/dQw4w9WgXcQ"))
        assertNull(NewPipeMappers.extractNativeVideoId("https://fakeyoutube-nocookie.com/embed/dQw4w9WgXcQ"))

        // Channel spoofing rejections
        assertNull(NewPipeMappers.extractNativeChannelId("https://notyoutube.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg"))
        assertNull(NewPipeMappers.extractNativeChannelId("https://youtube.com.evil.com/channel/UCuCKox3vgM_q8p1Ufx9kGqg"))

        // Playlist spoofing rejections
        assertNull(NewPipeMappers.extractNativePlaylistId("https://notyoutube.com/playlist?list=PL_TEST_1234567890"))
        assertNull(NewPipeMappers.extractNativePlaylistId("https://youtube.com.evil.com/playlist?list=PL_TEST_1234567890"))
    }

    @Test
    fun mapDateWrapperToTimestamp_converts_date_wrapper_correctly() {
        val instant = java.time.Instant.ofEpochMilli(1700000000000L)
        val dateWrapperInstant = org.schabi.newpipe.extractor.localization.DateWrapper(instant)
        assertEquals(1700000000000L, NewPipeMappers.mapDateWrapperToTimestamp(dateWrapperInstant))

        val localDateTime = java.time.LocalDateTime.of(2024, 1, 1, 12, 0, 0)
        val dateWrapperLocal = org.schabi.newpipe.extractor.localization.DateWrapper(localDateTime, false)
        assertNotNull(NewPipeMappers.mapDateWrapperToTimestamp(dateWrapperLocal))

        assertNull(NewPipeMappers.mapDateWrapperToTimestamp(null))
    }

    @Test
    fun mapStreamInfo_handles_only_audio_only_video_manifests_and_empty_candidates() {
        // 1. Only audio stream
        val onlyAudio = StreamInfo(0, "https://youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?v=dQw4w9WgXcQ", StreamType.VIDEO_STREAM, "dQw4w9WgXcQ", "Audio Only", 0).apply {
            audioStreams = listOf(AudioStream.Builder().setId("a1").setContent("https://audio.HPre/a1.m4a", true).build())
        }
        val mappedAudio = NewPipeMappers.mapStreamInfo(onlyAudio, fallbackServiceId = 0)
        assertNotNull(mappedAudio)
        assertEquals(0, mappedAudio!!.videoStreams.size)
        assertEquals(1, mappedAudio.audioStreams.size)

        // 2. Only video stream (video-only)
        val onlyVideo = StreamInfo(0, "https://youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?v=dQw4w9WgXcQ", StreamType.VIDEO_STREAM, "dQw4w9WgXcQ", "Video Only", 0).apply {
            videoOnlyStreams = listOf(VideoStream.Builder().setId("v1").setContent("https://video.HPre/v1.mp4", true).setIsVideoOnly(true).setResolution("1080p").build())
        }
        val mappedVideo = NewPipeMappers.mapStreamInfo(onlyVideo, fallbackServiceId = 0)
        assertNotNull(mappedVideo)
        assertEquals(1, mappedVideo!!.videoStreams.size)
        assertTrue(mappedVideo.videoStreams[0].isVideoOnly)

        // 3. Only manifests
        val onlyManifest = StreamInfo(0, "https://youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?v=dQw4w9WgXcQ", StreamType.VIDEO_STREAM, "dQw4w9WgXcQ", "Manifest Only", 0).apply {
            hlsUrl = "https://manifest.HPre/hls.m3u8"
        }
        val mappedManifest = NewPipeMappers.mapStreamInfo(onlyManifest, fallbackServiceId = 0)
        assertNotNull(mappedManifest)
        assertEquals("https://manifest.HPre/hls.m3u8", mappedManifest!!.hlsManifestUrl)

        // 4. Invalid streams only -> returns null
        val allInvalid = StreamInfo(0, "https://youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?v=dQw4w9WgXcQ", StreamType.VIDEO_STREAM, "dQw4w9WgXcQ", "Invalid Only", 0).apply {
            videoStreams = listOf(VideoStream.Builder().setId("v1").setContent("http://localhost/v1.mp4", true).setIsVideoOnly(false).setResolution("1080p").build())
            audioStreams = listOf(AudioStream.Builder().setId("a1").setContent("https://user:pass@audio.HPre/a.m4a", true).build())
            hlsUrl = "http://10.0.0.1/hls.m3u8"
            dashMpdUrl = "http://127.0.0.1/dash.mpd"
        }
        assertNull(NewPipeMappers.mapStreamInfo(allInvalid, fallbackServiceId = 0))
    }
}
